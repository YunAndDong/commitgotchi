package com.commitgotchi.debug;

import com.commitgotchi.report.application.ReportMidnightEnqueueService;
import com.commitgotchi.report.application.ReportOutboxDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 시연/디버그 전용 컨트롤러 — architecture 계약(흐름 A 리포트 SQS)을 사람이
 * 버튼처럼 즉시 트리거하기 위한 엔드포인트.
 *
 * <p><b>경계/주의 (파트너가 이어받을 부분):</b>
 * <ul>
 *   <li>{@code @Profile("local","dev")} 로 운영(prod) 프로파일에는 빈 자체가 등록되지 않는다.
 *       즉 prod에서는 이 엔드포인트가 존재하지 않는다(404).</li>
 *   <li>인증을 붙이지 않았다. 대신 추측이 어려운 <b>난수 경로 토큰</b>({@link #DEMO_TOKEN})으로만
 *       호출할 수 있게 했다. <b>이건 "혼자 하는 시연" 한정 임시 방편</b>이며, 운영/공유 환경에서는
 *       반드시 내부 인증(예: {@code Authorization: Internal <secret>}) 또는 ADMIN 권한으로 교체해야 한다.</li>
 *   <li>여기서 호출하는 것은 자정 스케줄러가 하는 일과 동일하다:
 *       {@link ReportMidnightEnqueueService#enqueueForTargetDate(LocalDate)} (outbox 적재) +
 *       {@link ReportOutboxDispatcher#dispatchAvailable(Instant)} (outbox → SQS 전송).
 *       즉 흐름 A①을 즉시 수행한다. 이후 FastAPI 워커가 SQS를 소비해
 *       {@code POST /api/report} 콜백으로 결과/추천 퀴즈를 돌려준다(흐름 A②).</li>
 *   <li>SecurityConfig 에서 {@code /api/debug/**} 를 permitAll 로 열어두었다(인증 미적용과 짝).</li>
 * </ul>
 *
 * <p>TODO(파트너): 시연 후 (1) 내부 인증/ADMIN 가드로 교체하거나 (2) 이 컨트롤러와
 * SecurityConfig 의 /api/debug/** permitAll 을 함께 제거할 것.
 */
@RestController
@Profile({"local", "dev"})
public class DebugController {

    private static final Logger log = LoggerFactory.getLogger(DebugController.class);

    /**
     * 난수 경로 토큰. 인증 대신 "추측 불가 경로"로만 동작하게 하는 시연용 가드.
     * 운영에서는 이 방식을 쓰지 말 것(위 클래스 주석 참고).
     */
    private static final String DEMO_TOKEN = "demo-7f3a9c2e8b1d40569ace12b4d7f60a83";
    private static final Set<String> EMOTIONS = Set.of("JOY", "SAD", "ANGRY");

    private final ReportMidnightEnqueueService enqueueService;
    private final ReportOutboxDispatcher dispatcher;
    private final JdbcTemplate jdbcTemplate;

    public DebugController(
            ReportMidnightEnqueueService enqueueService,
            ReportOutboxDispatcher dispatcher,
            DataSource dataSource
    ) {
        this.enqueueService = enqueueService;
        this.dispatcher = dispatcher;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * 흐름 A를 즉시 실행한다: 대상일의 리포트 요청을 outbox에 적재 → (재시연 위해) 강제로
     * PENDING 재설정 → 곧바로 SQS로 dispatch. 자정 스케줄러 + dispatcher 를 기다리지 않는다.
     *
     * <p>발표 시연 흐름: "오늘 리포트 작성 → 이 버튼 → 즉시 결과/추천 퀴즈".
     * 같은 날 여러 번 눌러도(리허설/본방) 매번 재처리되도록 outbox 를 PENDING 으로 되돌린다
     * (requestId 멱등이라 점수 중복 누적은 Spring 콜백 처리에서 막는다).
     *
     * @param token   경로 난수 토큰. {@link #DEMO_TOKEN} 과 일치해야 한다.
     * @param date    대상일(YYYY-MM-DD). 생략 시 오늘(Asia/Seoul). saveReport 가 today 로 저장하므로 보통 오늘.
     * @param emotion (선택) JOY|SAD|ANGRY. 주면 outbox 의 감정 스냅샷을 덮어써 캐릭터 감정에 따른
     *                말투 차이를 시연할 수 있다. 생략 시 작성 시점 감정을 그대로 사용.
     */
    @PostMapping("/api/debug/{token}/report/run-now")
    public ResponseEntity<Map<String, Object>> runReportNow(
            @PathVariable String token,
            @RequestParam(name = "date", required = false) String date,
            @RequestParam(name = "emotion", required = false) String emotion
    ) {
        if (!DEMO_TOKEN.equals(token)) {
            // 잘못된 토큰은 존재를 노출하지 않도록 404.
            return ResponseEntity.notFound().build();
        }

        LocalDate targetDate = (date == null || date.isBlank())
                ? LocalDate.now(ZoneId.of("Asia/Seoul"))
                : LocalDate.parse(date.trim());

        String normalizedEmotion = null;
        if (emotion != null && !emotion.isBlank()) {
            normalizedEmotion = emotion.trim().toUpperCase();
            if (!EMOTIONS.contains(normalizedEmotion)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "emotion must be one of JOY, SAD, ANGRY"));
            }
        }

        log.info("[debug] run report flow A now for targetDate={} emotion={}", targetDate, normalizedEmotion);

        // 1) outbox 적재 (없으면 생성)
        var enqueueResult = enqueueService.enqueueForTargetDate(targetDate);

        // 2) 재시연을 위해 해당 대상일 outbox 를 강제로 PENDING 으로 되돌린다.
        //    emotion 을 주면 감정 스냅샷도 덮어쓴다(시연용). 운영 코드 경로가 아닌 디버그 전용.
        int reset;
        if (normalizedEmotion != null) {
            reset = jdbcTemplate.update(
                    "UPDATE report_request_outbox "
                            + "SET status='PENDING', sent_at=NULL, available_at=now(), "
                            + "    character_emotion=?, attempt_count=0, last_error=NULL "
                            + "WHERE target_date=?",
                    normalizedEmotion, java.sql.Date.valueOf(targetDate));
        } else {
            reset = jdbcTemplate.update(
                    "UPDATE report_request_outbox "
                            + "SET status='PENDING', sent_at=NULL, available_at=now(), "
                            + "    attempt_count=0, last_error=NULL "
                            + "WHERE target_date=?",
                    java.sql.Date.valueOf(targetDate));
        }

        // 3) 즉시 SQS 로 dispatch (자동 dispatcher 는 데모에서 꺼져 있음)
        var dispatchResult = dispatcher.dispatchAvailable(Instant.now());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetDate", targetDate.toString());
        body.put("emotionOverride", normalizedEmotion);
        body.put("enqueue", enqueueResult);
        body.put("outboxResetToPending", reset);
        body.put("dispatch", dispatchResult);
        body.put("note", "흐름 A① 트리거 완료. FastAPI 워커가 SQS 소비 후 /api/report 콜백으로 결과를 반영한다(흐름 A②).");
        return ResponseEntity.ok(body);
    }
}
