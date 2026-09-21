package io.github.bbororo5.cloudbilling.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Operator-controlled simulator. The immutable command input is the replay plan. */
public final class UsageGenerator {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigInteger MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private final String source, resource;
    private final Instant start;
    private final BigInteger storageQuantity, networkQuantity;

    public UsageGenerator(String producerId, String resource, Instant start,
                          BigInteger storageGiB, BigInteger networkBytesPerMinute) {
        if (!producerId.matches("[A-Za-z0-9._-]+") || !resource.matches("[A-Za-z0-9._-]+"))
            throw new IllegalArgumentException("Invalid source/resource identifier");
        if (start.getNano()%1_000_000!=0) throw new IllegalArgumentException("Millisecond precision required");
        this.source="urn:cloud-usage:meter:"+producerId;
        this.resource=resource;
        this.start=start;
        this.storageQuantity=checked(storageGiB.multiply(BigInteger.valueOf(60)));
        this.networkQuantity=checked(networkBytesPerMinute);
    }
    private static BigInteger checked(BigInteger value) {
        if (value.signum()<0 || value.compareTo(MAX)>0) throw new IllegalArgumentException("UInt64 range exceeded");
        return value;
    }
    public long completedIntervals(long occupiedSeconds) {
        if (occupiedSeconds<0) throw new IllegalArgumentException("Negative occupancy duration");
        return occupiedSeconds/60;
    }
    public Event event(long index) {
        if (index<0) throw new IllegalArgumentException("Negative interval index");
        Instant from=start.plusSeconds(Math.multiplyExact(index,60)), to=from.plusSeconds(60);
        String id=UUID.nameUUIDFromBytes((source+"|"+resource+"|"+from).getBytes(StandardCharsets.UTF_8)).toString();
        ObjectNode event=JSON.createObjectNode();
        event.put("specversion","1.0").put("id",id).put("source",source)
            .put("type","io.github.bbororo5.cloudusage.instance.usage.v1")
            .put("subject","instances/"+resource).put("time",to.toString())
            .put("datacontenttype","application/json")
            .put("dataschema","https://raw.githubusercontent.com/bbororo5/cloud-usage-billing/main/contracts/v1/instance-usage-event.schema.json");
        ArrayNode records=event.putArray("data");
        measurement(records,from,to,"Compute Usage",BigInteger.valueOf(60),"Second");
        measurement(records,from,to,"Block Volume Usage",storageQuantity,"GiB-Second");
        measurement(records,from,to,"Data Transfer",networkQuantity,"Byte");
        return new Event(source,event.toString());
    }
    private void measurement(ArrayNode records,Instant from,Instant to,String meter,BigInteger quantity,String unit) {
        records.addObject().put("ChargePeriodStart",from.toString()).put("ChargePeriodEnd",to.toString())
            .put("RegionId","kr-central-1").put("ResourceId",resource).put("ResourceType","Virtual Machine")
            .put("Meter",meter).put("ConsumedQuantity",quantity).put("ConsumedUnit",unit);
    }
    public record Event(String key,String value) { }
}
