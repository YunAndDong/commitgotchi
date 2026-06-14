/*
 * Game store — MOCK data layer (reactive singleton).
 *
 * The Spring Boot backend currently only ships the auth epic; there are no
 * character / report / quiz / ranking / board endpoints yet. Everything here is
 * an in-memory mock that mirrors the PRD growth rules and EXPERIENCE.md flows so
 * the full UI is exercisable today. Swap each action for a real `authed(...)`
 * call once the SoR endpoints land — the shapes are intentionally API-like.
 *
 * Growth rules (PRD FR-21~23):
 *   육아점수(nurtureScore) = 5 스탯 총합 (algo+cs+db+net+fw)
 *   진화(evolution): 육아점수가 1,000 최초 통과 시 1회
 *   감정(emotion): joy | sad | angry
 */
import { reactive, computed } from 'vue'

export const STAT_KEYS = ['algo', 'cs', 'db', 'net', 'fw']
export const STAT_LABELS = { algo: '알고리즘', cs: 'CS', db: 'DB', net: '네트워크', fw: '프레임워크' }
export const EVOLVE_THRESHOLD = 1000
export const MAX_CHARACTERS = 3
export const CURRENT_OWNER = '나'

let _id = 100
const nextId = () => ++_id
const todayISO = () => new Intl.DateTimeFormat('en-CA').format(new Date())

function newStats() { return { algo: 0, cs: 0, db: 0, net: 0, fw: 0 } }
export function nurtureScore(c) { return STAT_KEYS.reduce((s, k) => s + (Number.isFinite(c?.stats?.[k]) ? c.stats[k] : 0), 0) }

function makeCharacter({ name, keyword, personality }) {
  return {
    id: nextId(),
    name,
    keyword,
    personality,
    stats: newStats(),
    emotion: 'joy',
    isEvolved: false,
    imageStatus: 'PENDING',   // PENDING → READY (mocked async, Flow C)
    active: false,
    message: '잘 부탁해! 오늘부터 함께 공부하자.',
    createdAt: new Date().toISOString(),
  }
}

/* ---- seed: a started demo character so the dashboard isn't empty on first look ---- */
function seedCharacter() {
  const c = makeCharacter({
    name: '새싹이',
    keyword: '연두색 새싹 + 동그란 눈 + 흙갈색 화분',
    personality: '칭찬은 많이, 틀린 건 명확히 짚어주는',
  })
  c.stats = { algo: 142, cs: 96, db: 120, net: 58, fw: 84 }
  c.imageStatus = 'READY'
  c.active = true
  c.emotion = 'joy'
  c.message = '다익스트라 드디어 이해했네! 이 기세 그대로 가자.'
  return c
}

function initialState() {
  const seed = seedCharacter()
  return {
  characters: [seed],
  reports: [
    { id: nextId(), date: todayISO(), mood: 'joy', title: '다익스트라 드디어 이해함!',
      content: '우선순위 큐로 최단경로를 푸는 과정을 손으로 따라가니 그제서야 감이 왔다.',
      tags: ['algo'], status: 'analyzing', scoreApplied: false, characterId: seed.id },
  ],
  quizzes: [
    { id: nextId(), date: todayISO(), tag: 'algo',
      question: '다익스트라 알고리즘이 음의 가중치 간선을 처리하지 못하는 이유로 가장 적절한 것은?',
      options: [
        '우선순위 큐를 쓰기 때문에',
        '한 번 확정한 최단거리를 다시 갱신하지 않는 그리디 전제 때문에',
        '인접 리스트를 사용하기 때문에',
        '시간복잡도가 높기 때문에',
      ],
      answer: 1, submitted: false, selected: null, correct: null, scored: false,
      gradeFailed: false, feedback: null, deltaStat: 'algo', deltaAmount: 0, characterId: seed.id },
    { id: nextId(), date: todayISO(), tag: 'net',
      question: 'TCP 3-way handshake에서 클라이언트가 마지막으로 보내는 세그먼트는?',
      options: ['SYN', 'SYN-ACK', 'ACK', 'FIN'],
      answer: 2, submitted: false, selected: null, correct: null, scored: false,
      gradeFailed: false, feedback: null, deltaStat: 'net', deltaAmount: 0, characterId: seed.id },
  ],
  // AI daily report (자정 배치 결과). 'pending' until next-morning arrival.
  dailyReport: {
    status: 'pending', // pending | ready | failed
    date: todayISO(),
    characterId: seed.id,
    summary: null,
    deltas: null,
    quizComment: null,
    nextRecommendation: null,
  },
  notice: null,
  }
}

