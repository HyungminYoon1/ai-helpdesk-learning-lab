package lab.helpdesk.ai.query;

import java.util.Optional;

public interface AiSuggestionQueryRepository {

    Optional<StoredAiSuggestionQuery> findByTicketId(long ticketId);
}
