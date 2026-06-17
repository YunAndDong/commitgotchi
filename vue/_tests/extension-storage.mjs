import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
import {
  ACTIVE_GOTCHI_STORAGE_KEY,
  GOTCHI_VISIBILITY_STORAGE_KEY,
  activeGotchiSnapshot,
  clearActiveGotchi,
  publishActiveGotchi,
  readGotchiVisibility,
  setActiveGotchiPublishingEnabled,
  setGotchiVisibility,
} from '../src/extension/activeGotchi.js'

const character = {
  id: 101,
  name: '새싹이',
  keyword: '저장하면 안 되는 필드',
  emotion: 'sad',
  isEvolved: true,
  stats: { algo: 999 },
}

delete globalThis.chrome
assert.equal(await publishActiveGotchi(character), false, 'Chrome API가 없는 웹 환경에서 조용히 무시')
assert.equal(await clearActiveGotchi(), false, 'Chrome API가 없는 웹 환경에서 제거도 조용히 무시')
assert.equal(await readGotchiVisibility(), true, 'Chrome API가 없는 웹 환경에서 기본 표시')
assert.equal(await setGotchiVisibility(false), false, 'Chrome API가 없는 웹 환경에서 표시 설정도 조용히 무시')

const calls = []
globalThis.chrome = {
  storage: {
    local: {
      async get() { return {} },
      async set(value) { calls.push(['set', value]) },
      async remove(key) { calls.push(['remove', key]) },
    },
  },
}
setActiveGotchiPublishingEnabled(true)

assert.deepEqual(activeGotchiSnapshot(character), {
  id: 101,
  name: '새싹이',
  emotion: 'sad',
  isEvolved: true,
}, '방문 페이지 렌더링에 필요한 최소 정보만 직렬화')
assert.equal(await publishActiveGotchi(character), true, '활성 커밋고치 저장 성공')
assert.equal(await clearActiveGotchi(), true, '활성 커밋고치 제거 성공')
assert.equal(await readGotchiVisibility(), true, '표시 설정이 없으면 기본 표시')
assert.equal(await setGotchiVisibility(false), true, '표시 설정 저장 성공')
assert.deepEqual(calls, [
  ['set', { [ACTIVE_GOTCHI_STORAGE_KEY]: activeGotchiSnapshot(character) }],
  ['remove', ACTIVE_GOTCHI_STORAGE_KEY],
  ['set', { [GOTCHI_VISIBILITY_STORAGE_KEY]: false }],
], '고정 저장소 키로 저장하고 제거')

let releaseSet
const delayedCalls = []
globalThis.chrome.storage.local = {
  async set(value) {
    delayedCalls.push(['set', value])
    await new Promise(resolve => { releaseSet = resolve })
  },
  async remove(key) { delayedCalls.push(['remove', key]) },
}
const delayedPublish = publishActiveGotchi(character)
const queuedClear = clearActiveGotchi()
await new Promise(resolve => setTimeout(resolve, 0))
assert.equal(delayedCalls.length, 1, '저장 중에는 뒤따른 제거가 먼저 실행되지 않음')
releaseSet()
await Promise.all([delayedPublish, queuedClear])
assert.deepEqual(delayedCalls.map(([method]) => method), ['set', 'remove'], '저장 작업을 호출 순서대로 직렬화')

setActiveGotchiPublishingEnabled(false)
assert.equal(await publishActiveGotchi(character), false, '로그아웃 이후 지연 게시를 차단')
assert.deepEqual(delayedCalls.map(([method]) => method), ['set', 'remove'], '비활성 게시가 저장소를 다시 변경하지 않음')
setActiveGotchiPublishingEnabled(true)

const gameCalls = []
globalThis.chrome.storage.local = {
  async set(value) { gameCalls.push(['set', value]) },
  async remove(key) { gameCalls.push(['remove', key]) },
}
setActiveGotchiPublishingEnabled(false)
const { activeCharacter, deleteCharacter, gameState, setActive } = await import('../src/stores/game.js')
assert.equal(setActive(gameState.characters[0].id), true, '게임 스토어 활성 선택 성공')
await new Promise(resolve => setTimeout(resolve, 0))
assert.equal(gameCalls.at(-1)[0], 'set', '게임 스토어 활성 선택이 꺼져 있던 확장 게시를 켜고 저장소에 반영')
deleteCharacter(activeCharacter.value.id)
await new Promise(resolve => setTimeout(resolve, 0))
assert.deepEqual(gameCalls.at(-1), ['remove', ACTIVE_GOTCHI_STORAGE_KEY], '활성 캐릭터가 없으면 확장 저장소에서 제거')

