<script setup>
import { computed } from 'vue'

const props = defineProps({
  name: { type: String, default: '' },
  keyword: { type: String, default: '' },
})

const displayName = computed(() => props.name?.trim() || '새 커밋고치')
const keywordText = computed(() => props.keyword?.trim() || '기본 설계')
</script>

<template>
  <section class="cg-forge" role="status" aria-live="polite" aria-busy="true">
    <div class="cg-forge__machine">
      <div class="cg-forge__viewport" aria-hidden="true">
        <div class="cg-forge__orbit">
          <span class="cg-forge__spark cg-forge__spark--one" />
          <span class="cg-forge__spark cg-forge__spark--two" />
          <span class="cg-forge__spark cg-forge__spark--three" />
        </div>

        <div class="cg-forge__core">
          <span class="cg-forge__stem" />
          <span class="cg-forge__leaf cg-forge__leaf--left" />
          <span class="cg-forge__leaf cg-forge__leaf--right" />
          <div class="cg-forge__body">
            <span class="cg-forge__eye cg-forge__eye--left" />
            <span class="cg-forge__eye cg-forge__eye--right" />
            <span class="cg-forge__mouth" />
          </div>
        </div>

        <div class="cg-forge__graph">
          <span />
          <span />
          <span />
        </div>
      </div>

      <div class="cg-forge__copy">
        <span class="tiny muted">커밋고치 공방 가동 중</span>
        <h2>{{ displayName }} 만드는 중</h2>
        <p>디자인 키워드를 픽셀로 다듬고 있어요. 이미지가 도착하면 자동으로 완성 화면이 열려요.</p>
        <span class="cg-forge__keyword mono">{{ keywordText }}</span>
      </div>

      <div class="cg-forge__meter" aria-hidden="true">
        <span />
      </div>
    </div>
  </section>
</template>

<style scoped>
.cg-forge {
  width: min(100%, 520px);
  min-height: 420px;
  display: grid;
  place-items: center;
  padding: var(--sp-5);
  background: var(--screen);
  border: 2px solid var(--surface-edge);
  border-radius: var(--r);
  box-shadow: var(--shadow-card);
}

.cg-forge__machine {
  width: 100%;
  display: grid;
  justify-items: center;
  gap: var(--sp-4);
}

.cg-forge__viewport {
  position: relative;
  width: min(250px, 72vw);
  aspect-ratio: 1;
  display: grid;
  place-items: center;
  border: 2px solid var(--surface-edge);
  border-radius: var(--r);
  background:
    linear-gradient(90deg, color-mix(in srgb, var(--track) 44%, transparent) 1px, transparent 1px),
    linear-gradient(0deg, color-mix(in srgb, var(--track) 44%, transparent) 1px, transparent 1px),
    linear-gradient(180deg, var(--sky-top), var(--screen));
  background-size: 24px 24px, 24px 24px, 100% 100%;
  overflow: hidden;
}

.cg-forge__orbit {
  position: absolute;
  inset: 28px;
  border: 3px dashed var(--accent);
  border-radius: 50%;
  animation: forge-spin 1.35s linear infinite;
}

.cg-forge__spark {
  position: absolute;
  width: 12px;
  height: 12px;
  border: 2px solid var(--popup-edge);
  border-radius: 3px;
  background: var(--gold);
  box-shadow: 2px 2px 0 var(--shadow-hard);
}

.cg-forge__spark--one { top: -7px; left: 50%; transform: translateX(-50%); }
.cg-forge__spark--two { right: 9px; bottom: 25px; background: var(--primary); }
.cg-forge__spark--three { left: 9px; bottom: 25px; background: var(--sad); }

.cg-forge__core {
  position: relative;
  z-index: 1;
  width: 118px;
  height: 142px;
  animation: forge-breathe 1.4s ease-in-out infinite;
}

.cg-forge__stem {
  position: absolute;
  top: 10px;
  left: 57px;
  width: 8px;
  height: 32px;
  background: var(--primary-d);
  border: 2px solid var(--popup-edge);
}

