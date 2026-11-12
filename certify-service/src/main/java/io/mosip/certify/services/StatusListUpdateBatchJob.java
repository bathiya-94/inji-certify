package io.mosip.certify.services;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.mosip.certify.repository.CredentialStatusTransactionRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Publishes pending revocations. Each run asks the publication service to handle one list at a time and keeps
 * going while there is work, so a backlog is drained in one run.
 * <p>
 * Only one node runs the job per interval (ShedLock). The lease is shorter than the interval so a node that
 * crashes mid-run costs at most one run; the per-list database lock in the publication service keeps two nodes
 * from ever publishing the same list together, for example if a run outlasts its lease.
 */
@Slf4j
@Service
public class StatusListUpdateBatchJob {

    private static final int LISTS_PER_PASS = 100;
    private static final int MAX_PASSES = 50;

    @Autowired
    private CredentialStatusTransactionRepository transactionRepository;

    @Autowired
    private StatusListPublicationService publicationService;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Value("${mosip.certify.batch.status-list-update.enabled:true}")
    private boolean batchJobEnabled;

    @Value("${mosip.certify.batch.status-list-update.batch-size:1000}")
    private int batchSize;

    private Clock clock = Clock.systemUTC();
    private Timer publicationTimer;
    private Counter failureCounter;

    @PostConstruct
    void registerMetrics() {
        if (meterRegistry == null) {
            return;
        }
        Gauge.builder("certify.status_list.pending", transactionRepository, CredentialStatusTransactionRepository::countByIsProcessedFalse)
                .description("Revocations accepted but not yet published").register(meterRegistry);
        Gauge.builder("certify.status_list.oldest_pending_age_seconds", this, StatusListUpdateBatchJob::oldestPendingAgeSeconds)
                .description("Age of the oldest unpublished revocation").register(meterRegistry);
        publicationTimer = Timer.builder("certify.status_list.publication.duration")
                .description("Time to publish one status list").register(meterRegistry);
        failureCounter = Counter.builder("certify.status_list.publication.failures")
                .description("Status list publications that failed").register(meterRegistry);
    }

    private double oldestPendingAgeSeconds() {
        return transactionRepository.findOldestPendingTime()
                .map(oldest -> (double) Duration.between(oldest, LocalDateTime.now(clock)).toSeconds())
                .orElse(0.0);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Scheduled(cron = "${mosip.certify.batch.status-list-update.cron-expression:0 0/1 * * * *}")
    @SchedulerLock(
            name = "updateStatusLists",
            lockAtMostFor = "${mosip.certify.batch.status-list-update.lock-at-most-for:55s}",
            lockAtLeastFor = "${mosip.certify.batch.status-list-update.lock-at-least-for:50s}"
    )
    public void updateStatusLists() {
        LockAssert.assertLocked();
        runOnce();
    }

    /** One run: publish every list that has pending changes. Package-visible so tests need not hold the lock. */
    void runOnce() {
        if (!batchJobEnabled) {
            log.info("Status list update batch job is disabled");
            return;
        }
        // A list that failed, or that another node is publishing, is left alone for the rest of this run so the
        // other lists make progress and there is no tight retry loop.
        Set<String> skipped = new HashSet<>();
        int published = 0;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean progressed = false;
            List<String> lists = transactionRepository.findListsWithPendingWork(LISTS_PER_PASS);
            for (String listId : lists) {
                if (skipped.contains(listId)) {
                    continue;
                }
                try {
                    long started = System.nanoTime();
                    int count = publicationService.publish(listId, batchSize);
                    if (count > 0) {
                        progressed = true;
                        published += count;
                        if (publicationTimer != null) {
                            publicationTimer.record(Duration.ofNanos(System.nanoTime() - started));
                        }
                    } else {
                        skipped.add(listId);
                    }
                } catch (Exception e) {
                    skipped.add(listId);
                    if (failureCounter != null) {
                        failureCounter.increment();
                    }
                    log.error("Failed to publish status list {}; its changes stay pending", listId, e);
                }
            }
            if (!progressed) {
                break;
            }
        }
        if (published > 0) {
            log.info("Status list update completed: {} change(s) published", published);
        }
    }
}
