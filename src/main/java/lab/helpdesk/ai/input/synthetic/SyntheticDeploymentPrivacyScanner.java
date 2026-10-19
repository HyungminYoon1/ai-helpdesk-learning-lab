package lab.helpdesk.ai.input.synthetic;

import java.time.Duration;
import java.util.Objects;

import lab.helpdesk.ai.input.AiPrivacyScanner;

/** Exact, fixed synthetic experiment scope. This is not a general PII detector. */
public final class SyntheticDeploymentPrivacyScanner implements AiPrivacyScanner {
    public static final String TITLE = "합성 배포 검증 문의";
    public static final String EMAIL = "a@b.invalid";
    public static final String BODY = "로그인 링크가 만료됐지만 새 링크로 로그인에 성공했습니다. "
            + "합성 연락처 a@b.invalid로 안내를 요청합니다.";
    private final AiPrivacyScanner delegate;

    public SyntheticDeploymentPrivacyScanner(AiPrivacyScanner delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public String scan(String title, String body, Duration timeout) {
        requireAllowed(title, body); // Before starting a local process, not just before HTTP.
        return delegate.scan(title, body, timeout);
    }

    static void requireAllowed(String title, String body) {
        // No trimming, substring match, request-selected case, or Unicode normalization.
        if (!TITLE.equals(title) || !BODY.equals(body)) {
            throw new IllegalArgumentException("AI_SYNTHETIC_INPUT_NOT_ALLOWED");
        }
    }

    @Override
    public String toString() {
        return "SyntheticDeploymentPrivacyScanner[fixed synthetic case only]";
    }
}
