<script setup>
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { gameState, STAT_LABELS } from '../stores/game.js'

const route = useRoute()
const quiz = computed(() => gameState.quizzes.find(item => String(item.id) === String(route.params.id)) || null)
const character = computed(() => gameState.characters.find(
  item => String(item.id) === String(quiz.value?.characterId)
) || null)
const backTarget = computed(() => (
  character.value ? { name: 'character', params: { id: character.value.id } } : { name: 'dashboard' }
))
const status = computed(() => {
  if (!quiz.value) return { label: '', className: '' }
  if (quiz.value.gradeFailed) return { label: '재시도 필요', className: 'cg-badge--warn' }
  if (!quiz.value.submitted) return { label: '미제출', className: 'cg-badge--warn' }
  return quiz.value.correct
    ? { label: '충분', className: 'cg-badge--ok' }
    : { label: '보완', className: 'cg-badge--fire' }
})
const selectedOption = computed(() => {
  if (!quiz.value?.options?.length) return ''
  const index = Number(quiz.value.selected)
  return Number.isInteger(index) ? quiz.value.options[index] || '' : ''
})
const submittedAnswer = computed(() => {
  const value = quiz.value?.userAnswer || quiz.value?.selected || selectedOption.value
  return value == null ? '' : String(value)
})
</script>

<template>
  <div v-if="quiz" class="detail col">
    <header class="row between wrap">
      <div class="col title-block">
        <RouterLink :to="backTarget" class="cg-btn cg-btn--sm back" aria-label="캐릭터 상세로 돌아가기">←</RouterLink>
        <h1 class="cg-section-title big">퀴즈 상세</h1>
      </div>
      <span class="cg-badge" :class="status.className">{{ status.label }}</span>
    </header>

    <section class="cg-card col">
      <div class="row between wrap">
        <span class="cg-tag">{{ STAT_LABELS[quiz.tag] || quiz.tag }}</span>
        <p class="tiny faint mono">{{ quiz.date }}</p>
      </div>
      <h2 class="question">{{ quiz.question }}</h2>
      <RouterLink v-if="character" :to="backTarget" class="cg-tag character-link">{{ character.name }}</RouterLink>
    </section>

    <section v-if="quiz.options.length" class="cg-card col">
      <div class="cg-section-title">선택지</div>
      <ol class="options col">
        <li v-for="(option, index) in quiz.options" :key="`${index}-${option}`" :class="{ selected: Number(quiz.selected) === index }">
          <span class="mono">{{ index + 1 }}</span>
          <span>{{ option }}</span>
        </li>
      </ol>
    </section>

    <section class="cg-card col">
      <div class="cg-section-title">내 답변</div>
      <p class="body">{{ submittedAnswer || '아직 제출한 답변이 없어요.' }}</p>
    </section>

    <section v-if="quiz.scored" class="cg-card col">
      <div class="row between wrap">
        <div class="cg-section-title">채점 결과</div>
        <span class="cg-badge" :class="status.className">
          {{ STAT_LABELS[quiz.deltaStat] || quiz.deltaStat }} +{{ quiz.deltaAmount }}
        </span>
      </div>
      <p class="body">{{ quiz.feedback || '피드백이 아직 없어요.' }}</p>
      <p v-if="quiz.modelAnswer" class="model body">모범 답안: {{ quiz.modelAnswer }}</p>
    </section>
  </div>

  <div v-else class="cg-card center col missing">
    <p>퀴즈를 찾을 수 없어요.</p>
    <RouterLink to="/" class="cg-btn">대시보드로</RouterLink>
  </div>
</template>

<style scoped>
.detail {
  max-width: 720px;
  margin: 0 auto;
  gap: var(--sp-4);
}
.title-block {
  gap: var(--sp-2);
}
.back {
  width: 36px;
  min-width: 36px;
  padding: 0;
  font-size: 18px;
}
.question {
  font-size: 19px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}
.character-link {
  align-self: flex-start;
}
.options {
  list-style: none;
  gap: 8px;
}
.options li {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
  padding: 10px 12px;
  border: 2px solid var(--surface-edge);
  border-radius: var(--r);
  background: var(--surface-2);
}
.options li.selected {
  border-color: var(--primary-d);
  background: var(--badge-ok-bg);
}
.body {
  line-height: 1.85;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.model {
  color: var(--ink-soft);
}
.missing {
  max-width: 520px;
  margin: 0 auto;
}
</style>
