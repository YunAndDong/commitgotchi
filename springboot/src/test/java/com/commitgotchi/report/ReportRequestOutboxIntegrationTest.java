package com.commitgotchi.report;

import com.commitgotchi.character.api.dto.CharacterCreateRequest;
import com.commitgotchi.character.application.CharacterCreationService;
import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.report.application.ReportRequestProducer;
import com.commitgotchi.support.AdminTestFixture;
import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportRequestOutboxIntegrationTest extends PostgresIntegrationTest {

    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminTestFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CharacterCreationService characterCreationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ReportRequestProducer reportRequestProducer;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
    }

    @Test
    void saveReportUsesCharactersIdAsOutboxCharacterSnapshotAndRefreshesPendingRow() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        LearningCharacter character = createCharacter(user.id());
        LocalDate targetDate = LocalDate.now(APP_ZONE);

        saveReport(user.bearer(), "First report", "Studied SQL, DP, and TCP.", """
                ["db","database","sql","algo","algorithm","algorithms","dp","greedy","cs","os","network","tcp","http"]
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.dailyReport.status").value("pending"))
                .andExpect(jsonPath("$.item.status").value("analyzing"))
                .andExpect(jsonPath("$.item.requestId").value(expectedRequestId(user.id(), character.getId(), targetDate)));

        saveReport(user.bearer(), "Updated report", "Studied Spring and Vue.", """
                ["spring","springboot","vue","fw","framework","framework"]
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.notice").value("리포트 저장됨 - 자정에 분석돼요. 내일 오전 9시 도착."))
                .andExpect(jsonPath("$.item.requestId").value(expectedRequestId(user.id(), character.getId(), targetDate)));

        List<OutboxRow> rows = outboxRows(user.id(), character.getId(), targetDate);
        assertThat(rows).singleElement()
                .satisfies(row -> {
                    assertThat(row.requestId()).isEqualTo(expectedRequestId(user.id(), character.getId(), targetDate));
                    assertThat(row.userId()).isEqualTo(user.id());
                    assertThat(row.characterId()).isEqualTo(character.getId());
                    assertThat(row.targetDate()).isEqualTo(targetDate);
                    assertThat(row.reportTitle()).isEqualTo("Updated report");
                    assertThat(row.reportContent()).isEqualTo("Studied Spring and Vue.");
                    assertThat(row.weeklyStudyStreak()).isEqualTo("0000001");
                    assertThat(row.focus()).isEqualTo("프레임워크 학습 증가분을 중심으로 코멘트");
                    assertThat(row.scoreDeltaHintDb()).isZero();
                    assertThat(row.scoreDeltaHintAlgorithm()).isZero();
                    assertThat(row.scoreDeltaHintCs()).isZero();
                    assertThat(row.scoreDeltaHintNetwork()).isZero();
                    assertThat(row.scoreDeltaHintFramework()).isEqualTo(10);
                    assertThat(row.status()).isEqualTo("PENDING");
                    assertThat(row.attemptCount()).isZero();
                    assertThat(row.availableAt()).isNotNull();
                });
        verifyNoInteractions(reportRequestProducer);
    }

    @Test
    void saveReportDoesNotRewriteSentOutboxSnapshotOrInsertDuplicate() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        LearningCharacter character = createCharacter(user.id());
        LocalDate targetDate = LocalDate.now(APP_ZONE);

        saveReport(user.bearer(), "Original report", "Original content", """
                ["algo"]
                """)
                .andExpect(status().isOk());
        jdbcTemplate.update(
                """
                        UPDATE report_request_outbox
                        SET status = 'SENT',
                            sent_at = CURRENT_TIMESTAMP,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE user_id = ?
                          AND character_id = ?
                          AND target_date = ?
                        """,
                user.id(),
                character.getId(),
                targetDate
        );

        saveReport(user.bearer(), "Should not overwrite", "Second content", """
                ["db"]
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item.requestId").value(expectedRequestId(user.id(), character.getId(), targetDate)));

        assertThat(outboxRows(user.id(), character.getId(), targetDate)).singleElement()
                .satisfies(row -> {
                    assertThat(row.reportTitle()).isEqualTo("Original report");
                    assertThat(row.reportContent()).isEqualTo("Original content");
                    assertThat(row.status()).isEqualTo("SENT");
                });
        verifyNoInteractions(reportRequestProducer);
    }

    private org.springframework.test.web.servlet.ResultActions saveReport(
            String bearer,
            String title,
            String content,
            String tagsJson
    ) throws Exception {
        return mockMvc.perform(post("/api/game/reports")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content("""
                        {
                          "mood": "joy",
                          "title": "%s",
                          "content": "%s",
                          "tags": %s
                        }
                        """.formatted(title, content, tagsJson)));
    }

    private LearningCharacter createCharacter(long userId) {
        return characterCreationService.create(
                userId,
                new CharacterCreateRequest("Reporter", "report keyword", "steady")
        );
    }

    private List<OutboxRow> outboxRows(long userId, long characterId, LocalDate targetDate) {
        return jdbcTemplate.query(
                """
                        SELECT request_id,
                               user_id,
                               character_id,
                               target_date,
                               report_title,
                               report_content,
                               weekly_study_streak,
                               focus,
                               score_delta_hint_db,
                               score_delta_hint_algorithm,
                               score_delta_hint_cs,
                               score_delta_hint_network,
                               score_delta_hint_framework,
                               status,
                               attempt_count,
                               available_at
                        FROM report_request_outbox
                        WHERE user_id = ?
                          AND character_id = ?
                          AND target_date = ?
                        ORDER BY id
                        """,
                (rs, rowNum) -> new OutboxRow(
                        rs.getString("request_id"),
                        rs.getLong("user_id"),
                        rs.getLong("character_id"),
                        rs.getObject("target_date", LocalDate.class),
                        rs.getString("report_title"),
                        rs.getString("report_content"),
                        rs.getString("weekly_study_streak"),
                        rs.getString("focus"),
                        rs.getInt("score_delta_hint_db"),
                        rs.getInt("score_delta_hint_algorithm"),
                        rs.getInt("score_delta_hint_cs"),
                        rs.getInt("score_delta_hint_network"),
                        rs.getInt("score_delta_hint_framework"),
                        rs.getString("status"),
                        rs.getInt("attempt_count"),
                        rs.getTimestamp("available_at").toInstant()
                ),
                userId,
                characterId,
                targetDate
        );
    }

    private String expectedRequestId(long userId, long characterId, LocalDate targetDate) {
        return "report-request-%d-%s-%d".formatted(userId, targetDate, characterId);
    }

    private String uniqueEmail() {
        return "outbox-" + UUID.randomUUID() + "@example.com";
    }

    private record OutboxRow(
            String requestId,
            long userId,
            long characterId,
            LocalDate targetDate,
            String reportTitle,
            String reportContent,
            String weeklyStudyStreak,
            String focus,
            int scoreDeltaHintDb,
            int scoreDeltaHintAlgorithm,
            int scoreDeltaHintCs,
            int scoreDeltaHintNetwork,
            int scoreDeltaHintFramework,
            String status,
            int attemptCount,
            Instant availableAt
    ) {
    }
}
