package com.commitgotchi.report.domain;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class ReportRequestOutboxRepository {

    private final ReportRequestOutboxMapper mapper;

    public ReportRequestOutboxRepository(ReportRequestOutboxMapper mapper) {
        this.mapper = mapper;
    }

    public ReportRequestOutbox upsertPendingSnapshot(ReportRequestOutbox outbox) {
        mapper.upsertPendingSnapshot(outbox);
        return findByUserCharacterAndTargetDate(
                outbox.getUserId(),
                outbox.getCharacterId(),
                outbox.getTargetDate()
        ).orElseThrow(() -> new IllegalStateException("Report request outbox row was not persisted."));
    }

    public Optional<ReportRequestOutbox> findByUserCharacterAndTargetDate(
            long userId,
            long characterId,
            LocalDate targetDate
    ) {
        return Optional.ofNullable(mapper.findByUserCharacterAndTargetDate(userId, characterId, targetDate));
    }

    public Optional<ReportRequestOutbox> findByRequestId(String requestId) {
        return Optional.ofNullable(mapper.findByRequestId(requestId));
    }

    public List<ReportRequestOutbox> claimAvailable(Instant now, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return mapper.claimAvailable(now, limit);
    }

    public void markSent(long id, Instant sentAt) {
        mapper.markSent(id, sentAt);
    }

    public void markRetryableFailure(long id, int attemptCount, Instant availableAt, String lastError, Instant updatedAt) {
        mapper.markRetryableFailure(id, attemptCount, availableAt, lastError, updatedAt);
    }

    public void markFailed(long id, int attemptCount, String lastError, Instant updatedAt) {
        mapper.markFailed(id, attemptCount, lastError, updatedAt);
    }
}
