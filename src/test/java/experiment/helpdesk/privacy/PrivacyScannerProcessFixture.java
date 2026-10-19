package experiment.helpdesk.privacy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/** Controlled local process, not a privacy model. Never loads credentials or uses a network. */
public final class PrivacyScannerProcessFixture {
    public static final String TITLE = "  문의 \"따옴표\" 😀  ";
    public static final String BODY = "메일 a@b.invalid; 로그인 성공\n$(not-a-command)";
    public static final String VALID = "{\"completed\":true,\"spans\":["
            + "{\"field\":\"body\",\"start\":3,\"end\":14,\"type\":\"EMAIL\"}]}";
    public static final String EMPTY = "{\"completed\":true,\"spans\":[]}";

    private PrivacyScannerProcessFixture() {
    }

    public static void main(String[] args) {
        try {
            // PID/start identity only, published atomically; no request or environment value is written to disk.
            Path identity = Path.of(args[1]);
            Path staging = identity.resolveSibling(identity.getFileName() + ".tmp");
            Files.writeString(staging, ProcessHandle.current().pid() + " "
                    + ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli(), StandardCharsets.UTF_8);
            Files.move(staging, identity, StandardCopyOption.ATOMIC_MOVE);
            for (String key : List.of("OPENAI_API_KEY", "HELPDESK_OPENAI_API_KEY", "JAVA_TOOL_OPTIONS",
                    "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "SPRING_DATASOURCE_PASSWORD")) {
                if (System.getenv(key) != null) throw new IllegalStateException();
            }
            switch (args[0]) {
                case "VALIDATE_INPUT" -> {
                    var input = JsonMapper.builder().build().readTree(System.in.readAllBytes());
                    if (input.size() != 2 || !TITLE.equals(input.path("title").asString())
                            || !BODY.equals(input.path("body").asString())) {
                        throw new IllegalStateException();
                    }
                    write(VALID);
                }
                case "EMPTY_JSON" -> { System.in.readAllBytes(); write(EMPTY); }
                case "EMAIL_SPAN" -> {
                    var mapper = JsonMapper.builder().build();
                    String body = mapper.readTree(System.in.readAllBytes()).path("body").asString();
                    int at = body.indexOf("a@b.invalid");
                    if (at < 0) throw new IllegalStateException();
                    int start = body.codePointCount(0, at);
                    write(mapper.writeValueAsString(Map.of("completed", true, "spans", List.of(
                            Map.of("field", "body", "start", start, "end", start + 11, "type", "EMAIL")))));
                }
                case "EMPTY_OUTPUT" -> System.in.readAllBytes();
                case "INVALID_JSON" -> { System.in.readAllBytes(); write("{"); }
                case "INVALID_UTF8" -> { System.in.readAllBytes(); System.out.write(new byte[] {(byte) 0xc3, 0x28}); }
                case "NONZERO" -> { System.in.readAllBytes(); write(EMPTY); System.exit(7); }
                case "RAW_STDERR_FAILURE" -> {
                    System.err.write(System.in.readAllBytes());
                    System.exit(7);
                }
                case "HANG", "NEVER_READ_STDIN" -> hang();
                case "VALID_BUT_ALIVE" -> { System.in.readAllBytes(); write(EMPTY); System.out.flush(); hang(); }
                case "FLOOD_STDOUT" -> {
                    byte[] bytes = "가".repeat(2_048).getBytes(StandardCharsets.UTF_8);
                    while (true) { System.out.write(bytes); System.out.flush(); }
                }
                case "FLOOD_STDERR" -> {
                    byte[] bytes = new byte[4_096];
                    while (true) { System.err.write(bytes); System.err.flush(); }
                }
                default -> throw new IllegalStateException();
            }
        } catch (Throwable failure) {
            // Do not echo a malformed input, original text or parser/OS diagnostics.
            System.err.println("PRIVACY_FIXTURE_FAILED");
            System.exit(9);
        }
    }

    private static void write(String value) throws Exception {
        System.out.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void hang() throws InterruptedException {
        while (true) Thread.sleep(1_000);
    }
}
