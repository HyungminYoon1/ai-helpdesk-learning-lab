package experiment.helpdesk.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyntheticRuntimeResourceExperimentTest {
    @ParameterizedTest
    @ValueSource(strings = {"NORMAL", "TIMEOUT", "NON_ZERO_EXIT"})
    void acceptsOnlyExplicitExperimentModes(String mode) {
        var environment = new MockEnvironment().withProperty("helpdesk.runtime.probe.scan-mode", mode);
        assertThat(SyntheticRuntimeResourceExperiment.scanMode(environment).name()).isEqualTo(mode);
    }

    @Test
    void missingModeCannotSilentlyEnableAnExperiment() {
        assertThatThrownBy(() -> SyntheticRuntimeResourceExperiment.scanMode(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void arbitraryModeIsNotAnExecutableOrRequestSelectedOption() {
        var environment = new MockEnvironment().withProperty("helpdesk.runtime.probe.scan-mode", "CUSTOM_COMMAND");
        assertThatThrownBy(() -> SyntheticRuntimeResourceExperiment.scanMode(environment))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
