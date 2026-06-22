/*
 * HTTP client for the Spring Boot System-of-Record API.
 * Auth contract (README §인증 API):
 *   POST /api/auth/signup  → 201 SignupResponse
 *   POST /api/auth/login   → 200 TokenPairResponse
 *   POST /api/auth/refresh → 200 TokenPairResponse (rotation)
 *   POST /api/auth/logout  → 204
 *   GET  /api/users/me     → 200 CurrentUserResponse (Bearer)
 *   GET  /api/health       → health probe
 * Access token = 15min JWT and is persisted locally. The 30d refresh token is
 * held by the backend in an HttpOnly cookie and transparently rotated on a 401.
 */

const BASE = import.meta.env?.VITE_API_BASE_URL || ''
const NORMALIZED_BASE = String(BASE || '').replace(/\/+$/, '')
const TOKENS_KEY = 'cg.tokens'
const REQUEST_TIMEOUT_MS = 15000
let memoryTokens = null
let refreshPromise = null
let authGeneration = 0
const tokenListeners = new Set()

function validTokens(tokens) {
  return tokens && typeof tokens === 'object' && typeof tokens.accessToken === 'string' && tokens.accessToken.length > 0
}

export function loadTokens() {
  try {
    const saved = JSON.parse(localStorage.getItem(TOKENS_KEY))
    memoryTokens = validTokens(saved) ? saved : null
  } catch { /* use memory fallback */ }
  return memoryTokens
}
export function saveTokens(t) {
  memoryTokens = validTokens(t) ? {
    tokenType: t.tokenType,
    accessToken: t.accessToken,
    accessTokenExpiresAt: t.accessTokenExpiresAt,
  } : null
  try {
    if (memoryTokens) localStorage.setItem(TOKENS_KEY, JSON.stringify(memoryTokens))
    else localStorage.removeItem(TOKENS_KEY)
  } catch { /* memory state remains authoritative */ }
  tokenListeners.forEach(listener => listener(memoryTokens))
  return memoryTokens
}
export function onTokensChanged(listener) {
  tokenListeners.add(listener)
  return () => tokenListeners.delete(listener)
}
export function clearAuthSession() {
  authGeneration += 1
  refreshPromise = null
  saveTokens(null)
}

export function apiAssetUrl(path) {
  if (typeof path !== 'string') return path
  const value = path.trim()
  if (!value || !value.startsWith('/') || !NORMALIZED_BASE) return value
  if (/^[a-z][a-z\d+\-.]*:/i.test(value) || value.startsWith('//')) return value
  if (/^https?:\/\//i.test(NORMALIZED_BASE)) {
    return new URL(value, NORMALIZED_BASE).toString()
  }
  return `${NORMALIZED_BASE}${value}`
}

export class ApiError extends Error {
  constructor(status, code, message, payload) {
    super(message || code || `HTTP ${status}`)
    this.status = status
    this.code = code
    this.payload = payload
  }
}

async function parse(res) {
  if (res.status === 204) return null
  const text = await res.text()
  if (!text) return null
  try { return JSON.parse(text) } catch { return text }
}

async function raw(method, path, { body, token } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
  let res
  try {
    res = await fetch(`${BASE}${path}`, {
      method,
      headers,
      credentials: 'include',
      signal: controller.signal,
      body: body != null ? JSON.stringify(body) : undefined,
    })
  } finally {
    clearTimeout(timeout)
  }
  const data = await parse(res)
  if (!res.ok) {
    const code = data && typeof data === 'object' ? data.code : undefined
    const message = data && typeof data === 'object' ? data.message : data
    throw new ApiError(res.status, code, message, data)
  }
  return data
}

/* ---- public auth endpoints ---- */
export const auth = {
  signup: (email, password) => raw('POST', '/api/auth/signup', { body: { email, password } }),
  login: (email, password) => raw('POST', '/api/auth/login', { body: { email, password } }),
  refresh: () => raw('POST', '/api/auth/refresh-cookie'),
  logout: () => raw('POST', '/api/auth/logout-cookie'),
}

export const health = () => raw('GET', '/api/health')

/*
 * Authenticated GET/POST that auto-refreshes once on 401 using the HttpOnly
 * refresh cookie, then retries. Returns the parsed body.
 */
export async function authed(method, path, opts = {}) {
  const tokens = loadTokens()
  if (!tokens) throw new ApiError(401, 'AUTH_ACCESS_TOKEN_MISSING', 'Not logged in')
  try {
    return await raw(method, path, { ...opts, token: tokens.accessToken })
  } catch (e) {
    if (e.status !== 401) throw e
    const generation = authGeneration
    if (!refreshPromise) {
      refreshPromise = auth.refresh()
        .catch(error => {
          if (error?.status === 401) clearAuthSession()
          throw error
        })
        .then(next => {
          if (generation !== authGeneration) throw new ApiError(401, 'AUTH_CANCELLED', 'Session ended')
          return saveTokens(next)
        })
        .finally(() => { refreshPromise = null })
    }
    const next = await refreshPromise
    return await raw(method, path, { ...opts, token: next.accessToken })
  }
}

export const users = {
  me: () => authed('GET', '/api/users/me'),
}

export const game = {
  state: () => authed('GET', '/api/game/state'),
  createCharacter: (body) => authed('POST', '/api/game/characters', { body }),
  updateCharacter: (id, body) => authed('PATCH', `/api/game/characters/${encodeURIComponent(String(id))}`, { body }),
  setActiveCharacter: (id) => authed('PATCH', `/api/game/characters/${encodeURIComponent(String(id))}/active`),
  retryImage: (id) => authed('POST', `/api/game/characters/${encodeURIComponent(String(id))}/retry-image`),
  deleteCharacter: (id) => authed('DELETE', `/api/game/characters/${encodeURIComponent(String(id))}`),
  saveReport: (body) => authed('POST', '/api/game/reports', { body }),
  submitQuiz: (id, body) => authed('POST', `/api/game/quizzes/${encodeURIComponent(String(id))}/submit`, { body }),
  deliverDailyReport: (body = {}) => authed('POST', '/api/game/daily-report/deliver', { body }),
  createBoardPost: (body) => authed('POST', '/api/game/board-posts', { body }),
  updateBoardPost: (id, body) => authed('PATCH', `/api/game/board-posts/${encodeURIComponent(String(id))}`, { body }),
  deleteBoardPost: (id) => authed('DELETE', `/api/game/board-posts/${encodeURIComponent(String(id))}`),
  addReview: (postId, body) => authed('POST', `/api/game/board-posts/${encodeURIComponent(String(postId))}/reviews`, { body }),
  updateReview: (postId, reviewId, body) => authed('PATCH', `/api/game/board-posts/${encodeURIComponent(String(postId))}/reviews/${encodeURIComponent(String(reviewId))}`, { body }),
  deleteReview: (postId, reviewId) => authed('DELETE', `/api/game/board-posts/${encodeURIComponent(String(postId))}/reviews/${encodeURIComponent(String(reviewId))}`),
}
