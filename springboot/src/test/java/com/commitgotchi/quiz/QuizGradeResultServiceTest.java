package com.commitgotchi.quiz;

import com.commitgotchi.character.api.dto.FastApiScoreDelta;
import com.commitgotchi.character.domain.CharacterEmotion;
import com.commitgotchi.quiz.api.dto.QuizGradeResultRequest;
import com.commitgotchi.quiz.api.dto.QuizGradeStatus;
import com.commitgotchi.quiz.application.QuizGradeResultService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuizGradeResultServiceTest {

    private final QuizGradeResultService service = new QuizGradeResultService();

    @Test
    void gradeResultMapsFastApiScoreKeysToDbDomainOrderAndUsesWebhookEmotion() {
        QuizGradeResultRequest request = new QuizGradeResultRequest(
                "submission-1",
                42L,
                55L,
                QuizGradeStatus.GRADED,
                new FastApiScoreDelta(1, 2, 3, 4, 5),
                new FastApiScoreDelta(1, 2, 3, 4, 5),
                "feedback",
                CharacterEmotion.JOY,
                "좋아요, 핵심은 잡았어요!",
                null
        );

        QuizGradeResultService.QuizGrowthDecision decision = service.toGrowthDecision(request);

        assertThat(decision.dbDelta()).isEqualTo(1);
        assertThat(decision.algorithmDelta()).isEqualTo(2);
        assertThat(decision.csDelta()).isEqualTo(3);
        assertThat(decision.networkDelta()).isEqualTo(4);
        assertThat(decision.frameworkDelta()).isEqualTo(5);
        assertThat(decision.emotion()).isEqualTo(CharacterEmotion.JOY);
        assertThat(decision.statusMessage()).isEqualTo("좋아요, 핵심은 잡았어요!");
    }

    @Test
    void ungradedResultMustNotCarryPositiveScoreDelta() {
        QuizGradeResultRequest request = new QuizGradeResultRequest(
                "submission-2",
                42L,
                55L,
                QuizGradeStatus.UNGRADED,
                new FastApiScoreDelta(1, 2, 3, 4, 5),
                new FastApiScoreDelta(0, 1, 0, 0, 0),
                null,
                null,
                "AI가 잠깐 쉬는 중이에요.",
                "LLM_TIMEOUT"
        );

        assertThatThrownBy(() -> service.toGrowthDecision(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNGRADED");
    }
}
