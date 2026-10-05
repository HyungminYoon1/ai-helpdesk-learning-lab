package lab.helpdesk.ai.suggestion;

import java.util.Objects;
import java.util.Optional;

import lab.helpdesk.ai.job.AiJobClaim;
import lab.helpdesk.ai.job.AiJobStatus;
import lab.helpdesk.ai.job.AiSuggestionJobResultRepository;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.ContractValidatedOutput;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.Decision;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("postgres")
public class AiSuggestionResultService {

    private final AiSuggestionJobResultRepository jobs;
    private final TicketSuggestionRepository suggestions;

    public AiSuggestionResultService(
            AiSuggestionJobResultRepository jobs, TicketSuggestionRepository suggestions) {
        this.jobs = jobs;
        this.suggestions = suggestions;
    }

    // 외부 호출·출력 검증을 끝낸 뒤에만 이 짧은 Transaction으로 들어온다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiSuggestionCompletion complete(AiJobClaim claim, ContractValidatedOutput output) {
        Objects.requireNonNull(claim);
        Objects.requireNonNull(output);
        try {
            if (!jobs.lockCurrentExecution(claim)) {
                return AiSuggestionCompletion.NOT_CURRENT;
            }
            AiJobStatus status;
            AiSuggestionCompletion completion;
            if (output.decision() == Decision.SUGGEST) {
                suggestions.save(claim.jobId(), output);
                status = AiJobStatus.SUCCEEDED;
                completion = AiSuggestionCompletion.STORED;
            } else {
                status = AiJobStatus.ABSTAINED;
                completion = AiSuggestionCompletion.ABSTAINED;
            }
            if (!jobs.finishIfCurrent(claim, status)) {
                throw new AiSuggestionStorageException();
            }
            return completion;
        } catch (DataAccessException exception) {
            // 재시도와 FAILED 기록은 별도 정책이다. 여기서는 결과 Transaction만 Rollback한다.
            throw new AiSuggestionStorageException();
        }
    }

    // 세 SELECT가 같은 Snapshot을 본다. HTTP 공개나 자동 재호출은 하지 않는다.
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ)
    public Optional<AiSuggestionStoredResult> findStoredResult(long jobId) {
        try {
            return jobs.findByJobId(jobId).map(job -> new AiSuggestionStoredResult(
                    job, suggestions.findByJobId(jobId)));
        } catch (DataAccessException exception) {
            throw new AiSuggestionStorageException();
        }
    }
}
