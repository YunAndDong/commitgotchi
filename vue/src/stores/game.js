/*
 * Game store — Spring Boot backed reactive singleton.
 *
 * The UI keeps reading from this local reactive state, while every mutation is
 * persisted through /api/game/** and then replaced with the backend response.
 */
import { reactive, computed } from 'vue'
import { apiAssetUrl, game as gameApi, openCharacterEventStream, openReportEventStream } from '../api/client.js'
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
export const REPORT_SUBMITTED_NOTICE = '리포트 저장됨 - 자정에 분석돼요. 내일 오전 9시 도착.'
const REPORT_RESULT_TIMEOUT_MS = 45000
const REPORT_RESULT_POLL_INTERVAL_MS = 1500

const todayISO = () => new Intl.DateTimeFormat('en-CA').format(new Date())
const MOCK_SPRITE_META = Object.freeze({
  columns: 3,
  rows: 1,
  frameMap: {
    joy: [0, 0],
    happy: [0, 0],
    sad: [0, 1],
    angry: [0, 2],
  },
  transparent: true,
})
const DEFAULT_SPRITE_SHEET_URL = apiAssetUrl('/character-assets/default_image1.png')
const MOCK_SPRITES = [DEFAULT_SPRITE_SHEET_URL, DEFAULT_SPRITE_SHEET_URL]

function newStats() { return { algo: 0, cs: 0, db: 0, net: 0, fw: 0 } }
export function nurtureScore(c) { return STAT_KEYS.reduce((s, k) => s + (Number.isFinite(c?.stats?.[k]) ? c.stats[k] : 0), 0) }

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

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
    evolution: null,
    evolutionQueue: [],
    boardPosts: [],
  }
}

function mockState() {
  const first = {
    id: 101,
    name: '새싹이',
    keyword: 'mint pixel sprout',
    personality: '칭찬은 많이, 틀린 건 명확히 짚어주는',
    stats: { algo: 12, cs: 8, db: 4, net: 0, fw: 6 },
    emotion: 'joy',
    isEvolved: false,
    imageStatus: 'READY',
    spriteSheetUrl: MOCK_SPRITES[0],
    spriteMeta: clone(MOCK_SPRITE_META),
    active: false,
    message: '오늘도 한 커밋씩 자라보자.',
    createdAt: new Date().toISOString(),
  }
  const second = {
    id: 102,
    name: '알고핑',
    keyword: 'blue study pet',
    personality: '차분하게 힌트를 주는',
    stats: { algo: 80, cs: 60, db: 30, net: 20, fw: 44 },
    emotion: 'sad',
    isEvolved: true,
    imageStatus: 'READY',
    spriteSheetUrl: MOCK_SPRITES[1],
    spriteMeta: clone(MOCK_SPRITE_META),
    active: false,
    message: '막힌 부분은 천천히 뜯어보면 돼.',
    createdAt: new Date().toISOString(),
  }
  return {
    ...blankState(),
    nextId: 103,
    characters: [first, second],
    quizzes: [],
    boardPosts: [
      {
        id: 'b1',
        characterId: 900,
        name: '코드몬',
        owner: 'jimin',
        emotion: 'joy',
        isEvolved: true,
        spriteSheetUrl: MOCK_SPRITES[0],
        spriteMeta: clone(MOCK_SPRITE_META),
        score: 1840,
        desc: '매일 아침 알고리즘 한 문제로 자라는 커밋고치예요.',
        rating: 5,
        reviews: [{ id: 'r1', author: 'sora', stars: 5, text: '꾸준함이 멋져요.', createdAt: new Date().toISOString() }],
      },
    ],
  }
}

function completeStats(stats = {}) {
  return Object.fromEntries(STAT_KEYS.map(key => [
    key,
    Number.isFinite(Number(stats[key])) ? Number(stats[key]) : 0,
  ]))
}

function normalizeSpriteMeta(raw) {
  if (!raw) return null
  if (typeof raw === 'string') {
    try { return normalizeSpriteMeta(JSON.parse(raw)) } catch { return null }
  }
  return typeof raw === 'object' ? raw : null
}

function normalizeSpriteSheetUrl(raw) {
  if (!raw) return null
  const value = String(raw)
  return value.startsWith('/character-assets/') ? apiAssetUrl(value) : value
}

function selectedSpriteSheetUrl(raw) {
  if (!raw) return null
  return raw.isEvolved
    ? (raw.evolvedSpriteSheetUrl || raw.spriteSheetUrl)
    : (raw.babySpriteSheetUrl || raw.spriteSheetUrl)
}

function selectedSpriteMeta(raw) {
  if (!raw) return null
  return raw.isEvolved
    ? (raw.evolvedSpriteMeta || raw.spriteMeta)
    : (raw.babySpriteMeta || raw.spriteMeta)
}

function isDeletedCharacter(raw) {
  return !!(raw?.deletedAt || raw?.deleted_at)
}

