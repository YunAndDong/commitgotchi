/*
 * FE-10 — AI 상태 패턴 카피의 단일 출처(SSOT).
 *
 * 로딩(즉시 채점) / 대기(자정 배치) / Fallback(실패) 카피를 한 곳에 모은다.
 * 모든 표면(퀴즈·일일 레포트·이미지)은 이 상수를 재사용해 카피·톤을 일관되게 유지하고
 * 흐름 A(대기)와 흐름 B(즉시)의 카피가 섞이지 않도록 한다(EXPERIENCE Voice 표 준수).
 *
 * 규칙: 사용자에게 "Error" 원문을 노출하지 않는다. 실패는 항상 친화적 Fallback으로 표현한다.
 */

// 즉시 처리(흐름 B) — 짧은 로딩
export const LOADING = {
  quiz: '채점 중…',
}

// 자정 배치(흐름 A) — 대기. "처리 중..." 같은 모호한 표현 금지.
export const WAITING = {
  report: '오늘 학습 리포트는 자정에 분석돼요. 내일 오전 9시 도착.',
  reportResult: '리포트 분석 대기 — 내일 오전 9시 도착해요.',
}

// 실패(Fallback) — 데이터는 보존됐음을 항상 함께 안내.
export const FALLBACK = {
  base: 'AI가 잠깐 쉬는 중 — 학습은 저장됐어요.',
  report: 'AI가 잠깐 쉬는 중 — 학습은 저장됐어요. 점수 변화는 다음 분석에 반영돼요.',
  quiz: 'AI가 잠깐 쉬는 중 — 답안은 저장됐어요. 잠시 후 다시 채점해 주세요.',
  quizRetry: '잠시 후 재시도',
  image: 'AI가 잠깐 쉬는 중 — 기본 모습으로 함께해요.',
}

// CgState tone → 아이콘 매핑(시각만으로 정보 전달하지 않도록 카피와 항상 병기).
export const TONE_ICON = {
  loading: '⏳',
  waiting: '⏳',
  fallback: '😴',
}
