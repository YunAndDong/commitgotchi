package com.commitgotchi.report.application;

import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.character.domain.LearningCharacterRepository;
import com.commitgotchi.report.domain.ReportEnqueueCandidate;
import com.commitgotchi.report.domain.ReportEnqueueCandidateRepository;
import com.commitgotchi.report.domain.ReportRequestOutbox;
import com.commitgotchi.report.domain.ReportRequestOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReportMidnightEnqueueService {

    private static final Logger log = LoggerFactory.getLogger(ReportMidnightEnqueueService.class);

    private final ReportEnqueueCandidateRepository candidateRepository;
    private final LearningCharacterRepository characterRepository;
    private final ReportRequestOutboxRepository outboxRepository;
    private final ReportRequestOutboxService outboxService;
    private final ObjectMapper objectMapper;

    public ReportMidnightEnqueueService(
            ReportEnqueueCandidateRepository candidateRepository,
            LearningCharacterRepository characterRepository,
            ReportRequestOutboxRepository outboxRepository,
            ReportRequestOutboxService outboxService,
            ObjectMapper objectMapper
    ) {
        this.candidateRepository = candidateRepository;
        this.characterRepository = characterRepository;
        this.outboxRepository = outboxRepository;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
    }

    public ReportMidnightEnqueueResult enqueueForTargetDate(LocalDate targetDate) {
        LocalDate resolvedTargetDate = Objects.requireNonNull(targetDate, "targetDate must not be null");
        String jobId = UUID.randomUUID().toString();
        List<ReportEnqueueCandidate> candidates = candidateRepository.findByTargetDate(resolvedTargetDate);

        int created = 0;
        int updated = 0;
        int skipped = 0;
        int failures = 0;

        for (ReportEnqueueCandidate candidate : candidates) {
            ProcessingOutcome outcome = processCandidate(candidate, resolvedTargetDate, jobId);
            switch (outcome) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case SKIPPED -> skipped++;
                case FAILED -> failures++;
            }
        }

        log.info(
                "Report midnight enqueue completed targetDate={} jobId={} candidates={} created={} updated={} skipped={} failures={}",
                resolvedTargetDate,
                jobId,
                candidates.size(),
                created,
                updated,
                skipped,
                failures
        );
        return new ReportMidnightEnqueueResult(
                resolvedTargetDate,
                jobId,
                candidates.size(),
                created,
                updated,
                skipped,
                failures
        );
    }

    private ProcessingOutcome processCandidate(
            ReportEnqueueCandidate candidate,
            LocalDate targetDate,
            String jobId
    ) {
        String reportId = "unknown";
        try {
            JsonNode report = readJson(candidate.getReportJson());
            reportId = report.path("id").asText("unknown");
            JsonNode state = readJson(candidate.getStateJson());
            Optional<Long> characterId = resolveCharacterId(candidate.getUserId(), report);
            if (characterId.isEmpty()) {
                log.info(
                        "Report midnight enqueue skipped targetDate={} jobId={} userId={} reportId={} reason=missing-owned-character",
                        targetDate,
                        jobId,
                        candidate.getUserId(),
                        reportId
                );
                return ProcessingOutcome.SKIPPED;
            }

            Optional<ReportRequestOutbox> existing = outboxRepository.findByUserCharacterAndTargetDate(
                    candidate.getUserId(),
                    characterId.get(),
                    targetDate
            );
            if (existing.isPresent() && !ReportRequestOutbox.STATUS_PENDING.equals(existing.get().getStatus())) {
                log.info(
                        "Report midnight enqueue skipped targetDate={} jobId={} userId={} characterId={} reportId={} reason=outbox-status-{}",
                        targetDate,
                        jobId,
                        candidate.getUserId(),
                        characterId.get(),
                        reportId,
                        existing.get().getStatus()
                );
                return ProcessingOutcome.SKIPPED;
            }

            ReportRequestOutbox persisted = outboxService.createOrRefreshSnapshot(
                    candidate.getUserId(),
                    characterId.get(),
                    targetDate,
                    report,
                    state
            );
            if (!ReportRequestOutbox.STATUS_PENDING.equals(persisted.getStatus())) {
                log.info(
                        "Report midnight enqueue skipped targetDate={} jobId={} userId={} characterId={} reportId={} reason=outbox-status-{}",
                        targetDate,
                        jobId,
                        candidate.getUserId(),
                        characterId.get(),
                        reportId,
                        persisted.getStatus()
                );
                return ProcessingOutcome.SKIPPED;
            }
            return existing.isPresent() ? ProcessingOutcome.UPDATED : ProcessingOutcome.CREATED;
        } catch (Exception exception) {
            log.warn(
                    "Report midnight enqueue candidate failed targetDate={} jobId={} userId={} reportId={} reason={}",
                    targetDate,
                    jobId,
                    candidate.getUserId(),
                    reportId,
                    exception.getClass().getSimpleName()
            );
            return ProcessingOutcome.FAILED;
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Report enqueue candidate JSON is invalid.", exception);
        }
    }

    private Optional<Long> resolveCharacterId(long userId, JsonNode report) {
        JsonNode fixedCharacterId = report.path("characterId");
        if (fixedCharacterId.isMissingNode() || fixedCharacterId.isNull()) {
            return characterRepository.findActiveByUserId(userId).map(LearningCharacter::getId);
        }

        String rawCharacterId = fixedCharacterId.asText(null);
        if (!StringUtils.hasText(rawCharacterId)) {
            return characterRepository.findActiveByUserId(userId).map(LearningCharacter::getId);
        }

        Long characterId = parseCharacterId(rawCharacterId);
        if (characterId == null) {
            return Optional.empty();
        }
        return characterRepository.findByIdAndUserId(characterId, userId).map(LearningCharacter::getId);
    }

    private Long parseCharacterId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private enum ProcessingOutcome {
        CREATED,
        UPDATED,
        SKIPPED,
        FAILED
    }
}
