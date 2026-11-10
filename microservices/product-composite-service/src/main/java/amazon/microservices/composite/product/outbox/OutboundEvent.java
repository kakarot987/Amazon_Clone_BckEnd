package amazon.microservices.composite.product.outbox;

import amazon.api.event.Event;

/** Whitelisted broker binding and immutable business envelope to enqueue. */
public record OutboundEvent(String bindingName, Event<?, ?> event) {}
