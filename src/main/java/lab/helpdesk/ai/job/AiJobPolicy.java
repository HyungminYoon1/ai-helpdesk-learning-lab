package lab.helpdesk.ai.job;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("helpdesk.ai.job")
public record AiJobPolicy(
        @DefaultValue("job-policy-v1") String policyVersion,
        @DefaultValue("3") int maxGenerationAttempts,
        @DefaultValue("1") int maxOutputRepairAttempts,
        @DefaultValue("60000") long requestTimeoutMs,
        @DefaultValue("120000") long attemptLeaseMs,
        @DefaultValue("5000") long retryBackoffMs,
        @DefaultValue("300000") long jobProcessingTimeoutMs) {

    public AiJobPolicy {
        if (policyVersion == null || !policyVersion.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("invalid job policy version");
        }
        if (maxGenerationAttempts < 1 || maxOutputRepairAttempts < 0) {
            throw new IllegalArgumentException("invalid job attempt limits");
        }
        if (requestTimeoutMs <= 0 || attemptLeaseMs <= requestTimeoutMs
                || retryBackoffMs <= 0 || jobProcessingTimeoutMs <= 0) {
            throw new IllegalArgumentException("invalid job time limits");
        }
    }
}
