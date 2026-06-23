package com.commitgotchi.report.sqs;

import com.commitgotchi.report.application.ReportRequestMessage;
import com.commitgotchi.report.application.ReportRequestProducer;
import com.commitgotchi.report.application.ReportRequestPublishException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

public class SqsReportRequestProducer implements ReportRequestProducer {

    private static final Logger log = LoggerFactory.getLogger(SqsReportRequestProducer.class);

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final ReportQueueProperties properties;

    public SqsReportRequestProducer(
            SqsClient sqsClient,
            ObjectMapper objectMapper,
            ReportQueueProperties properties
    ) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void send(ReportRequestMessage message) {
        String queueUrl = resolveQueueUrl();

        try {
            SendMessageResponse response = sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(objectMapper.writeValueAsString(message))
                    .build());
            log.info(
                    "Report request enqueued requestId={} userId={} queueName={} messageId={} traceId={}",
                    message.requestId(),
                    message.userId(),
                    properties.normalizedRequestQueueName(),
                    response.messageId(),
                    MDC.get("traceId")
            );
        } catch (JsonProcessingException exception) {
            throw new ReportRequestPublishException("Could not serialize report request message.", exception);
        } catch (SdkException exception) {
            log.warn(
                    "Report request enqueue failed requestId={} userId={} queueName={} traceId={}",
                    message.requestId(),
                    message.userId(),
                    properties.normalizedRequestQueueName(),
                    MDC.get("traceId")
            );
            throw new ReportRequestPublishException("Could not enqueue report request message.", exception);
        }
    }

    private String resolveQueueUrl() {
        String queueUrl = properties.normalizedRequestQueueUrl();
        if (StringUtils.hasText(queueUrl)) {
            return queueUrl;
        }

        String queueName = properties.normalizedRequestQueueName();
        if (!StringUtils.hasText(queueName)) {
            throw new ReportRequestPublishException(
                    "REPORT_REQUEST_QUEUE_URL or REPORT_REQUEST_QUEUE_NAME is required when report SQS is enabled."
            );
        }
        try {
            return sqsClient.getQueueUrl(GetQueueUrlRequest.builder()
                    .queueName(queueName)
                    .build()).queueUrl();
        } catch (SdkException exception) {
            throw new ReportRequestPublishException("Could not resolve report request queue URL.", exception);
        }
    }
}
