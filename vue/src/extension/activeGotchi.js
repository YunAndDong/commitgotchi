export const ACTIVE_GOTCHI_STORAGE_KEY = 'commitgotchi.activeGotchi'

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

export function activeGotchiSnapshot(character) {
  if (!character || (typeof character.id !== 'string' && typeof character.id !== 'number')) return null
  return {
    id: character.id,
    name: String(character.name || '커밋고치'),
    emotion: ['joy', 'sad', 'angry'].includes(character.emotion) ? character.emotion : 'joy',
    isEvolved: !!character.isEvolved,
  }
}

export function publishActiveGotchi(character) {
  const snapshot = activeGotchiSnapshot(character)
  if (!publishingEnabled || !snapshot) return Promise.resolve(false)
  return enqueueStorage(storage => storage.set({ [ACTIVE_GOTCHI_STORAGE_KEY]: snapshot }))
}

export function clearActiveGotchi() {
  return enqueueStorage(storage => storage.remove(ACTIVE_GOTCHI_STORAGE_KEY))
}
