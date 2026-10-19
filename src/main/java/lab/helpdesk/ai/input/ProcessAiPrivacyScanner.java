package lab.helpdesk.ai.input;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import lab.helpdesk.ai.input.AiPrivacyScanResultValidator.InvalidScanResultException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit, local foreground-process transport; not a detector or an auto-configured Bean.
 * The administrator supplies a fixed executable/argument list, never a shell command or
 * request-derived arguments. The program must not daemonize or spawn other processes.
 * JSON/position validation and masking remain the preparer's responsibility.
 */
public final class ProcessAiPrivacyScanner implements AiPrivacyScanner {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final List<String> command;
    private final int maxInputUtf8Bytes;
    private final int maxResponseUtf8Bytes;
    private final int maxErrorBytes;
    private final Duration cleanupTimeout;

    /** No runtime defaults: limits and a local absolute executable are explicit. */
    public ProcessAiPrivacyScanner(List<String> command, int maxInputUtf8Bytes,
            int maxResponseUtf8Bytes, int maxErrorBytes, Duration cleanupTimeout) {
        if (command == null || command.isEmpty()
                || command.stream().anyMatch(value -> value == null || value.indexOf('\0') >= 0)
                || command.getFirst().isBlank()
                || maxInputUtf8Bytes < 1 || maxResponseUtf8Bytes < 1 || maxErrorBytes < 1
                || !positiveNanos(cleanupTimeout)) {
            throw configurationFailure();
        }
        try {
            if (!Path.of(command.getFirst()).isAbsolute()) throw configurationFailure();
        } catch (RuntimeException exception) {
            // Paths, command arguments and operating-system messages are not safe diagnostics.
            throw configurationFailure();
        }
        this.command = List.copyOf(command);
        this.maxInputUtf8Bytes = maxInputUtf8Bytes;
        this.maxResponseUtf8Bytes = maxResponseUtf8Bytes;
        this.maxErrorBytes = maxErrorBytes;
        this.cleanupTimeout = cleanupTimeout;
    }

