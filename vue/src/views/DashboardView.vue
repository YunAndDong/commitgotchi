<script setup>
/*
 * Screen #2-2 — 대시보드.
 * Hero character stage (left) + side rail (육아점수 게이지·랭킹·감정·상태 메시지·활동 로그).
 * Empty state when 캐릭터 0개 → 생성 유도 (#State Patterns).
 */
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import {
  activeCharacter, hasCharacter, nurtureScore, ranking, todayReport, activeQuizzes, pendingReport,
  gameState, deliverDailyReport, EVOLVE_THRESHOLD, STAT_KEYS, STAT_LABELS,
} from '../stores/game.js'
import { WAITING, FALLBACK } from '../constants/aiStates.js'
import CgSprite from '../components/CgSprite.vue'
import CgGauge from '../components/CgGauge.vue'
import CgEmo from '../components/CgEmo.vue'
import CgStatTile from '../components/CgStatTile.vue'
import CgState from '../components/CgState.vue'

const c = activeCharacter
const score = computed(() => (c.value ? nurtureScore(c.value) : 0))
const myRank = computed(() => ranking().find(r => r.me))
const quizDone = computed(() => activeQuizzes.value.filter(q => q.scored).length)
const reportStatus = computed(() => gameState.dailyReport.status) // pending | failed | ready
const recentActivity = computed(() => [
  ...gameState.reports.map(r => ({ id: `r${r.id}`, date: r.date, text: `리포트 · ${r.title}` })),
  ...gameState.quizzes.filter(q => q.scored).map(q => ({ id: `q${q.id}`, date: q.date, text: `퀴즈 · ${q.correct ? '충분' : '보완'}` })),
].sort((a, b) => b.date.localeCompare(a.date)).slice(0, 4))
</script>

