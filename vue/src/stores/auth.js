/*
 * Auth store — reactive singleton (no Pinia dependency).
 * Wires the real Spring Boot auth API. The gotchi/quiz/report data is mocked
 * separately in stores/game.js until those backend endpoints exist.
 */
import { reactive, readonly } from 'vue'
import { auth, users, loadTokens, saveTokens, clearAuthSession, onTokensChanged, ApiError } from '../api/client.js'
import { activeCharacter, resetGameState } from './game.js'
import {
  clearActiveGotchi,
  publishActiveGotchi,
  setActiveGotchiPublishingEnabled,
} from '../extension/activeGotchi.js'

const state = reactive({
  user: null,          // { id, email, role }
  tokens: loadTokens(),
  status: 'idle',      // idle | loading | ready
  error: null,
})

const DEMO_KEY = 'cg.demo'
const DEMO_USER = { id: 0, email: 'demo@commitgotchi.local', role: 'USER' }
const isDemo = () => { try { return localStorage.getItem(DEMO_KEY) === '1' } catch { return false } }

export const isAuthenticated = () => !!state.tokens
onTokensChanged(tokens => {
  state.tokens = tokens
  if (!tokens) {
    setActiveGotchiPublishingEnabled(false)
    void clearActiveGotchi()
  }
})

async function fetchMe() {
  state.user = await users.me()
  return state.user
}

function publishAuthenticatedGotchi() {
  setActiveGotchiPublishingEnabled(true)
  if (activeCharacter.value) void publishActiveGotchi(activeCharacter.value)
  else void clearActiveGotchi()
}

export async function bootstrap() {
  if (isDemo()) {
    state.user = DEMO_USER
    if (!state.tokens) state.tokens = { tokenType: 'Bearer', accessToken: 'demo', refreshToken: 'demo' }
    state.status = 'ready'
    publishAuthenticatedGotchi()
    return
  }
  if (!state.tokens) {
    state.status = 'ready'
    setActiveGotchiPublishingEnabled(false)
    void clearActiveGotchi()
    return
  }
  state.status = 'loading'
  try {
    await fetchMe()
    publishAuthenticatedGotchi()
  } catch (e) {
    // refresh already attempted inside client; treat as logged out
    clearAuthSession()
    state.user = null
    resetGameState()
    setActiveGotchiPublishingEnabled(false)
    void clearActiveGotchi()
  } finally {
    state.status = 'ready'
  }
}

export async function login(email, password) {
  state.error = null
  const tokens = await auth.login(email, password)
  saveTokens(tokens)
  try {
    await fetchMe()
    publishAuthenticatedGotchi()
  } catch (e) {
    clearAuthSession()
    state.user = null
    setActiveGotchiPublishingEnabled(false)
    void clearActiveGotchi()
    throw e
  }
  return state.user
}

export async function signup(email, password) {
  state.error = null
  await auth.signup(email, password)
  // auto-login after signup for a smooth first-run (Flow 2)
  return login(email, password)
}

/**
 * Demo / offline mode — bypasses the backend so the app (and the Chrome
 * extension popup) is fully browsable without a running Spring Boot server or
 * CORS allow-listing of the chrome-extension:// origin. All gotchi data is
 * mocked anyway (stores/game.js); this only fakes the auth gate.
 */
export function demoLogin() {
  const fake = { tokenType: 'Bearer', accessToken: 'demo', refreshToken: 'demo' }
  try { localStorage.setItem(DEMO_KEY, '1') } catch { /* ignore */ }
  saveTokens(fake)
  state.tokens = fake
  state.user = DEMO_USER
  state.status = 'ready'
  publishAuthenticatedGotchi()
  return state.user
}

export async function logout() {
  setActiveGotchiPublishingEnabled(false)
  void clearActiveGotchi()
  if (!isDemo()) {
    try { await auth.logout() } catch { /* idempotent 204 anyway */ }
  }
  try { localStorage.removeItem(DEMO_KEY) } catch { /* ignore */ }
  clearAuthSession()
  state.user = null
  resetGameState()
}

/** Friendly Korean message for known auth error codes. */
export function authMessage(e) {
  if (!(e instanceof ApiError)) return '네트워크 오류 — 잠시 후 다시 시도해 주세요.'
  switch (e.code) {
    case 'AUTH_INVALID_CREDENTIALS': return '이메일 또는 비밀번호가 올바르지 않아요.'
    case 'USER_EMAIL_CONFLICT': return '이미 가입된 이메일이에요.'
    case 'VALIDATION_FAILED': return '입력값을 다시 확인해 주세요. (비밀번호 12~64자)'
    default: return '오류가 발생했어요. 잠시 후 다시 시도해 주세요.'
  }
}

export const authState = readonly(state)
