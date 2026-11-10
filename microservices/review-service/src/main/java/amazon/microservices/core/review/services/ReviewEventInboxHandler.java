package amazon.microservices.core.review.services;

import amazon.api.core.review.Review;
import amazon.api.event.Event;
import amazon.api.event.EventFingerprint;
import amazon.microservices.core.review.persistence.ProcessedReviewEvent;
import amazon.microservices.core.review.persistence.ProcessedReviewEventRepository;
import amazon.microservices.core.review.persistence.ReviewRepository;
import amazon.util.exceptions.EventProcessingException;
import amazon.util.exceptions.InvalidInputException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Blocking SQL handler. The event receipt and legacy review mutation share
 * the same JPA transaction (do not call async Reactor code inside this tx).
 */
@Component
public class ReviewEventInboxHandler {
    private final ReviewRepository reviews;
    private final ReviewMapper mapper;
    private final ProcessedReviewEventRepository inbox;
    private final ObjectMapper objectMapper;

    public ReviewEventInboxHandler(ReviewRepository reviews, ReviewMapper mapper,
                                   ProcessedReviewEventRepository inbox, ObjectMapper objectMapper) {
        this.reviews=reviews; this.mapper=mapper;
        this.inbox=inbox; this.objectMapper=objectMapper;
    }

    @Transactional
    public void accept(Event<Integer, Review> event) {
        if (event == null || event.getEventType() == null
                || event.getKey() == null || event.getKey() < 1)
            throw new EventProcessingException("Invalid review event");
        if (event.getEventType() == Event.Type.CREATE) {
            Review review = event.getData();
            if (review == null || review.getProductId() != event.getKey())
                throw new EventProcessingException("Review key/payload mismatch");
        } else if (event.getEventType() != Event.Type.DELETE) {
            throw new EventProcessingException("Unsupported review event");
        }

        String id = EventFingerprint.checkedId(event);
        if (id != null) {
            String fingerprint = EventFingerprint.of(event, objectMapper);
            var previous = inbox.findById(id);
            if (previous.isPresent()) {
                if (!fingerprint.equals(previous.get().getFingerprint()))
                    throw new EventProcessingException("Conflicting review event ID");
                return; // Already committed, do not repeat legacy business action.
            }
            inbox.saveAndFlush(new ProcessedReviewEvent(id, fingerprint));
        }

        switch (event.getEventType()) {
            case CREATE -> {
                Review data = event.getData();
                try {
                    reviews.save(mapper.apiToEntity(data));
                    reviews.flush();
                } catch (DataIntegrityViolationException ex) {
                    throw new InvalidInputException("Duplicate key, Product Id: "
                            + data.getProductId() + ", Review Id:" + data.getReviewId());
                }
            }
            case DELETE -> reviews.deleteAll(reviews.findByProductId(event.getKey()));
            default -> throw new EventProcessingException("Unsupported review event");
        }
    }
}