    @Override
    public String scan(String originalTitle, String originalBody, Duration timeout) {
        if (originalTitle == null || originalTitle.isBlank()
                || originalBody == null || originalBody.isBlank()) {
            throw new IllegalArgumentException("AI_INPUT_REQUIRED");
        }
        if (!positiveNanos(timeout)) throw configurationFailure();
        if (Thread.currentThread().isInterrupted()) throw interruptedFailure();

        long started = System.nanoTime();
        byte[] request = serialize(originalTitle, originalBody);
        Process process = null;
        ExecutorService io = null;
        try {
            if (remaining(started, timeout) <= 0) throw new ScanTimeoutException();
            var builder = new ProcessBuilder(command);
            // Do not pass App keys, credentials, JVM options or ambient proxy settings.
            // An OS may add its own minimal process environment; no App values are copied.
            builder.environment().clear();
            process = builder.start();
            Process owned = process;
            io = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().daemon(true)
                    .name("helpdesk-privacy-io-" + owned.pid() + "-", 0).factory());
            var completions = new ExecutorCompletionService<Part>(io);
            completions.submit(() -> {
                try (var stdin = owned.getOutputStream()) {
                    stdin.write(request);
                }
                return new Part(null);
            });
            completions.submit(() -> new Part(readResponse(owned.getInputStream())));
            completions.submit(() -> {
                discardError(owned.getErrorStream());
                return new Part(null);
            });
            completions.submit(() -> {
                if (owned.waitFor() != 0) throw executionFailure();
                return new Part(null);
            });

            byte[] response = null;
            for (int completed = 0; completed < 4; completed++) {
                long nanos = remaining(started, timeout);
                if (nanos <= 0) throw new ScanTimeoutException();
                var finished = completions.poll(nanos, TimeUnit.NANOSECONDS);
                if (finished == null) throw new ScanTimeoutException();
                Part part = finished.get(); // A failed stream aborts without waiting for the others.
                if (part.response() != null) response = part.response();
            }
            if (remaining(started, timeout) <= 0) throw new ScanTimeoutException();
            if (response == null || response.length == 0) throw new InvalidScanResultException();
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(response)).toString();
            } catch (CharacterCodingException exception) {
                throw new InvalidScanResultException();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw interruptedFailure();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof InvalidScanResultException) {
                throw new InvalidScanResultException();
            }
            // Do not retain stream exceptions, stderr, OS messages or their causes.
            throw executionFailure();
        } catch (IOException exception) {
            throw executionFailure();
        } catch (RuntimeException exception) {
            if (exception instanceof ScanTimeoutException || exception instanceof InvalidScanResultException) {
                throw exception;
            }
            throw executionFailure();
        } finally {
            if (process != null) cleanup(process, io);
        }
    }

    private byte[] serialize(String title, String body) {
        // Bound work before allocating escaped JSON. Byte length is checked again afterwards.
        if (title.length() > maxInputUtf8Bytes || body.length() > maxInputUtf8Bytes) {
            throw configurationFailure();
        }
        byte[] bytes;
        try {
            bytes = MAPPER.writeValueAsBytes(Map.of("title", title, "body", body));
        } catch (RuntimeException exception) {
            throw configurationFailure();
        }
        if (bytes.length > maxInputUtf8Bytes) throw configurationFailure();
        return bytes;
    }

    private byte[] readResponse(InputStream stdout) throws IOException {
        try (stdout; var bytes = new ByteArrayOutputStream(Math.min(4_096, maxResponseUtf8Bytes))) {
            byte[] buffer = new byte[4_096];
            int size;
            while ((size = stdout.read(buffer)) != -1) {
                if (size > maxResponseUtf8Bytes - bytes.size()) {
                    throw new InvalidScanResultException();
                }
                bytes.write(buffer, 0, size);
            }
            return bytes.toByteArray();
        }
    }

    private void discardError(InputStream stderr) throws IOException {
        try (stderr) {
            byte[] buffer = new byte[4_096];
            int total = 0;
            int size;
            while ((size = stderr.read(buffer)) != -1) {
                if (size > maxErrorBytes - total) throw executionFailure();
                total += size;
            }
        }
    }

    private void cleanup(Process process, ExecutorService io) {
        // Interruption must not skip cleanup, but must be restored for the caller afterwards.
        boolean interrupted = Thread.interrupted();
        long started = System.nanoTime();
        try {
            if (process.isAlive()) process.destroyForcibly();
            while (process.isAlive()) {
                long nanos = remaining(started, cleanupTimeout);
                if (nanos <= 0) throw cleanupFailure();
                try {
                    process.waitFor(nanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            // Killing the owned process closes the other ends before closing local pipe handles.
            closeQuietly(process.getOutputStream());
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
        } finally {
            try {
                if (io != null) {
                    io.shutdownNow();
                    while (!io.isTerminated()) {
                        long nanos = remaining(started, cleanupTimeout);
                        if (nanos <= 0) throw cleanupFailure();
                        try {
                            io.awaitTermination(nanos, TimeUnit.NANOSECONDS);
                        } catch (InterruptedException exception) {
                            interrupted = true;
                        }
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeQuietly(AutoCloseable stream) {
        try {
            stream.close();
        } catch (Exception ignored) {
            // No raw I/O diagnostic or input/output payload is propagated.
        }
    }

    private static long remaining(long started, Duration timeout) {
        return timeout.toNanos() - (System.nanoTime() - started);
    }

    private static boolean positiveNanos(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) return false;
        try {
            return duration.toNanos() > 0;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private static IllegalArgumentException configurationFailure() {
        return new IllegalArgumentException("AI_PRIVACY_CONFIGURATION_INVALID");
    }

    private static IllegalStateException executionFailure() {
        return new IllegalStateException("AI_PRIVACY_PROCESS_EXECUTION_FAILED");
    }

    private static IllegalStateException interruptedFailure() {
        return new IllegalStateException("AI_PRIVACY_PROCESS_INTERRUPTED");
    }

    private static IllegalStateException cleanupFailure() {
        return new IllegalStateException("AI_PRIVACY_PROCESS_CLEANUP_UNCONFIRMED");
    }

    @Override
    public String toString() {
        return "ProcessAiPrivacyScanner[configuration omitted]";
    }

    private record Part(byte[] response) {
        @Override
        public String toString() {
            return "Part[content omitted]";
        }
    }
}
