<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import {
  GOTCHI_VISIBILITY_STORAGE_KEY,
  readGotchiVisibility,
  setGotchiVisibility,
} from '../extension/activeGotchi.js'

const visible = ref(true)
const busy = ref(true)
let visibilityRevision = 0

function handleStorageChange(changes, areaName) {
  if (areaName === 'local' && Object.prototype.hasOwnProperty.call(changes, GOTCHI_VISIBILITY_STORAGE_KEY)) {
    visibilityRevision++
    visible.value = changes[GOTCHI_VISIBILITY_STORAGE_KEY].newValue !== false
  }
}

async function toggleVisibility() {
  if (busy.value) return

  const previous = visible.value
  visible.value = !previous
  busy.value = true
  const saved = await setGotchiVisibility(visible.value)
  if (!saved) visible.value = previous
  busy.value = false
}

onMounted(async () => {
  globalThis.chrome?.storage?.onChanged?.addListener(handleStorageChange)
  const initialRevision = visibilityRevision
  const storedVisibility = await readGotchiVisibility()
  if (visibilityRevision === initialRevision) visible.value = storedVisibility
  busy.value = false
})

onUnmounted(() => {
  globalThis.chrome?.storage?.onChanged?.removeListener?.(handleStorageChange)
})
</script>

<template>
  <button
    class="visibility-toggle"
    type="button"
    role="switch"
    :aria-checked="visible"
    :aria-label="`웹페이지 커밋고치 ${visible ? '숨기기' : '보이기'}`"
    :disabled="busy"
    @click="toggleVisibility"
  >
    <span aria-hidden="true">{{ visible ? '🌱' : '💤' }}</span>
    <span class="tiny">웹페이지 커밋고치</span>
    <span class="switch-track" aria-hidden="true">
      <span class="switch-thumb"></span>
    </span>
  </button>
</template>

<style scoped>
.visibility-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  border: 1px solid var(--surface-edge);
  border-radius: var(--r-pill);
  background: var(--surface);
  color: var(--ink);
  cursor: pointer;
}
.visibility-toggle:disabled { cursor: wait; opacity: .7; }
.switch-track {
  position: relative;
  width: 30px;
  height: 17px;
  border-radius: var(--r-pill);
  background: var(--ink-faint);
  transition: background .15s ease;
}
.switch-thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 13px;
  height: 13px;
  border-radius: 50%;
  background: var(--surface);
  box-shadow: 0 1px 2px var(--shadow-hard);
  transition: transform .15s ease;
}
.visibility-toggle[aria-checked='true'] .switch-track { background: var(--primary); }
.visibility-toggle[aria-checked='true'] .switch-thumb { transform: translateX(13px); }
</style>
