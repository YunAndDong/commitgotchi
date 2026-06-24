<script setup>
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { gameState, STAT_LABELS } from '../stores/game.js'

const route = useRoute()
const report = computed(() => gameState.reports.find(item => String(item.id) === String(route.params.id)) || null)
const character = computed(() => gameState.characters.find(
  item => String(item.id) === String(report.value?.characterId)
) || null)
const backTarget = computed(() => (
  character.value ? { name: 'character', params: { id: character.value.id } } : { name: 'dashboard' }
))
const tagLabels = computed(() => (report.value?.tags || []).map(tag => STAT_LABELS[tag] || tag))
const status = computed(() => {
  if (!report.value) return { label: '', className: '' }
  if (report.value.status === 'analyzing') {
    return { label: '분석 대기', className: 'cg-badge--warn' }
  }
  if (report.value.scoreApplied || report.value.status === 'applied') {
    return { label: '반영됨', className: 'cg-badge--ok' }
  }
  return { label: '저장됨', className: 'cg-badge--warn' }
})
</script>

<template>
  <div v-if="report" class="detail col">
    <header class="cg-pagehead">
      <div class="cg-pagehead__main">
        <RouterLink :to="backTarget" class="cg-btn cg-btn--sm cg-back" aria-label="캐릭터 상세로 돌아가기">←</RouterLink>
        <h1 class="cg-page-title">리포트 상세</h1>
      </div>
    </header>

    <section class="cg-card col">
      <div class="report-header row between wrap">
        <div>
          <h2 class="report-title">{{ report.title }}</h2>
          <p class="tiny faint mono">{{ report.date }}</p>
        </div>
        <div v-if="tagLabels.length" class="tag-list row wrap" aria-label="학습 태그">
          <span v-for="label in tagLabels" :key="label" class="cg-tag">{{ label }}</span>
        </div>
      </div>
      <p class="body">{{ report.content }}</p>
    </section>

    <section class="cg-card col">
      <div class="status-header row between wrap">
        <div class="cg-section-title">반영 상태</div>
        <span class="cg-badge" :class="status.className">{{ status.label }}</span>
      </div>
      <p class="body">
        {{ report.scoreApplied || report.status === 'applied'
          ? '이 리포트의 학습 태그가 캐릭터 능력치에 반영됐어요.'
          : '이 리포트는 저장됐고 분석을 기다리는 중이에요.' }}
      </p>
    </section>
  </div>

  <div v-else class="cg-card center col missing">
    <p>리포트를 찾을 수 없어요.</p>
    <RouterLink to="/" class="cg-btn">대시보드로</RouterLink>
  </div>
</template>

<style scoped>
.detail {
  max-width: 760px;
  margin: 0 auto;
  gap: var(--sp-4);
}
.report-title {
  font-size: 19px;
  overflow-wrap: anywhere;
}
.report-header {
  align-items: flex-start;
}
.body {
  line-height: 1.85;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.tag-list {
  gap: 8px;
  justify-content: flex-end;
}
.status-header {
  align-items: center;
}
.missing {
  max-width: 520px;
  margin: 0 auto;
}
</style>
