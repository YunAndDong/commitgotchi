package com.commitgotchi.report.sqs;

import com.commitgotchi.report.application.ReportRequestProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
public class ReportQueueConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "commitgotchi.report.queue", name = "enabled", havingValue = "true")
    public SqsClient reportSqsClient(ReportQueueProperties properties) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(properties.normalizedRegion()))
                .credentialsProvider(credentialsProvider(properties));
        if (properties.hasEndpointUrl()) {
            builder.endpointOverride(URI.create(properties.normalizedEndpointUrl()));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "commitgotchi.report.queue", name = "enabled", havingValue = "true")
    public ReportRequestProducer sqsReportRequestProducer(
            @Qualifier("reportSqsClient") SqsClient sqsClient,
            ObjectMapper objectMapper,
            ReportQueueProperties properties
    ) {
        return new SqsReportRequestProducer(sqsClient, objectMapper, properties);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "commitgotchi.report.queue",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    public ReportRequestProducer noopReportRequestProducer() {
        return ignored -> {
        };
    }

    private AwsCredentialsProvider credentialsProvider(ReportQueueProperties properties) {
        if (!properties.hasStaticCredentials()) {
            return DefaultCredentialsProvider.builder().build();
        }
        if (properties.hasSessionToken()) {
            return StaticCredentialsProvider.create(AwsSessionCredentials.create(
                    properties.normalizedAccessKeyId(),
                    properties.normalizedSecretAccessKey(),
                    properties.normalizedSessionToken()
            ));
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(
                properties.normalizedAccessKeyId(),
                properties.normalizedSecretAccessKey()
        ));
    }
}
