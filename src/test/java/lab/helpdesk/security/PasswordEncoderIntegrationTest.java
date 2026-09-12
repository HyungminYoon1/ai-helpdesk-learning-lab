package lab.helpdesk.security;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PasswordEncoderIntegrationTest {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void same_candidate_has_distinct_encodings_that_both_match() {
        String candidate = UUID.randomUUID().toString();
        String wrongCandidate = candidate + "-different";

        String encodedA = passwordEncoder.encode(candidate);
        String encodedB = passwordEncoder.encode(candidate);

        assertThat(encodedA.equals(encodedB)).isFalse();
        assertThat(passwordEncoder.matches(candidate, encodedA)).isTrue();
        assertThat(passwordEncoder.matches(candidate, encodedB)).isTrue();
        assertThat(passwordEncoder.matches(wrongCandidate, encodedA)).isFalse();
    }
}
