<script setup>
// cg-confetti / cg-firework — one-shot celebration (생성 완료 · 진화). Reduced-motion safe via base.css.
import { computed } from 'vue'
const props = defineProps({ count: { type: Number, default: 70 } })
const colors = ['#54b878', '#e08658', '#eab43f', '#5f97d6', '#e0705a', '#fffdf5']
const pieces = computed(() =>
  Array.from({ length: props.count }, (_, i) => ({
    left: Math.random() * 100,
    delay: Math.random() * 0.6,
    dur: 1.6 + Math.random() * 1.4,
    color: colors[i % colors.length],
    size: 6 + Math.random() * 8,
    rot: Math.random() * 360,
  }))
)
</script>

<template>
  <div class="confetti" aria-hidden="true">
    <span v-for="(p, i) in pieces" :key="i" class="confetti__p" :style="{
      left: p.left + '%',
      background: p.color,
      width: p.size + 'px',
      height: p.size + 'px',
      transform: 'rotate(' + p.rot + 'deg)',
      animationDelay: p.delay + 's',
      animationDuration: p.dur + 's',
    }" />
  </div>
</template>

<style scoped>
.confetti { position: fixed; inset: 0; pointer-events: none; overflow: hidden; z-index: 50; }
.confetti__p {
  position: absolute; top: -16px; border-radius: 2px;
  animation-name: confetti-fall; animation-timing-function: ease-in; animation-fill-mode: forwards;
}
</style>
