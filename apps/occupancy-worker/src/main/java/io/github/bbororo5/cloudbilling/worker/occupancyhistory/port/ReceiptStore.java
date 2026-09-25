package io.github.bbororo5.cloudbilling.worker.occupancyhistory.port;

import io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain.Event;
import java.util.List;

public interface ReceiptStore {
  record Header(String key, byte[] value) {
    public Header {
      value = value == null ? null : value.clone();
    }

    @Override
    public byte[] value() {
      return value == null ? null : value.clone();
    }
  }

  record Record(
      String topic, int partition, long offset, byte[] key, List<Header> headers, byte[] bytes) {
    public Record {
      key = key == null ? null : key.clone();
      headers = List.copyOf(headers);
      bytes = bytes == null ? null : bytes.clone();
    }

    @Override
    public byte[] key() {
      return key == null ? null : key.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes == null ? null : bytes.clone();
    }
  }

  record Input(Record record, Event event, String error) {}

  void preserve(Input input);
}