function normalizeCharacter(raw) {
  if (isDeletedCharacter(raw)) return null
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
    imageStatus: raw.imageStatus === 'FAILED'
      ? 'FALLBACK'
      : (['PENDING', 'READY', 'FALLBACK'].includes(raw.imageStatus) ? raw.imageStatus : 'READY'),
    spriteSheetUrl: normalizeSpriteSheetUrl(selectedSpriteSheetUrl(raw)),
    spriteMeta: normalizeSpriteMeta(selectedSpriteMeta(raw)),
    babySpriteSheetUrl: normalizeSpriteSheetUrl(raw.babySpriteSheetUrl),
    babySpriteMeta: normalizeSpriteMeta(raw.babySpriteMeta),
    evolvedSpriteSheetUrl: normalizeSpriteSheetUrl(raw.evolvedSpriteSheetUrl),
    evolvedSpriteMeta: normalizeSpriteMeta(raw.evolvedSpriteMeta),
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
    createdAt: raw?.createdAt || raw?.created_at || raw?.date || null,
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
    spriteSheetUrl: normalizeSpriteSheetUrl(raw.spriteSheetUrl),
    spriteMeta: normalizeSpriteMeta(raw.spriteMeta),
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
    sourceReportRequestId: raw?.sourceReportRequestId ?? null,
    problemId: raw?.problemId ?? null,
    quizId: raw?.quizId ?? null,
    tag: raw?.tag || raw?.deltaStat || 'algo',
    question: String(raw?.question || ''),
    modelAnswer: String(raw?.modelAnswer || ''),
    scoreAllocation: raw?.scoreAllocation && typeof raw.scoreAllocation === 'object' ? raw.scoreAllocation : null,
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

function isRecommendedQuiz(quiz) {
  return !!quiz?.sourceReportRequestId
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
    requestId: raw?.requestId ?? null,
    summary: raw?.summary == null ? null : String(raw.summary),
    feedback: raw?.feedback == null ? null : String(raw.feedback),
    nextRecommendation: raw?.nextRecommendation ?? null,
    deltas: raw?.deltas && typeof raw.deltas === 'object' ? raw.deltas : null,
  }
}

let mockMode = true
const state = reactive(mockState())
const characterEventStreams = new Map()
const characterEventReconnectTimers = new Map()
let reportEventStream = null
let reportEventReconnectTimer = null
const reportResultWaiters = new Set()
const characterSseResultNotices = reactive({})
const studyAnalysisNotices = reactive({})
const announcedEvolutionIds = new Set()

function evolutionProgress(character) {
  return {
    isEvolved: !!character?.isEvolved,
    score: nurtureScore(character),
  }
}

function snapshotEvolutionProgress(characters = state.characters) {
  return new Map(characters.map(character => [
    String(character.id),
    evolutionProgress(character),
  ]))
}

function hasNewEvolution(previous, character) {
  if (!previous || !character) return false
  const beforeReached = previous.isEvolved || previous.score >= EVOLVE_THRESHOLD
  const afterReached = !!character.isEvolved || nurtureScore(character) >= EVOLVE_THRESHOLD
  return !beforeReached && afterReached
}

function evolutionPayload(character) {
  return {
    id: character.id,
    name: character.name,
    score: nurtureScore(character),
    emotion: character.emotion,
    spriteSheetUrl: character.spriteSheetUrl,
    spriteMeta: character.spriteMeta,
    imageStatus: character.imageStatus,
    createdAt: Date.now(),
  }
}

function announceEvolution(character) {
  if (!character?.id) return
  const key = String(character.id)
  if (announcedEvolutionIds.has(key)) return
  announcedEvolutionIds.add(key)
  const payload = evolutionPayload(character)
  if (state.evolution) state.evolutionQueue.push(payload)
  else state.evolution = payload
}

function detectEvolutionArrivals(previousById) {
  if (!previousById) return
  state.characters.forEach(character => {
    if (hasNewEvolution(previousById.get(String(character.id)), character)) {
      announceEvolution(character)
    }
  })
}

function markCharacterEvolutionIfReady(character, previous = evolutionProgress(character)) {
  if (!character) return false
  if (nurtureScore(character) >= EVOLVE_THRESHOLD) character.isEvolved = true
  const evolvedNow = hasNewEvolution(previous, character)
  if (evolvedNow) announceEvolution(character)
  return evolvedNow
}

export function dismissEvolution() {
  state.evolution = state.evolutionQueue.shift() || null
}

function characterNoticeKey(characterId) {
  return String(characterId)
}

function studyAnalysisNoticeKey(type, id) {
  if (id == null) return null
  return `${type}:${String(id)}`
}

function studyAnalysisNoticeParts(key) {
  const separator = String(key).indexOf(':')
  if (separator < 0) return null
  return {
    type: String(key).slice(0, separator),
    id: String(key).slice(separator + 1),
  }
}

function studyRecordCharacterId(type, id) {
  if (type === 'report') {
    return state.reports.find(report => String(report.id) === String(id))?.characterId ?? null
  }
  if (type === 'quiz') {
    return state.quizzes.find(quiz => String(quiz.id) === String(id))?.characterId ?? null
  }
  return null
}

function studyAnalysisNoticeCharacterId(key) {
  const notice = studyAnalysisNotices[key]
  if (notice && typeof notice === 'object' && notice.characterId != null) {
    return notice.characterId
  }
  const parts = studyAnalysisNoticeParts(key)
  return parts ? studyRecordCharacterId(parts.type, parts.id) : null
}

function hasUnreadStudyAnalysisForCharacter(characterId) {
  if (characterId == null) return false
  return Object.keys(studyAnalysisNotices).some(key => (
    String(studyAnalysisNoticeCharacterId(key)) === String(characterId)
  ))
}

function syncCharacterNoticeReadState(characterId) {
  if (characterId == null) return
  const key = characterNoticeKey(characterId)
  if (hasUnreadStudyAnalysisForCharacter(characterId)) {
    characterSseResultNotices[key] = 'unread'
  } else if (characterSseResultNotices[key] === 'unread') {
    characterSseResultNotices[key] = 'read'
  }
}

export function markCharacterResultArrived(characterId) {
  if (characterId == null) return
  characterSseResultNotices[characterNoticeKey(characterId)] = 'unread'
}

export function hasCharacterSseResult(characterId) {
  return !!characterSseResultNotices[characterNoticeKey(characterId)]
}

