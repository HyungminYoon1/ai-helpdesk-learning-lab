package lab.helpdesk.testing;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/** Observe actual cleanup after the default AFTER_CLASS dirty-context listener has run. */
public final class ClassOwnedContextCleanupVerifier extends AbstractTestExecutionListener {
    private static final String RESOURCE = ClassOwnedContextCleanupVerifier.class.getName();
    private record Captured(ConfigurableApplicationContext context, HikariDataSource source, String poolName) { }

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override public void beforeTestExecution(TestContext testContext) {
        if (testContext.getAttribute(RESOURCE) != null) return;
        var context = (ConfigurableApplicationContext) testContext.getApplicationContext();
        var source = context.getBean(DataSource.class);
        if (!(source instanceof HikariDataSource hikari)) {
            throw new AssertionError("cleanup probe requires the test-owned Hikari data source");
        }
        testContext.setAttribute(RESOURCE, new Captured(context, hikari, hikari.getPoolName()));
    }

    @Override public void afterTestClass(TestContext testContext) throws InterruptedException {
        var captured = (Captured) testContext.removeAttribute(RESOURCE);
        if (captured == null) throw new AssertionError("cleanup probe did not observe a test context");
        if (captured.context().isActive() || !captured.source().isClosed()) {
            throw new AssertionError("class-owned context or data source remains open after the test class");
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (livePoolThreads(captured.poolName()) > 0 && System.nanoTime() < deadline) Thread.sleep(10);
        if (livePoolThreads(captured.poolName()) != 0) throw new AssertionError("test-owned pool threads remain after close");
        System.out.println("TEST_CLASS_CONTEXT_CLEANUP completed=true contextInactive=true dataSourceClosed=true poolThreads=0 test="
                + testContext.getTestClass().getSimpleName());
    }

    private static long livePoolThreads(String poolName) {
        if (poolName == null || poolName.isBlank()) throw new AssertionError("cleanup probe requires a started pool");
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().startsWith(poolName + ":")).count();
    }
}
