package io.github.bbororo5.cloudbilling.worker;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.kafka.EventDecoder;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.ReceiptStore;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DecoderTest {
    @Test void normalizeAndPreserveInvalidWire() throws Exception {
        var decoder=new EventDecoder();
        byte[] bytes=Files.readAllBytes(Path.of(System.getProperty("contracts.dir"),"examples/occupancy/started.json"));
        var record=new ReceiptStore.Record("occupancy",0,0,"urn:vm:a".getBytes(),List.of(),bytes);
        var good=decoder.decode(record);
        assertNull(good.error());assertEquals(2,good.event().sequence());
        assertEquals("KEY_SOURCE_MISMATCH",decoder.decode(new ReceiptStore.Record("occupancy",0,1,"wrong".getBytes(),List.of(),bytes)).error());
        assertNotNull(decoder.decode(new ReceiptStore.Record("occupancy",0,2,null,List.of(),"{".getBytes())).error());
        assertNotNull(decoder.decode(new ReceiptStore.Record("occupancy",0,3,null,List.of(),null)).error());
        var repeated=new String(bytes).replace("\"sequence\": 2","\"sequence\": 2, \"sequence\": 3");
        assertNotNull(decoder.decode(new ReceiptStore.Record("occupancy",0,4,"urn:vm:a".getBytes(),List.of(),repeated.getBytes())).error());
    }
}
