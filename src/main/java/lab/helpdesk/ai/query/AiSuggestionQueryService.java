package lab.helpdesk.ai.query;

import lab.helpdesk.ai.job.AiJobFailureCode;
import lab.helpdesk.ai.job.AiJobStatus;
import lab.helpdesk.ai.query.AiSuggestionQueryException.Code;
import lab.helpdesk.ai.suggestion.TicketSuggestion;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.Category;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.Priority;
import lab.helpdesk.ticket.application.TicketNotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("postgres")
public class AiSuggestionQueryService {

    private final AiSuggestionQueryRepository repository;

    public AiSuggestionQueryService(AiSuggestionQueryRepository repository) {
        this.repository = repository;
    }

    // Provider·Worker·Claim을 의존하지 않는다. 상태를 읽을 뿐 실행하거나 고치지 않는다.
    @Transactional(readOnly = true)
    public AiSuggestionQueryResult findByTicketId(long ticketId) {
        if (ticketId <= 0) {
            throw new AiSuggestionQueryException(Code.INVALID_TICKET_ID);
        }
        try {
            StoredAiSuggestionQuery stored = repository.findByTicketId(ticketId)
                    .orElseThrow(() -> new TicketNotFoundException(ticketId));
            if (stored.ticketId() != ticketId) {
                throw new AiSuggestionQueryException(Code.AI_RESULT_INCONSISTENT);
            }
            return verifyResult(stored);
        } catch (DataAccessException exception) {
            throw new AiSuggestionQueryException(Code.AI_RESULT_QUERY_FAILED);
        } catch (IllegalArgumentException | NullPointerException exception) {
            // 알 수 없는 DB Enum·분류 부재를 null·UNDETERMINED로 바꾸지 않는다.
            throw new AiSuggestionQueryException(Code.AI_RESULT_INCONSISTENT);
        }
    }

    private AiSuggestionQueryResult verifyResult(StoredAiSuggestionQuery stored) {
        if (stored.job() == null) {
            if (stored.suggestion() != null) {
                throw new AiSuggestionQueryException(Code.AI_RESULT_INCONSISTENT);
            }
            return new AiSuggestionQueryResult(stored.ticketId(), null, null);
        }
        var job = stored.job();
        AiJobStatus status = AiJobStatus.valueOf(job.status());
        if (job.id() <= 0 || (status == AiJobStatus.SUCCEEDED) != (stored.suggestion() != null)) {
            throw new AiSuggestionQueryException(Code.AI_RESULT_INCONSISTENT);
        }
        // RUNNING·PENDING에 남아 있는 이전 실패 코드는 현재의 최종 실패가 아니다.
        AiJobFailureCode failureCode = status == AiJobStatus.FAILED
                ? AiJobFailureCode.valueOf(job.failureCode()) : null;
        TicketSuggestion suggestion = null;
        if (stored.suggestion() != null) {
            var row = stored.suggestion();
            suggestion = new TicketSuggestion(row.id(), job.id(), row.summary(),
                    row.categories().stream().map(Category::valueOf).toList(),
                    Priority.valueOf(row.priority()),
                    TicketSuggestion.ReviewStatus.valueOf(row.reviewStatus()), row.createdAt());
        }
        return new AiSuggestionQueryResult(stored.ticketId(),
                new AiSuggestionQueryResult.Job(job.id(), status, failureCode), suggestion);
    }
}