const state = reactive(initialState())
export function resetGameState() {
  Object.assign(state, initialState())
  boardPosts.splice(0, boardPosts.length, ...initialBoardPosts())
}

/* ---- derived ---- */
export const activeCharacter = computed(() => state.characters.find(c => c.active) || null)
export const hasCharacter = computed(() => state.characters.length > 0)
export const todayReport = computed(() => state.reports.find(
  r => r.date === todayISO() && String(r.characterId) === String(activeCharacter.value?.id)
) || null)
export const todayQuizzes = computed(() => state.quizzes.filter(q => q.date === todayISO()))
export const activeQuizzes = computed(() => state.quizzes.filter(
  q => q.date === todayISO() && String(q.characterId) === String(activeCharacter.value?.id)
))
export const pendingReport = computed(() => state.reports
  .filter(r => r.status === 'analyzing')
  .sort((a, b) => a.date.localeCompare(b.date))[0] || null)

/* ---- ranking (mock leaderboard, 육아점수 desc) ---- */
export function ranking() {
  const mine = activeCharacter.value
  const others = [
    { id: 1, name: '코드몬', owner: 'jimin', score: 1840, isEvolved: true, emotion: 'joy' },
    { id: 2, name: '알고핑', owner: 'dwshin', score: 1320, isEvolved: true, emotion: 'joy' },
    { id: 3, name: '쿼리냥', owner: 'sora', score: 760, isEvolved: false, emotion: 'sad' },
    { id: 4, name: '패킷이', owner: 'minho', score: 540, isEvolved: false, emotion: 'angry' },
    { id: 5, name: '리액트리', owner: 'hana', score: 410, isEvolved: false, emotion: 'joy' },
  ]
  const rows = [...others]
  if (mine) rows.push({ id: mine.id, name: mine.name, owner: '나', score: nurtureScore(mine), isEvolved: mine.isEvolved, emotion: mine.emotion, me: true })
  return rows.sort((a, b) => b.score - a.score).map((r, i) => ({ ...r, rank: i + 1 }))
}

/* ---- codex (도감) — collected vs locked ---- */
export function codex() {
  const owned = state.characters.map(c => ({ id: c.id, name: c.name, emotion: c.emotion, isEvolved: c.isEvolved, owned: true }))
  const locked = [
    { id: 'lk1', name: '???', emotion: 'joy', isEvolved: false, owned: false },
    { id: 'lk2', name: '???', emotion: 'sad', isEvolved: true, owned: false },
    { id: 'lk3', name: '???', emotion: 'angry', isEvolved: false, owned: false },
    { id: 'lk4', name: '???', emotion: 'joy', isEvolved: true, owned: false },
  ]
  return [...owned, ...locked]
}