.cg-forge__leaf {
  position: absolute;
  top: 0;
  width: 42px;
  height: 24px;
  border: 2px solid var(--popup-edge);
  background: var(--primary);
}

.cg-forge__leaf--left {
  left: 20px;
  border-radius: 28px 4px 28px 4px;
  transform: rotate(-16deg);
}

.cg-forge__leaf--right {
  right: 12px;
  border-radius: 4px 28px 4px 28px;
  transform: rotate(18deg);
}

.cg-forge__body {
  position: absolute;
  left: 50%;
  bottom: 0;
  width: 104px;
  height: 104px;
  transform: translateX(-50%);
  border: 3px solid var(--popup-edge);
  border-radius: 46% 46% 42% 42%;
  background: var(--primary);
  box-shadow: inset -12px -12px 0 color-mix(in srgb, var(--primary-d) 52%, transparent), 4px 5px 0 var(--shadow-hard);
}

.cg-forge__eye {
  position: absolute;
  top: 43px;
  width: 10px;
  height: 10px;
  background: var(--popup-edge);
  border-radius: 50%;
  animation: forge-blink 2.2s steps(1, end) infinite;
}

.cg-forge__eye--left { left: 30px; }
.cg-forge__eye--right { right: 30px; }

.cg-forge__mouth {
  position: absolute;
  left: 50%;
  top: 61px;
  width: 28px;
  height: 14px;
  transform: translateX(-50%);
  border-bottom: 3px solid var(--popup-edge);
  border-radius: 0 0 28px 28px;
}

.cg-forge__graph {
  position: absolute;
  left: 22px;
  right: 22px;
  bottom: 24px;
  height: 22px;
}

.cg-forge__graph::before {
  content: "";
  position: absolute;
  left: 16px;
  right: 16px;
  top: 9px;
  height: 3px;
  background: var(--surface-edge);
}

.cg-forge__graph span {
  position: absolute;
  top: 2px;
  width: 18px;
  height: 18px;
  border: 2px solid var(--popup-edge);
  border-radius: 50%;
  background: var(--surface);
  animation: forge-pulse 1.5s ease-in-out infinite;
}

.cg-forge__graph span:nth-child(1) { left: 8px; }
.cg-forge__graph span:nth-child(2) { left: calc(50% - 9px); animation-delay: .18s; background: var(--gold); }
.cg-forge__graph span:nth-child(3) { right: 8px; animation-delay: .36s; background: var(--accent); }

.cg-forge__copy {
  display: grid;
  justify-items: center;
  gap: var(--sp-2);
  text-align: center;
}

.cg-forge__copy h2 {
  max-width: 100%;
  font-family: var(--font-display);
  font-size: 26px;
  line-height: 1.2;
  overflow-wrap: anywhere;
}

.cg-forge__copy p {
  max-width: 370px;
  color: var(--ink-soft);
  font-size: 14px;
}

.cg-forge__keyword {
  max-width: min(100%, 360px);
  padding: 4px 10px;
  border: 1.5px solid var(--surface-edge);
  border-radius: var(--r-pill);
  background: var(--surface-2);
  color: var(--ink-soft);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.cg-forge__meter {
  width: min(100%, 320px);
  height: 12px;
  border: 2px solid var(--surface-edge);
  border-radius: var(--r-gauge);
  background: var(--track);
  overflow: hidden;
}

.cg-forge__meter span {
  display: block;
  width: 42%;
  height: 100%;
  background: linear-gradient(90deg, var(--primary), var(--gold), var(--accent));
  animation: forge-meter 1.2s ease-in-out infinite;
}

@keyframes forge-spin {
  to { transform: rotate(360deg); }
}

@keyframes forge-breathe {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-7px); }
}

@keyframes forge-blink {
  0%, 88%, 100% { transform: scaleY(1); }
  92% { transform: scaleY(.2); }
}

@keyframes forge-pulse {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-5px); }
}

@keyframes forge-meter {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(240%); }
}

@media (max-width: 560px) {
  .cg-forge {
    min-height: 390px;
    padding: var(--sp-4);
  }

  .cg-forge__copy h2 {
    font-size: 22px;
  }
}
</style>
