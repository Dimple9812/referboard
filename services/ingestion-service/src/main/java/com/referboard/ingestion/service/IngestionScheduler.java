package com.referboard.ingestion.service;

import com.referboard.ingestion.config.IngestionProperties;
import com.referboard.ingestion.domain.IngestionRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Every 6 hours by default ({@code INGESTION_CRON}); optional run at startup; manual runs via the admin API. */
@Component
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final IngestionOrchestrator orchestrator;
    private final SourceRegistry registry;
    private final IngestionProperties props;
    private final TaskExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public IngestionScheduler(IngestionOrchestrator orchestrator, SourceRegistry registry, IngestionProperties props,
                              @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.orchestrator = orchestrator;
        this.registry = registry;
        this.props = props;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        registry.sync();
        if (props.runOnStartup()) {
            trigger(IngestionRun.Trigger.STARTUP);
        }
    }

    @Scheduled(cron = "${referboard.ingestion.cron:0 0 */6 * * *}")
    public void scheduled() {
        runNow(IngestionRun.Trigger.SCHEDULED);
    }

    /** Starts a run in the background. Returns false if this instance is already running one. */
    public boolean trigger(IngestionRun.Trigger trigger) {
        return trigger(trigger, false);
    }

    public boolean trigger(IngestionRun.Trigger trigger, boolean forceRepublish) {
        if (running.get()) {
            return false;
        }
        executor.execute(() -> runNow(trigger, forceRepublish));
        return true;
    }

    private void runNow(IngestionRun.Trigger trigger) {
        runNow(trigger, false);
    }

    private void runNow(IngestionRun.Trigger trigger, boolean forceRepublish) {
        if (!running.compareAndSet(false, true)) {
            log.info("Ingestion already running in this instance; {} trigger ignored", trigger);
            return;
        }
        try {
            orchestrator.run(trigger, forceRepublish);
        } catch (Exception e) {
            log.error("Ingestion run ({}) crashed", trigger, e);
        } finally {
            running.set(false);
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
