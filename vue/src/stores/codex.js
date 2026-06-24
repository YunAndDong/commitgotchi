import { reactive } from 'vue'
import { apiAssetUrl, codex as codexApi } from '../api/client.js'

export const CODEX_PAGE_SIZE = 12
export const CODEX_VISIBLE_RADIUS = 2
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

function resetState() {
  codexState.items = []
  codexState.nextCursor = null
  codexState.hasMore = true
  codexState.initialized = false
  codexState.pageError = null
  codexState.imageCacheById = {}
  codexState.loadingImageIds = {}
  codexState.imageErrorById = {}
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

export function resetCodexState() {
  resetState()
}