/* ---- share board (게시판) ---- */
function initialBoardPosts() {
  return [
  { id: 'b1', name: '코드몬', owner: 'jimin', emotion: 'joy', isEvolved: true, score: 1840,
    desc: '6개월 키운 알고리즘 특화 분신. 그리디/DP 위주로 먹였어요.',
    rating: 4.6, reviews: [
      { id: 'r1', author: 'sora', stars: 5, text: '진화 연출 너무 귀엽다 ㅠㅠ' },
      { id: 'r2', author: 'minho', stars: 4, text: '스탯 밸런스가 좋네요.' },
    ] },
  { id: 'b2', name: '쿼리냥', owner: 'sora', emotion: 'sad', isEvolved: false, score: 760,
    desc: 'DB만 파고든 외길 분신. 인덱스 튜닝 리포트가 많아요.',
    rating: 4.1, reviews: [{ id: 'r3', author: 'hana', stars: 4, text: 'DB 리포트 참고됐어요!' }] },
  { id: 'b3', name: '패킷이', owner: 'minho', emotion: 'angry', isEvolved: false, score: 540,
    desc: '네트워크 위주. TCP/IP 정주행 기록.',
    rating: 3.9, reviews: [] },
  ]
}
const boardPosts = reactive(initialBoardPosts())
export function board() { return boardPosts }
export function boardPost(id) { return boardPosts.find(p => p.id === id) || null }
function recalculateRating(p) {
  const avg = p.reviews.length
    ? p.reviews.reduce((sum, review) => sum + review.stars, 0) / p.reviews.length
    : 0
  p.rating = Math.round(avg * 10) / 10
}
function ownedByMe(item, ownerKey = 'owner') { return item?.[ownerKey] === CURRENT_OWNER }

export function createBoardPost(desc) {
  const c = activeCharacter.value
  const clean = desc?.trim()
  if (!c || !clean) return null
  const post = {
    id: 'b' + nextId(),
    characterId: c.id,
    name: c.name,
    owner: CURRENT_OWNER,
    emotion: c.emotion,
    isEvolved: c.isEvolved,
    score: nurtureScore(c),
    desc: clean,
    rating: 0,
    reviews: [],
  }
  boardPosts.unshift(post)
  return post
}
export function updateBoardPost(postId, desc) {
  const p = boardPost(postId)
  const clean = desc?.trim()
  if (!ownedByMe(p) || !clean) return false
  p.desc = clean
  return true
}
export function deleteBoardPost(postId) {
  const index = boardPosts.findIndex(p => p.id === postId)
  if (index < 0 || !ownedByMe(boardPosts[index])) return false
  boardPosts.splice(index, 1)
  return true
}
export function addReview(postId, stars, text) {
  const p = boardPost(postId)
  const clean = text?.trim()
  const score = Number(stars)
  if (!p || !clean || !Number.isInteger(score) || score < 1 || score > 5) return null
  const review = { id: 'r' + nextId(), author: CURRENT_OWNER, stars: score, text: clean }
  p.reviews.unshift(review)
  recalculateRating(p)
  return review
}
export function updateReview(postId, reviewId, stars, text) {
  const p = boardPost(postId)
  const review = p?.reviews.find(r => r.id === reviewId)
  const clean = text?.trim()
  const score = Number(stars)
  if (!ownedByMe(review, 'author') || !clean || !Number.isInteger(score) || score < 1 || score > 5) return false
  Object.assign(review, { stars: score, text: clean })
  recalculateRating(p)
  return true
}
export function deleteReview(postId, reviewId) {
  const p = boardPost(postId)
  const index = p?.reviews.findIndex(r => r.id === reviewId) ?? -1
  if (index < 0 || !ownedByMe(p.reviews[index], 'author')) return false
  p.reviews.splice(index, 1)
  recalculateRating(p)
  return true
}

/* ---- actions ---- */
export function createCharacter({ name, keyword, personality }, { failImage = false } = {}) {
  if (state.characters.length >= MAX_CHARACTERS) {
    throw new Error(`캐릭터는 최대 ${MAX_CHARACTERS}개까지 만들 수 있어요.`)
  }
  if (!name?.trim()) throw new Error('이름을 입력해 주세요.')
  const c = makeCharacter({ name: name.trim(), keyword: keyword?.trim() || '', personality: personality?.trim() || '' })
  state.characters.forEach(o => (o.active = false))
  c.active = true
  state.characters.push(c)
  // mock async image generation (Flow C): PENDING → READY, 또는 실패 시 FAILED.
  // FE-10 AC4: 실패해도 캐릭터(생성/상세/대시보드)는 정상 동작 — 상태값만 FAILED 로 둔다.
  setTimeout(() => { c.imageStatus = failImage ? 'FAILED' : 'READY' }, 2200)
  return c
}

