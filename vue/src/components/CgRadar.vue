<script setup>
/*
 * cg-radar — 5각형 능력치 레이더 (DESIGN.md).
 * Axis order (fixed): 알고리즘 · CS · DB · 네트워크 · 프레임워크.
 */
import { computed } from 'vue'
import { STAT_KEYS, STAT_LABELS } from '../stores/game.js'

const props = defineProps({
  stats: { type: Object, default: () => ({}) },   // { algo, cs, db, net, fw }
  max: { type: Number, default: 0 },         // 0 → auto
  size: { type: Number, default: 240 },
})

const cx = computed(() => props.size / 2)
const cy = computed(() => props.size / 2)
const radius = computed(() => props.size / 2 - 34)
const labelBleed = computed(() => Math.max(20, Math.round(props.size * 0.09)))
const svgWidth = computed(() => props.size + labelBleed.value * 2)
const viewBox = computed(() => `${-labelBleed.value} 0 ${svgWidth.value} ${props.size}`)
const axisMax = computed(() => {
  if (props.max > 0) return props.max
  const m = Math.max(...STAT_KEYS.map(k => stat(k)), 50)
  return Math.ceil(m / 50) * 50
})

function point(i, r) {
  const ang = (-90 + i * 72) * (Math.PI / 180)
  return [cx.value + r * Math.cos(ang), cy.value + r * Math.sin(ang)]
}
const rings = [0.25, 0.5, 0.75, 1]
function stat(k) { return Number.isFinite(props.stats?.[k]) ? props.stats[k] : 0 }
function ringPath(scale) {
  return STAT_KEYS.map((_, i) => point(i, radius.value * scale).join(',')).join(' ')
}
const dataPath = computed(() =>
  STAT_KEYS.map((k, i) => point(i, radius.value * (stat(k) / axisMax.value)).join(',')).join(' ')
)
function labelPos(i) { return point(i, radius.value + 20) }
</script>

<template>
  <svg :width="svgWidth" :height="size" :viewBox="viewBox" class="radar">
    <polygon v-for="(s, idx) in rings" :key="idx" :points="ringPath(s)"
             fill="none" stroke="var(--surface-edge)" stroke-width="1.5" />
    <line v-for="(k, i) in STAT_KEYS" :key="'ax' + i"
          :x1="cx" :y1="cy" :x2="point(i, radius)[0]" :y2="point(i, radius)[1]"
          stroke="var(--surface-edge)" stroke-width="1.5" />
    <polygon :points="dataPath" fill="var(--primary)" fill-opacity="0.32"
             stroke="var(--primary-d)" stroke-width="2.5" />
    <g v-for="(k, i) in STAT_KEYS" :key="'lb' + i">
      <text :x="labelPos(i)[0]" :y="labelPos(i)[1]" text-anchor="middle" dominant-baseline="middle"
            class="radar__label">{{ STAT_LABELS[k] }}</text>
      <text :x="labelPos(i)[0]" :y="labelPos(i)[1] + 13" text-anchor="middle" dominant-baseline="middle"
            class="radar__val mono">{{ stat(k) }}</text>
    </g>
  </svg>
</template>

<style scoped>
.radar { display: block; overflow: visible; }
.radar__label { font-family: var(--font-head); font-size: 12px; fill: var(--ink-soft); }
.radar__val { font-size: 11px; fill: var(--ink); }
</style>
