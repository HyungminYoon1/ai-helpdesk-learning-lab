package experiment.helpdesk.privacy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Test-only launch metadata; no source text, credentials or model are put in arguments. */
public final class PrivacyScannerProcessSupport {
    private PrivacyScannerProcessSupport() {
    }

    public static Launch prepare(Path temporary, String mode) throws Exception {
        Path args = Files.createTempFile(temporary, "privacy-", ".args");
        Path pid = args.resolveSibling(args.getFileName() + ".pid");
        String javaName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path executable = Path.of(System.getProperty("java.home"), "bin", javaName);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        if (classpath.contains("\n") || classpath.contains("\r")) throw new IllegalStateException("FIXTURE_CLASSPATH_INVALID");
        Files.writeString(args, "-Xms16m\n-Xmx64m\n-XX:ActiveProcessorCount=1\n-Dfile.encoding=UTF-8\n-classpath\n"
                + quote(classpath) + "\n" + PrivacyScannerProcessFixture.class.getName() + "\n"
                + mode + "\n" + quote(pid.toString()) + "\n", StandardCharsets.UTF_8);
        return new Launch(List.of(executable.toString(), "@" + args), pid);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static long pid(Path identity) throws Exception {
        return Long.parseLong(Files.readString(identity, StandardCharsets.UTF_8).trim().split(" ")[0]);
    }

    public static Optional<ProcessHandle> stillOwned(Path identity) throws Exception {
        String[] parts = Files.readString(identity, StandardCharsets.UTF_8).trim().split(" ");
        long pid = Long.parseLong(parts[0]);
        long started = Long.parseLong(parts[1]);
        // A recycled PID is not this fixture. Never terminate a different process by PID alone.
        return ProcessHandle.of(pid).filter(handle -> handle.info().startInstant()
                .map(value -> value.toEpochMilli() == started).orElse(false));
    }

    public static void stopIfStillOwned(Path identity) throws Exception {
        if (!Files.isRegularFile(identity)) return;
        Optional<ProcessHandle> handle = stillOwned(identity);
        if (handle.isPresent() && handle.orElseThrow().isAlive()) {
            handle.orElseThrow().destroyForcibly();
            handle.orElseThrow().onExit().get(5, TimeUnit.SECONDS);
        }
    }

    public record Launch(List<String> command, Path pidFile) {
    }
}
