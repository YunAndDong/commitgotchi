import {
  activeCharacter, addReview, board, createBoardPost, createCharacter, deleteBoardPost,
  deleteReview, deliverDailyReport, gameState, nurtureScore, resetGameState, saveReport,
  setActive, submitQuiz, updateBoardPost, updateReview,
} from '../src/stores/game.js'

let pass = 0, fail = 0
const ok = (condition, message) => {
  if (condition) { pass++; console.log('  ✓', message) }
  else { fail++; console.error('  ✗', message) }
}

console.log('FE-6 — 일일 레포트 귀속과 멱등성')
resetGameState()
setActive(gameState.characters[0].id)
gameState.reports.splice(0)
{
  const author = activeCharacter.value
  const tags = ['algo']
  const report = saveReport({ mood: 'joy', title: '오늘 학습', content: '내용', tags })
  tags.push('db')
  ok(report.tags.length === 1, '저장된 태그는 입력 배열과 참조를 공유하지 않음')

  createCharacter({ name: '다른 캐릭터', keyword: '', personality: '' })
  const authorBefore = nurtureScore(author)
  const otherBefore = nurtureScore(activeCharacter.value)
  deliverDailyReport()
  const authorAfter = nurtureScore(author)
  deliverDailyReport()
  ok(authorAfter === authorBefore + 4, '리포트 작성 캐릭터에 점수 반영')
  ok(nurtureScore(author) === authorAfter, '같은 리포트 반복 전달은 점수 중복 반영 없음')
  ok(nurtureScore(activeCharacter.value) === otherBefore, '현재 활성 캐릭터가 점수를 가로채지 않음')
}

console.log('FE-7 — 퀴즈 귀속과 동시 제출 방지')
resetGameState()
setActive(gameState.characters[0].id)
{
  const owner = activeCharacter.value
  const quiz = gameState.quizzes[0]
  createCharacter({ name: '다른 캐릭터', keyword: '', personality: '' })
  const ownerBefore = nurtureScore(owner)
  const otherBefore = nurtureScore(activeCharacter.value)
  await Promise.all([submitQuiz(quiz.id, quiz.answer), submitQuiz(quiz.id, quiz.answer)])
  ok(nurtureScore(owner) === ownerBefore + 12, '동시 제출도 퀴즈 소유 캐릭터에 한 번만 가산')
  ok(nurtureScore(activeCharacter.value) === otherBefore, '활성 캐릭터 변경 후에도 점수 귀속 유지')
}

console.log('FE-9 — 게시글·리뷰 CRUD 소유권')
resetGameState()
setActive(gameState.characters[0].id)
{
  const post = createBoardPost('내 캐릭터 소개')
  ok(post && board()[0]?.id === post.id, '본인 게시글 작성')
  ok(updateBoardPost(post.id, '수정된 소개') && post.desc === '수정된 소개', '본인 게시글 수정')
  ok(updateBoardPost('b1', '탈취 수정') === false, '타인 게시글 수정 거부')

  const review = addReview(post.id, 5, '좋아요')
  ok(review && updateReview(post.id, review.id, 4, '수정 리뷰'), '본인 리뷰 작성·수정')
  ok(updateReview('b1', 'r1', 1, '탈취 수정') === false, '타인 리뷰 수정 거부')
  ok(deleteReview(post.id, review.id) && post.rating === 0, '본인 리뷰 삭제 후 평점 재계산')
  ok(deleteBoardPost('b1') === false && deleteBoardPost(post.id), '타인 글 삭제 거부·본인 글 삭제')
}

setActive(gameState.characters[0]?.id)
console.log(`\n결과: ${pass} passed, ${fail} failed`)
process.exit(fail ? 1 : 0)
