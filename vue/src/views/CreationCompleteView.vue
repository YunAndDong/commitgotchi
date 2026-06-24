<script setup>
/*
 * Screen #11 — 캐릭터 생성 완료.
 * 이미지 PENDING 동안 로딩 → READY 시 완성 캐릭터가 컨페티·불꽃과 함께 등장 (Flow 2 climax).
 */
import { computed, ref, watch } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import { gameState } from '../stores/game.js'
import { FALLBACK } from '../constants/aiStates.js'
import CgSprite from '../components/CgSprite.vue'
import CgConfetti from '../components/CgConfetti.vue'
import CgState from '../components/CgState.vue'
import CgCreationSpinner from '../components/CgCreationSpinner.vue'

const route = useRoute()
const char = computed(() => gameState.characters.find(c => String(c.id) === String(route.params.id)) || null)
const ready = computed(() => char.value?.imageStatus === 'READY')
// FE-10 AC4: 이미지 실패해도 기본 모습으로 생성은 성공 — 흐름을 막지 않는다.
const failed = computed(() => ['FALLBACK', 'FAILED'].includes(char.value?.imageStatus))
const usable = computed(() => ready.value || failed.value)
const celebrate = ref(false)

watch(usable, (v) => { if (v) celebrate.value = true }, { immediate: true })
</script>

<template>
  <div class="complete center col" v-if="char">
    <CgConfetti v-if="celebrate" :count="90" />

    <CgCreationSpinner v-if="!usable" :name="char.name" :keyword="char.keyword" />

    <div v-else class="cg-card stage">
      <div class="stage__art" :class="{ pop: usable }">
        <CgSprite :size="240" :emotion="char.emotion" :evolved="char.isEvolved"
                  :sprite-sheet-url="char.spriteSheetUrl" :sprite-meta="char.spriteMeta"
                  :pending="!usable" :failed="failed" />
      </div>

      <div class="stage__copy col">
        <span class="tiny muted">{{ ready ? '생성 완료!' : failed ? '생성 완료 (기본 모습)' : '캐릭터를 빚는 중…' }}</span>
        <h1 class="title">🎉 {{ char.name }} 탄생!</h1>
        <p class="summary">
          키워드: <span class="mono">{{ char.keyword || '기본' }}</span><br />
          5개 능력치는 <strong>0</strong>에서 시작해요. 오늘의 학습으로 키워봐요.
        </p>

        <CgState v-if="failed" tone="fallback" inline :message="FALLBACK.image" />

        <div class="actions row">
          <RouterLink :to="usable ? '/report' : ''" class="cg-btn cg-btn--primary" :class="{ disabled: !usable }"
                      :aria-disabled="!usable" :tabindex="usable ? 0 : -1">첫 리포트 쓰러 가기</RouterLink>
          <RouterLink to="/select" class="cg-btn">커밋고치 선택</RouterLink>
        </div>
      </div>
    </div>
  </div>

  <div v-else class="cg-card center col">
    <p>캐릭터를 찾을 수 없어요.</p>
    <RouterLink to="/select" class="cg-btn">커밋고치 선택</RouterLink>
  </div>
</template>

<style scoped>
.complete { min-height: 70vh; width: 100%; }
.stage {
  display: grid;
  grid-template-columns: minmax(240px, .9fr) minmax(280px, 1fr);
  align-items: center;
  gap: var(--sp-5);
  width: 100%;
  max-width: 780px;
  padding: var(--sp-6);
}
.stage__art {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 280px;
}
.stage__copy {
  align-items: flex-start;
  gap: var(--sp-3);
  min-width: 0;
}
.pop { animation: pop .6s cubic-bezier(.2,.9,.3,1.2); }
.title {
  font-family: var(--font-display);
  font-size: 26px;
  line-height: 1.3;
  overflow-wrap: anywhere;
}
.summary {
  color: var(--ink-soft);
  line-height: 1.75;
}
.actions {
  gap: var(--sp-2);
  flex-wrap: wrap;
  margin-top: var(--sp-1);
}
.disabled { pointer-events: none; opacity: .5; }

@media (max-width: 720px) {
  .stage {
    grid-template-columns: 1fr;
    gap: var(--sp-4);
    padding: var(--sp-5);
    text-align: center;
  }
  .stage__art { min-height: 230px; }
  .stage__copy { align-items: center; }
  .actions { justify-content: center; }
}

@media (max-width: 430px) {
  .actions,
  .actions .cg-btn { width: 100%; }
}
</style>
