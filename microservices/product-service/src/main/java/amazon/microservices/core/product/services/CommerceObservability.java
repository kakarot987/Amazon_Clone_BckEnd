package amazon.microservices.core.product.services;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Low-cardinality operational metrics. NEVER add JWT subjects, order IDs,
 * product IDs, exception messages, or arbitrary request values as tags.
 */
@Component
public class CommerceObservability {
    private final MeterRegistry registry;

    public CommerceObservability(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One timing and outcome per subscription, including cancellations. */
    public <T> Mono<T> measure(String operation, Mono<T> publisher) {
        return Mono.defer(() -> {
            Timer.Sample started = Timer.start(registry);
            AtomicBoolean completed = new AtomicBoolean();
            return publisher
                    .doOnSuccess(ignored -> finish(started, completed, operation, "success"))
                    .doOnError(ignored -> finish(started, completed, operation, "error"))
                    .doOnCancel(() -> finish(started, completed, operation, "cancelled"));
        });
    }

    private void finish(Timer.Sample started, AtomicBoolean completed,
                        String operation, String outcome) {
        if (!completed.compareAndSet(false, true)) return;
        Counter.builder("commerce.order.operations")
                .description("Checkout, cancellation, and recovery request outcomes")
                .tags("operation", operation, "outcome", outcome)
                .register(registry).increment();
        started.stop(Timer.builder("commerce.order.operation.duration")
                .description("Time spent on a commerce operation")
                .tags("operation", operation, "outcome", outcome)
                .publishPercentileHistogram()
                .register(registry));
    }

    public void recoveryFailure() {
        Counter.builder("commerce.recovery.failures")
                .description("Orders that could not be reconciled in a recovery pass")
                .register(registry).increment();
    }

    /** An observed audit finding, not an inventory gauge. */
    public void auditFindings(String kind, long count) {
        if (count <= 0) return;
        Counter.builder("commerce.inventory.audit.findings")
                .description("Cumulative findings observed in operator reconciliation runs")
                .tag("kind", kind)
                .register(registry).increment(count);
    }

    public void auditRun(boolean truncated) {
        Counter.builder("commerce.inventory.audit.runs")
                .description("Number of read-only reconciliation audit requests")
                .tag("truncated", Boolean.toString(truncated))
                .register(registry).increment();
    }
}
