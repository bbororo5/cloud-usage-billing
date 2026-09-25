package io.github.bbororo5.cloudbilling.worker.occupancyhistory.port;
import java.util.function.Supplier;
public interface TransactionRunner {
    <T> T write(Supplier<T> work);
    <T> T read(Supplier<T> work);
}
