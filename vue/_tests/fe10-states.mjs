/*
 * FE-10 store 로직 검증 (프레임워크 없이 node 단독 실행).
 *   node _tests/fe10-states.mjs
 *
 * 확인 대상:
 *  - AC3 퀴즈 채점 실패 → 답안 보존 · 점수 미반영 · 재시도 가능, 재시도 성공 시 점수 반영
 *  - AC3 일일 레포트 실패 → status 'failed' · 작성 리포트 보존 · 점수 변화 없음
 *  - AC4 이미지 실패 → imageStatus 'FALLBACK' (캐릭터는 계속 사용 가능), retryImage 로 복구
 */
import {
  gameState, activeCharacter, nurtureScore,
  submitQuiz, deliverDailyReport, createCharacter, retryImage,
  setActive,
} from '../src/stores/game.js'

let pass = 0, fail = 0
const ok = (cond, msg) => { if (cond) { pass++; console.log('  ✓', msg) } else { fail++; console.error('  ✗', msg) } }
const sleep = (ms) => new Promise(r => setTimeout(r, ms))
const seedQuiz = (characterId, id = 'test-q1') => {
  const quiz = {
    id,
    date: new Intl.DateTimeFormat('en-CA').format(new Date()),
    sourceReportRequestId: 'test-report-request',
    tag: 'algo',
    question: '테스트 퀴즈',
    answer: 0,
    submitted: false,
    selected: null,
    correct: null,
    scored: false,
    gradeFailed: false,
    feedback: null,
    deltaStat: 'algo',
    deltaAmount: 12,
    characterId,
  }
  gameState.quizzes.push(quiz)
  return quiz
}

console.log('AC3 — 퀴즈 채점 실패 Fallback')
setActive(gameState.characters[0].id)
{
  const q = seedQuiz(activeCharacter.value.id)
  const before = nurtureScore(activeCharacter.value)
  const res = await submitQuiz(q.id, 0, { fail: true })
  ok(res && res.ok === false, '실패는 { ok:false } 로 반환')
  ok(q.selected === 0, '고른 답안(selected) 보존')
  ok(q.gradeFailed === true, 'gradeFailed 플래그 표시')
  ok(q.scored === false, '채점 미완료(scored=false)')
  ok(q.submitted === false, '재시도 가능(submitted 해제)')
  ok(nurtureScore(activeCharacter.value) === before, '점수 미반영(데이터 무손실)')

  // 재시도 성공
  const res2 = await submitQuiz(q.id, q.answer, { fail: false })
  ok(res2 && res2.scored === true && res2.correct === true, '재시도 성공 시 정상 채점')
  ok(q.gradeFailed === false, '성공 후 gradeFailed 해제')
  ok(nurtureScore(activeCharacter.value) > before, '재시도 성공 시 점수 반영')
}

console.log('AC3 — 일일 레포트 실패 Fallback')
{
  const reportsBefore = gameState.reports.length
  const scoreBefore = nurtureScore(activeCharacter.value)
  const rep = deliverDailyReport({ fail: true })
  ok(rep.status === 'failed', "status='failed'")
  ok(rep.summary === null && rep.deltas === null, '결과 본문 없음(빈 화면 금지는 뷰가 Fallback 표시)')
  ok(gameState.reports.length === reportsBefore, '작성한 리포트 보존')
  ok(nurtureScore(activeCharacter.value) === scoreBefore, '점수 변화 없음')
  ok(!/error/i.test(gameState.notice || ''), '알림 카피에 "Error" 원문 없음')

  const rep2 = deliverDailyReport()
  ok(rep2.status === 'ready', '재요청 시 정상 도착')
}

console.log('AC4 — 이미지 생성 실패 Fallback')
{
  const c = createCharacter({ name: '테스트', keyword: 'k', personality: 'p' }, { failImage: true })
  ok(c.imageStatus === 'PENDING', '생성 직후 PENDING')
  ok(activeCharacter.value && activeCharacter.value.id === c.id, '실패 예정이어도 캐릭터는 활성/사용 가능')
  await sleep(2400)
  ok(c.imageStatus === 'FALLBACK', '실패 시 imageStatus=FALLBACK (깨진 이미지 아님)')
  retryImage(c.id)
  ok(c.imageStatus === 'PENDING', 'retryImage 후 PENDING 재진입')
  await sleep(1800)
  ok(c.imageStatus === 'READY', '재시도 성공 시 READY')
}

console.log(`\n결과: ${pass} passed, ${fail} failed`)
process.exit(fail ? 1 : 0)
