package io.trino.gateway.ha.router;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.trino.gateway.ha.clustermonitor.ClusterStats;
import io.trino.gateway.ha.config.ProxyBackendConfiguration;
import io.trino.gateway.proxyserver.ProxyServerConfiguration;
import jakarta.ws.rs.HttpMethod;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * This class performs health check, stats counts for each backend and provides a backend given
 * request object. Default implementation comes here.
 */
@Slf4j
public abstract class RoutingManager {
  private static final Random RANDOM = new Random();
  private final LoadingCache<String, String> queryIdBackendCache;
  private ExecutorService executorService = Executors.newFixedThreadPool(5);
  private GatewayBackendManager gatewayBackendManager;
  private ConcurrentHashMap<String, Boolean> backendToHealth;

  public RoutingManager(GatewayBackendManager gatewayBackendManager) {
    this.gatewayBackendManager = gatewayBackendManager;
    queryIdBackendCache =
        CacheBuilder.newBuilder()
            .maximumSize(10000)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .build(
                new CacheLoader<String, String>() {
                  @Override
                  public String load(String queryId) {
                    return findBackendForUnknownQueryId(queryId);
                  }
                });

    this.backendToHealth = new ConcurrentHashMap<String, Boolean>();
  }

  protected GatewayBackendManager getGatewayBackendManager() {
    return gatewayBackendManager;
  }

  public void setBackendForQueryId(String queryId, String backend) {
    queryIdBackendCache.put(queryId, backend);
  }

  /**
   * Performs routing to an adhoc backend.
   *
   * <p>d.
   *
   * @return
   */
  public String provideAdhocBackend(String user) {
    List<ProxyBackendConfiguration> backends = this.gatewayBackendManager.getActiveAdhocBackends();
    backends.removeIf(backend -> isBackendNotHealthy(backend.getName()));
    if (backends.size() == 0) {
      throw new IllegalStateException("Number of active backends found zero");
    }
    int backendId = Math.abs(RANDOM.nextInt()) % backends.size();
    return backends.get(backendId).getProxyTo();
  }

  /**
   * Performs routing to a given cluster group. This falls back to an adhoc backend, if no scheduled
   * backend is found.
   *
   * @return
   */
  public String provideBackendForRoutingGroup(String routingGroup, String user) {
    List<ProxyBackendConfiguration> backends =
        gatewayBackendManager.getActiveBackends(routingGroup);
    backends.removeIf(backend -> isBackendNotHealthy(backend.getName()));
    if (backends.isEmpty()) {
      return provideAdhocBackend(user);
    }
    int backendId = Math.abs(RANDOM.nextInt()) % backends.size();
    return backends.get(backendId).getProxyTo();
  }

  /**
   * Performs cache look up, if a backend not found, it checks with all backends and tries to find
   * out which backend has info about given query id.
   *
   * @param queryId
   * @return
   */
  public String findBackendForQueryId(String queryId) {
    String backendAddress = null;
    try {
      backendAddress = queryIdBackendCache.get(queryId);
    } catch (ExecutionException e) {
      log.error("Exception while loading queryId from cache {}", e.getLocalizedMessage());
    }
    return backendAddress;
  }

  public void upateBackEndHealth(String backendId, Boolean value) {
    log.info("backend {} isHealthy {}", backendId, value);
    backendToHealth.put(backendId, value);
  }

  public void updateBackEndHealthDB(ClusterStats stats) {
    String name = stats.getClusterId();
    if (stats.isHealthy()) {
      gatewayBackendManager.activateBackend(name);
    } else {
      gatewayBackendManager.deactivateBackend(name);
    }
  }


  /**
   * This tries to find out which backend may have info about given query id. If not found returns
   * the first healthy backend.
   *
   * @param queryId
   * @return
   */
  protected String findBackendForUnknownQueryId(String queryId) {
    List<ProxyBackendConfiguration> backends = gatewayBackendManager.getAllBackends();

    Map<String, Future<Integer>> responseCodes = new HashMap<>();
    try {
      for (ProxyServerConfiguration backend : backends) {
        String target = backend.getProxyTo() + "/v1/query/" + queryId;

        Future<Integer> call =
            executorService.submit(
                () -> {
                  URL url = new URL(target);
                  HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                  conn.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(5));
                  conn.setReadTimeout((int) TimeUnit.SECONDS.toMillis(5));
                  conn.setRequestMethod(HttpMethod.HEAD);
                  return conn.getResponseCode();
                });
        responseCodes.put(backend.getProxyTo(), call);
      }
      for (Map.Entry<String, Future<Integer>> entry : responseCodes.entrySet()) {
        if (entry.getValue().isDone()) {
          int responseCode = entry.getValue().get();
          if (responseCode == 200) {
            log.info("Found query [{}] on backend [{}]", queryId, entry.getKey());
            setBackendForQueryId(queryId, entry.getKey());
            return entry.getKey();
          }
        }
      }
    } catch (Exception e) {
      log.warn("Query id [{}] not found", queryId);
    }
    // Fallback on first active backend if queryId mapping not found.
    return gatewayBackendManager.getActiveAdhocBackends().get(0).getProxyTo();
  }


  // Predicate helper function to remove the backends from the list
  // We are returning the unhealthy (not healthy) 
  private boolean isBackendNotHealthy(String backendId) {
    if (backendToHealth.isEmpty()) {
      log.error("backends can not be empty");
      return true;
    }
    Boolean isHealthy = backendToHealth.get(backendId);
    if (isHealthy == null) {
      return true;
    }
    return !isHealthy;
  }

}
