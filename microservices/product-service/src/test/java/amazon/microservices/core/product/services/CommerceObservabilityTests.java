package amazon.microservices.core.product.services;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class CommerceObservabilityTests {
    @Test
    void recordsSuccessAndErrorWithoutUserIdentifiers() {
        {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            CommerceObservability observed = new CommerceObservability(registry);
            StepVerifier.create(observed.measure("checkout", Mono.just("ok")))
                    .expectNext("ok").verifyComplete();
            StepVerifier.create(observed.measure("checkout",
                            Mono.<String>error(new IllegalStateException("secret user data"))))
                    .expectError(IllegalStateException.class).verify();

            assertEquals(1.0, registry.get("commerce.order.operations")
                    .tags("operation", "checkout", "outcome", "success").counter().count());
            assertEquals(1.0, registry.get("commerce.order.operations")
                    .tags("operation", "checkout", "outcome", "error").counter().count());
            assertEquals(1, registry.get("commerce.order.operation.duration")
                    .tags("operation", "checkout", "outcome", "success").timer().count());
            assertEquals(1, registry.get("commerce.order.operation.duration")
                    .tags("operation", "checkout", "outcome", "error").timer().count());
            assertTrue(registry.getMeters().stream()
                    .flatMap(meter -> meter.getId().getTags().stream())
                    .noneMatch(tag -> tag.getValue().contains("secret user data")));
        }
    }

    @Test
    void cancellationIsCountedExactlyOnce() {
        {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            CommerceObservability observed = new CommerceObservability(registry);
            StepVerifier.create(observed.measure("reconcile", Mono.never()))
                    .thenCancel().verify(Duration.ofSeconds(2));

            assertEquals(1.0, registry.get("commerce.order.operations")
                    .tags("operation", "reconcile", "outcome", "cancelled").counter().count());
            assertEquals(1, registry.get("commerce.order.operation.duration")
                    .tags("operation", "reconcile", "outcome", "cancelled").timer().count());
        }
    }

    @Test
    void auditAndRecoveryUseBoundedCategoricalTags() {
        {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            CommerceObservability observed = new CommerceObservability(registry);
            observed.recoveryFailure();
            observed.auditRun(true);
            observed.auditFindings("ORPHAN_HOLD", 3);
            observed.auditFindings("ORPHAN_HOLD", 0);

            assertEquals(1.0, registry.get("commerce.recovery.failures").counter().count());
            assertEquals(1.0, registry.get("commerce.inventory.audit.runs")
                    .tag("truncated", "true").counter().count());
            assertEquals(3.0, registry.get("commerce.inventory.audit.findings")
                    .tag("kind", "ORPHAN_HOLD").counter().count());
        }
    }
}
