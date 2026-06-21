package com.commitgotchi.report.application;

import com.commitgotchi.character.api.dto.FastApiScoreDelta;
import com.commitgotchi.character.application.CharacterCommandService;
import com.commitgotchi.character.domain.CharacterEmotion;
import com.commitgotchi.report.api.dto.ReportCallbackRequest;
import com.commitgotchi.report.api.dto.ReportCallbackResponse;
import com.commitgotchi.report.api.dto.ReportCallbackStatus;
import org.springframework.stereotype.Service;

@Service
public class ReportCallbackService {

    private final CharacterCommandService characterCommandService;

    public ReportCallbackService(CharacterCommandService characterCommandService) {
        this.characterCommandService = characterCommandService;
    }

    public ReportCallbackResponse handle(ReportCallbackRequest request) {
        FastApiScoreDelta delta = request.scoreDelta();
        characterCommandService.applyScoreDeltas(
                request.userId(),
                request.characterId(),
                delta.dbDelta(),
                delta.algorithmDelta(),
                delta.csDelta(),
                delta.networkDelta(),
                delta.frameworkDelta(),
                decideEmotion(request.status(), delta),
                request.statusMessage()
        );
        // TODO(BE-3): replace this placeholder with a durable report_results idempotency marker keyed by requestId.
        return ReportCallbackResponse.acceptedResult();
    }

    private CharacterEmotion decideEmotion(ReportCallbackStatus status, FastApiScoreDelta delta) {
        if (status == ReportCallbackStatus.FALLBACK) {
            return CharacterEmotion.SAD;
        }
        return delta.sum() > 0 ? CharacterEmotion.JOY : CharacterEmotion.SAD;
    }
}
