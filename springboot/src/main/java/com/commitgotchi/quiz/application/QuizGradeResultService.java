package com.commitgotchi.quiz.application;

import com.commitgotchi.character.api.dto.FastApiScoreDelta;
import com.commitgotchi.character.domain.CharacterEmotion;
import com.commitgotchi.quiz.api.dto.QuizGradeResultRequest;
import com.commitgotchi.quiz.api.dto.QuizGradeResultResponse;
import com.commitgotchi.quiz.api.dto.QuizGradeStatus;
import org.springframework.stereotype.Service;

@Service
public class QuizGradeResultService {

    public QuizGradeResultResponse handle(QuizGradeResultRequest request) {
        validateDeltaBounds(request.scoreAllocation(), request.scoreDelta());
        toGrowthDecision(request);
        // TODO(BE-3): persist quiz_submissions idempotency by submissionId and apply growth in one transaction.
        return QuizGradeResultResponse.acceptedResult();
    }

    public QuizGrowthDecision toGrowthDecision(QuizGradeResultRequest request) {
        FastApiScoreDelta allocation = request.scoreAllocation();
        FastApiScoreDelta delta = request.scoreDelta();
        if (request.status() == QuizGradeStatus.UNGRADED && delta.sum() != 0) {
            throw new IllegalArgumentException("UNGRADED grade-result must not include positive scoreDelta.");
        }
        return new QuizGrowthDecision(
                delta.dbDelta(),
                delta.algorithmDelta(),
                delta.csDelta(),
                delta.networkDelta(),
                delta.frameworkDelta(),
                decideEmotion(request.status(), allocation, delta),
                decideStatusMessage(request.status(), allocation, delta)
        );
    }

    private void validateDeltaBounds(FastApiScoreDelta allocation, FastApiScoreDelta delta) {
        requireDeltaWithinAllocation("db", delta.dbDelta(), allocation.dbDelta());
        requireDeltaWithinAllocation("algorithm", delta.algorithmDelta(), allocation.algorithmDelta());
        requireDeltaWithinAllocation("cs", delta.csDelta(), allocation.csDelta());
        requireDeltaWithinAllocation("network", delta.networkDelta(), allocation.networkDelta());
        requireDeltaWithinAllocation("framework", delta.frameworkDelta(), allocation.frameworkDelta());
    }

    private void requireDeltaWithinAllocation(String key, int delta, int allocation) {
        if (delta > allocation) {
            throw new IllegalArgumentException("scoreDelta." + key + " cannot exceed scoreAllocation." + key);
        }
    }

    private CharacterEmotion decideEmotion(QuizGradeStatus status, FastApiScoreDelta allocation, FastApiScoreDelta delta) {
        if (status == QuizGradeStatus.UNGRADED) {
            return CharacterEmotion.SAD;
        }
        int maxScore = allocation.sum();
        if (maxScore == 0) {
            return CharacterEmotion.SAD;
        }
        return delta.sum() * 10 >= maxScore * 6 ? CharacterEmotion.JOY : CharacterEmotion.SAD;
    }

    private String decideStatusMessage(QuizGradeStatus status, FastApiScoreDelta allocation, FastApiScoreDelta delta) {
        if (decideEmotion(status, allocation, delta) == CharacterEmotion.JOY) {
            return "좋아요, 핵심은 잡았어요!";
        }
        return "괜찮아요, 답을 다듬으면서 크는 중이에요.";
    }

    public record QuizGrowthDecision(
            int dbDelta,
            int algorithmDelta,
            int csDelta,
            int networkDelta,
            int frameworkDelta,
            CharacterEmotion emotion,
            String statusMessage
    ) {
    }
}
