package io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.cache;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.SnapshotCache;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.HistoryReader.Snapshot;
import java.util.*;
public final class LocalSnapshotCache implements SnapshotCache {
    private final int capacity;
    private final LinkedHashMap<Key,Snapshot> entries=new LinkedHashMap<>(16,0.75f,true);
    private long hits,misses;
    public LocalSnapshotCache(int capacity) {if(capacity<1) throw new IllegalArgumentException("capacity"); this.capacity=capacity;}
    @Override public synchronized Optional<Snapshot> get(Key key) {
        var value=entries.get(key);if(value==null)misses++;else hits++;
        return Optional.ofNullable(value);
    }
    public synchronized long hits(){return hits;}
    public synchronized long misses(){return misses;}
    public synchronized int size(){return entries.size();}
    @Override public synchronized void put(Key key,Snapshot value) {
        if(!key.source().equals(value.source()) || key.version()!=value.version()) throw new IllegalArgumentException("Snapshot key mismatch");
        if(value.slices().size()>1000) return; // Do not retain unbounded historical query results.
        entries.put(key,value);
        while(entries.size()>capacity) entries.remove(entries.keySet().iterator().next());
    }
}
