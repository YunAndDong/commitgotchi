package com.commitgotchi.report;

import com.commitgotchi.character.api.dto.CharacterCreateRequest;
import com.commitgotchi.character.application.CharacterCreationService;
import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.report.application.ReportMidnightEnqueueResult;
import com.commitgotchi.report.application.ReportMidnightEnqueueService;
import com.commitgotchi.support.AdminTestFixture;
import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ReportMidnightEnqueueServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private ReportMidnightEnqueueService service;

    @Autowired
    private AdminTestFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CharacterCreationService characterCreationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
    }

    @Test
    void enqueueForTargetDateIsIdempotentAndRefreshesPendingSnapshot() {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        LearningCharacter character = createCharacter(user.id(), "Reporter");
        LocalDate targetDate = LocalDate.of(2026, 6, 22);

        putGameState(user.id(), List.of(report("r1", targetDate, character.getId(), "First report", "Studied SQL.", "[\"db\"]")));

        ReportMidnightEnqueueResult first = service.enqueueForTargetDate(targetDate);

        assertThat(first.createdCount()).isEqualTo(1);
        assertThat(first.updatedCount()).isZero();
        assertThat(outboxRows(user.id(), character.getId(), targetDate)).singleElement()
                .satisfies(row -> {
                    assertThat(row.reportTitle()).isEqualTo("First report");
                    assertThat(row.reportContent()).isEqualTo("Studied SQL.");
                    assertThat(row.status()).isEqualTo("PENDING");
                });

        putGameState(user.id(), List.of(report("r1", targetDate, character.getId(), "Updated report", "Studied Spring.", "[\"spring\"]")));

        ReportMidnightEnqueueResult second = service.enqueueForTargetDate(targetDate);

        assertThat(second.createdCount()).isZero();
        assertThat(second.updatedCount()).isEqualTo(1);
        assertThat(outboxRows(user.id(), character.getId(), targetDate)).singleElement()
                .satisfies(row -> {
                    assertThat(row.reportTitle()).isEqualTo("Updated report");
                    assertThat(row.reportContent()).isEqualTo("Studied Spring.");
                    assertThat(row.status()).isEqualTo("PENDING");
                });
    }

    @Test
    void skipsUsersWithoutTargetDateReportOrOwnedCharacter() {
        AdminTestFixture.ProvisionedUser noReportUser = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        createCharacter(noReportUser.id(), "No Report");
        putGameState(noReportUser.id(), List.of());

        AdminTestFixture.ProvisionedUser missingCharacterUser = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        LocalDate targetDate = LocalDate.of(2026, 6, 22);
        putGameState(missingCharacterUser.id(), List.of(report("r2", targetDate, 999_999L, "Stale character", "Studied queues.", "[\"cs\"]")));

        ReportMidnightEnqueueResult result = service.enqueueForTargetDate(targetDate);

        assertThat(result.createdCount()).isZero();
        assertThat(result.updatedCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(totalOutboxRows()).isZero();
    }

    @Test
    void malformedStoredGameStateDoesNotAbortOtherCandidates() {
        AdminTestFixture.ProvisionedUser malformedUser = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        putMalformedGameState(malformedUser.id());
        AdminTestFixture.ProvisionedUser validUser = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        LearningCharacter validCharacter = createCharacter(validUser.id(), "Valid Reporter");
        LocalDate targetDate = LocalDate.of(2026, 6, 22);
        putGameState(validUser.id(), List.of(report("r7", targetDate, validCharacter.getId(), "Valid report", "Studied indexes.", "[\"db\"]")));

        ReportMidnightEnqueueResult result = service.enqueueForTargetDate(targetDate);

        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.createdCount()).isEqualTo(1);
        assertThat(outboxRows(validUser.id(), validCharacter.getId(), targetDate)).singleElement()
                .satisfies(row -> assertThat(row.reportTitle()).isEqualTo("Valid report"));
    }

    @Test
    void usesActiveCharacterWhenReportDoesNotPinCharacterId() {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        LearningCharacter activeCharacter = createCharacter(user.id(), "Active Reporter");
        LocalDate targetDate = LocalDate.of(2026, 6, 22);
        putGameState(user.id(), List.of(reportWithoutCharacter("r3", targetDate, "No pinned character", "Studied HTTP.", "[\"network\"]")));

        ReportMidnightEnqueueResult result = service.enqueueForTargetDate(targetDate);

        assertThat(result.createdCount()).isEqualTo(1);
        assertThat(outboxRows(user.id(), activeCharacter.getId(), targetDate)).singleElement()
                .satisfies(row -> {
                    assertThat(row.reportTitle()).isEqualTo("No pinned character");
                    assertThat(row.status()).isEqualTo("PENDING");
                });
    }

    @Test
    void sentRowsAreNotMovedBackToPendingOrOverwritten() {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        LearningCharacter character = createCharacter(user.id(), "Sent Reporter");
        LocalDate targetDate = LocalDate.of(2026, 6, 22);
        insertSentOutbox(user.id(), character.getId(), targetDate, "Already sent", "Original content");
        putGameState(user.id(), List.of(report("r4", targetDate, character.getId(), "Should not overwrite", "Second content", "[\"db\"]")));

        ReportMidnightEnqueueResult result = service.enqueueForTargetDate(targetDate);

        assertThat(result.createdCount()).isZero();
        assertThat(result.updatedCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(outboxRows(user.id(), character.getId(), targetDate)).singleElement()
                .satisfies(row -> {
                    assertThat(row.reportTitle()).isEqualTo("Already sent");
                    assertThat(row.reportContent()).isEqualTo("Original content");
                    assertThat(row.status()).isEqualTo("SENT");
                });
    }

    @Test
    void continuesProcessingWhenOneCandidateFailsSnapshotCreation() {
        AdminTestFixture.ProvisionedUser brokenUser = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        LearningCharacter brokenCharacter = createCharacter(brokenUser.id(), "Broken Reporter");
        AdminTestFixture.ProvisionedUser validUser = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        LearningCharacter validCharacter = createCharacter(validUser.id(), "Valid Reporter");
        LocalDate targetDate = LocalDate.of(2026, 6, 22);
        putGameState(brokenUser.id(), List.of(report(
                "r5",
                targetDate,
                brokenCharacter.getId(),
                "x".repeat(201),
                "This title is too long for the outbox snapshot.",
                "[\"db\"]"
        )));
        putGameState(validUser.id(), List.of(report("r6", targetDate, validCharacter.getId(), "Valid report", "Studied DP.", "[\"algo\"]")));

        ReportMidnightEnqueueResult result = service.enqueueForTargetDate(targetDate);

        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.createdCount()).isEqualTo(1);
        assertThat(outboxRows(validUser.id(), validCharacter.getId(), targetDate)).singleElement()
                .satisfies(row -> assertThat(row.reportTitle()).isEqualTo("Valid report"));
    }

    private LearningCharacter createCharacter(long userId, String name) {
        return characterCreationService.create(
                userId,
                new CharacterCreateRequest(name, "report keyword", "steady")
        );
    }

    private void putGameState(long userId, List<String> reports) {
        String stateJson = """
                {
                  "nextId": 100,
                  "reports": [%s],
                  "quizzes": [],
                  "dailyReport": {"status": "pending", "date": "2026-06-23", "characterId": null},
                  "notice": null
                }
                """.formatted(String.join(",", reports));
        jdbcTemplate.update(
                """
                        INSERT INTO game_states (user_id, state_json, created_at, updated_at)
                        VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        ON CONFLICT (user_id) DO UPDATE
                        SET state_json = EXCLUDED.state_json,
                            updated_at = CURRENT_TIMESTAMP
                        """,
                userId,
                stateJson
        );
    }

    private void putMalformedGameState(long userId) {
        jdbcTemplate.update(
                """
                        INSERT INTO game_states (user_id, state_json, created_at, updated_at)
                        VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        ON CONFLICT (user_id) DO UPDATE
                        SET state_json = EXCLUDED.state_json,
                            updated_at = CURRENT_TIMESTAMP
                        """,
                userId,
                "{malformed-json"
        );
    }

    private String report(String id, LocalDate targetDate, Long characterId, String title, String content, String tagsJson) {
        return """
                {
                  "id": "%s",
                  "date": "%s",
                  "status": "analyzing",
                  "characterId": "%d",
                  "title": "%s",
                  "content": "%s",
                  "tags": %s
                }
                """.formatted(id, targetDate, characterId, title, content, tagsJson);
    }

    private String reportWithoutCharacter(String id, LocalDate targetDate, String title, String content, String tagsJson) {
        return """
                {
                  "id": "%s",
                  "date": "%s",
                  "status": "analyzing",
                  "title": "%s",
                  "content": "%s",
                  "tags": %s
                }
                """.formatted(id, targetDate, title, content, tagsJson);
    }

    private void insertSentOutbox(long userId, long characterId, LocalDate targetDate, String title, String content) {
        jdbcTemplate.update(
                """
                        INSERT INTO report_request_outbox (
                            request_id,
                            user_id,
                            user_character_id,
                            target_date,
                            report_title,
                            report_content,
                            weekly_study_streak,
                            score_delta_hint,
                            focus,
                            status,
                            attempt_count,
                            available_at,
                            sent_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, '0000001', '{}'::jsonb, '이미 전송된 요청',
                                'SENT', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """,
                expectedRequestId(userId, characterId, targetDate),
                userId,
                characterId,
                targetDate,
                title,
                content
        );
    }

    private List<OutboxRow> outboxRows(long userId, long characterId, LocalDate targetDate) {
        return jdbcTemplate.query(
                """
                        SELECT user_character_id AS character_id, target_date, report_title, report_content, status
                        FROM report_request_outbox
                        WHERE user_id = ?
                          AND user_character_id = ?
                          AND target_date = ?
                        ORDER BY id
                        """,
                (rs, rowNum) -> new OutboxRow(
                        rs.getLong("character_id"),
                        rs.getObject("target_date", LocalDate.class),
                        rs.getString("report_title"),
                        rs.getString("report_content"),
                        rs.getString("status")
                ),
                userId,
                characterId,
                targetDate
        );
    }

    private long totalOutboxRows() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM report_request_outbox", Long.class);
        return count == null ? 0 : count;
    }

    private String expectedRequestId(long userId, long characterId, LocalDate targetDate) {
        return "report-request-%d-%s-%d".formatted(userId, targetDate, characterId);
    }

    private String uniqueEmail() {
        return "midnight-" + UUID.randomUUID() + "@example.com";
    }

    private record OutboxRow(
            long characterId,
            LocalDate targetDate,
            String reportTitle,
            String reportContent,
            String status
    ) {
    }
}
