const storage = new Map()
globalThis.localStorage = {
  getItem: key => storage.get(key) ?? null,
  setItem: (key, value) => storage.set(key, value),
  removeItem: key => storage.delete(key),
}

const { authed, saveTokens } = await import('../src/api/client.js')

let refreshCalls = 0
let protectedCalls = 0
globalThis.fetch = async (url, options) => {
  if (url.endsWith('/api/auth/refresh-cookie')) {
    refreshCalls++
    await new Promise(resolve => setTimeout(resolve, 20))
    return response(200, { tokenType: 'Bearer', accessToken: 'new-access' })
  }
  protectedCalls++
  return options.headers.Authorization === 'Bearer new-access'
    ? response(200, { ok: true })
    : response(401, { code: 'AUTH_ACCESS_TOKEN_EXPIRED' })
}

saveTokens({ tokenType: 'Bearer', accessToken: 'old-access', refreshToken: 'must-not-persist' })
const [a, b] = await Promise.all([authed('GET', '/one'), authed('GET', '/two')])
const persisted = JSON.parse(storage.get('cg.tokens'))

const checks = [
  [refreshCalls === 1, '동시 401은 refresh 요청 하나로 합쳐짐'],
  [protectedCalls === 4 && a.ok && b.ok, '두 원 요청 모두 새 access token으로 재시도'],
  [persisted.accessToken === 'new-access' && !('refreshToken' in persisted), 'refresh token을 localStorage에 저장하지 않음'],
]
let failures = 0
for (const [condition, message] of checks) {
  console.log(condition ? '  ✓' : '  ✗', message)
  if (!condition) failures++
}
process.exit(failures ? 1 : 0)

function response(status, body) {
  return {
    status,
    ok: status >= 200 && status < 300,
    text: async () => JSON.stringify(body),
  }
}