<template>
  <!-- EMPTY STATE -->
  <section v-if="!hasCharacter || !c" class="empty cg-card center col">
    <CgSprite :size="120" :pending="false" emotion="joy" />
    <h1 class="big">아직 분신이 없어요</h1>
    <p class="muted">{{ hasCharacter ? '학습을 시작할 커밋고치를 먼저 골라주세요.' : '첫 캐릭터를 만들어 오늘의 학습을 먹여보세요.' }}</p>
    <RouterLink :to="hasCharacter ? '/select' : '/create'" class="cg-btn cg-btn--primary">
      {{ hasCharacter ? '커밋고치 선택하기' : '🌱 첫 분신 만들기' }}
    </RouterLink>
  </section>

  <!-- DASHBOARD -->
  <div v-else class="dash">
    <!-- hero stage -->
    <section class="stage cg-screen col center">
      <div class="row between" style="width:100%">
        <h2 class="cg-section-title">{{ c.name }}</h2>
        <CgEmo :emotion="c.emotion" />
      </div>

      <div class="stage__char">
        <CgSprite :size="200" :emotion="c.emotion" :evolved="c.isEvolved"
                  :pending="c.imageStatus === 'PENDING'" :failed="c.imageStatus === 'FAILED'" />
      </div>

      <p class="bubble">“{{ c.message }}”</p>

      <div class="row wrap center" style="gap: var(--sp-2)">
        <RouterLink to="/report" class="cg-btn cg-btn--primary">📓 오늘의 학습 기록하기</RouterLink>
        <RouterLink to="/quiz" class="cg-btn cg-btn--accent">🧩 추천 퀴즈 풀기</RouterLink>
        <RouterLink :to="`/character/${c.id}`" class="cg-btn">캐릭터 상세</RouterLink>
      </div>
    </section>

    <!-- side rail -->
    <aside class="rail col">
      <div class="cg-card col">
        <CgGauge :value="score" :max="EVOLVE_THRESHOLD" label="육아점수" />
        <p class="tiny muted">진화까지 {{ Math.max(0, EVOLVE_THRESHOLD - score).toLocaleString() }}점</p>
        <span v-if="c.isEvolved" class="cg-badge cg-badge--fire">⭐ 진화 완료</span>
      </div>

      <div class="cg-card col">
        <div class="cg-section-title">최근 활동</div>
        <p v-if="!recentActivity.length" class="tiny muted">아직 활동 기록이 없어요.</p>
        <div v-for="item in recentActivity" :key="item.id" class="row between tiny">
          <span>{{ item.text }}</span><span class="faint mono">{{ item.date }}</span>
        </div>
      </div>

      <div class="cg-card col">
        <div class="cg-section-title">능력치</div>
        <div class="tiles">
          <CgStatTile v-for="k in STAT_KEYS" :key="k" :label="STAT_LABELS[k]" :value="c.stats[k]" />
        </div>
      </div>

      <div class="cg-card col">
        <div class="row between">
          <span class="cg-section-title">내 랭킹</span>
          <RouterLink to="/ranking" class="tiny linkish">전체 보기</RouterLink>
        </div>
        <div class="row between">
          <span class="big mono">#{{ myRank?.rank ?? '—' }}</span>
          <span class="muted">육아점수 {{ score.toLocaleString() }}</span>
        </div>
      </div>

      <div class="cg-card col">
        <div class="cg-section-title">오늘 할 일</div>
        <div class="row between">
          <span>학습 리포트</span>
          <span v-if="todayReport" class="cg-badge cg-badge--ok">작성 완료</span>
          <RouterLink v-else to="/report" class="cg-badge cg-badge--warn">미작성 →</RouterLink>
        </div>
        <div class="row between">
          <span>추천 퀴즈</span>
          <span class="cg-badge" :class="quizDone === activeQuizzes.length ? 'cg-badge--ok' : 'cg-badge--warn'">
            {{ quizDone }}/{{ activeQuizzes.length }} 제출
          </span>
        </div>
      </div>

      <!-- report rhythm: 대기(자정 배치) → 도착, 또는 Fallback(실패) -->
      <div class="cg-card col">
        <div class="cg-section-title">일일 레포트</div>

        <template v-if="reportStatus === 'pending'">
          <p class="tiny muted">{{ WAITING.report }}</p>
          <div class="row wrap" style="gap:6px">
            <button class="cg-btn cg-btn--sm" :disabled="!pendingReport" @click="deliverDailyReport()">⏩ (데모) 내일 아침으로</button>
            <button class="cg-btn cg-btn--sm cg-btn--ghost" :disabled="!pendingReport" @click="deliverDailyReport({ fail: true })">(데모) 분석 실패</button>
          </div>
        </template>

        <template v-else-if="reportStatus === 'failed'">
          <CgState tone="fallback" inline :message="FALLBACK.report" />
          <div class="row wrap" style="gap:6px">
            <button class="cg-btn cg-btn--sm cg-btn--primary" @click="deliverDailyReport()">다시 분석 요청</button>
            <RouterLink to="/report/result" class="cg-btn cg-btn--sm">상태 보기</RouterLink>
          </div>
        </template>

        <template v-else>
          <p class="tiny">📬 어제의 레포트가 도착했어요.</p>
          <RouterLink to="/report/result" class="cg-btn cg-btn--sm cg-btn--primary">레포트 결과 보기</RouterLink>
        </template>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.dash { display: grid; grid-template-columns: 1fr 340px; gap: var(--sp-4); align-items: start; }
.stage { padding: var(--sp-5); gap: var(--sp-4); min-height: 520px; }
.stage__char { margin: var(--sp-3) 0; }
.bubble {
  background: var(--surface-2); border: 2px solid var(--surface-edge); border-radius: var(--r);
  padding: 10px 16px; font-family: var(--font-head); text-align: center; max-width: 460px;
}
.rail { gap: var(--sp-3); }
.tiles { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.empty { gap: var(--sp-4); padding: var(--sp-6); text-align: center; margin: 6vh auto; max-width: 460px; }
.linkish, .empty :deep(a) { color: var(--primary-d); }
.linkish { font-family: var(--font-head); text-decoration: underline; }
@media (max-width: 880px) { .dash { grid-template-columns: 1fr; } .tiles { grid-template-columns: repeat(5, 1fr); } }
</style>
