package amazon.microservices.core.recommendation.services;

import amazon.api.core.recommendation.Recommendation;
import amazon.api.core.recommendation.RecommendationService;
import amazon.api.event.Event;
import amazon.api.event.EventFingerprint;
import amazon.microservices.core.recommendation.persistence.ProcessedRecommendationEvent;
import amazon.microservices.core.recommendation.persistence.ProcessedRecommendationEventRepository;
import amazon.util.exceptions.EventProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** Prevent duplicate recommendation mutations on broker redelivery. */
@Component
public class RecommendationEventInboxHandler {
    private final RecommendationService service;
    private final ProcessedRecommendationEventRepository inbox;
    private final TransactionalOperator transactions;
    private final ObjectMapper mapper;

    public RecommendationEventInboxHandler(RecommendationService service,
            ProcessedRecommendationEventRepository inbox,
            TransactionalOperator recommendationEventTransactions,
            ObjectMapper mapper) {
        this.service=service; this.inbox=inbox;
        this.transactions=recommendationEventTransactions; this.mapper=mapper;
    }

    public void accept(Event<Integer, Recommendation> event) {
        if (event == null || event.getEventType() == null || event.getKey() == null
                || event.getKey() < 1)
            throw new EventProcessingException("Invalid recommendation event");
        if (event.getEventType() == Event.Type.CREATE) {
            Recommendation r = event.getData();
            if (r == null || r.getProductId() != event.getKey())
                throw new EventProcessingException("Recommendation key/payload mismatch");
        } else if (event.getEventType() != Event.Type.DELETE) {
            throw new EventProcessingException("Unsupported recommendation event");
        }

        String id = EventFingerprint.checkedId(event);
        if (id == null) {
            operation(event).block();
            return;
        }
        String fingerprint = EventFingerprint.of(event, mapper);
        Mono<Void> processed = Mono.defer(() -> inbox.findById(id)
                .flatMap(previous -> {
                    if (!fingerprint.equals(previous.getFingerprint()))
                        return Mono.<Boolean>error(new EventProcessingException(
                                "Conflicting recommendation event ID"));
                    return Mono.just(false);
                })
                .switchIfEmpty(inbox.save(new ProcessedRecommendationEvent(id, fingerprint))
                        .thenReturn(true))
                .flatMap(shouldRun -> shouldRun ? operation(event) : Mono.<Void>empty()))
                .as(transactions::transactional);
        processed.block();
    }

    private Mono<Void> operation(Event<Integer, Recommendation> event) {
        return switch (event.getEventType()) {
            case CREATE -> service.createRecommendation(event.getData()).then();
            case DELETE -> service.deleteRecommendations(event.getKey()).then();
            default -> Mono.error(new EventProcessingException("Unsupported recommendation event"));
        };
    }
}