/** FE-10: 실패한 이미지 재생성(데모/재시도). FAILED → PENDING → READY. */
export function retryImage(id) {
  const c = state.characters.find(x => x.id === id)
  if (!c || c.imageStatus !== 'FAILED') return
  c.imageStatus = 'PENDING'
  setTimeout(() => { c.imageStatus = 'READY' }, 1600)
}

export function setActive(id) {
  const target = state.characters.find(c => String(c.id) === String(id))
  if (!target) return false
  state.characters.forEach(c => (c.active = c === target))
  return true
}

export function deleteCharacter(id) {
  const idx = state.characters.findIndex(c => String(c.id) === String(id))
  if (idx === -1) return
  const wasActive = state.characters[idx].active
  state.characters.splice(idx, 1)
  if (wasActive && state.characters.length) state.characters[0].active = true
}

export function updateCharacter(id, changes) {
  const c = state.characters.find(x => String(x.id) === String(id))
  if (!c) return false
  const name = changes.name?.trim()
  if (!name) throw new Error('이름을 입력해 주세요.')
  Object.assign(c, {
    name,
    keyword: changes.keyword?.trim() || '',
    personality: changes.personality?.trim() || '',
  })
  return true
}

export function saveReport({ mood, title, content, tags }) {
  const c = activeCharacter.value
  if (!c) return null
  const existing = todayReport.value
  const data = {
    date: todayISO(), mood, title, content, tags: [...(tags || [])],
    status: 'analyzing', characterId: c.id,
  }
  if (existing) Object.assign(existing, data)              // 하루 1개 · 덮어쓰기
  else state.reports.unshift({ id: nextId(), scoreApplied: false, ...data })
  state.dailyReport = {
    status: 'pending',
    date: todayISO(),
    characterId: c.id,
    summary: null, deltas: null, quizComment: null, nextRecommendation: null,
  }
  c.emotion = mood
  c.message = reactionFor(mood)
  state.notice = '리포트 저장됨 — 자정에 분석돼요. 내일 오전 9시 도착.'
  return todayReport.value
}

/**
 * Instant quiz grading (흐름 B). Returns the graded quiz, or `{ ok:false }` on failure.
 *
 * FE-10 AC3/AC5 — 채점 실패 시:
 *   · 사용자가 고른 답안(selected)은 보존한다.
 *   · 점수는 반영하지 않는다(데이터 무손실).
 *   · submitted 를 풀어 "잠시 후 재시도"가 가능하게 한다(흐름 끊김 없음).
 */
export function submitQuiz(quizId, selected, { fail = false } = {}) {
  const q = state.quizzes.find(x => x.id === quizId)
  if (!q) return null
  if (q.scored) return Promise.resolve(q)
  if (q._gradingPromise) return q._gradingPromise
  const scoringCharacter = state.characters.find(c => String(c.id) === String(q.characterId))
  if (!scoringCharacter) return Promise.resolve({ ok: false, quiz: q })
  q.submitted = true
  q.selected = selected           // 답안 보존(성공/실패 무관)
  q.scored = false
  q.gradeFailed = false
  // simulate "채점 중…" then resolve
  q._gradingPromise = new Promise((resolve) => {
    setTimeout(() => {
      if (fail) {
        // Fallback: 점수 미반영, 답안만 보존, 재제출 허용.
        q.submitted = false
        q.gradeFailed = true
        resolve({ ok: false, quiz: q })
        return
      }
      const correct = selected === q.answer
      q.correct = correct
      q.scored = true
      q.gradeFailed = false
      q.deltaAmount = correct ? 12 : 3
      q.feedback = correct
        ? '정답! 핵심을 정확히 짚었어요. 한 번 확정한 최단거리를 다시 보지 않는 그리디 전제가 음수 간선과 충돌하죠.'
        : '아쉬워요. 다익스트라는 확정한 노드를 다시 갱신하지 않는 그리디라 음수 간선을 놓칠 수 있어요. 음수 간선엔 벨만-포드를 씁니다.'
      const c = scoringCharacter
      if (c) {
        q.characterId = c.id
        c.stats[q.deltaStat] = (c.stats[q.deltaStat] || 0) + q.deltaAmount
        const prev = c.isEvolved
        maybeEvolve(c)
        c.emotion = correct ? 'joy' : 'sad'
        c.message = correct ? '오 정답! 똑똑한데?' : '괜찮아, 틀리면서 크는 거지.'
        q._evolvedNow = !prev && c.isEvolved
      }
      resolve(q)
    }, 1100)
  }).finally(() => { q._gradingPromise = null })
  return q._gradingPromise
}

