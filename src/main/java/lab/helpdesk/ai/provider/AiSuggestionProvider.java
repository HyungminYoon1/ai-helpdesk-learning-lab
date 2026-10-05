package lab.helpdesk.ai.provider;

import java.time.Duration;

import lab.helpdesk.ai.input.AiInputPrivacyGuard.PreparedInput;
import lab.helpdesk.ai.job.AiJobRequestKind;

/** One call means one reserved generation request; an Adapter must not retry internally. */
public interface AiSuggestionProvider {

    String generate(PreparedInput input, Duration timeout, AiJobRequestKind requestKind);
}
