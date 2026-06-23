package com.commitgotchi.report.application;

import com.commitgotchi.character.api.dto.FastApiScoreDelta;
import com.commitgotchi.report.domain.ReportRequestOutbox;
import com.commitgotchi.report.domain.ReportRequestOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class ReportOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ReportOutboxDispatcher.class);
    private static final int MAX_ERROR_LENGTH = 500;

    private final ReportRequestOutboxRepository outboxRepository;
    private final ReportRequestProducer producer;
    private final ReportDispatcherProperties properties;

    public ReportOutboxDispatcher(
            ReportRequestOutboxRepository outboxRepository,
            ReportRequestProducer producer,
            ReportDispatcherProperties properties
    ) {
        this.outboxRepository = outboxRepository;
        this.producer = producer;
        this.properties = properties;
    }

    @Transactional
    public DispatchResult dispatchAvailable(Instant now) {
        Instant dispatchTime = Objects.requireNonNull(now, "now must not be null");
        List<ReportRequestOutbox> claimed = outboxRepository.claimAvailable(dispatchTime, properties.getBatchSize());
        int sent = 0;
        int retry = 0;
        int failed = 0;

        for (ReportRequestOutbox row : claimed) {
            try {
                producer.send(toMessage(row));
                outboxRepository.markSent(row.getId(), dispatchTime);
                sent++;
            } catch (RuntimeException exception) {
                FailureTransition transition = recordFailure(row, exception, dispatchTime);
                if (transition.failed()) {
                    failed++;
                } else {
                    retry++;
                }
            }
        }

        if (!claimed.isEmpty()) {
            log.info(
                    "Report outbox dispatch completed claimed={} sent={} retry={} failed={}",
                    claimed.size(),
                    sent,
                    retry,
                    failed
            );
        }
        return new DispatchResult(claimed.size(), sent, retry, failed);
    }

    private FailureTransition recordFailure(
            ReportRequestOutbox row,
            RuntimeException exception,
            Instant dispatchTime
    ) {
        int nextAttempt = row.getAttemptCount() + 1;
        String lastError = sanitizedError(exception);
        if (nextAttempt >= properties.getMaxAttempts()) {
            outboxRepository.markFailed(row.getId(), nextAttempt, lastError, dispatchTime);
            return new FailureTransition(true);
        }

        Instant availableAt = dispatchTime.plus(properties.getRetryDelay());
        outboxRepository.markRetryableFailure(row.getId(), nextAttempt, availableAt, lastError, dispatchTime);
        return new FailureTransition(false);
    }

    private String sanitizedError(RuntimeException exception) {
        String type = exception.getClass().getSimpleName();
        String message = exception.getMessage();
        String value = message == null || message.isBlank() ? type : type + ": " + message.strip();
        if (value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }

    private ReportRequestMessage toMessage(ReportRequestOutbox row) {
        return new ReportRequestMessage(
                row.getRequestId(),
                row.getUserId(),
                row.getTargetDate(),
                new ReportRequestMessage.UserMetadata(
                        row.getWeeklyStudyStreak(),
                        new ReportRequestMessage.ReportDirection(
                                new FastApiScoreDelta(
                                        row.getScoreDeltaHintDb(),
                                        row.getScoreDeltaHintAlgorithm(),
                                        row.getScoreDeltaHintCs(),
                                        row.getScoreDeltaHintNetwork(),
                                        row.getScoreDeltaHintFramework()
                                ),
                                row.getFocus()
                        )
                ),
                new ReportRequestMessage.CharacterMetadata(
                        row.getCharacterId(),
                        row.getCharacterName(),
                        row.getCharacterPersonality(),
                        row.getCharacterEmotion(),
                        new ReportRequestMessage.CurrentStats(
                                row.getCharacterStatDb(),
                                row.getCharacterStatAlgorithm(),
                                row.getCharacterStatCs(),
                                row.getCharacterStatNetwork(),
                                row.getCharacterStatFramework()
                        )
                ),
                new ReportRequestMessage.DailyReport(row.getReportTitle(), row.getReportContent())
        );
    }

    public record DispatchResult(int claimedCount, int sentCount, int retryCount, int failedCount) {
    }

    private record FailureTransition(boolean failed) {
    }
}