function maybeEvolve(c) {
  if (!c.isEvolved && nurtureScore(c) >= EVOLVE_THRESHOLD) {
    c.isEvolved = true
    state.notice = `🎉 ${c.name} 진화! 육아점수 ${EVOLVE_THRESHOLD} 돌파.`
  }
}

function reactionFor(mood) {
  if (mood === 'joy') return '오늘도 한 발 나아갔다! 뿌듯해.'
  if (mood === 'sad') return '힘든 날도 있지. 기록한 것만으로 충분해.'
  return 'answer가 막혔구나. 내일 다시 도전하자!'
}

/**
 * Mock: pull the "다음날 아침" AI daily report (흐름 A climax).
 *
 * FE-10 AC3 — `{ fail:true }` 이면 Fallback: status='failed', 점수 변화 0,
 * 작성해 둔 리포트(state.reports)는 그대로 보존되어 흐름이 끊기지 않는다.
 */
export function deliverDailyReport({ fail = false } = {}) {
  const report = pendingReport.value
  if (!report) return state.dailyReport
  if (fail) {
    state.dailyReport = {
      status: 'failed',
      date: report.date,
      characterId: report.characterId,
      summary: null, deltas: null, quizComment: null, nextRecommendation: null,
    }
    // 점수·캐릭터 변화 없음. 작성한 리포트는 보존(데이터 무손실).
    state.notice = 'AI가 잠깐 쉬는 중 — 학습은 저장됐어요. 점수 변화는 다음 분석에 반영돼요.'
    return state.dailyReport
  }
  state.dailyReport = {
    status: 'ready',
    date: report.date,
    characterId: report.characterId,
    summary: '어제 다익스트라 학습 리포트를 분석했어요. 최단경로 그리디 전제를 정확히 이해했고, 우선순위 큐 구현까지 연결한 점이 좋았습니다.',
    deltas: { algo: 3, net: 1 },
    quizComment: '어제 추천 퀴즈 2개 중 1개 정답. 그리디 전제는 확실하지만, 음수 간선 대안(벨만-포드)을 보강하면 좋아요.',
    nextRecommendation: '다음 학습: 벨만-포드와 플로이드-워셜 비교, 그리고 우선순위 큐의 시간복잡도 분석.',
  }
  const c = state.characters.find(character => String(character.id) === String(report.characterId))
  let evolvedNow = false
  if (c && !report.scoreApplied) {
    c.stats.algo += 3; c.stats.net += 1
    const wasEvolved = c.isEvolved
    maybeEvolve(c)
    evolvedNow = !wasEvolved && c.isEvolved
    c.emotion = 'joy'
    c.message = '어제 공부가 몸에 스며들었어! 레이더가 차오르는 게 느껴져.'
  }
  report.status = 'reflected'
  report.scoreApplied = true
  state.notice = evolvedNow
    ? `🎉 ${c.name} 진화! 육아점수 ${EVOLVE_THRESHOLD} 돌파.`
    : '어제의 레포트 도착 — 학습이 반영됐어요.'
  return state.dailyReport
}

export function clearNotice() { state.notice = null }

export const gameState = state
