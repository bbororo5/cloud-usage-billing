package io.github.bbororo5.cloudbilling.worker;

import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.cache.LocalSnapshotCache;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres.PostgresSnapshotStore;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.HistoryReader;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.application.QueryService;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain.Event;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.SnapshotCache;
import org.junit.jupiter.api.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class QueryTest extends PersistenceTest {
    @Test void confirmedBoundariesIdleAndOldCache() {
        var cache=new LocalSnapshotCache(2);
        var query=new QueryService(tx,new PostgresSnapshotStore(tx),cache);
        var range=new HistoryReader.Query(source,t,t.plusSeconds(240));
        assertEquals(new HistoryReader.NotReady(HistoryReader.Reason.UNREGISTERED),query.lookup(range));
        receive(event(source,1,Event.Kind.INITIALIZED,null,null),1);apply.applyNext(source);
        assertEquals(new HistoryReader.NotReady(HistoryReader.Reason.AWAITING_FACTS),query.lookup(range));
        var occ=UUID.randomUUID();
        receive(event(source,2,Event.Kind.STARTED,occ,"x"),2);apply.applyNext(source);
        receive(event(source,3,Event.Kind.ENDED,occ,null),3);apply.applyNext(source);
        receive(event(source,4,Event.Kind.CONFIRMED,null,null),4);apply.applyNext(source);
        var first=((HistoryReader.Confirmed)query.lookup(range)).snapshot();
        assertEquals(1,first.slices().size()); assertEquals(t.plusSeconds(120),first.slices().getFirst().startedAt());
        assertEquals(t.plusSeconds(180),first.slices().getFirst().endedAt());
        assertEquals(first,((HistoryReader.Confirmed)query.lookup(range)).snapshot());
        receive(event(source,5,Event.Kind.CONFIRMED,null,null),5);apply.applyNext(source);
        cache.put(new SnapshotCache.Key(source,first.version(),range.from(),range.to()),first);
        assertTrue(((HistoryReader.Confirmed)query.lookup(range)).snapshot().version()>first.version());
        var idle=(HistoryReader.Confirmed)query.lookup(new HistoryReader.Query(source,t,t.plusSeconds(120)));
        assertTrue(idle.snapshot().slices().isEmpty());
        assertEquals(new HistoryReader.NotReady(HistoryReader.Reason.BEFORE_BASELINE),query.lookup(new HistoryReader.Query(source,t.minusMillis(1),t)));
    }
}
