import {
  activeCharacter, createCharacter, deleteCharacter, gameState, nurtureScore,
  resetGameState, setActive, submitQuiz, updateCharacter,
} from '../src/stores/game.js'

let pass = 0, fail = 0
const ok = (condition, message) => {
  if (condition) { pass++; console.log('  ✓', message) }
  else { fail++; console.error('  ✗', message) }
}

console.log('FE-3~5 — 캐릭터 불변식')
resetGameState()
const original = activeCharacter.value
const second = createCharacter({ name: '둘째', keyword: 'blue', personality: 'calm' })
ok(activeCharacter.value?.id === second.id && !original.active, '신규 캐릭터만 활성화')
ok(setActive('missing') === false && activeCharacter.value?.id === second.id, '잘못된 활성 ID는 상태를 변경하지 않음')
ok(setActive(String(original.id)) && activeCharacter.value === original, '문자열 route ID로 활성 지정')
updateCharacter(original.id, { name: '수정됨', keyword: 'new', personality: 'kind', stats: { algo: 9999 } })
ok(original.name === '수정됨' && original.stats.algo !== 9999, '편집은 허용 필드만 변경')
deleteCharacter(String(second.id))
ok(!gameState.characters.includes(second) && activeCharacter.value === original, '문자열 route ID 삭제와 활성 단일성 유지')

console.log('FE-5 — 기록 귀속과 중복 점수 방지')
const quiz = gameState.quizzes[0]
const before = nurtureScore(original)
await submitQuiz(quiz.id, quiz.answer)
const after = nurtureScore(original)
await submitQuiz(quiz.id, quiz.answer)
ok(quiz.characterId === original.id, '채점 기록을 점수 수령 캐릭터에 귀속')
ok(after > before && nurtureScore(original) === after, '채점 완료 퀴즈 재호출로 점수 중복 지급하지 않음')

console.log(`\n결과: ${pass} passed, ${fail} failed`)
process.exit(fail ? 1 : 0)
