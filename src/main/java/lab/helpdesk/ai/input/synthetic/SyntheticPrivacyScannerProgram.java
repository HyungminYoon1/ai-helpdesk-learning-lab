package lab.helpdesk.ai.input.synthetic;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Foreground child for one known synthetic case; no Spring, keys, model or network. */
public final class SyntheticPrivacyScannerProgram {
    static final int MAX_INPUT_BYTES = 4_096;
    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(1).build()).build())
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private SyntheticPrivacyScannerProgram() { }

    public static void main(String[] args) {
        int code = run(System.in, System.out);
        if (code != 0) {
            System.err.println("SYNTHETIC_PRIVACY_SCAN_FAILED");
            System.exit(code);
        }
    }

    static int run(InputStream input, OutputStream output) {
        try {
            byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
            if (bytes.length > MAX_INPUT_BYTES) return 9;
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            var root = MAPPER.readTree(text);
            if (root == null || !root.isObject() || root.size() != 2
                    || !root.propertyNames().containsAll(Set.of("title", "body"))
                    || !root.get("title").isString() || !root.get("body").isString()) return 9;
            SyntheticDeploymentPrivacyScanner.requireAllowed(root.get("title").asString(), root.get("body").asString());
            String body = SyntheticDeploymentPrivacyScanner.BODY;
            int offset = body.indexOf(SyntheticDeploymentPrivacyScanner.EMAIL);
            int start = body.codePointCount(0, offset);
            int length = SyntheticDeploymentPrivacyScanner.EMAIL.codePointCount(0,
                    SyntheticDeploymentPrivacyScanner.EMAIL.length());
            // The location is fixed fixture data, not a detection result for arbitrary input.
            byte[] result = MAPPER.writeValueAsBytes(Map.of("completed", true, "spans", List.of(
                    Map.of("field", "body", "start", start, "end", start + length, "type", "EMAIL"))));
            output.write(result);
            output.flush();
            return 0;
        } catch (IOException | RuntimeException exception) {
            // No original input, parser message, file path or cause goes to either pipe.
            return 9;
        }
    }
}
