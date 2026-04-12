package br.com.fzdevx.infrastructure.config;

import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class AnalysisExecutorProducer {

    @Inject
    @ConfigProperty(name = "log.analyzer.parallel-threads", defaultValue = "auto")
    String parallelThreads;

    private ExecutorService executor;

    @PostConstruct
    void init() {
        int threads;
        String mode;
        if ("auto".equalsIgnoreCase(parallelThreads.trim())) {
            // Reserve 2 cores for HTTP serving and OS overhead
            threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
            mode = "auto-detected";
        } else {
            try {
                threads = Math.max(1, Integer.parseInt(parallelThreads.trim()));
                mode = "configured";
            } catch (NumberFormatException e) {
                threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
                mode = "auto-detected (invalid config: " + parallelThreads + ")";
                Log.warnf("Invalid log.analyzer.parallel-threads value '%s', using auto (%d)", parallelThreads, threads);
            }
        }
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);
        executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "log-analysis-worker-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        Log.infof("Log analysis executor: %d threads (%s)", threads, mode);
    }

    @Produces
    @ApplicationScoped
    @Named("analysisExecutor")
    public ExecutorService analysisExecutor() {
        return executor;
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