export function hasUnreadCharacterSseResult(characterId) {
  return characterSseResultNotices[characterNoticeKey(characterId)] === 'unread'
}

export function markCharacterResultRead(characterId) {
  if (characterId == null) return
  const key = characterNoticeKey(characterId)
  const hadCharacterNotice = !!characterSseResultNotices[key]
  clearStudyAnalysisNoticesForCharacter(characterId)
  if (hadCharacterNotice) characterSseResultNotices[key] = 'read'
}

export function clearCharacterSseResult(characterId) {
  delete characterSseResultNotices[characterNoticeKey(characterId)]
}

function clearAllCharacterSseResults() {
  Object.keys(characterSseResultNotices).forEach(characterId => {
    delete characterSseResultNotices[characterId]
  })
}

function clearStudyAnalysisNoticeKey(key) {
  if (!key) return
  const characterId = studyAnalysisNoticeCharacterId(key)
  delete studyAnalysisNotices[key]
  syncCharacterNoticeReadState(characterId)
}

function markStudyAnalysisNotice(type, id, characterId = studyRecordCharacterId(type, id)) {
  const key = studyAnalysisNoticeKey(type, id)
  if (!key) return
  studyAnalysisNotices[key] = { characterId: characterId ?? null }
  if (characterId != null) markCharacterResultArrived(characterId)
}

export function hasStudyAnalysisNotice(type, id) {
  const key = studyAnalysisNoticeKey(type, id)
  return !!(key && studyAnalysisNotices[key])
}

export function clearStudyAnalysisNotice(type, id) {
  clearStudyAnalysisNoticeKey(studyAnalysisNoticeKey(type, id))
}

function clearStudyAnalysisNoticesForCharacter(characterId) {
  if (characterId == null) return
  Object.keys(studyAnalysisNotices).forEach(key => {
    if (String(studyAnalysisNoticeCharacterId(key)) === String(characterId)) {
      delete studyAnalysisNotices[key]
    }
  })
}

function clearAllStudyAnalysisNotices() {
  Object.keys(studyAnalysisNotices).forEach(key => {
    delete studyAnalysisNotices[key]
  })
}

function pruneCharacterSseResults() {
  const characterIds = new Set(state.characters.map(character => characterNoticeKey(character.id)))
  Object.keys(characterSseResultNotices).forEach(characterId => {
    if (!characterIds.has(characterId)) delete characterSseResultNotices[characterId]
  })
}

function pruneStudyAnalysisNotices() {
  const validKeys = new Set([
    ...state.reports.map(report => studyAnalysisNoticeKey('report', report.id)),
    ...state.quizzes.map(quiz => studyAnalysisNoticeKey('quiz', quiz.id)),
  ].filter(Boolean))
  Object.keys(studyAnalysisNotices).forEach(key => {
    if (!validKeys.has(key)) clearStudyAnalysisNoticeKey(key)
  })
}

function applyGameState(next = {}, { detectEvolution = false } = {}) {
  const previousEvolution = detectEvolution ? snapshotEvolutionProgress() : null
  const fallback = blankState()
  state.nextId = Number(next.nextId) || fallback.nextId
  state.characters = Array.isArray(next.characters)
    ? next.characters.map(normalizeCharacter).filter(Boolean)
    : []
  state.reports = Array.isArray(next.reports) ? next.reports.map(normalizeReport).filter(r => r.id != null) : []
  state.quizzes = Array.isArray(next.quizzes) ? next.quizzes.map(normalizeQuiz).filter(q => q.id != null) : []
  state.dailyReport = next.dailyReport || fallback.dailyReport
  state.notice = null
  state.boardPosts = Array.isArray(next.boardPosts)
    ? next.boardPosts.map(normalizeBoardPost).filter(Boolean)
    : []
  pruneCharacterSseResults()
  pruneStudyAnalysisNotices()
  detectEvolutionArrivals(previousEvolution)
  resolveReportResultWaiters()
}

export function resetGameState() {
  mockMode = true
  disconnectCharacterEvents()
  disconnectReportEvents()
  applyGameState(mockState())
  state.evolution = null
  state.evolutionQueue = []
  announcedEvolutionIds.clear()
  clearAllCharacterSseResults()
  clearAllStudyAnalysisNotices()
  setActiveGotchiPublishingEnabled(false)
  void clearActiveGotchi()
}

export async function loadGameState() {
  const response = await gameApi.state()
  mockMode = false
  clearAllStudyAnalysisNotices()
  applyGameState(response.state)
  syncCharacterEventStreams()
  syncReportEventStream()
  syncActiveGotchi()
  return state
}

async function mutate(request) {
  const response = await request
  applyGameState(response.state, { detectEvolution: true })
  syncCharacterEventStreams()
  syncReportEventStream()
  syncActiveGotchi()
  return response.item
}

/* ---- derived ---- */
export const activeCharacter = computed(() => state.characters.find(c => c.active) || null)
export const hasCharacter = computed(() => state.characters.length > 0)
export const todayReport = computed(() => state.reports.find(
  r => r.date === todayISO() && String(r.characterId) === String(activeCharacter.value?.id)
) || null)
export const todayQuizzes = computed(() => state.quizzes.filter(q => q.date === todayISO() && isRecommendedQuiz(q)))
export function quizzesForCharacter(characterId) {
  if (characterId == null) return []
  return state.quizzes.filter(
    q => q.date === todayISO()
      && String(q.characterId) === String(characterId)
      && isRecommendedQuiz(q)
  )
}
export const activeQuizzes = computed(() => quizzesForCharacter(activeCharacter.value?.id))
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

