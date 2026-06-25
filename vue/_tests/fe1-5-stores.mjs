import {
  activeCharacter, clearCharacterSseResult, createCharacter, deleteCharacter, gameState,
  hasCharacterSseResult, hasUnreadCharacterSseResult, markCharacterResultArrived,
  markCharacterResultRead, nurtureScore, resetGameState, setActive, submitQuiz,
  updateCharacter,
} from '../src/stores/game.js'

let pass = 0, fail = 0
const ok = (condition, message) => {
  if (condition) { pass++; console.log('  ✓', message) }
  else { fail++; console.error('  ✗', message) }
}
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

console.log('FE-3~5 — 캐릭터 불변식')
resetGameState()
const original = gameState.characters[0]
ok(activeCharacter.value === null, '로그인 직후에는 활성 캐릭터가 자동 선택되지 않음')
ok(setActive(original.id) && activeCharacter.value === original, '사용자가 선택해야 활성 캐릭터가 지정됨')
markCharacterResultArrived(original.id)
ok(hasCharacterSseResult(original.id) && hasUnreadCharacterSseResult(original.id), '도착한 결과는 읽기 전 깜빡임 대상')
markCharacterResultRead(original.id)
ok(hasCharacterSseResult(original.id) && !hasUnreadCharacterSseResult(original.id), '읽은 결과는 내부 기록으로만 남고 미확인 배지 대상에서 제외')
clearCharacterSseResult(original.id)
ok(!hasCharacterSseResult(original.id), '결과 알림은 명시적으로 정리 가능')
const second = createCharacter({ name: '둘째', keyword: 'blue', personality: 'calm' })
ok(activeCharacter.value?.id === second.id && !original.active, '신규 캐릭터만 활성화')
ok(setActive('missing') === false && activeCharacter.value?.id === second.id, '잘못된 활성 ID는 상태를 변경하지 않음')
ok(setActive(String(original.id)) && activeCharacter.value === original, '문자열 route ID로 활성 지정')
updateCharacter(original.id, { name: '수정됨', keyword: 'new', personality: 'kind', stats: { algo: 9999 } })
ok(original.name === '수정됨' && original.stats.algo !== 9999, '편집은 허용 필드만 변경')
deleteCharacter(String(second.id))
ok(!gameState.characters.includes(second) && activeCharacter.value === original, '문자열 route ID 삭제와 활성 단일성 유지')

console.log('FE-5 — 기록 귀속과 중복 점수 방지')
const quiz = seedQuiz(original.id)
const before = nurtureScore(original)
await submitQuiz(quiz.id, quiz.answer)
const after = nurtureScore(original)
await submitQuiz(quiz.id, quiz.answer)
ok(quiz.characterId === original.id, '채점 기록을 점수 수령 캐릭터에 귀속')
ok(after > before && nurtureScore(original) === after, '채점 완료 퀴즈 재호출로 점수 중복 지급하지 않음')

console.log(`\n결과: ${pass} passed, ${fail} failed`)
process.exit(fail ? 1 : 0)
