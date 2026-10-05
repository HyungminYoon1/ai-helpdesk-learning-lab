package lab.helpdesk.ai.suggestion;

import java.util.Optional;

import lab.helpdesk.ai.job.AiJobResultState;

public record AiSuggestionStoredResult(AiJobResultState job, Optional<TicketSuggestion> suggestion) {
}
