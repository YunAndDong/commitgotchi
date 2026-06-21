<script setup>
// cg-gauge — 육아점수 진척 게이지 (DESIGN.md). Numeric value also shown as text (a11y).
import { computed } from 'vue'
const props = defineProps({
  value: { type: Number, default: 0 },
  max: { type: Number, default: 1000 },
  label: { type: String, default: '육아점수' },
})
const safeValue = computed(() => Number.isFinite(props.value) ? props.value : 0)
const safeMax = computed(() => Number.isFinite(props.max) && props.max > 0 ? props.max : 1000)
const pct = computed(() => Math.max(0, Math.min(100, (safeValue.value / safeMax.value) * 100)))
</script>

<template>
  <div class="cg-gauge">
    <div class="cg-gauge__meta">
      <span>{{ label }}</span>
      <span class="mono">{{ safeValue.toLocaleString() }} / {{ safeMax.toLocaleString() }}</span>
    </div>
    <div class="cg-gauge__track" role="progressbar" :aria-valuenow="safeValue" :aria-valuemax="safeMax" :aria-label="label">
      <div class="cg-gauge__fill" :style="{ width: pct + '%' }" />
    </div>
  </div>
</template>