export function disconnectCharacterEvents() {
  characterEventReconnectTimers.forEach(timer => globalThis.clearTimeout(timer))
  characterEventReconnectTimers.clear()
  characterEventStreams.forEach(stream => stream.close())
  characterEventStreams.clear()
}

export function disconnectReportEvents() {
  if (reportEventReconnectTimer != null) {
    globalThis.clearTimeout(reportEventReconnectTimer)
    reportEventReconnectTimer = null
  }
  if (reportEventStream) {
    reportEventStream.close()
    reportEventStream = null
  }
}

function syncCharacterEventStreams() {
  if (mockMode) {
    disconnectCharacterEvents()
    return
  }
  const characterIds = new Set(state.characters.map(character => String(character.id)))
  characterEventStreams.forEach((stream, characterId) => {
    if (!characterIds.has(characterId)) {
      stream.close()
      characterEventStreams.delete(characterId)
    }
  })
  characterIds.forEach(characterId => {
    if (!characterEventStreams.has(characterId)) openCharacterEventSubscription(characterId)
  })
}

function openCharacterEventSubscription(characterId) {
  const key = String(characterId)
  if (characterEventReconnectTimers.has(key)) {
    globalThis.clearTimeout(characterEventReconnectTimers.get(key))
    characterEventReconnectTimers.delete(key)
  }
  const stream = openCharacterEventStream(key, {
    onEvent(eventName, payload) {
      if (eventName === 'character.snapshot') {
        applyCharacterSnapshot(payload)
        return
      }
      if (eventName === 'character.updated') {
        const character = applyCharacterSnapshot(payload)
        if (character) markCharacterResultArrived(character.id)
        return
      }
      if (eventName === 'quiz.graded') {
        applyQuizSnapshot(payload, { highlightArrival: true })
      }
    },
    onClose() {
      characterEventStreams.delete(key)
      if (!mockMode && state.characters.some(character => String(character.id) === key)) {
        const timer = globalThis.setTimeout(() => {
          characterEventReconnectTimers.delete(key)
          if (!characterEventStreams.has(key)) openCharacterEventSubscription(key)
        }, 3000)
        characterEventReconnectTimers.set(key, timer)
      }
    },
  })
  characterEventStreams.set(key, stream)
}

function syncReportEventStream() {
  if (mockMode) {
    disconnectReportEvents()
    return
  }
  if (!reportEventStream) openReportEventSubscription()
}

function openReportEventSubscription() {
  if (reportEventReconnectTimer != null) {
    globalThis.clearTimeout(reportEventReconnectTimer)
    reportEventReconnectTimer = null
  }
  reportEventStream = openReportEventStream({
    onEvent(eventName, payload) {
      applyReportSnapshot(payload, { highlightArrivals: eventName !== 'report.snapshot' })
    },
    onClose() {
      reportEventStream = null
      if (!mockMode) {
        reportEventReconnectTimer = globalThis.setTimeout(() => {
          reportEventReconnectTimer = null
          if (!reportEventStream) openReportEventSubscription()
        }, 3000)
      }
    },
  })
}

function reportHasAnalysisResult(report) {
  const status = String(report?.status || '')
  return !!report && (report.scoreApplied || ['applied', 'reflected', 'ready', 'fallback', 'failed'].includes(status))
}

function dailyReportHasResult(dailyReport) {
  return ['ready', 'fallback', 'failed'].includes(String(dailyReport?.status || ''))
}

function reportResultMatches(dailyReport, requestId) {
  if (!requestId) return true
  return String(dailyReport?.requestId || '') === String(requestId)
}

function currentReportResult(requestId) {
  const dailyReport = state.dailyReport
  if (dailyReportHasResult(dailyReport) && reportResultMatches(dailyReport, requestId)) {
    return dailyReport
  }
  return null
}

function clearReportResultWaiter(waiter) {
  if (waiter.timeoutId != null) globalThis.clearTimeout(waiter.timeoutId)
  if (waiter.pollId != null) globalThis.clearInterval(waiter.pollId)
  reportResultWaiters.delete(waiter)
}

function resolveReportResultWaiters() {
  reportResultWaiters.forEach(waiter => {
    const dailyReport = currentReportResult(waiter.requestId)
    if (!dailyReport) return
    clearReportResultWaiter(waiter)
    waiter.resolve({ dailyReport, timedOut: false })
  })
}

function waitForReportResult({ requestId, timeoutMs = REPORT_RESULT_TIMEOUT_MS } = {}) {
  const ready = currentReportResult(requestId)
  if (ready || mockMode) {
    return Promise.resolve({ dailyReport: ready || state.dailyReport, timedOut: false })
  }

  return new Promise(resolve => {
    const waiter = {
      requestId,
      resolve,
      timeoutId: null,
      pollId: null,
    }
    waiter.timeoutId = globalThis.setTimeout(() => {
      clearReportResultWaiter(waiter)
      state.notice = '답변 생성이 아직 진행 중이에요. 결과 화면에서 도착 상태를 확인할 수 있어요.'
      resolve({ dailyReport: state.dailyReport, timedOut: true })
    }, timeoutMs)
    waiter.pollId = globalThis.setInterval(async () => {
      try {
        const response = await gameApi.state()
        mockMode = false
        applyGameState(response.state, { detectEvolution: true })
        syncCharacterEventStreams()
        syncReportEventStream()
        syncActiveGotchi()
      } catch {
        // SSE reconnect and timeout still cover the user path.
      }
    }, REPORT_RESULT_POLL_INTERVAL_MS)
    reportResultWaiters.add(waiter)
    syncReportEventStream()
    resolveReportResultWaiters()
  })
}

