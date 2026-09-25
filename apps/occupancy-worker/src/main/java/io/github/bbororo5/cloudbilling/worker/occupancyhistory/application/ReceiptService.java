package io.github.bbororo5.cloudbilling.worker.occupancyhistory.application;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.*;
public final class ReceiptService {
    private final TransactionRunner transactions;
    private final ReceiptStore store;
    public ReceiptService(TransactionRunner transactions, ReceiptStore store) { this.transactions=transactions; this.store=store; }
    public void receive(ReceiptStore.Input input) { transactions.write(() -> { store.preserve(input); return null; }); }
}
