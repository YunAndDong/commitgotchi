package com.commitgotchi.report;

import com.commitgotchi.character.api.dto.FastApiScoreDelta;
import com.commitgotchi.report.application.ReportRequestMessage;
import com.commitgotchi.report.application.ReportRequestPublishException;
import com.commitgotchi.report.sqs.ReportQueueProperties;
import com.commitgotchi.report.sqs.SqsReportRequestProducer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SqsReportRequestProducerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void sendsReportRequestJsonToConfiguredQueueUrl() throws Exception {
        SqsClient sqsClient = mock(SqsClient.class);
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("message-1").build());
        ReportQueueProperties properties = new ReportQueueProperties();
        properties.setRequestQueueUrl(" https://sqs.fake/report-requests ");
        properties.setRequestQueueName(" report-requests ");
        SqsReportRequestProducer producer = new SqsReportRequestProducer(sqsClient, objectMapper, properties);

        producer.send(message());

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        SendMessageRequest request = captor.getValue();
        assertThat(request.queueUrl()).isEqualTo("https://sqs.fake/report-requests");
        JsonNode body = objectMapper.readTree(request.messageBody());
        assertThat(body.path("requestId").asText()).isEqualTo("report-request-1");
        assertThat(body.path("userId").asLong()).isEqualTo(42L);
        assertThat(body.path("characterMetadata").path("currentStats").path("algorithm").asInt()).isEqualTo(200);
        assertThat(body.path("userMetadata").path("reportDirection").path("scoreDeltaHint").path("network").asInt())
                .isEqualTo(1);
    }

    @Test
    void resolvesQueueUrlFromQueueNameWhenUrlIsMissing() {
        SqsClient sqsClient = mock(SqsClient.class);
        when(sqsClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(GetQueueUrlResponse.builder().queueUrl("https://sqs.fake/from-name").build());
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("message-1").build());
        ReportQueueProperties properties = new ReportQueueProperties();
        properties.setRequestQueueName(" report-requests ");
        SqsReportRequestProducer producer = new SqsReportRequestProducer(sqsClient, objectMapper, properties);

        producer.send(message());

        ArgumentCaptor<GetQueueUrlRequest> queueUrlCaptor = ArgumentCaptor.forClass(GetQueueUrlRequest.class);
        ArgumentCaptor<SendMessageRequest> sendCaptor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).getQueueUrl(queueUrlCaptor.capture());
        verify(sqsClient).sendMessage(sendCaptor.capture());
        assertThat(queueUrlCaptor.getValue().queueName()).isEqualTo("report-requests");
        assertThat(sendCaptor.getValue().queueUrl()).isEqualTo("https://sqs.fake/from-name");
    }

    @Test
    void failsBeforeCallingSqsWhenQueueUrlIsMissing() {
        SqsClient sqsClient = mock(SqsClient.class);
        SqsReportRequestProducer producer = new SqsReportRequestProducer(
                sqsClient,
                objectMapper,
                new ReportQueueProperties()
        );

        assertThatThrownBy(() -> producer.send(message()))
                .isInstanceOf(ReportRequestPublishException.class)
                .hasMessageContaining("REPORT_REQUEST_QUEUE_URL or REPORT_REQUEST_QUEUE_NAME");
        verifyNoInteractions(sqsClient);
    }

    private ReportRequestMessage message() {
        return new ReportRequestMessage(
                "report-request-1",
                42L,
                LocalDate.of(2026, 6, 19),
                new ReportRequestMessage.UserMetadata(
                        "0100011",
                        new ReportRequestMessage.ReportDirection(
                                new FastApiScoreDelta(0, 3, 0, 1, 0),
                                "알고리즘과 네트워크 학습 증가분을 중심으로 코멘트"
                        )
                ),
                new ReportRequestMessage.CharacterMetadata(
                        10L,
                        "Commit Monster",
                        "Precise and kind",
                        "JOY",
                        new ReportRequestMessage.CurrentStats(120, 200, 80, 60, 140)
                ),
                new ReportRequestMessage.DailyReport("오늘 학습 기록", "Spring JPA")
        );
    }
}
