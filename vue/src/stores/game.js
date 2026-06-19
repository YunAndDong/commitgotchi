/*
 * Game store — Spring Boot backed reactive singleton.
 *
 * The UI keeps reading from this local reactive state, while every mutation is
 * persisted through /api/game/** and then replaced with the backend response.
 */
import { reactive, computed } from 'vue'
import { game as gameApi } from '../api/client.js'
import {
  clearActiveGotchi,
  publishActiveGotchi,
  setActiveGotchiPublishingEnabled,
} from '../extension/activeGotchi.js'

export const STAT_KEYS = ['algo', 'cs', 'db', 'net', 'fw']
export const STAT_LABELS = { algo: '알고리즘', cs: 'CS', db: 'DB', net: '네트워크', fw: '프레임워크' }
export const EVOLVE_THRESHOLD = 1000
export const MAX_CHARACTERS = 3
export const CURRENT_OWNER = '나'

const todayISO = () => new Intl.DateTimeFormat('en-CA').format(new Date())

function newStats() { return { algo: 0, cs: 0, db: 0, net: 0, fw: 0 } }
export function nurtureScore(c) { return STAT_KEYS.reduce((s, k) => s + (Number.isFinite(c?.stats?.[k]) ? c.stats[k] : 0), 0) }

function blankState() {
  return {
    nextId: 100,
    characters: [],
    reports: [],
    quizzes: [],
    dailyReport: {
      status: 'pending',
      date: todayISO(),
      characterId: null,
      summary: null,
      deltas: null,
      quizComment: null,
      nextRecommendation: null,
    },
    notice: null,
    boardPosts: [],
  }
}

function completeStats(stats = {}) {
  return Object.fromEntries(STAT_KEYS.map(key => [
    key,
    Number.isFinite(Number(stats[key])) ? Number(stats[key]) : 0,
  ]))
}

function normalizeCharacter(raw) {
  if (!raw || raw.id == null) return null
  const name = String(raw.name || '').trim()
  if (!name) return null
  return {
    id: raw.id,
    name,
    keyword: String(raw.keyword || ''),
    personality: String(raw.personality || ''),
    stats: completeStats(raw.stats),
    emotion: ['joy', 'sad', 'angry'].includes(raw.emotion) ? raw.emotion : 'joy',
    isEvolved: !!raw.isEvolved,
    imageStatus: ['PENDING', 'READY', 'FAILED'].includes(raw.imageStatus) ? raw.imageStatus : 'READY',
    active: !!raw.active,
    message: String(raw.message || '잘 부탁해! 오늘부터 함께 공부하자.'),
    createdAt: raw.createdAt || new Date().toISOString(),
  }
}

function normalizeReview(raw) {
  return {
    id: raw?.id,
    author: String(raw?.author || ''),
    stars: Math.min(5, Math.max(1, Number(raw?.stars) || 1)),
    text: String(raw?.text || ''),
  }
}

function normalizeBoardPost(raw) {
  if (!raw || raw.id == null) return null
  return {
    id: raw.id,
    characterId: raw.characterId,
    name: String(raw.name || ''),
    owner: String(raw.owner || ''),
    emotion: ['joy', 'sad', 'angry'].includes(raw.emotion) ? raw.emotion : 'joy',
    isEvolved: !!raw.isEvolved,
    score: Number(raw.score) || 0,
    desc: String(raw.desc || ''),
    rating: Number(raw.rating) || 0,
    reviews: Array.isArray(raw.reviews) ? raw.reviews.map(normalizeReview).filter(r => r.id != null) : [],
  }
}

function normalizeQuiz(raw) {
  return {
    id: raw?.id,
    date: raw?.date || todayISO(),
    tag: raw?.tag || raw?.deltaStat || 'algo',
    question: String(raw?.question || ''),
    modelAnswer: String(raw?.modelAnswer || ''),
    answerKeywords: Array.isArray(raw?.answerKeywords) ? raw.answerKeywords : [],
    userAnswer: raw?.userAnswer ?? '',
    options: Array.isArray(raw?.options) ? raw.options : [],
    answer: Number(raw?.answer),
    submitted: !!raw?.submitted,
    selected: raw?.selected ?? null,
    correct: raw?.correct ?? null,
    scored: !!raw?.scored,
    gradeFailed: !!raw?.gradeFailed,
    feedback: raw?.feedback ?? null,
    deltaStat: raw?.deltaStat || 'algo',
    deltaAmount: Number(raw?.deltaAmount) || 0,
    characterId: raw?.characterId ?? null,
    _evolvedNow: !!raw?._evolvedNow,
  }
}

function normalizeReport(raw) {
  return {
    id: raw?.id,
    date: raw?.date || todayISO(),
    mood: ['joy', 'sad', 'angry'].includes(raw?.mood) ? raw.mood : 'joy',
    title: String(raw?.title || ''),
    content: String(raw?.content || ''),
    tags: Array.isArray(raw?.tags) ? raw.tags : [],
    status: raw?.status || 'analyzing',
    scoreApplied: !!raw?.scoreApplied,
    characterId: raw?.characterId ?? null,
  }
}

