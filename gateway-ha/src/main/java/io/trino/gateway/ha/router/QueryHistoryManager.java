package io.trino.gateway.ha.router;

import java.util.List;
import java.util.Optional;
import lombok.Data;
import lombok.ToString;

public interface QueryHistoryManager {
  void submitQueryDetail(QueryDetail queryDetail);

  List<QueryDetail> fetchQueryHistory(Optional<String> user);

  String getBackendForQueryId(String queryId);

  @Data
  @ToString
  class QueryDetail implements Comparable<QueryDetail> {
    private String queryId;
    private String queryText;
    private String user;
    private String source;
    private String backendUrl;
    private long captureTime;

    @Override
    public int compareTo(QueryDetail o) {
      if (this.captureTime < o.captureTime) {
        return 1;
      } else {
        return this.captureTime == o.captureTime ? 0 : -1;
      }
    }
  }
}
