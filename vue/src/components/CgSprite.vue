<script setup>
/*
 * cg-spr — pixel character sprite (SVG stand-in for the AI-generated image).
 * DESIGN.md cg-spr: (is_evolved, emotion) frame selection, baby/mature stage,
 * grey (도감 미획득), bob idle motion, PENDING placeholder, ground shadow.
 * Real product renders a 6-frame spritesheet; here we draw an equivalent
 * pixel sprout so the whole UX works before assets/AI image exist.
 */
import { computed } from 'vue'

const props = defineProps({
  emotion: { type: String, default: 'joy' },   // joy | sad | angry
  evolved: { type: Boolean, default: false },
  grey: { type: Boolean, default: false },
  bob: { type: Boolean, default: true },
  pending: { type: Boolean, default: false },
  // FE-10 흐름 C: 이미지 생성 실패 시 깨진 이미지 대신 기본 스프라이트(Fallback)를 유지한다.
  failed: { type: Boolean, default: false },
  imageStatus: { type: String, default: '' },
  size: { type: Number, default: 160 },
})

const body = computed(() => props.grey ? '#b8b2a6' : '#7FC86A')
const bodyDark = computed(() => props.grey ? '#857f73' : '#2F5E34')
const leaf = computed(() => props.grey ? '#cfc9bd' : '#54A857')
const cheek = computed(() => props.grey ? 'transparent' : '#f3a6a0')
const emotion = computed(() => ['joy', 'sad', 'angry'].includes(props.emotion) ? props.emotion : 'joy')
const pending = computed(() => props.pending || props.imageStatus === 'PENDING')
const failed = computed(() => props.failed || props.imageStatus === 'FAILED')
</script>

<template>
  <div class="spr" :style="{ width: size + 'px' }">
    <!-- 생성 대기(흐름 C) — failed 일 때는 placeholder 대신 기본 스프라이트로 폴백 -->
    <div v-if="pending && !failed" class="spr__pending" :style="{ height: size + 'px' }">
      <div class="spr__egg">🥚</div>
      <span class="tiny muted">이미지 생성 중…</span>
    </div>

    <svg v-else :class="['spr__svg', { bobbing: bob }]" :width="size" :height="size"
         viewBox="0 0 64 64" shape-rendering="crispEdges" aria-hidden="true">
      <!-- evolved aura -->
      <circle v-if="evolved && !grey" cx="32" cy="34" r="26" :fill="'#fff7d6'" opacity="0.5" />

      <!-- sprout stem + leaves on head -->
      <rect x="30" y="8" width="4" height="9" :fill="bodyDark" />
      <ellipse cx="26" cy="9" rx="7" ry="4" :fill="leaf" />
      <ellipse cx="39" cy="7" rx="8" ry="4.5" :fill="leaf" />
      <ellipse v-if="evolved" cx="32" cy="3" rx="5" ry="3.5" fill="#eab43f" />

      <!-- body -->
      <circle cx="32" cy="36" :r="evolved ? 22 : 18" :fill="body" :stroke="bodyDark" stroke-width="2.5" />

      <!-- cheeks -->
      <circle cx="22" cy="40" r="3" :fill="cheek" />
      <circle cx="42" cy="40" r="3" :fill="cheek" />

      <!-- eyes by emotion -->
      <template v-if="emotion === 'joy'">
        <path d="M23 33 q3 -4 6 0" :stroke="bodyDark" stroke-width="2.5" fill="none" stroke-linecap="round" />
        <path d="M35 33 q3 -4 6 0" :stroke="bodyDark" stroke-width="2.5" fill="none" stroke-linecap="round" />
      </template>
      <template v-else-if="emotion === 'sad'">
        <circle cx="26" cy="34" r="2.4" :fill="bodyDark" />
        <circle cx="38" cy="34" r="2.4" :fill="bodyDark" />
        <path d="M40 39 q2 2 3 5" stroke="#5f97d6" stroke-width="2" fill="none" stroke-linecap="round" />
      </template>
      <template v-else>
        <line x1="23" y1="31" x2="29" y2="34" :stroke="bodyDark" stroke-width="2.5" stroke-linecap="round" />
        <line x1="41" y1="31" x2="35" y2="34" :stroke="bodyDark" stroke-width="2.5" stroke-linecap="round" />
        <circle cx="26" cy="35" r="2" :fill="bodyDark" />
        <circle cx="38" cy="35" r="2" :fill="bodyDark" />
      </template>

      <!-- mouth by emotion -->
      <path v-if="emotion === 'joy'" d="M26 42 q6 6 12 0" :stroke="bodyDark" stroke-width="2.5" fill="none" stroke-linecap="round" />
      <path v-else-if="emotion === 'sad'" d="M27 45 q5 -4 10 0" :stroke="bodyDark" stroke-width="2.5" fill="none" stroke-linecap="round" />
      <path v-else d="M27 44 l10 0" :stroke="bodyDark" stroke-width="2.5" fill="none" stroke-linecap="round" />

      <!-- lock for unowned codex -->
      <text v-if="grey" x="32" y="40" text-anchor="middle" font-size="14" fill="#6b6459">🔒</text>
    </svg>

    <div class="spr__shadow" :style="{ width: size * 0.55 + 'px' }" />
    <span v-if="failed" class="spr__fallback tiny">기본 모습</span>
  </div>
</template>

<style scoped>
.spr { display: inline-flex; flex-direction: column; align-items: center; }
.spr__svg { display: block; }
.spr__svg.bobbing { animation: bob 1.8s ease-in-out infinite; }
.spr__shadow {
  height: 9px; margin-top: -4px; border-radius: 50%;
  background: var(--shadow-hard); filter: blur(1px);
}
.spr__pending {
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px;
  width: 100%;
}
.spr__egg { font-size: 44px; animation: bob 1.4s ease-in-out infinite; }
.spr__fallback {
  margin-top: 6px; padding: 2px 8px; border-radius: var(--r-pill);
  background: var(--badge-warn-bg); color: var(--badge-warn-fg);
  border: 1.5px solid var(--badge-warn-edge); font-family: var(--font-head);
}
</style>