function quizHasAnalysisResult(quiz) {
  return !!quiz?.scored
}

function markReportAnalysisArrivals(previousReports, nextReports) {
  const previousById = new Map(previousReports.map(report => [String(report.id), report]))
  nextReports.forEach(report => {
    if (!reportHasAnalysisResult(report)) return
    const previous = previousById.get(String(report.id))
    if (!previous || !reportHasAnalysisResult(previous)) {
      markStudyAnalysisNotice('report', report.id, report.characterId)
    }
  })
}

function markQuizAnalysisArrivals(previousQuizzes, nextQuizzes) {
  const previousById = new Map(previousQuizzes.map(quiz => [String(quiz.id), quiz]))
  nextQuizzes.forEach(quiz => {
    if (!quizHasAnalysisResult(quiz)) return
    const previous = previousById.get(String(quiz.id))
    if (!previous || !quizHasAnalysisResult(previous)) {
      markStudyAnalysisNotice('quiz', quiz.id, quiz.characterId)
    }
  })
}

function applyReportSnapshot(payload, { highlightArrivals = false } = {}) {
  if (!payload || typeof payload !== 'object') return
  const previousEvolution = Array.isArray(payload.characters) ? snapshotEvolutionProgress() : null
  if (Array.isArray(payload.characters)) {
    state.characters = payload.characters.map(normalizeCharacter).filter(Boolean)
  }
  if (Array.isArray(payload.reports)) {
    const reports = payload.reports.map(normalizeReport).filter(r => r.id != null)
    if (highlightArrivals) markReportAnalysisArrivals(state.reports, reports)
    state.reports = reports
  }
  if (Array.isArray(payload.quizzes)) {
    const quizzes = payload.quizzes.map(normalizeQuiz).filter(q => q.id != null)
    if (highlightArrivals) markQuizAnalysisArrivals(state.quizzes, quizzes)
    state.quizzes = quizzes
  }
  if (payload.dailyReport && typeof payload.dailyReport === 'object') {
    state.dailyReport = payload.dailyReport
  }
  pruneCharacterSseResults()
  pruneStudyAnalysisNotices()
  detectEvolutionArrivals(previousEvolution)
  syncCharacterEventStreams()
  syncActiveGotchi()
  resolveReportResultWaiters()
}

function applyQuizSnapshot(payload, { highlightArrival = false } = {}) {
  const quiz = normalizeQuiz(payload)
  if (quiz.id == null) return null
  const index = state.quizzes.findIndex(item => String(item.id) === String(quiz.id))
  const previous = index >= 0 ? state.quizzes[index] : null
  if (index >= 0) Object.assign(state.quizzes[index], quiz)
  else state.quizzes.unshift(quiz)
  const current = index >= 0 ? state.quizzes[index] : quiz
  if (highlightArrival && quizHasAnalysisResult(current) && (!previous || !quizHasAnalysisResult(previous))) {
    markStudyAnalysisNotice('quiz', current.id, current.characterId)
  }
  pruneStudyAnalysisNotices()
  return current
}

function applyCharacterSnapshot(payload) {
  if (isDeletedCharacter(payload)) {
    const index = state.characters.findIndex(item => String(item.id) === String(payload.id))
    if (index >= 0) state.characters.splice(index, 1)
    syncActiveGotchi()
    return null
  }
  const previousEvolution = snapshotEvolutionProgress()
  const character = normalizeCharacter(payload)
  if (!character) return null
  const index = state.characters.findIndex(item => String(item.id) === String(character.id))
  if (index >= 0) Object.assign(state.characters[index], character)
  else state.characters.unshift(character)
  detectEvolutionArrivals(previousEvolution)
  syncActiveGotchi()
  return character
}

function requireActiveCharacter() {
  if (!activeCharacter.value) return null
  return activeCharacter.value
}

function mockSpriteFor(id) {
  return MOCK_SPRITES[Math.abs(Number(id) || 0) % MOCK_SPRITES.length]
}

function scheduleImageResult(character, { fail = false, delay = 1200 } = {}) {
  setTimeout(() => {
    if (!state.characters.includes(character) || character.imageStatus !== 'PENDING') return
    if (fail) {
      character.imageStatus = 'FALLBACK'
      character.spriteSheetUrl = mockSpriteFor(character.id)
      character.spriteMeta = clone(MOCK_SPRITE_META)
    } else {
      character.imageStatus = 'READY'
      character.spriteSheetUrl = mockSpriteFor(character.id)
      character.spriteMeta = clone(MOCK_SPRITE_META)
    }
    if (character.active) syncActiveGotchi()
  }, delay)
}

function localSetActive(id) {
  const next = state.characters.find(c => String(c.id) === String(id))
  if (!next) return false
  state.characters.forEach(c => { c.active = c === next })
  state.quizzes
    .filter(q => q.date === todayISO() && q.characterId == null)
    .forEach(q => { q.characterId = next.id })
  syncActiveGotchi()
  return true
}

function localBoostCharacterStat(id, stat, amount = 200) {
  const character = state.characters.find(c => String(c.id) === String(id))
  if (!character || !STAT_KEYS.includes(stat)) return null
  const delta = Math.trunc(Number(amount))
  if (!Number.isFinite(delta) || delta <= 0) return null
  const previousEvolution = evolutionProgress(character)
  if (!character.stats || typeof character.stats !== 'object') character.stats = newStats()
  character.stats[stat] = (Number.isFinite(character.stats[stat]) ? character.stats[stat] : 0) + delta
  character.emotion = 'joy'
  character.message = `시연용 보너스로 ${STAT_LABELS[stat]} 스탯이 ${delta} 올랐어요.`
  const evolvedNow = markCharacterEvolutionIfReady(character, previousEvolution)
  if (character.active) syncActiveGotchi()
  return { ...character, _evolvedNow: evolvedNow }
}

