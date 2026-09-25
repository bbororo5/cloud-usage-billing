package io.github.bbororo5.cloudbilling.worker.occupancyhistory.application;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.IssueRetry;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.*;
public final class RetryService implements IssueRetry {
    private final TransactionRunner tx;
    private final IssueStore issues;
    public RetryService(TransactionRunner tx,IssueStore issues){this.tx=tx;this.issues=issues;}
    @Override public Result retry(Command cmd,OperatorContext operator) {
        if(operator==null || !operator.mayRetry() || operator.principal()==null || operator.principal().isBlank())return new Rejected(Reason.FORBIDDEN);
        if(cmd==null || cmd.requestId()==null || cmd.issueId()==null || cmd.reason()==null || cmd.reason().isBlank() || cmd.reason().length()>1000)
            return new Rejected(Reason.INVALID_REQUEST);
        return tx.write(()->issues.retry(cmd,operator));
    }
}