const manifest = JSON.parse(await readFile(new URL('../public/manifest.json', import.meta.url)))
assert.ok(manifest.permissions.includes('storage'), 'manifest에 storage 권한 등록')
assert.deepEqual(manifest.content_scripts[0].matches, ['http://*/*', 'https://*/*'], '일반 HTTP(S) 페이지만 content script 대상으로 등록')

const contentScript = await readFile(new URL('../public/content-script.js', import.meta.url), 'utf8')
for (const contract of ['attachShadow', 'position: fixed', 'pointer-events: none', 'prefers-reduced-motion', 'storage.onChanged', 'Date.now()', 'requestAnimationFrame']) {
  assert.ok(contentScript.includes(contract), `content script 계약 포함: ${contract}`)
}
assert.ok(contentScript.includes("mode: 'closed'"), '방문 페이지에서 Shadow DOM 내부를 직접 변경할 수 없음')
assert.ok(!contentScript.includes('animation: commitgotchi-stroll'), '탭마다 독립적으로 시작하는 CSS 이동 애니메이션을 사용하지 않음')
assert.ok(!contentScript.includes('getElementById'), '방문 페이지 소유 ID 요소를 조회하거나 제거하지 않음')

class FakeElement {
  constructor(tag) {
    this.tag = tag
    this.children = []
    this.isConnected = false
    this.style = { setProperty: () => {} }
  }
  setAttribute() {}
  attachShadow() {
    this.testShadow = new FakeElement('shadow')
    this.testShadow.replaceChildren = (...nodes) => { this.testShadow.children = nodes }
    return this.testShadow
  }
  append(...nodes) {
    for (const node of nodes) node.isConnected = true
    this.children.push(...nodes)
  }
  remove() { this.isConnected = false }
}

let initialResolve
let storageListener
const observerInstances = []
let animationCallback
const documentElement = new FakeElement('html')
const fakeDocument = {
  documentElement,
  createElement: tag => new FakeElement(tag),
  getElementById: () => { throw new Error('content script must not query page-owned IDs') },
}
const fakeChrome = {
  storage: {
    local: { get: () => new Promise(resolve => { initialResolve = resolve }) },
    onChanged: { addListener: listener => { storageListener = listener } },
  },
}
class FakeMutationObserver {
  constructor(callback) {
    this.callback = callback
    observerInstances.push(this)
  }
  observe() {}
  disconnect() {}
}
class FixedDate extends Date {
  static now() { return 6_000 }
}

vm.runInNewContext(contentScript, {
  chrome: fakeChrome,
  document: fakeDocument,
  MutationObserver: FakeMutationObserver,
  Object,
  Date: FixedDate,
  innerWidth: 1000,
  matchMedia: () => ({ matches: false }),
  requestAnimationFrame: callback => {
    animationCallback = callback
    return 1
  },
  cancelAnimationFrame: () => {},
})
const latest = { id: 2, name: '최신이', emotion: 'joy', isEvolved: false }
storageListener({ [ACTIVE_GOTCHI_STORAGE_KEY]: { newValue: latest } }, 'local')
const renderedHost = documentElement.children.at(-1)
assert.equal(renderedHost.testShadow.children[1].children.at(-1).textContent, '최신이', '저장소 변경 이벤트를 즉시 렌더링')
const firstWalker = renderedHost.testShadow.children[1]
const firstPosition = firstWalker.style.transform
animationCallback()
assert.equal(firstWalker.style.transform, firstPosition, '같은 공통 시각에는 탭의 이동 위치가 유지됨')
storageListener({ [GOTCHI_VISIBILITY_STORAGE_KEY]: { newValue: false } }, 'local')
assert.equal(renderedHost.isConnected, false, '표시 토글을 끄면 활성 스냅샷을 지우지 않고 호스트를 제거')
storageListener({ [GOTCHI_VISIBILITY_STORAGE_KEY]: { newValue: true } }, 'local')
const restoredHost = documentElement.children.at(-1)
assert.equal(restoredHost.testShadow.children[1].children.at(-1).textContent, '최신이', '표시 토글을 켜면 활성 스냅샷을 즉시 복원')

const secondTabDocumentElement = new FakeElement('html')
vm.runInNewContext(contentScript, {
  chrome: {
    storage: {
      local: { get: async () => ({ [ACTIVE_GOTCHI_STORAGE_KEY]: latest }) },
      onChanged: { addListener: () => {} },
    },
  },
  document: {
    documentElement: secondTabDocumentElement,
    createElement: tag => new FakeElement(tag),
  },
  MutationObserver: FakeMutationObserver,
  Object,
  Date: FixedDate,
  innerWidth: 1000,
  matchMedia: () => ({ matches: false }),
  requestAnimationFrame: () => 1,
  cancelAnimationFrame: () => {},
})
await new Promise(resolve => setTimeout(resolve, 0))
const secondTabWalker = secondTabDocumentElement.children.at(-1).testShadow.children[1]
assert.equal(secondTabWalker.style.transform, firstPosition, '서로 독립된 탭도 같은 공통 시각에는 같은 위치를 계산')

