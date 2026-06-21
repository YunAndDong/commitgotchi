<script setup>
/*
 * CgState — FE-10 공통 상태 블록 (로딩 / 대기 / Fallback).
 *
 * 퀴즈·일일 레포트·이미지 등 모든 표면에서 동일한 표현·톤으로 재사용한다.
 * 색만으로 정보를 전달하지 않도록 아이콘과 카피를 항상 함께 노출하고,
 * 보조기술이 상태 변화를 읽도록 role="status" + aria-live 를 둔다.
 *
 * tone:
 *   loading  — 즉시 처리 중(흐름 B), 스피너.
 *   waiting  — 자정 배치 대기(흐름 A), 모래시계.
 *   fallback — AI 실패. 친화적 카피("AI가 잠깐 쉬는 중…"), "Error" 노출 금지.
 */
import { computed } from 'vue'
import { TONE_ICON } from '../constants/aiStates.js'

const props = defineProps({
  tone: { type: String, default: 'loading' }, // loading | waiting | fallback
  message: { type: String, required: true },
  title: { type: String, default: '' },
  inline: { type: Boolean, default: false },  // 카드 내부 한 줄용(작게)
})

const icon = computed(() => TONE_ICON[props.tone] || TONE_ICON.loading)
const isLoading = computed(() => props.tone === 'loading')
</script>

<template>
  <div class="cg-state" :class="[`cg-state--${tone}`, { 'cg-state--inline': inline }]"
       role="status" aria-live="polite">
    <span v-if="isLoading" class="cg-state__spin" aria-hidden="true" />
    <span v-else class="cg-state__icon" aria-hidden="true">{{ icon }}</span>
    <div class="cg-state__text">
      <strong v-if="title" class="cg-state__title">{{ title }}</strong>
      <p class="cg-state__msg">{{ message }}</p>
      <div v-if="$slots.default" class="cg-state__actions">
        <slot />
      </div>
    </div>
  </div>
</template>

<style scoped>
.cg-state {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: var(--sp-3); text-align: center; padding: var(--sp-5);
  border-radius: var(--r); border: 2px dashed var(--surface-edge); background: var(--surface-2);
}
.cg-state--inline {
  flex-direction: row; gap: var(--sp-2); padding: 10px 14px; text-align: left;
  border-style: solid;
}
.cg-state--fallback { border-color: var(--badge-warn-edge); background: var(--badge-warn-bg); }
.cg-state--fallback .cg-state__msg { color: var(--badge-warn-fg); }
.cg-state__icon { font-size: 34px; line-height: 1; }
.cg-state--inline .cg-state__icon { font-size: 20px; }
.cg-state__title { font-family: var(--font-head); display: block; }
.cg-state__msg { color: var(--ink-soft, var(--ink)); line-height: 1.6; }
.cg-state--inline .cg-state__msg { font-size: 13px; }
.cg-state__actions { margin-top: var(--sp-2); display: flex; gap: var(--sp-2); justify-content: center; flex-wrap: wrap; }

/* loading spinner — bob/스핀, 색만이 아닌 모션으로 진행 표현 */
.cg-state__spin {
  width: 26px; height: 26px; border-radius: 50%;
  border: 3px solid var(--surface-edge); border-top-color: var(--primary-d);
  animation: cg-spin .8s linear infinite;
}
.cg-state--inline .cg-state__spin { width: 18px; height: 18px; border-width: 2.5px; }
@keyframes cg-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) {
  .cg-state__spin { animation-duration: 2s; }
}
</style>
