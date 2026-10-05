package lab.helpdesk.ai.job;

import java.util.Optional;

public interface AiSuggestionJobResultRepository {

    boolean lockCurrentExecution(AiJobClaim claim);

    boolean finishIfCurrent(AiJobClaim claim, AiJobStatus terminalStatus);

    Optional<AiJobResultState> findByJobId(long jobId);
}
