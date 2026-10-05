package lab.helpdesk.ai.suggestion;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.Category;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.Priority;

public record TicketSuggestion(
        long id, long jobId, String summary, List<Category> categories,
        Priority priority, ReviewStatus reviewStatus, Instant createdAt) {

    public TicketSuggestion {
        if (id <= 0 || jobId <= 0) {
            throw new IllegalArgumentException("invalid suggestion metadata");
        }
        Objects.requireNonNull(summary);
        categories = List.copyOf(categories);
        if (categories.isEmpty() || categories.stream().distinct().count() != categories.size()) {
            throw new IllegalArgumentException("invalid stored suggestion categories");
        }
        Objects.requireNonNull(priority);
        Objects.requireNonNull(reviewStatus);
        Objects.requireNonNull(createdAt);
    }

    public enum ReviewStatus {
        PENDING_REVIEW
    }
}
