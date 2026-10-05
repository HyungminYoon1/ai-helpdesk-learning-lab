package lab.helpdesk.ai.input;

import java.util.Optional;

import lab.helpdesk.ai.job.AiJobClaim;

public interface AiSuggestionInputRepository {

    Optional<StoredAiSuggestionInput> findCurrentInput(AiJobClaim claim);

    boolean canSendReservedRequest(AiJobClaim claim);
}
