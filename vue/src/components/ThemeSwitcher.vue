<script setup>
// Theme switch (cozy / device / cli) — sets <html data-theme>. Persists choice.
import { ref, onMounted } from 'vue'
const THEMES = [
  { key: 'cozy', label: 'cozy', icon: '🌱' },
  { key: 'device', label: 'device', icon: '🎮' },
  { key: 'cli', label: 'cli', icon: '⌨️' },
]
const current = ref('cozy')
function apply(key) {
  const safe = THEMES.some(t => t.key === key) ? key : 'cozy'
  current.value = safe
  document.documentElement.setAttribute('data-theme', safe)
  try { localStorage.setItem('cg.theme', safe) } catch { /* ignore */ }
}
onMounted(() => {
  let saved = 'cozy'
  try { saved = localStorage.getItem('cg.theme') || 'cozy' } catch { /* ignore */ }
  apply(saved)
})
</script>

<template>
  <div class="theme-switch row" role="group" aria-label="테마 선택">
    <button v-for="t in THEMES" :key="t.key"
            :class="['cg-btn', 'cg-btn--sm', { 'cg-btn--primary': current === t.key }]"
            :aria-pressed="current === t.key" @click="apply(t.key)">
      <span aria-hidden="true">{{ t.icon }}</span><span class="tiny">{{ t.label }}</span>
    </button>
  </div>
</template>

<style scoped>
.theme-switch { gap: 6px; }
</style>
