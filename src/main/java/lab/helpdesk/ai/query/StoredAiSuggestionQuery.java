package lab.helpdesk.ai.query;

import java.time.Instant;
import java.util.List;

// DB 조회용 Projection. 허용된 상태와 결과의 정합성은 Application Service가 검사한다.
public record StoredAiSuggestionQuery(long ticketId, Job job, Suggestion suggestion) {

    public record Job(long id, String status, String failureCode) {
    }

    public record Suggestion(long id, String summary, List<String> categories,
            String priority, String reviewStatus, Instant createdAt) {
        public Suggestion {
            categories = List.copyOf(categories);
        }
    }
}