function localCreateCharacter({ name, keyword, personality }, { failImage = false } = {}) {
  if (state.characters.length >= MAX_CHARACTERS) {
    throw new Error(`캐릭터는 최대 ${MAX_CHARACTERS}개까지 만들 수 있어요.`)
  }
  if (!name?.trim()) throw new Error('이름을 입력해 주세요.')
  const id = state.nextId++
  const character = normalizeCharacter({
    id,
    name: name.trim(),
    keyword: keyword?.trim() || '',
    personality: personality?.trim() || '칭찬은 많이, 틀린 건 명확히 짚어주는',
    stats: newStats(),
    emotion: 'joy',
    isEvolved: false,
    imageStatus: 'PENDING',
    active: true,
    message: '새로 태어났어. 오늘부터 같이 커밋하자!',
  })
  state.characters.forEach(c => { c.active = false })
  state.characters.push(character)
  syncActiveGotchi()
  scheduleImageResult(character, { fail: failImage, delay: failImage ? 2000 : 900 })
  return character
}

function localRetryImage(id) {
  const character = state.characters.find(c => String(c.id) === String(id))
  if (!character) return null
  character.imageStatus = 'PENDING'
  character.spriteSheetUrl = null
  character.spriteMeta = null
  scheduleImageResult(character, { delay: 900 })
  return character
}

function localDeleteCharacter(id) {
  const index = state.characters.findIndex(c => String(c.id) === String(id))
  if (index < 0) return null
  const [deleted] = state.characters.splice(index, 1)
  if (deleted.active) syncActiveGotchi()
  return deleted
}

function localUpdateCharacter(id, changes) {
  const character = state.characters.find(c => String(c.id) === String(id))
  if (!character) return false
  const name = changes.name?.trim()
  if (!name) throw new Error('이름을 입력해 주세요.')
  character.name = name
  character.keyword = changes.keyword?.trim() || ''
  character.personality = changes.personality?.trim() || ''
  syncActiveGotchi()
  return true
}

function localSaveReport({ mood, title, content, tags }) {
  const character = requireActiveCharacter()
  if (!character) return null
  const date = todayISO()
  const report = normalizeReport({
    id: `r${state.reports.length + 1}`,
    date,
    mood,
    title: title.trim(),
    content,
    tags: [...(tags || [])],
    status: 'analyzing',
    scoreApplied: false,
    characterId: character.id,
  })
  const existing = state.reports.findIndex(r => r.date === date && String(r.characterId) === String(character.id))
  if (existing >= 0) state.reports.splice(existing, 1, report)
  else state.reports.push(report)
  state.dailyReport = { ...state.dailyReport, status: 'pending', date, characterId: character.id }
  return report
}

function localSubmitQuiz(quizId, userAnswer, { fail = false } = {}) {
  const quiz = state.quizzes.find(q => String(q.id) === String(quizId))
  if (!quiz) return null
  if (!quiz.characterId) quiz.characterId = activeCharacter.value?.id ?? null
  quiz.selected = userAnswer
  if (fail) {
    quiz.gradeFailed = true
    quiz.scored = false
    quiz.submitted = false
    return { ok: false }
  }
  if (quiz.scored) return quiz
  quiz.gradeFailed = false
  quiz.submitted = true
  quiz.correct = Number(userAnswer) === Number(quiz.answer)
  quiz.scored = true
  const owner = state.characters.find(c => String(c.id) === String(quiz.characterId))
  if (owner && quiz.correct) {
    const previousEvolution = evolutionProgress(owner)
    if (!owner.stats || typeof owner.stats !== 'object') owner.stats = newStats()
    owner.stats[quiz.deltaStat] = (owner.stats[quiz.deltaStat] || 0) + quiz.deltaAmount
    quiz._evolvedNow = markCharacterEvolutionIfReady(owner, previousEvolution)
  } else {
    quiz._evolvedNow = false
  }
  markStudyAnalysisNotice('quiz', quiz.id, quiz.characterId)
  return quiz
}

function localDeliverDailyReport({ fail = false } = {}) {
  if (fail) {
    state.dailyReport = {
      ...state.dailyReport,
      status: 'failed',
      summary: null,
      deltas: null,
      quizComment: null,
      nextRecommendation: null,
    }
    return state.dailyReport
  }

  const report = state.reports.find(r => r.status === 'analyzing' && !r.scoreApplied)
  const character = state.characters.find(c => String(c.id) === String(report?.characterId))
  const previousEvolution = character ? evolutionProgress(character) : null
  const deltas = {}
  if (report && character) {
    const tags = report.tags.length ? report.tags : ['algo']
    tags.forEach(tag => {
      deltas[tag] = 4
      character.stats[tag] = (character.stats[tag] || 0) + 4
    })
    report.status = 'applied'
    report.scoreApplied = true
    character.emotion = report.mood
    markCharacterEvolutionIfReady(character, previousEvolution)
    markStudyAnalysisNotice('report', report.id, report.characterId)
  }
  state.dailyReport = {
    status: 'ready',
    date: todayISO(),
    characterId: report?.characterId ?? activeCharacter.value?.id ?? null,
    summary: report ? `${report.title} 기록이 능력치에 반영됐어요.` : '아직 반영할 리포트가 없어요.',
    deltas,
    quizComment: '오늘 퀴즈 흐름은 안정적이에요.',
    nextRecommendation: '내일은 약한 능력치 태그를 하나 골라 짧게 복습해 보세요.',
  }
  return state.dailyReport
}

