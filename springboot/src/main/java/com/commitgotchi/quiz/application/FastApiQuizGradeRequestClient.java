package com.commitgotchi.quiz.application;

import com.commitgotchi.security.InternalApiProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class FastApiQuizGradeRequestClient implements QuizGradeRequestClient {

    private static final Logger log = LoggerFactory.getLogger(FastApiQuizGradeRequestClient.class);

    private final QuizGradingProperties properties;
    private final InternalApiProperties internalApiProperties;
    private final RestClient restClient;

    public FastApiQuizGradeRequestClient(
            RestClient.Builder restClientBuilder,
            QuizGradingProperties properties,
            InternalApiProperties internalApiProperties
    ) {
        this.properties = properties;
        this.internalApiProperties = internalApiProperties;
        RestClient.Builder quizClientBuilder = restClientBuilder.clone()
                .requestFactory(requestFactory(properties));
        if (properties.hasBaseUrl()) {
            quizClientBuilder.baseUrl(properties.normalizedBaseUrl());
        }
        this.restClient = quizClientBuilder.build();
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled() && properties.hasBaseUrl();
    }

    @Override
    public QuizGradeRequestResult requestGrade(QuizGradeRequestMessage request) {
        if (!properties.isEnabled()) {
            return QuizGradeRequestResult.failed("DISABLED");
        }
        if (!properties.hasBaseUrl()) {
            return QuizGradeRequestResult.failed("BASE_URL_MISSING");
        }

        try {
            RestClient.RequestBodySpec requestSpec = restClient.post()
                    .uri("/api/internal/quizzes/grade")
                    .contentType(MediaType.APPLICATION_JSON);
            if (internalApiProperties.hasSecret()) {
                requestSpec.header("Authorization", "Internal " + internalApiProperties.normalizedSecret());
            }

            FastApiAcceptedResponse response = requestSpec
                    .body(request)
                    .retrieve()
                    .body(FastApiAcceptedResponse.class);
            return toResult(response, request.submissionId());
        } catch (RestClientResponseException exception) {
            String reason = "HTTP_" + exception.getStatusCode().value();
            logFailure(reason, request.submissionId(), request.quizId());
            return QuizGradeRequestResult.failed(reason);
        } catch (RestClientException exception) {
            String reason = "HTTP_CLIENT_ERROR";
            logFailure(reason, request.submissionId(), request.quizId());
            return QuizGradeRequestResult.failed(reason);
        }
    }

    private QuizGradeRequestResult toResult(FastApiAcceptedResponse response, String expectedSubmissionId) {
        if (response == null) {
            return QuizGradeRequestResult.failed("EMPTY_RESPONSE");
        }
        if (!response.accepted()) {
            return QuizGradeRequestResult.failed("NOT_ACCEPTED");
        }
        if (response.submissionId() == null || response.submissionId().isBlank()) {
            return QuizGradeRequestResult.failed("SUBMISSION_ID_MISSING");
        }
        if (!expectedSubmissionId.equals(response.submissionId())) {
            return QuizGradeRequestResult.failed("SUBMISSION_ID_MISMATCH");
        }
        return QuizGradeRequestResult.accepted(response.submissionId());
    }

    private void logFailure(String reason, String submissionId, long quizId) {
        log.warn(
                "Quiz grade request failed reason={} submissionId={} quizId={} traceId={}",
                reason,
                submissionId,
                quizId,
                MDC.get("traceId")
        );
    }

    private SimpleClientHttpRequestFactory requestFactory(QuizGradingProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return requestFactory;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FastApiAcceptedResponse(boolean accepted, String submissionId) {
    }
}
