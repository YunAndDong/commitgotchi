export const ACTIVE_GOTCHI_STORAGE_KEY = 'commitgotchi.activeGotchi'
export const GOTCHI_VISIBILITY_STORAGE_KEY = 'commitgotchi.gotchiVisible'

let publishingEnabled = false
let storageQueue = Promise.resolve()

function extensionStorage() {
  return globalThis.chrome?.storage?.local || null
}

function enqueueStorage(operation) {
  const storage = extensionStorage()
  if (!storage) return Promise.resolve(false)
  const result = storageQueue.then(() => operation(storage)).then(() => true, () => false)
  storageQueue = result.then(() => undefined)
  return result
}

export function setActiveGotchiPublishingEnabled(enabled) {
  publishingEnabled = !!enabled
}

function normalizeSpriteMeta(raw) {
  if (!raw) return null
  if (typeof raw === 'string') {
    try { return normalizeSpriteMeta(JSON.parse(raw)) } catch { return null }
  }
  return typeof raw === 'object' ? raw : null
}

function selectedSpriteSheetUrl(character) {
  return character.isEvolved
    ? (character.evolvedSpriteSheetUrl || character.spriteSheetUrl)
    : (character.babySpriteSheetUrl || character.spriteSheetUrl)
}

function selectedSpriteMeta(character) {
  return character.isEvolved
    ? (character.evolvedSpriteMeta || character.spriteMeta)
    : (character.babySpriteMeta || character.spriteMeta)
}

export function activeGotchiSnapshot(character) {
  if (!character || (typeof character.id !== 'string' && typeof character.id !== 'number')) return null
  const snapshot = {
    id: character.id,
    name: String(character.name || '커밋고치'),
    emotion: ['joy', 'sad', 'angry'].includes(character.emotion) ? character.emotion : 'joy',
    isEvolved: !!character.isEvolved,
  }
  const spriteSheetUrl = typeof selectedSpriteSheetUrl(character) === 'string'
    ? selectedSpriteSheetUrl(character).trim()
    : ''
  const spriteMeta = normalizeSpriteMeta(selectedSpriteMeta(character))
  if (spriteSheetUrl && spriteMeta) {
    snapshot.spriteSheetUrl = spriteSheetUrl
    snapshot.spriteMeta = spriteMeta
  }
  if (['PENDING', 'READY', 'FALLBACK', 'FAILED'].includes(character.imageStatus)) {
    snapshot.imageStatus = character.imageStatus
  }
  return snapshot
}

export function publishActiveGotchi(character) {
  const snapshot = activeGotchiSnapshot(character)
  if (!publishingEnabled || !snapshot) return Promise.resolve(false)
  return enqueueStorage(storage => storage.set({ [ACTIVE_GOTCHI_STORAGE_KEY]: snapshot }))
}

export function clearActiveGotchi() {
  return enqueueStorage(storage => storage.remove(ACTIVE_GOTCHI_STORAGE_KEY))
}

export async function readGotchiVisibility() {
  const storage = extensionStorage()
  if (!storage) return true
  try {
    const result = await storage.get(GOTCHI_VISIBILITY_STORAGE_KEY)
    return result[GOTCHI_VISIBILITY_STORAGE_KEY] !== false
  } catch {
    return true
  }
}

export function setGotchiVisibility(visible) {
  return enqueueStorage(storage => storage.set({
    [GOTCHI_VISIBILITY_STORAGE_KEY]: !!visible,
  }))
}
