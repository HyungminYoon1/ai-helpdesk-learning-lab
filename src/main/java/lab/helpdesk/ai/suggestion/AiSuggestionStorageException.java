package lab.helpdesk.ai.suggestion;

// 실패 Row·요약·Driver 오류의 원문을 Exception Message나 Cause에 복사하지 않는다.
public final class AiSuggestionStorageException extends RuntimeException {

    public AiSuggestionStorageException() {
        super("AI_RESULT_STORAGE_FAILED");
    }
}
