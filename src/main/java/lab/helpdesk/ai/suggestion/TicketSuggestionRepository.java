package lab.helpdesk.ai.suggestion;

import java.util.Optional;

import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.ContractValidatedOutput;

public interface TicketSuggestionRepository {

    long save(long jobId, ContractValidatedOutput output);

    Optional<TicketSuggestion> findByJobId(long jobId);
}
