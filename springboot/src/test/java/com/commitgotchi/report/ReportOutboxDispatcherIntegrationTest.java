package com.commitgotchi.report;

import com.commitgotchi.character.api.dto.CharacterCreateRequest;
import com.commitgotchi.character.application.CharacterCreationService;
import com.commitgotchi.character.domain.LearningCharacter;
import com.commitgotchi.report.application.ReportOutboxDispatcher;
import com.commitgotchi.report.application.ReportRequestMessage;
import com.commitgotchi.report.application.ReportRequestProducer;
import com.commitgotchi.report.application.ReportRequestPublishException;
import com.commitgotchi.support.AdminTestFixture;
import com.commitgotchi.support.PostgresIntegrationTest;
import com.commitgotchi.user.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "commitgotchi.report.dispatcher.max-attempts=2",
        "commitgotchi.report.dispatcher.retry-delay=PT5M"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportOutboxDispatcherIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminTestFixture fixture;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CharacterCreationService characterCreationService;

    @Autowired
    private ReportOutboxDispatcher dispatcher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private ReportRequestProducer reportRequestProducer;

    @BeforeEach
    void cleanDatabase() {
        userRepository.deleteAll();
    }

    @Test
    void dispatchAvailableSendsSnapshotAndMarksRowSent() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        LearningCharacter character = createCharacter(user.id(), "Dispatch");
        saveReport(user.bearer(), "Dispatcher report", "Studied SQS dispatcher.", "[\"spring\",\"network\"]");

        ReportOutboxDispatcher.DispatchResult result = dispatcher.dispatchAvailable(Instant.now().plusSeconds(1));

        assertThat(result.claimedCount()).isEqualTo(1);
        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(result.retryCount()).isZero();
        assertThat(result.failedCount()).isZero();

        ArgumentCaptor<ReportRequestMessage> messageCaptor = ArgumentCaptor.forClass(ReportRequestMessage.class);
        verify(reportRequestProducer).send(messageCaptor.capture());
        ReportRequestMessage message = messageCaptor.getValue();
        assertThat(message.requestId()).startsWith("report-request-" + user.id());
        assertThat(message.userId()).isEqualTo(user.id());
        assertThat(message.characterMetadata().characterId()).isEqualTo(character.getId());
        assertThat(message.characterMetadata().name()).isEqualTo("Dispatch");
        assertThat(message.characterMetadata().currentStats().network()).isZero();
        assertThat(message.dailyReport().title()).isEqualTo("Dispatcher report");

        Map<String, Object> row = outboxRow(user.id());
        assertThat(row).containsEntry("status", "SENT");
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(row.get("last_error")).isNull();
    }

    @Test
    void dispatchFailureBacksOffThenMovesToFailedAtMaxAttempts() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        createCharacter(user.id(), "Retry");
        saveReport(user.bearer(), "Retry report", "Studied retries.", "[\"db\"]");
        doThrow(new ReportRequestPublishException("Could not enqueue report request message."))
                .when(reportRequestProducer).send(any(ReportRequestMessage.class));

        Instant firstAttempt = Instant.now().plusSeconds(1);
        ReportOutboxDispatcher.DispatchResult first = dispatcher.dispatchAvailable(firstAttempt);

        assertThat(first.claimedCount()).isEqualTo(1);
        assertThat(first.retryCount()).isEqualTo(1);
        Map<String, Object> retryRow = outboxRow(user.id());
        assertThat(retryRow).containsEntry("status", "PENDING");
        assertThat(retryRow).containsEntry("attempt_count", 1);
        assertThat(toInstant(retryRow.get("available_at"))).isAfterOrEqualTo(firstAttempt.plusSeconds(300));
        assertThat((String) retryRow.get("last_error")).contains("ReportRequestPublishException");

        ReportOutboxDispatcher.DispatchResult second = dispatcher.dispatchAvailable(firstAttempt.plusSeconds(301));

        assertThat(second.claimedCount()).isEqualTo(1);
        assertThat(second.failedCount()).isEqualTo(1);
        Map<String, Object> failedRow = outboxRow(user.id());
        assertThat(failedRow).containsEntry("status", "FAILED");
        assertThat(failedRow).containsEntry("attempt_count", 2);
    }

    @Test
    void concurrentDispatchersDoNotSendSameLockedRowTwice() throws Exception {
        AdminTestFixture.ProvisionedUser user = fixture.provisionUser(uniqueEmail(), "very-secure-password");
        createCharacter(user.id(), "Concurrent");
        saveReport(user.bearer(), "Concurrent report", "Studied locks.", "[\"db\"]");
        CountDownLatch producerEntered = new CountDownLatch(1);
        CountDownLatch releaseProducer = new CountDownLatch(1);
        AtomicInteger sendCount = new AtomicInteger();
        doAnswer(invocation -> {
            sendCount.incrementAndGet();
            producerEntered.countDown();
            assertThat(releaseProducer.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(reportRequestProducer).send(any(ReportRequestMessage.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Instant dispatchTime = Instant.now().plusSeconds(1);
            Future<ReportOutboxDispatcher.DispatchResult> first =
                    executor.submit(() -> dispatcher.dispatchAvailable(dispatchTime));
            assertThat(producerEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<ReportOutboxDispatcher.DispatchResult> second =
                    executor.submit(() -> dispatcher.dispatchAvailable(dispatchTime));
            ReportOutboxDispatcher.DispatchResult secondResult = second.get(5, TimeUnit.SECONDS);
            assertThat(secondResult.claimedCount()).isZero();

            releaseProducer.countDown();
            ReportOutboxDispatcher.DispatchResult firstResult = first.get(5, TimeUnit.SECONDS);
            assertThat(firstResult.claimedCount()).isEqualTo(1);
            assertThat(firstResult.sentCount()).isEqualTo(1);
            assertThat(sendCount.get()).isEqualTo(1);
        } finally {
            releaseProducer.countDown();
            executor.shutdownNow();
        }
    }

    private void saveReport(String bearer, String title, String content, String tagsJson) throws Exception {
        mockMvc.perform(post("/api/game/reports")
                        .header("Authorization", bearer)
                        .contentType("application/json")
                        .content("""
                                {
                                  "mood": "joy",
                                  "title": "%s",
                                  "content": "%s",
                                  "tags": %s
                                }
                                """.formatted(title, content, tagsJson)))
                .andExpect(status().isOk());
    }

    private LearningCharacter createCharacter(long userId, String name) {
        return characterCreationService.create(
                userId,
                new CharacterCreateRequest(name, "dispatcher keyword", "steady")
        );
    }

    private Map<String, Object> outboxRow(long userId) {
        return jdbcTemplate.queryForMap(
                """
                        SELECT status, attempt_count, available_at, sent_at, last_error
                        FROM report_request_outbox
                        WHERE user_id = ?
                        """,
                userId
        );
    }

    private Instant toInstant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        throw new IllegalArgumentException("Unsupported timestamp value: " + value);
    }

    private String uniqueEmail() {
        return "dispatcher-" + UUID.randomUUID() + "@example.com";
    }
}
