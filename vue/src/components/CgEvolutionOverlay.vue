<script setup>
import { computed, onBeforeUnmount, onMounted } from 'vue'
import CgConfetti from './CgConfetti.vue'
import CgSprite from './CgSprite.vue'

const props = defineProps({
  evolution: { type: Object, required: true },
})
const emit = defineEmits(['close'])

const score = computed(() => Number(props.evolution?.score) || 0)

function close() {
  emit('close')
}

function onKeydown(event) {
  if (event.key === 'Escape') close()
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="evo" role="dialog" aria-modal="true" aria-labelledby="evo-title" @click.self="close">
    <CgConfetti :count="96" />
    <div class="evo__flash" aria-hidden="true" />
    <div class="evo__alarm evo__alarm--top" aria-hidden="true" />
    <div class="evo__alarm evo__alarm--bottom" aria-hidden="true" />

    <div class="evo__stack">
      <section class="evo__panel cg-card col">
        <div class="evo__status row between">
          <span class="evo__label mono">LIMIT BREAK</span>
          <span class="evo__signal mono">1000+</span>
        </div>
        <div class="evo__meter" aria-hidden="true"><span /></div>

        <div class="evo__stage" aria-hidden="true">
          <span class="evo__ring evo__ring--outer" />
          <span class="evo__ring evo__ring--inner" />
          <span class="evo__core" />
          <span
            v-for="spark in 12"
            :key="spark"
            class="evo__spark"
            :style="{ '--spark-index': spark - 1 }"
          />
          <div class="evo__sprite">
            <CgSprite
              :size="188"
              :emotion="evolution.emotion || 'joy'"
              evolved
              :sprite-sheet-url="evolution.spriteSheetUrl"
              :sprite-meta="evolution.spriteMeta"
              :image-status="evolution.imageStatus"
            />
          </div>
        </div>

        <div class="evo__copy col center">
          <h2 id="evo-title" class="evo__title">!!! 진화 임계치 돌파 !!!</h2>
          <p class="evo__score mono">능력치 총합 {{ score.toLocaleString() }}</p>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.evo {
  position: fixed;
  inset: 0;
  z-index: 90;
  display: grid;
  place-items: center;
  padding: var(--sp-5) var(--sp-4);
  background:
    radial-gradient(circle at 50% 45%, color-mix(in srgb, var(--fire) 22%, transparent) 0 16%, transparent 38%),
    linear-gradient(180deg, color-mix(in srgb, var(--overlay) 94%, transparent), var(--overlay)),
    repeating-linear-gradient(
      0deg,
      color-mix(in srgb, var(--fire) 17%, transparent) 0 2px,
      transparent 2px 7px
    );
  overflow: hidden;
}
.evo::before,
.evo::after {
  content: "";
  position: absolute;
  inset: -12%;
  pointer-events: none;
}
.evo::before {
  background:
    repeating-linear-gradient(
      90deg,
      transparent 0 20px,
      color-mix(in srgb, var(--gold) 14%, transparent) 20px 24px
    );
  opacity: .65;
  mix-blend-mode: screen;
  animation: evo-scan .36s linear infinite;
}
.evo::after {
  background: radial-gradient(circle, transparent 0 42%, color-mix(in srgb, var(--fire) 36%, transparent) 64%, transparent 78%);
  opacity: .7;
  animation: evo-warning-pulse .62s steps(2, end) infinite;
}
.evo__flash {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: color-mix(in srgb, var(--fire) 32%, transparent);
  animation: evo-flash .8s ease-out both;
}
.evo__alarm {
  position: absolute;
  left: 0;
  right: 0;
  height: 22px;
  pointer-events: none;
  background:
    repeating-linear-gradient(
      90deg,
      color-mix(in srgb, var(--fire) 88%, #000) 0 34px,
      color-mix(in srgb, var(--gold) 86%, #fff) 34px 68px
    );
  border-block: 2px solid color-mix(in srgb, var(--screen) 58%, transparent);
  opacity: .86;
  animation: evo-alarm-slide .42s linear infinite;
}
.evo__alarm--top { top: 0; }
.evo__alarm--bottom {
  bottom: 0;
  animation-direction: reverse;
}
.evo__stack {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--sp-3);
  width: min(100%, 500px);
  animation: evo-stack-enter .72s cubic-bezier(.2,.8,.2,1) both;
}
.evo__panel {
  position: relative;
  width: 100%;
  min-height: 430px;
  align-items: center;
  justify-content: flex-start;
  gap: var(--sp-3);
  padding: var(--sp-4);
  background: var(--popup-bg);
  border-color: var(--fire);
  box-shadow: 8px 8px 0 var(--shadow-hard);
  text-align: center;
  overflow: hidden;
  animation: evo-panel-shake .92s steps(2, end) 2;
}
.evo__panel::before {
  content: "";
  position: absolute;
  inset: 0;
  pointer-events: none;
  border: 3px solid color-mix(in srgb, var(--gold) 75%, transparent);
  opacity: .72;
  animation: evo-panel-border .54s steps(2, end) infinite;
}
.evo__status {
  position: relative;
  z-index: 1;
  align-self: stretch;
  gap: var(--sp-2);
}
.evo__label {
  color: var(--fire);
  font-size: 12px;
  letter-spacing: 0;
}
.evo__signal {
  color: var(--gold);
  font-size: 12px;
  animation: evo-blink .42s steps(2, end) infinite;
}
.evo__meter {
  position: relative;
  z-index: 1;
  align-self: stretch;
  height: 12px;
  border: 2px solid var(--surface-edge);
  background: var(--screen);
  overflow: hidden;
}
.evo__meter span {
  display: block;
  height: 100%;
  width: 100%;
  background: linear-gradient(90deg, var(--primary), var(--gold), var(--fire));
  transform-origin: left center;
  animation: evo-meter .72s cubic-bezier(.2,.8,.2,1) both, evo-meter-panic .18s steps(2, end) .72s infinite;
}
.evo__stage {
  position: relative;
  display: grid;
  place-items: center;
  width: min(100%, 274px);
  aspect-ratio: 1;
  isolation: isolate;
  animation: evo-stage-quake .2s steps(2, end) 8;
}
.evo__stage::before,
.evo__stage::after {
  content: "";
  position: absolute;
  inset: 18%;
  border: 3px solid color-mix(in srgb, var(--gold) 76%, var(--screen));
  transform: rotate(45deg);
  animation: evo-diamond .95s cubic-bezier(.2,.8,.2,1) both, evo-diamond-spin 1.1s linear .95s infinite;
}
.evo__stage::after {
  inset: 27%;
  border-color: color-mix(in srgb, var(--fire) 72%, var(--screen));
  animation-delay: .14s;
}
.evo__ring {
  position: absolute;
  border: 3px solid var(--accent2);
  border-radius: 50%;
  opacity: .78;
  animation: evo-ring .74s ease-out infinite;
}
.evo__ring--outer {
  width: 86%;
  height: 86%;
}
.evo__ring--inner {
  width: 62%;
  height: 62%;
  border-color: var(--primary);
  animation-delay: .18s;
}
.evo__core {
  position: absolute;
  width: 42%;
  height: 42%;
  border-radius: 50%;
  background: radial-gradient(circle, color-mix(in srgb, #fff 88%, var(--gold)) 0 8%, color-mix(in srgb, var(--gold) 78%, transparent) 18%, transparent 70%);
  filter: blur(1px);
  opacity: .9;
  animation: evo-core .46s steps(2, end) infinite;
}
.evo__spark {
  --spark-angle: calc(var(--spark-index) * 30deg);
  position: absolute;
  left: 50%;
  top: 50%;
  width: 10px;
  height: 10px;
  margin: -5px 0 0 -5px;
  background: var(--gold);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--screen) 62%, transparent);
  transform: rotate(var(--spark-angle)) translateY(-118px) rotate(45deg);
  animation: evo-spark .86s ease-out infinite;
  animation-delay: calc(var(--spark-index) * .032s);
}
.evo__spark:nth-child(3n) { background: var(--primary); }
.evo__spark:nth-child(4n) { background: var(--accent); }
.evo__sprite {
  position: relative;
  z-index: 1;
  animation: evo-sprite 1.05s cubic-bezier(.2,.8,.2,1) both, evo-sprite-power .52s ease-in-out 1.05s infinite;
}
.evo__copy {
  position: relative;
  z-index: 1;
  gap: var(--sp-2);
}
.evo__title {
  max-width: 100%;
  font-size: 24px;
  white-space: nowrap;
}
.evo__score {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 34px;
  padding: 5px 12px;
  border: 2px solid var(--surface-edge);
  border-radius: var(--r-pill);
  background: var(--surface-2);
  color: var(--ink-soft);
  font-size: 13px;
}
@keyframes evo-flash {
  0% { opacity: 0; }
  22% { opacity: .95; }
  100% { opacity: 0; }
}
@keyframes evo-scan {
  to { transform: translateX(24px); }
}
@keyframes evo-warning-pulse {
  0%, 100% { opacity: .12; }
  50% { opacity: .78; }
}
@keyframes evo-alarm-slide {
  to { background-position-x: 68px; }
}
@keyframes evo-stack-enter {
  0% { opacity: 0; transform: translateY(18px) scale(.96); }
  100% { opacity: 1; transform: translateY(0) scale(1); }
}
@keyframes evo-panel-shake {
  0%, 100% { transform: translate(0, 0); }
  20% { transform: translate(-3px, 2px); }
  40% { transform: translate(3px, -2px); }
  60% { transform: translate(-2px, -1px); }
  80% { transform: translate(2px, 1px); }
}
@keyframes evo-panel-border {
  0%, 100% { opacity: .18; }
  50% { opacity: .92; }
}
@keyframes evo-blink {
  0%, 100% { opacity: .25; }
  50% { opacity: 1; }
}
@keyframes evo-meter {
  0% { transform: scaleX(.18); filter: brightness(1); }
  72% { transform: scaleX(1); filter: brightness(1.8); }
  100% { transform: scaleX(1); filter: brightness(1.15); }
}
@keyframes evo-meter-panic {
  0%, 100% { filter: brightness(1.1); }
  50% { filter: brightness(2); }
}
@keyframes evo-stage-quake {
  0%, 100% { transform: translate(0, 0) rotate(0deg); }
  50% { transform: translate(2px, -2px) rotate(-1deg); }
}
@keyframes evo-diamond {
  0% { opacity: 0; transform: rotate(45deg) scale(.28); }
  42% { opacity: .9; transform: rotate(45deg) scale(1.08); }
  100% { opacity: .45; transform: rotate(45deg) scale(1); }
}
@keyframes evo-diamond-spin {
  to { transform: rotate(405deg) scale(1); }
}
@keyframes evo-ring {
  0% { opacity: 0; transform: scale(.58); }
  38% { opacity: .84; }
  100% { opacity: 0; transform: scale(1.08); }
}
@keyframes evo-core {
  0%, 100% { opacity: .45; transform: scale(.92); }
  50% { opacity: 1; transform: scale(1.14); }
}
@keyframes evo-spark {
  0% { opacity: 0; transform: rotate(var(--spark-angle)) translateY(-32px) rotate(45deg) scale(.5); }
  34% { opacity: 1; }
  100% { opacity: 0; transform: rotate(var(--spark-angle)) translateY(-142px) rotate(225deg) scale(1); }
}
@keyframes evo-sprite {
  0% { opacity: 0; filter: brightness(2.1); transform: translateY(18px) scale(.54); }
  28% { opacity: 1; filter: brightness(2.6); transform: translateY(-10px) scale(1.18); }
  62% { filter: brightness(1.25); transform: translateY(0) scale(.97); }
  100% { filter: brightness(1); transform: translateY(0) scale(1); }
}
@keyframes evo-sprite-power {
  0%, 100% { filter: brightness(1); transform: translateY(0) scale(1); }
  50% { filter: brightness(1.32); transform: translateY(-4px) scale(1.03); }
}
@media (max-width: 520px) {
  .evo__panel {
    min-height: min(430px, calc(100vh - 96px));
    padding: var(--sp-3);
  }
  .evo__stage {
    width: min(100%, 240px);
  }
  .evo__title {
    font-size: 20px;
  }
}

@media (max-width: 360px) {
  .evo__title {
    font-size: 18px;
  }
}
</style>
