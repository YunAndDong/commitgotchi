<script setup>
/*
 * Screen #7 — 랭킹.
 * 포디움 TOP3 + 리스트, 내 캐릭터 하이라이트, 육아점수 기준.
 */
import { computed } from 'vue'
import { ranking } from '../stores/game.js'
import CgSprite from '../components/CgSprite.vue'
import CgEmo from '../components/CgEmo.vue'

const rows = computed(() => ranking())
const top3 = computed(() => rows.value.slice(0, 3))
const rest = computed(() => rows.value.slice(3))
const podiumOrder = computed(() => {
  const [a, b, c] = top3.value
  return [b, a, c].filter(Boolean)   // 2 · 1 · 3 visual order
})
</script>

<template>
  <div class="ranking col">
    <h1 class="cg-section-title big">랭킹 · 육아점수</h1>

    <section class="podium">
      <div v-for="r in podiumOrder" :key="r.id"
           class="podium__col" :class="['p' + r.rank, { me: r.me }]">
        <div class="medal">{{ ['🥇','🥈','🥉'][r.rank - 1] }}</div>
        <CgSprite :size="r.rank === 1 ? 96 : 76" :emotion="r.emotion" :evolved="r.isEvolved"
                  :sprite-sheet-url="r.spriteSheetUrl" :sprite-meta="r.spriteMeta" :bob="false" />
        <strong>{{ r.name }}</strong>
        <span class="tiny faint">@{{ r.owner }}</span>
        <CgEmo :emotion="r.emotion" />
        <span class="cg-badge" :class="r.isEvolved ? 'cg-badge--fire' : 'cg-badge--warn'">
          {{ r.isEvolved ? '진화 완료' : '성장 중' }}
        </span>
        <span class="mono score">{{ r.score.toLocaleString() }}</span>
        <div class="block" :style="{ height: (r.rank === 1 ? 84 : r.rank === 2 ? 62 : 46) + 'px' }">#{{ r.rank }}</div>
      </div>
    </section>

    <section class="cg-card col">
      <div v-for="r in rest" :key="r.id" class="lrow row between" :class="{ me: r.me }">
        <div class="row" style="gap:12px">
          <span class="mono rank">#{{ r.rank }}</span>
          <CgSprite :size="34" :emotion="r.emotion" :evolved="r.isEvolved"
                    :sprite-sheet-url="r.spriteSheetUrl" :sprite-meta="r.spriteMeta" :bob="false" />
          <div class="col" style="gap:0">
            <strong>{{ r.name }}<span v-if="r.me" class="cg-badge cg-badge--ok meb">나</span></strong>
            <span class="tiny faint">@{{ r.owner }}</span>
            <div class="row status">
              <CgEmo :emotion="r.emotion" />
              <span class="cg-badge" :class="r.isEvolved ? 'cg-badge--fire' : 'cg-badge--warn'">
                {{ r.isEvolved ? '진화 완료' : '성장 중' }}
              </span>
            </div>
          </div>
        </div>
        <span class="mono">{{ r.score.toLocaleString() }}</span>
      </div>
    </section>
  </div>
</template>

<style scoped>
.ranking { gap: var(--sp-4); max-width: 760px; margin: 0 auto; }
.podium { display: flex; justify-content: center; align-items: flex-end; gap: var(--sp-4); }
.podium__col { display: flex; flex-direction: column; align-items: center; gap: 4px; }
.podium__col.me strong { color: var(--primary-d); }
.medal { font-size: 26px; }
.score { font-size: 14px; }
.block {
  margin-top: 6px; width: 92px; border-radius: var(--r) var(--r) 0 0;
  background: var(--surface-2); border: 2px solid var(--surface-edge); border-bottom: none;
  display: flex; align-items: flex-start; justify-content: center; padding-top: 6px;
  font-family: var(--font-head); color: var(--ink-soft);
}
.p1 .block { background: color-mix(in srgb, var(--gold) 30%, var(--surface)); }
.lrow { padding: 10px 8px; border-bottom: 1.5px solid var(--surface-edge); }
.lrow:last-child { border-bottom: none; }
.lrow.me { background: color-mix(in srgb, var(--primary) 12%, transparent); border-radius: var(--r); }
.rank { width: 36px; color: var(--ink-soft); }
.meb { margin-left: 6px; }
.status { gap: 6px; flex-wrap: wrap; }
</style>
