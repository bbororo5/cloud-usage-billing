package io.github.bbororo5.cloudbilling.worker;

import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres.PostgresIssueStore;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.application.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.IssueRetry;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain.Event;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class OperationsTest extends PersistenceTest {
    @Test void authorizedRetryIsIdempotentAndResolutionNeedsSuccessfulApply() throws Exception {
        receive(event(source,1,Event.Kind.INITIALIZED,null,null),1);apply.applyNext(source);
        String account="missing-"+UUID.randomUUID();
        receive(event(source,2,Event.Kind.STARTED,UUID.randomUUID(),account),2);apply.applyNext(source);
        var issues=new PostgresIssueStore(tx);
        UUID issue=tx.read(()-> {try(var s=tx.connection().prepareStatement("select issue_id from billing.occupancy_issue where source=?")){s.setString(1,source);try(var r=s.executeQuery()){r.next();return r.getObject(1,UUID.class);}}catch(SQLException e){throw new RuntimeException(e);}});
        var retry=new RetryService(tx,issues);
        var cmd=new IssueRetry.Command(UUID.randomUUID(),issue,"account registered");
        assertEquals(new IssueRetry.Rejected(IssueRetry.Reason.FORBIDDEN),retry.retry(cmd,new IssueRetry.OperatorContext("customer",false)));
        var operator=new IssueRetry.OperatorContext("internal-operator",true);
        assertInstanceOf(IssueRetry.Scheduled.class,retry.retry(cmd,operator));
        assertEquals(retry.retry(cmd,operator),retry.retry(cmd,operator));
        assertEquals(1,number("select count(*) from billing.occupancy_issue_action where request_id='"+cmd.requestId()+"'"));
        assertEquals(1,number("select count(*) from billing.occupancy_issue where source='"+source+"' and status='OPEN'"));
        apply.applyNext(source); // Cause still exists: remains blocked.
        assertEquals(1,number("select count(*) from billing.occupancy_issue where source='"+source+"' and status='OPEN'"));
        try(var c=DriverManager.getConnection(System.getenv("OCCUPANCY_TEST_URL"),"billing_owner","local-dev-only");var s=c.prepareStatement("insert into billing.billing_account(billing_account_id,billing_account_name) values(?, 'Registered')")) {s.setString(1,account);s.executeUpdate();}
        retry.retry(new IssueRetry.Command(UUID.randomUUID(),issue,"cause fixed"),operator);
        apply.applyNext(source);
        assertEquals(0,number("select count(*) from billing.occupancy_issue where source='"+source+"' and status='OPEN'"));
        assertEquals(2,number("select last_applied_sequence from billing.occupancy_stream where source='"+source+"'"));
        assertEquals(new IssueRetry.Rejected(IssueRetry.Reason.INVALID_REQUEST),retry.retry(new IssueRetry.Command(cmd.requestId(),issue,"different"),operator));
    }
    @Test void notificationFailureKeepsPersistentPendingState() {
        receive(event(source,1,Event.Kind.INITIALIZED,null,null),1);apply.applyNext(source);
        receive(event(source,2,Event.Kind.STARTED,UUID.randomUUID(),"missing"),2);apply.applyNext(source);
        var issues=new PostgresIssueStore(tx);
        var sender=new NotificationService(tx,issues,alert->{throw new IllegalStateException("channel offline");});
        sender.deliverPending(100);
        assertEquals(1,number("select count(*) from billing.occupancy_issue where source='"+source+"' and alert_state='PENDING'"));
        tx.write(()->{try(var s=tx.connection().prepareStatement("update billing.occupancy_issue set next_alert_at=now() where source=?")){s.setString(1,source);s.executeUpdate();return null;}catch(SQLException e){throw new RuntimeException(e);}});
        new NotificationService(tx,issues,alert->{}).deliverPending(100);
        assertEquals(1,number("select count(*) from billing.occupancy_issue where source='"+source+"' and alert_state='SENT'"));
    }
}
