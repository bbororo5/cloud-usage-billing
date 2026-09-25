package io.github.bbororo5.cloudbilling.worker.occupancyhistory.port;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.IssueRetry;
import java.util.*;
public interface IssueStore {
    IssueRetry.Result retry(IssueRetry.Command command,IssueRetry.OperatorContext operator);
    record Alert(UUID issueId,String reason) { }
    List<Alert> claimAlerts(int limit);
    void delivered(UUID issueId);
}
