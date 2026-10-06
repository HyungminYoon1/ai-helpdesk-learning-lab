package lab.helpdesk.ai.query;

import lab.helpdesk.ai.job.AiJobFailureCode;
import lab.helpdesk.ai.job.AiJobStatus;
import lab.helpdesk.ai.suggestion.TicketSuggestion;

public record AiSuggestionQueryResult(long ticketId, Job job, TicketSuggestion suggestion) {

    public record Job(long id, AiJobStatus status, AiJobFailureCode failureCode) {
    }
}
