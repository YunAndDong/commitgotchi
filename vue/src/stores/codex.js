import { reactive } from 'vue'
import { apiAssetUrl, codex as codexApi } from '../api/client.js'

export const CODEX_PAGE_SIZE = 12
export const CODEX_VISIBLE_RADIUS = 2
export const CODEX_REVIEW_PAGE_SIZE = 3
const PAGE_PREFETCH_DISTANCE = 4

export const codexState = reactive({
  items: [],
  nextCursor: null,
  hasMore: true,
  initialized: false,
  loadingPage: false,
  pageError: null,
  imageCacheById: {},
  loadingImageIds: {},
  imageErrorById: {},
  reviewsById: {},
  loadingReviewIds: {},
  reviewErrorById: {},
  submittingReviewIds: {},
  raisingCharacterIds: {},
})

let pageLoadPromise = null

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

function normalizeImageStatus(raw) {
  return ['READY', 'FALLBACK'].includes(raw) ? raw : 'READY'
}

function normalizeItem(raw) {
  const id = Number(raw?.id)
  if (!Number.isFinite(id)) return null
  return {
    id,
    personality: String(raw?.personality || ''),
    designKeyword: String(raw?.designKeyword || ''),
    imageStatus: normalizeImageStatus(raw?.imageStatus),
    spriteMeta: normalizeSpriteMeta(raw?.spriteMeta),
  }
}

function normalizeImage(raw) {
  const id = Number(raw?.id)
  if (!Number.isFinite(id)) return null
  return {
    id,
    spriteSheetUrl: normalizeSpriteSheetUrl(raw?.spriteSheetUrl),
    spriteMeta: normalizeSpriteMeta(raw?.spriteMeta),
    imageStatus: normalizeImageStatus(raw?.imageStatus),
    expiresAt: raw?.expiresAt || null,
  }
}

function normalizeReview(raw) {
  const id = Number(raw?.id)
  const stars = Number(raw?.stars)
  if (!Number.isFinite(id) || !Number.isInteger(stars)) return null
  return {
    id,
    stars: Math.min(5, Math.max(1, stars)),
    text: String(raw?.text || ''),
    createdAt: raw?.createdAt || null,
    mine: !!raw?.mine,
  }
}

function normalizeReviewState(characterId, raw) {
  const id = Number(raw?.characterId ?? characterId)
  const reviews = Array.isArray(raw?.reviews)
    ? raw.reviews.map(normalizeReview).filter(Boolean)
    : []
  return {
    characterId: id,
    averageStars: Number(raw?.averageStars) || 0,
    totalReviews: Number(raw?.totalReviews) || 0,
    raisedByMe: !!raw?.raisedByMe,
    canReview: !!raw?.canReview,
    myReview: raw?.myReview ? normalizeReview(raw.myReview) : null,
    reviews,
    page: Number(raw?.page) || 0,
    size: Number(raw?.size) || CODEX_REVIEW_PAGE_SIZE,
    hasMore: !!raw?.hasMore,
  }
}

function resetState() {
  codexState.items = []
  codexState.nextCursor = null
  codexState.hasMore = true
  codexState.initialized = false
  codexState.pageError = null
  codexState.imageCacheById = {}
  codexState.loadingImageIds = {}
  codexState.imageErrorById = {}
  codexState.reviewsById = {}
  codexState.loadingReviewIds = {}
  codexState.reviewErrorById = {}
  codexState.submittingReviewIds = {}
  codexState.raisingCharacterIds = {}
}

function mergeItems(items) {
  const seen = new Set(codexState.items.map(item => String(item.id)))
  items.forEach(item => {
    const key = String(item.id)
    if (seen.has(key)) return
    seen.add(key)
    codexState.items.push(item)
  })
}

export async function loadNextCodexPage({ reset = false } = {}) {
  if (codexState.loadingPage) return pageLoadPromise || codexState
  if (reset) resetState()
  if (!codexState.hasMore) return codexState

  codexState.loadingPage = true
  codexState.pageError = null
  pageLoadPromise = codexApi.listCharacters({
    afterId: codexState.nextCursor,
    limit: CODEX_PAGE_SIZE,
  })
    .then(response => {
      const items = Array.isArray(response?.items)
        ? response.items.map(normalizeItem).filter(Boolean)
        : []
      mergeItems(items)
      codexState.nextCursor = response?.nextCursor ?? null
      codexState.hasMore = !!response?.hasMore
      codexState.initialized = true
      return codexState
    })
    .catch(error => {
      codexState.pageError = error
      codexState.initialized = true
      throw error
    })
    .finally(() => {
      codexState.loadingPage = false
      pageLoadPromise = null
    })
  return pageLoadPromise
}

export function ensureCodexLoaded() {
  if (codexState.initialized || codexState.loadingPage) {
    return pageLoadPromise || Promise.resolve(codexState)
  }
  return loadNextCodexPage()
}

