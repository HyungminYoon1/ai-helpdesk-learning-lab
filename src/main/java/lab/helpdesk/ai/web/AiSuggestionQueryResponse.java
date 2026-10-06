package lab.helpdesk.ai.web;

import java.util.List;

import lab.helpdesk.ai.job.AiJobFailureCode;
import lab.helpdesk.ai.job.AiJobStatus;
import lab.helpdesk.ai.query.AiSuggestionQueryResult;
import lab.helpdesk.ai.suggestion.TicketSuggestion;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.Category;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.Priority;

public record AiSuggestionQueryResponse(long ticketId, Job job, Suggestion suggestion) {

    public static AiSuggestionQueryResponse from(AiSuggestionQueryResult result) {
        var job = result.job();
        var suggestion = result.suggestion();
        return new AiSuggestionQueryResponse(result.ticketId(),
                job == null ? null : new Job(job.id(), job.status(), job.failureCode()),
                suggestion == null ? null : new Suggestion(suggestion.id(), suggestion.summary(),
                        suggestion.categories(), suggestion.priority(), suggestion.reviewStatus()));
    }

    public record Job(long id, AiJobStatus status, AiJobFailureCode failureCode) {
    }

    public record Suggestion(long id, String summary, List<Category> categories,
            Priority priority, TicketSuggestion.ReviewStatus reviewStatus) {
    }
}