const state = reactive(blankState())

function applyGameState(next = {}) {
  const fallback = blankState()
  state.nextId = Number(next.nextId) || fallback.nextId
  state.characters = Array.isArray(next.characters)
    ? next.characters.map(normalizeCharacter).filter(Boolean)
    : []
  state.reports = Array.isArray(next.reports) ? next.reports.map(normalizeReport).filter(r => r.id != null) : []
  state.quizzes = Array.isArray(next.quizzes) ? next.quizzes.map(normalizeQuiz).filter(q => q.id != null) : []
  state.dailyReport = next.dailyReport || fallback.dailyReport
  state.notice = next.notice ?? null
  state.boardPosts = Array.isArray(next.boardPosts)
    ? next.boardPosts.map(normalizeBoardPost).filter(Boolean)
    : []
}

export function resetGameState() {
  applyGameState(blankState())
  setActiveGotchiPublishingEnabled(false)
  void clearActiveGotchi()
}

export async function loadGameState() {
  const response = await gameApi.state()
  applyGameState(response.state)
  syncActiveGotchi()
  return state
}

async function mutate(request) {
  const response = await request
  applyGameState(response.state)
  syncActiveGotchi()
  return response.item
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

function syncActiveGotchi() {
  const character = activeCharacter.value
  if (character) {
    setActiveGotchiPublishingEnabled(true)
    void publishActiveGotchi(character)
  } else {
    setActiveGotchiPublishingEnabled(false)
    void clearActiveGotchi()
  }
}

/* ---- ranking (육아점수 desc) ---- */
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

/* ---- codex (도감) ---- */
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

/* ---- share board ---- */
export function board() { return state.boardPosts }
export function boardPost(id) { return state.boardPosts.find(p => String(p.id) === String(id)) || null }

export async function createBoardPost(desc) {
  const clean = desc?.trim()
  if (!clean) return null
  return mutate(gameApi.createBoardPost({ desc: clean }))
}
export async function updateBoardPost(postId, desc) {
  const clean = desc?.trim()
  if (!clean) return false
  const item = await mutate(gameApi.updateBoardPost(postId, { desc: clean }))
  return item != null
}
export async function deleteBoardPost(postId) {
  const item = await mutate(gameApi.deleteBoardPost(postId))
  return item != null
}
export async function addReview(postId, stars, text) {
  const clean = text?.trim()
  const score = Number(stars)
  if (!clean || !Number.isInteger(score) || score < 1 || score > 5) return null
  return mutate(gameApi.addReview(postId, { stars: score, text: clean }))
}
export async function updateReview(postId, reviewId, stars, text) {
  const clean = text?.trim()
  const score = Number(stars)
  if (!clean || !Number.isInteger(score) || score < 1 || score > 5) return false
  const item = await mutate(gameApi.updateReview(postId, reviewId, { stars: score, text: clean }))
  return item != null
}
export async function deleteReview(postId, reviewId) {
  const item = await mutate(gameApi.deleteReview(postId, reviewId))
  return item != null
}

/* ---- actions ---- */
export async function createCharacter({ name, keyword, personality }) {
  if (state.characters.length >= MAX_CHARACTERS) {
    throw new Error(`캐릭터는 최대 ${MAX_CHARACTERS}개까지 만들 수 있어요.`)
  }
  if (!name?.trim()) throw new Error('이름을 입력해 주세요.')
  if (!keyword?.trim()) throw new Error('디자인 키워드를 입력해 주세요.')
  return mutate(gameApi.createCharacter({
    name: name.trim(),
    keyword: keyword.trim(),
    personality: personality?.trim() || '칭찬은 많이, 틀린 건 명확히 짚어주는',
  }))
}

export async function retryImage(id) {
  return mutate(gameApi.retryImage(id))
}

export async function setActive(id) {
  const item = await mutate(gameApi.setActiveCharacter(id))
  return item != null
}

export async function deleteCharacter(id) {
  return mutate(gameApi.deleteCharacter(id))
}

export async function updateCharacter(id, changes) {
  const name = changes.name?.trim()
  if (!name) throw new Error('이름을 입력해 주세요.')
  const item = await mutate(gameApi.updateCharacter(id, {
    name,
    keyword: changes.keyword?.trim() || '',
    personality: changes.personality?.trim() || '',
  }))
  return item != null
}

export async function saveReport({ mood, title, content, tags }) {
  if (!activeCharacter.value) return null
  return mutate(gameApi.saveReport({
    mood,
    title,
    content,
    tags: [...(tags || [])],
  }))
}

export async function submitQuiz(quizId, userAnswer, { fail = false } = {}) {
  const item = await mutate(gameApi.submitQuiz(quizId, { userAnswer, fail }))
  if (item?.ok === false) return item
  return item
}

export async function deliverDailyReport({ fail = false } = {}) {
  return mutate(gameApi.deliverDailyReport({ fail }))
}

export function clearNotice() { state.notice = null }

export const gameState = state
