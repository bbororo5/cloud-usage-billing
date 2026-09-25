package io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres;

import com.fasterxml.jackson.databind.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain.Event;
import java.time.Instant;
import java.util.UUID;

/** Stable normalized representation; equality compares every event field, not a hash. */
final class EventJson {
    private static final ObjectMapper JSON=new ObjectMapper();
    static String encode(Event e) {
        var n=JSON.createObjectNode();
        n.put("source",e.source());n.put("subject",e.subject());n.put("id",e.id().toString());
        n.put("kind",e.kind().name());n.put("sequence",e.sequence());n.put("time",e.time().toString());
        n.put("occupancy",e.occupancyId()==null?null:e.occupancyId().toString());n.put("account",e.account());
        n.put("baseline",string(e.baseline()));n.put("through",string(e.through()));n.put("initialStart",string(e.initialStart()));
        return n.toString();
    }
    static Event decode(String json) {
        try {
            var n=JSON.readTree(json);
            return new Event(n.get("source").asText(),n.get("subject").asText(),UUID.fromString(n.get("id").asText()),
                Event.Kind.valueOf(n.get("kind").asText()),n.get("sequence").longValue(),Instant.parse(n.get("time").asText()),
                n.get("occupancy").isNull()?null:UUID.fromString(n.get("occupancy").asText()),
                n.get("account").isNull()?null:n.get("account").asText(),time(n,"baseline"),time(n,"through"),time(n,"initialStart"));
        } catch(Exception e) {throw new IllegalStateException("Corrupt normalized event",e);}
    }
    private static String string(Instant t) {return t==null?null:t.toString();}
    private static Instant time(JsonNode n,String k) {return n.get(k).isNull()?null:Instant.parse(n.get(k).asText());}
}
