import {
  activeCharacter, activeQuizzes, addReview, board, boostCharacterStat, clearStudyAnalysisNotice, createBoardPost,
  createCharacter, deleteBoardPost, deleteReview, deliverDailyReport, dismissEvolution, gameState, hasStudyAnalysisNotice,
  hasUnreadCharacterSseResult, nurtureScore, REPORT_SUBMITTED_NOTICE,
  createDemoQuizzes, quizzesForCharacter, resetGameState, runReportNow, saveReport, setActive,
  showReportSubmittedNotice, submitQuiz, updateBoardPost, updateReview,
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
  ok(gameState.notice === null, '저장 함수만으로는 제출 토스트를 표시하지 않음')
  showReportSubmittedNotice()
  ok(gameState.notice === REPORT_SUBMITTED_NOTICE, '리포트 제출 버튼 경로에서만 제출 토스트 표시')

  createCharacter({ name: '다른 캐릭터', keyword: '', personality: '' })
  const authorBefore = nurtureScore(author)
  const otherBefore = nurtureScore(activeCharacter.value)
  deliverDailyReport()
  const authorAfter = nurtureScore(author)
  deliverDailyReport()
  ok(authorAfter === authorBefore + 4, '리포트 작성 캐릭터에 점수 반영')
  ok(nurtureScore(author) === authorAfter, '같은 리포트 반복 전달은 점수 중복 반영 없음')
  ok(nurtureScore(activeCharacter.value) === otherBefore, '현재 활성 캐릭터가 점수를 가로채지 않음')
  ok(hasStudyAnalysisNotice('report', report.id) && hasUnreadCharacterSseResult(author.id), '도착한 리포트 결과는 해당 공부 기록만 미확인 표시')
  clearStudyAnalysisNotice('report', report.id)
  ok(!hasStudyAnalysisNotice('report', report.id) && !hasUnreadCharacterSseResult(author.id), '리포트 기록을 읽으면 미확인 표시 해제')
}

resetGameState()
setActive(gameState.characters[0].id)
{
  saveReport({ mood: 'joy', title: '즉시 제출', content: '내용', tags: ['algo'] })
  const result = await runReportNow()
  ok(result.timedOut === false && gameState.dailyReport.status === 'ready', '즉시 제출은 결과 준비까지 완료 신호를 반환')
}

console.log('FE-7 — 퀴즈 귀속과 동시 제출 방지')
resetGameState()
setActive(gameState.characters[0].id)
{
  const owner = activeCharacter.value
  const quiz = seedQuiz(owner.id)
  createCharacter({ name: '다른 캐릭터', keyword: '', personality: '' })
  const ownerBefore = nurtureScore(owner)
  const otherBefore = nurtureScore(activeCharacter.value)
  await Promise.all([submitQuiz(quiz.id, quiz.answer), submitQuiz(quiz.id, quiz.answer)])
  ok(nurtureScore(owner) === ownerBefore + 12, '동시 제출도 퀴즈 소유 캐릭터에 한 번만 가산')
  ok(nurtureScore(activeCharacter.value) === otherBefore, '활성 캐릭터 변경 후에도 점수 귀속 유지')
  ok(hasStudyAnalysisNotice('quiz', quiz.id) && hasUnreadCharacterSseResult(owner.id), '채점된 퀴즈 결과는 해당 공부 기록만 미확인 표시')
  clearStudyAnalysisNotice('quiz', quiz.id)
  ok(!hasStudyAnalysisNotice('quiz', quiz.id) && !hasUnreadCharacterSseResult(owner.id), '퀴즈 기록을 읽으면 미확인 표시 해제')

  owner.stats = { algo: 990, cs: 0, db: 0, net: 0, fw: 0 }
  owner.isEvolved = false
  dismissEvolution()
  const evolveQuiz = seedQuiz(owner.id, 'evolve-q1')
  evolveQuiz.deltaAmount = 12
  const evolveResult = await submitQuiz(evolveQuiz.id, evolveQuiz.answer)
  ok(evolveResult?._evolvedNow && owner.isEvolved, '퀴즈 점수로 총합 1000을 넘으면 캐릭터가 진화')
  ok(gameState.evolution?.id === owner.id && gameState.evolution.score >= 1000, '진화 화면 이벤트가 큐에 올라감')
  dismissEvolution()
  ok(gameState.evolution === null, '진화 화면 이벤트는 닫을 수 있음')
}

resetGameState()
setActive(gameState.characters[0].id)
{
  const target = activeCharacter.value
  const other = createCharacter({ name: '퀴즈 활성 캐릭터', keyword: '', personality: '' })
  createDemoQuizzes(target.id)
  ok(String(activeCharacter.value?.id) === String(other.id), '캐릭터 지정 퀴즈 생성은 현재 활성 캐릭터를 바꾸지 않음')
  ok(quizzesForCharacter(target.id).length === 2, '지정한 캐릭터의 추천 퀴즈만 생성')
  ok(activeQuizzes.value.length === 0, '활성 캐릭터가 달라도 scoped 퀴즈가 섞여 보이지 않음')
  ok(gameState.quizzes.every(q => String(q.characterId) === String(target.id)), '생성된 퀴즈는 지정 캐릭터 ID에 귀속')
}

console.log('FE-8 — 시연용 스탯 보강')
resetGameState()
setActive(gameState.characters[0].id)
{
  const character = activeCharacter.value
  const beforeScore = nurtureScore(character)
  const beforeDb = character.stats.db
  const result = await boostCharacterStat(character.id, 'db', 200)
  ok(result && result.stats.db === beforeDb + 200, '선택 스탯을 200 올림')
  ok(nurtureScore(character) === beforeScore + 200, '육아점수도 함께 증가')
  ok(character.message.includes('DB') && character.emotion === 'joy', '시연용 반영 메시지와 감정 갱신')

  character.stats = { algo: 960, cs: 0, db: 0, net: 0, fw: 0 }
  character.isEvolved = false
  dismissEvolution()
  const evolved = await boostCharacterStat(character.id, 'cs', 50)
  ok(evolved?._evolvedNow && character.isEvolved, '시연용 스탯 보강도 1000점 돌파 시 진화 처리')
  ok(gameState.evolution?.id === character.id, '시연용 진화도 전역 진화 화면 이벤트를 표시')
  dismissEvolution()
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
  ok(typeof review.createdAt === 'string' && !Number.isNaN(new Date(review.createdAt).getTime()), '리뷰 작성 일시 저장')
  ok(updateReview('b1', 'r1', 1, '탈취 수정') === false, '타인 리뷰 수정 거부')
  ok(deleteReview(post.id, review.id) && post.rating === 0, '본인 리뷰 삭제 후 평점 재계산')
  ok(deleteBoardPost('b1') === false && deleteBoardPost(post.id), '타인 글 삭제 거부·본인 글 삭제')
}

setActive(gameState.characters[0]?.id)
console.log(`\n결과: ${pass} passed, ${fail} failed`)
process.exit(fail ? 1 : 0)
