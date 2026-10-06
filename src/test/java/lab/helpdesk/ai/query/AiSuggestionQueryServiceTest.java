package lab.helpdesk.ai.query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import lab.helpdesk.ai.query.AiSuggestionQueryException.Code;
import lab.helpdesk.ticket.application.TicketNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiSuggestionQueryServiceTest {

    private final AiSuggestionQueryRepository repository = mock(AiSuggestionQueryRepository.class);
    private final AiSuggestionQueryService service = new AiSuggestionQueryService(repository);

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void nonpositive_id_is_rejected_before_database_access(long id) {
        assertThatThrownBy(() -> service.findByTicketId(id))
                .isInstanceOf(AiSuggestionQueryException.class)
                .hasMessage(Code.INVALID_TICKET_ID.name()).hasNoCause();
        verifyNoInteractions(repository);
    }

    @Test
    void missing_ticket_is_not_a_normal_null_result() {
        when(repository.findByTicketId(7)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findByTicketId(7)).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void database_exception_is_not_returned_as_absence_or_with_raw_cause() {
        when(repository.findByTicketId(7)).thenThrow(
                new DataAccessResourceFailureException("SYNTHETIC_PRIVATE_QUERY_CAUSE"));
        assertThatThrownBy(() -> service.findByTicketId(7))
                .isInstanceOf(AiSuggestionQueryException.class)
                .hasMessage(Code.AI_RESULT_QUERY_FAILED.name()).hasNoCause();
    }

    @ParameterizedTest
    @MethodSource("inconsistentResults")
    void inconsistent_results_do_not_become_normal_or_undetermined(StoredAiSuggestionQuery stored) {
        when(repository.findByTicketId(7)).thenReturn(Optional.of(stored));
        assertThatThrownBy(() -> service.findByTicketId(7))
                .isInstanceOf(AiSuggestionQueryException.class)
                .hasMessage(Code.AI_RESULT_INCONSISTENT.name()).hasNoCause();
    }

    static Stream<StoredAiSuggestionQuery> inconsistentResults() {
        var valid = suggestion(List.of("ACCOUNT"), "NORMAL", "PENDING_REVIEW");
        return Stream.of(
                new StoredAiSuggestionQuery(8, null, null),
                new StoredAiSuggestionQuery(7, null, valid),
                state("SUCCEEDED", null, null),
                state("PENDING", null, valid),
                state("RUNNING", null, valid),
                state("FAILED", "PROVIDER_REFUSED", valid),
                state("ABSTAINED", null, valid),
                state("UNKNOWN_STATUS", null, null),
                state("FAILED", null, null),
                state("FAILED", "SYNTHETIC_UNSAFE_FAILURE", null),
                state("SUCCEEDED", null, suggestion(List.of(), "NORMAL", "PENDING_REVIEW")),
                state("SUCCEEDED", null, suggestion(List.of("ACCOUNT", "ACCOUNT"), "NORMAL", "PENDING_REVIEW")),
                state("SUCCEEDED", null, suggestion(List.of("UNKNOWN_CATEGORY"), "NORMAL", "PENDING_REVIEW")),
                state("SUCCEEDED", null, suggestion(List.of("ACCOUNT"), "UNKNOWN_PRIORITY", "PENDING_REVIEW")),
                state("SUCCEEDED", null, suggestion(List.of("ACCOUNT"), "NORMAL", "UNKNOWN_REVIEW")));
    }

    private static StoredAiSuggestionQuery state(String status, String failure,
            StoredAiSuggestionQuery.Suggestion suggestion) {
        return new StoredAiSuggestionQuery(7, new StoredAiSuggestionQuery.Job(90, status, failure), suggestion);
    }

    private static StoredAiSuggestionQuery.Suggestion suggestion(
            List<String> categories, String priority, String reviewStatus) {
        return new StoredAiSuggestionQuery.Suggestion(12, "합성 요약", categories,
                priority, reviewStatus, Instant.parse("2026-10-06T00:00:00Z"));
    }
}