let hiddenTabListener
const hiddenTabDocumentElement = new FakeElement('html')
vm.runInNewContext(contentScript, {
  chrome: {
    storage: {
      local: {
        get: async () => ({
          [ACTIVE_GOTCHI_STORAGE_KEY]: latest,
          [GOTCHI_VISIBILITY_STORAGE_KEY]: false,
        }),
      },
      onChanged: { addListener: listener => { hiddenTabListener = listener } },
    },
  },
  document: {
    documentElement: hiddenTabDocumentElement,
    createElement: tag => new FakeElement(tag),
  },
  MutationObserver: FakeMutationObserver,
  Object,
  Date: FixedDate,
  innerWidth: 1000,
  matchMedia: () => ({ matches: false }),
  requestAnimationFrame: () => 1,
  cancelAnimationFrame: () => {},
})
await new Promise(resolve => setTimeout(resolve, 0))
assert.equal(hiddenTabDocumentElement.children.length, 0, '저장된 숨김 설정은 새 탭에서도 렌더링을 막음')
hiddenTabListener({ [GOTCHI_VISIBILITY_STORAGE_KEY]: { newValue: true } }, 'local')
assert.equal(hiddenTabDocumentElement.children.at(-1).isConnected, true, '새 탭에서도 토글을 다시 켜면 저장된 활성 커밋고치를 표시')

let visibilityRaceResolve
let visibilityRaceListener
const visibilityRaceDocumentElement = new FakeElement('html')
vm.runInNewContext(contentScript, {
  chrome: {
    storage: {
      local: { get: () => new Promise(resolve => { visibilityRaceResolve = resolve }) },
      onChanged: { addListener: listener => { visibilityRaceListener = listener } },
    },
  },
  document: {
    documentElement: visibilityRaceDocumentElement,
    createElement: tag => new FakeElement(tag),
  },
  MutationObserver: FakeMutationObserver,
  Object,
  Date: FixedDate,
  innerWidth: 1000,
  matchMedia: () => ({ matches: false }),
  requestAnimationFrame: () => 1,
  cancelAnimationFrame: () => {},
})
visibilityRaceListener({ [GOTCHI_VISIBILITY_STORAGE_KEY]: { newValue: false } }, 'local')
visibilityRaceResolve({ [ACTIVE_GOTCHI_STORAGE_KEY]: latest })
await new Promise(resolve => setTimeout(resolve, 0))
assert.equal(visibilityRaceDocumentElement.children.length, 0, '초기 조회 전 숨김 이벤트가 와도 숨김 상태 유지')
visibilityRaceListener({ [GOTCHI_VISIBILITY_STORAGE_KEY]: { newValue: true } }, 'local')
assert.equal(visibilityRaceDocumentElement.children.at(-1).isConnected, true, '초기 조회 전 숨김 이벤트가 활성 스냅샷 로딩을 막지 않음')

initialResolve({ [ACTIVE_GOTCHI_STORAGE_KEY]: { ...latest, id: 1, name: '오래된 값' } })
await new Promise(resolve => setTimeout(resolve, 0))
assert.equal(restoredHost.testShadow.children[1].children.at(-1).textContent, '최신이', '늦게 도착한 초기 조회가 최신 변경을 덮어쓰지 않음')
restoredHost.remove()
observerInstances[1].callback()
assert.equal(restoredHost.isConnected, true, '방문 페이지가 호스트를 제거해도 활성 상태면 복구')
storageListener({ [ACTIVE_GOTCHI_STORAGE_KEY]: { newValue: undefined } }, 'local')
assert.equal(restoredHost.isConnected, false, '저장소 제거 이벤트가 렌더링 호스트를 제거')

let failedReadListener
const failedReadDocumentElement = new FakeElement('html')
vm.runInNewContext(contentScript, {
  chrome: {
    storage: {
      local: { get: () => Promise.reject(new Error('storage unavailable')) },
      onChanged: { addListener: listener => { failedReadListener = listener } },
    },
  },
  document: {
    documentElement: failedReadDocumentElement,
    createElement: tag => new FakeElement(tag),
  },
  MutationObserver: FakeMutationObserver,
  Object,
  innerWidth: 1000,
  matchMedia: () => ({ matches: false }),
})
failedReadListener({ [ACTIVE_GOTCHI_STORAGE_KEY]: { newValue: latest } }, 'local')
await new Promise(resolve => setTimeout(resolve, 0))
assert.equal(failedReadDocumentElement.children.at(-1).isConnected, true, '늦은 초기 조회 실패가 최신 변경 렌더링을 제거하지 않음')

console.log('  ✓ extension storage/content-script contracts')
