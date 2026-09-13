package com.dinesh.wallet.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Domain counters exposed at {@code /actuator/prometheus} alongside the built-in
 * request-rate / latency (p99) / error-rate meters. These are the business-level
 * signals the exercise asks for.
 */
@Component
public class TransferMetrics {

    private final Counter completed;
    private final Counter declinedInsufficientFunds;
    private final Counter idempotentReplays;
    private final Counter conflicts;

    public TransferMetrics(MeterRegistry registry) {
        this.completed = Counter.builder("wallet.transfers.completed")
                .description("Transfers that completed a debit and credit")
                .register(registry);
        this.declinedInsufficientFunds = Counter.builder("wallet.transfers.declined")
                .description("Transfers declined for insufficient funds")
                .tag("reason", "insufficient_funds")
                .register(registry);
        this.idempotentReplays = Counter.builder("wallet.transfers.idempotent_replays")
                .description("Retries of an existing idempotency key that returned the original result")
                .register(registry);
        this.conflicts = Counter.builder("wallet.transfers.conflicts")
                .description("Same idempotency key reused with a different body (409)")
                .register(registry);
    }

    public void completed() {
        completed.increment();
    }

    public void declinedInsufficientFunds() {
        declinedInsufficientFunds.increment();
    }

    public void idempotentReplay() {
        idempotentReplays.increment();
    }

    public void conflict() {
        conflicts.increment();
    }
}
