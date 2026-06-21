<script setup>
// cg-emo — emotion chip: color dot + face + label (never color alone, a11y floor).
import { computed } from 'vue'
const props = defineProps({ emotion: { type: String, default: 'joy' } })
const MAP = {
  joy: { color: 'var(--joy)', face: '😊', label: '기쁨' },
  sad: { color: 'var(--sad)', face: '😢', label: '슬픔' },
  angry: { color: 'var(--angry)', face: '😠', label: '화남' },
}
const e = computed(() => MAP[props.emotion] || MAP.joy)
</script>

<template>
  <span class="cg-emo">
    <span class="cg-emo__dot" :style="{ background: e.color }" />
    <span>{{ e.face }}</span>
    <span>{{ e.label }}</span>
  </span>
</template>
