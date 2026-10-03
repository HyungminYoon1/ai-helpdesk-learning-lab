package lab.helpdesk.ai.job;

public interface AiSuggestionJobRepository {

    long enqueue(long inputMessageId);
}
