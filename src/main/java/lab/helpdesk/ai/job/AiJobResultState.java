package lab.helpdesk.ai.job;

public record AiJobResultState(long jobId, int currentAttempt, AiJobStatus status) {
}