function localCreateDemoRecommendedQuizzes(characterId = null) {
  const character = characterId == null
    ? requireActiveCharacter()
    : state.characters.find(c => String(c.id) === String(characterId))
  if (!character) return null

  const date = todayISO()
  const requestId = `demo-quiz-${character.id}-${date}`
  const existing = state.quizzes.filter(q => q.sourceReportRequestId === requestId)
  if (existing.length) return state.dailyReport

  const demoQuizzes = [
    normalizeQuiz({
      id: `q${state.nextId++}`,
      date,
      sourceReportRequestId: requestId,
      problemId: 1,
      quizId: 1,
      tag: 'algo',
      question: 'DFS와 BFS의 차이점은 무엇인가요?',
      modelAnswer: 'DFS는 깊이 우선 탐색으로 스택이나 재귀를 사용하고, BFS는 너비 우선 탐색으로 큐를 사용합니다. BFS는 가중치가 없는 그래프에서 최단 경로를 보장합니다.',
      scoreAllocation: { db: 0, algorithm: 5, cs: 5, network: 0, framework: 0 },
      answerKeywords: ['DFS', 'BFS', '큐', '스택'],
      characterId: character.id,
      deltaStat: 'algo',
    }),
    normalizeQuiz({
      id: `q${state.nextId++}`,
      date,
      sourceReportRequestId: requestId,
      problemId: 2,
      quizId: 2,
      tag: 'net',
      question: 'RESTful API의 설계 원칙은 무엇인가요?',
      modelAnswer: 'RESTful API는 리소스를 URI로 표현하고 HTTP 메서드로 행위를 나타내며, stateless, client-server, uniform interface, cacheable 원칙을 지킵니다.',
      scoreAllocation: { db: 0, algorithm: 0, cs: 0, network: 5, framework: 0 },
      answerKeywords: ['URI', 'HTTP', 'Stateless'],
      characterId: character.id,
      deltaStat: 'net',
    }),
  ]
  state.quizzes.push(...demoQuizzes)
  state.dailyReport = {
    ...state.dailyReport,
    status: 'ready',
    date,
    characterId: character.id,
    requestId,
    summary: '시연을 위해 추천 퀴즈 2개를 준비했어요.',
    text: '시연을 위해 추천 퀴즈 2개를 준비했어요.',
    feedback: '퀴즈 화면에서 바로 확인할 수 있어요.',
    deltas: { algo: 0, cs: 0, db: 0, net: 0, fw: 0 },
    quizComment: '추천 퀴즈로 어제 배운 내용을 한 번 더 확인해 보세요.',
    nextRecommendation: { topics: ['algorithm', 'network'], rationale: '데모 추천 퀴즈' },
    recommendedQuizIds: demoQuizzes.map(q => q.id),
  }
  return state.dailyReport
}

function recalculateRating(post) {
  post.rating = post.reviews.length
    ? Number((post.reviews.reduce((sum, review) => sum + review.stars, 0) / post.reviews.length).toFixed(1))
    : 0
}

function localCreateBoardPost(desc) {
  const character = requireActiveCharacter()
  const clean = desc?.trim()
  if (!character || !clean) return null
  const post = normalizeBoardPost({
    id: `p${Date.now()}-${state.boardPosts.length + 1}`,
    characterId: character.id,
    name: character.name,
    owner: CURRENT_OWNER,
    emotion: character.emotion,
    isEvolved: character.isEvolved,
    spriteSheetUrl: character.spriteSheetUrl,
    spriteMeta: character.spriteMeta,
    score: nurtureScore(character),
    desc: clean,
    rating: 0,
    reviews: [],
  })
  state.boardPosts.unshift(post)
  return post
}

function localUpdateBoardPost(postId, desc) {
  const post = state.boardPosts.find(p => String(p.id) === String(postId))
  const clean = desc?.trim()
  if (!post || post.owner !== CURRENT_OWNER || !clean) return false
  post.desc = clean
  return true
}

function localDeleteBoardPost(postId) {
  const index = state.boardPosts.findIndex(p => String(p.id) === String(postId))
  if (index < 0 || state.boardPosts[index].owner !== CURRENT_OWNER) return false
  state.boardPosts.splice(index, 1)
  return true
}

function localAddReview(postId, stars, text) {
  const post = state.boardPosts.find(p => String(p.id) === String(postId))
  const clean = text?.trim()
  const score = Number(stars)
  if (!post || !clean || !Number.isInteger(score) || score < 1 || score > 5) return null
  const review = normalizeReview({
    id: `rv${Date.now()}-${post.reviews.length + 1}`,
    author: CURRENT_OWNER,
    stars: score,
    text: clean,
    createdAt: new Date().toISOString(),
  })
  post.reviews.unshift(review)
  recalculateRating(post)
  return review
}

function localUpdateReview(postId, reviewId, stars, text) {
  const post = state.boardPosts.find(p => String(p.id) === String(postId))
  const review = post?.reviews.find(r => String(r.id) === String(reviewId))
  const clean = text?.trim()
  const score = Number(stars)
  if (!post || !review || review.author !== CURRENT_OWNER || !clean || !Number.isInteger(score) || score < 1 || score > 5) return false
  review.stars = score
  review.text = clean
  recalculateRating(post)
  return true
}