export async function loadImagesAround(index) {
  const start = Math.max(0, index - CODEX_VISIBLE_RADIUS)
  const end = Math.min(codexState.items.length, index + CODEX_VISIBLE_RADIUS + 1)
  const ids = codexState.items.slice(start, end)
    .map(item => item.id)
    .filter(id => !codexState.imageCacheById[String(id)] && !codexState.loadingImageIds[String(id)])
    .slice(0, 7)

  if (!ids.length) return []
  ids.forEach(id => {
    codexState.loadingImageIds[String(id)] = true
    delete codexState.imageErrorById[String(id)]
  })

  try {
    const response = await codexApi.spriteUrls(ids)
    const images = Array.isArray(response?.items)
      ? response.items.map(normalizeImage).filter(Boolean)
      : []
    images.forEach(image => {
      codexState.imageCacheById[String(image.id)] = image
    })
    return images
  } catch (error) {
    ids.forEach(id => {
      codexState.imageErrorById[String(id)] = error
    })
    throw error
  } finally {
    ids.forEach(id => {
      delete codexState.loadingImageIds[String(id)]
    })
  }
}

export async function prepareCodexWindow(index) {
  const tasks = [loadImagesAround(index)]
  if (
    codexState.hasMore
    && !codexState.loadingPage
    && codexState.items.length > 0
    && index >= codexState.items.length - PAGE_PREFETCH_DISTANCE
  ) {
    tasks.push(loadNextCodexPage())
  }
  return Promise.all(tasks)
}

export async function loadCodexReviews(characterId, { page = 0, size = CODEX_REVIEW_PAGE_SIZE } = {}) {
  const id = Number(characterId)
  if (!Number.isFinite(id)) return null
  const key = String(id)
  codexState.loadingReviewIds[key] = true
  delete codexState.reviewErrorById[key]
  try {
    const response = await codexApi.reviews(id, { page, size })
    const state = normalizeReviewState(id, response)
    codexState.reviewsById[key] = state
    return state
  } catch (error) {
    codexState.reviewErrorById[key] = error
    throw error
  } finally {
    delete codexState.loadingReviewIds[key]
  }
}

export async function submitCodexReview(characterId, { stars, text }) {
  const id = Number(characterId)
  const score = Number(stars)
  const clean = String(text || '').trim()
  if (!Number.isFinite(id) || !Number.isInteger(score) || score < 1 || score > 5 || !clean) {
    throw new Error('리뷰 내용을 확인해 주세요.')
  }
  const key = String(id)
  codexState.submittingReviewIds[key] = true
  delete codexState.reviewErrorById[key]
  try {
    const response = await codexApi.addReview(id, { stars: score, text: clean })
    const state = normalizeReviewState(id, response)
    codexState.reviewsById[key] = state
    return state
  } catch (error) {
    codexState.reviewErrorById[key] = error
    throw error
  } finally {
    delete codexState.submittingReviewIds[key]
  }
}

export async function updateCodexReview(characterId, reviewId, { stars, text }) {
  const id = Number(characterId)
  const review = Number(reviewId)
  const score = Number(stars)
  const clean = String(text || '').trim()
  if (
    !Number.isFinite(id)
    || !Number.isFinite(review)
    || !Number.isInteger(score)
    || score < 1
    || score > 5
    || !clean
  ) {
    throw new Error('리뷰 내용을 확인해 주세요.')
  }
  const key = String(id)
  codexState.submittingReviewIds[key] = true
  delete codexState.reviewErrorById[key]
  try {
    const response = await codexApi.updateReview(id, review, { stars: score, text: clean })
    const state = normalizeReviewState(id, response)
    codexState.reviewsById[key] = state
    return state
  } catch (error) {
    codexState.reviewErrorById[key] = error
    throw error
  } finally {
    delete codexState.submittingReviewIds[key]
  }
}

export async function deleteCodexReview(characterId, reviewId) {
  const id = Number(characterId)
  const review = Number(reviewId)
  if (!Number.isFinite(id) || !Number.isFinite(review)) {
    throw new Error('리뷰 정보를 확인해 주세요.')
  }
  const key = String(id)
  codexState.submittingReviewIds[key] = true
  delete codexState.reviewErrorById[key]
  try {
    const response = await codexApi.deleteReview(id, review)
    const state = normalizeReviewState(id, response)
    codexState.reviewsById[key] = state
    return state
  } catch (error) {
    codexState.reviewErrorById[key] = error
    throw error
  } finally {
    delete codexState.submittingReviewIds[key]
  }
}

export async function raiseCodexCharacter(characterId) {
  const id = Number(characterId)
  if (!Number.isFinite(id)) return null
  const key = String(id)
  codexState.raisingCharacterIds[key] = true
  delete codexState.reviewErrorById[key]
  try {
    const response = await codexApi.raiseCharacter(id)
    await loadCodexReviews(id, { page: 0, size: CODEX_REVIEW_PAGE_SIZE })
    return response
  } catch (error) {
    codexState.reviewErrorById[key] = error
    throw error
  } finally {
    delete codexState.raisingCharacterIds[key]
  }
}

export function resetCodexState() {
  resetState()
}