function localDeleteReview(postId, reviewId) {
  const post = state.boardPosts.find(p => String(p.id) === String(postId))
  const index = post?.reviews.findIndex(r => String(r.id) === String(reviewId)) ?? -1
  if (!post || index < 0 || post.reviews[index].author !== CURRENT_OWNER) return false
  post.reviews.splice(index, 1)
  recalculateRating(post)
  return true
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
  if (mine) rows.push({
    id: mine.id,
    name: mine.name,
    owner: '나',
    score: nurtureScore(mine),
    isEvolved: mine.isEvolved,
    emotion: mine.emotion,
    spriteSheetUrl: mine.spriteSheetUrl,
    spriteMeta: mine.spriteMeta,
    me: true,
  })
  return rows.sort((a, b) => b.score - a.score).map((r, i) => ({ ...r, rank: i + 1 }))
}

/* ---- codex (도감) ---- */
export function codex() {
  return state.characters.map(c => ({
    id: c.id,
    name: c.name,
    personality: c.personality,
    emotion: c.emotion,
    isEvolved: c.isEvolved,
    spriteSheetUrl: c.spriteSheetUrl,
    spriteMeta: c.spriteMeta,
    owned: true,
  }))
}

/* ---- share board ---- */
export function board() { return state.boardPosts }
export function boardPost(id) { return state.boardPosts.find(p => String(p.id) === String(id)) || null }

export function createBoardPost(desc) {
  if (mockMode) return localCreateBoardPost(desc)
  const clean = desc?.trim()
  if (!clean) return null
  return mutate(gameApi.createBoardPost({ desc: clean }))
}
export function updateBoardPost(postId, desc) {
  if (mockMode) return localUpdateBoardPost(postId, desc)
  const clean = desc?.trim()
  if (!clean) return false
  return mutate(gameApi.updateBoardPost(postId, { desc: clean })).then(item => item != null)
}
export function deleteBoardPost(postId) {
  if (mockMode) return localDeleteBoardPost(postId)
  return mutate(gameApi.deleteBoardPost(postId)).then(item => item != null)
}
export function addReview(postId, stars, text) {
  if (mockMode) return localAddReview(postId, stars, text)
  const clean = text?.trim()
  const score = Number(stars)
  if (!clean || !Number.isInteger(score) || score < 1 || score > 5) return null
  return mutate(gameApi.addReview(postId, { stars: score, text: clean }))
}
export function updateReview(postId, reviewId, stars, text) {
  if (mockMode) return localUpdateReview(postId, reviewId, stars, text)
  const clean = text?.trim()
  const score = Number(stars)
  if (!clean || !Number.isInteger(score) || score < 1 || score > 5) return false
  return mutate(gameApi.updateReview(postId, reviewId, { stars: score, text: clean })).then(item => item != null)
}
export function deleteReview(postId, reviewId) {
  if (mockMode) return localDeleteReview(postId, reviewId)
  return mutate(gameApi.deleteReview(postId, reviewId)).then(item => item != null)
}

/* ---- actions ---- */
export function createCharacter({ name, keyword, personality }, options = {}) {
  if (mockMode) return localCreateCharacter({ name, keyword, personality }, options)
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

export function retryImage(id) {
  if (mockMode) return localRetryImage(id)
  return mutate(gameApi.retryImage(id))
}

export function setActive(id) {
  if (mockMode) return localSetActive(id)
  return mutate(gameApi.setActiveCharacter(id)).then(item => item != null)
}

export function boostCharacterStat(id, stat, amount = 200) {
  if (mockMode) return localBoostCharacterStat(id, stat, amount)
  if (!STAT_KEYS.includes(stat)) throw new Error('알 수 없는 능력치예요.')
  return mutate(gameApi.boostCharacterStat(id, { stat, amount }))
}

export function deleteCharacter(id) {
  if (mockMode) return localDeleteCharacter(id)
  return mutate(gameApi.deleteCharacter(id))
}

export function updateCharacter(id, changes) {
  if (mockMode) return localUpdateCharacter(id, changes)
  const name = changes.name?.trim()
  if (!name) throw new Error('이름을 입력해 주세요.')
  return mutate(gameApi.updateCharacter(id, {
    name,
    keyword: changes.keyword?.trim() || '',
    personality: changes.personality?.trim() || '',
  })).then(item => item != null)
}

export function saveReport({ mood, title, content, tags }) {
  if (mockMode) {
    return localSaveReport({ mood, title, content, tags })
  }
  if (!activeCharacter.value) return null
  return mutate(gameApi.saveReport({
    mood,
    title,
    content,
    tags: [...(tags || [])],
  }))
}

export function submitQuiz(quizId, userAnswer, { fail = false } = {}) {
  if (mockMode) return localSubmitQuiz(quizId, userAnswer, { fail })
  return mutate(gameApi.submitQuiz(quizId, { userAnswer, fail }))
}

export function createDemoQuizzes(characterId = null) {
  if (mockMode) return localCreateDemoRecommendedQuizzes(characterId)
  const body = characterId == null ? {} : { characterId }
  return mutate(gameApi.createDemoQuizzes(body))
}

export function deliverDailyReport({ fail = false } = {}) {
  if (mockMode) return localDeliverDailyReport({ fail })
  return mutate(gameApi.deliverDailyReport({ fail }))
}

export async function runReportNow({ timeoutMs = REPORT_RESULT_TIMEOUT_MS } = {}) {
  if (mockMode) {
    const dailyReport = localDeliverDailyReport()
    resolveReportResultWaiters()
    return { dailyReport, timedOut: false }
  }
  const dailyReport = await mutate(gameApi.runReportNow())
  const requestId = dailyReport?.requestId || state.dailyReport?.requestId || todayReport.value?.requestId
  return waitForReportResult({ requestId, timeoutMs })
}

export function showReportSubmittedNotice() { state.notice = REPORT_SUBMITTED_NOTICE }
export function clearNotice() { state.notice = null }

export const gameState = state
