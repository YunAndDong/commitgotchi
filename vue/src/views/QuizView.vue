<script setup>
/*
 * 퀴즈 풀이 · 즉시 채점 결과 (스파인 보강, 흐름 B).
 * 답안 제출 → "답변 생성 중…" → 점수·피드백·점수 변화량·감정 갱신을 그 자리에서 표시.
 * FE-10: 채점 실패 시 Fallback(답안 보존 + 잠시 후 재시도, 점수 미반영) — 흐름 유지.
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { activeCharacter, createDemoQuizzes, gameState, quizzesForCharacter, submitQuiz, STAT_LABELS } from '../stores/game.js'
import { LOADING, FALLBACK } from '../constants/aiStates.js'
import CgState from '../components/CgState.vue'
import CgSprite from '../components/CgSprite.vue'

const route = useRoute()
const router = useRouter()
const routeCharacterId = computed(() => (
  typeof route.query.characterId === 'string' && route.query.characterId.trim()
    ? route.query.characterId.trim()
    : null
))
const c = computed(() => {
  if (routeCharacterId.value == null) return activeCharacter.value
  return gameState.characters.find(item => String(item.id) === String(routeCharacterId.value)) || null
})
const quizzes = computed(() => quizzesForCharacter(c.value?.id))
const grading = ref({})      // quizId -> bool
const answers = ref({})
const creatingDemoQuizzes = ref(false)
const submitError = ref('')

watch(quizzes, items => {
  answers.value = Object.fromEntries(items.map(q => [
    q.id,
    answers.value[q.id] ?? q.userAnswer ?? '',
  ]))
}, { immediate: true })

function answerFor(q) {
  return String(answers.value[q.id] || '').trim()
}

function isQuizInScope(q) {
  return c.value && String(q.characterId) === String(c.value.id)
}

function goBack() {
  if (router.options.history.state.back) {
    router.back()
    return
  }
  router.push({ name: 'dashboard' })
}

async function submit(q) {
  submitError.value = ''
  const answer = answerFor(q)
  if (!answer || grading.value[q.id]) return
  if (!isQuizInScope(q)) {
    submitError.value = '현재 캐릭터의 퀴즈만 제출할 수 있어요.'
    return
  }
  grading.value[q.id] = true
  try {
    const res = await submitQuiz(q.id, answer)
    if (res?.ok === false) return  // Fallback: 답안 보존·재시도 가능
  } finally {
    grading.value[q.id] = false
  }
}

async function createQuizzes() {
  if (creatingDemoQuizzes.value || !c.value) return
  creatingDemoQuizzes.value = true
  try {
    await createDemoQuizzes(c.value.id)
  } finally {
    creatingDemoQuizzes.value = false
  }
}
</script>

<template>
  <div class="quiz">
    <header class="cg-pagehead">
      <div class="cg-pagehead__main">
        <button type="button" class="cg-btn cg-btn--sm cg-back" aria-label="이전 화면으로 돌아가기" @click="goBack">←</button>
        <h1 class="cg-page-title">오늘의 추천 퀴즈</h1>
      </div>
      <div class="cg-pagehead__actions">
        <span v-if="c" class="cg-badge">{{ c.name }}</span>
        <span class="tiny muted">제출 즉시 채점돼요 · 채점 완료 후 결과가 고정돼요</span>
      </div>
    </header>

    <p v-if="submitError" class="err tiny" role="alert">{{ submitError }}</p>

    <div v-if="!c" class="empty-quiz cg-card center col" role="status" aria-live="polite">
      <CgSprite :size="148" emotion="sad" />
      <p class="empty-copy muted">퀴즈를 풀 캐릭터를 찾지 못했어요.</p>
      <button type="button" class="cg-btn cg-btn--primary" @click="router.push('/select')">커밋고치 선택하기</button>
    </div>

    <div v-else-if="!quizzes.length" class="empty-quiz cg-card center col" role="status" aria-live="polite">
      <CgSprite
        :size="148"
        emotion="sad"
        :evolved="c?.isEvolved"
        :sprite-sheet-url="c?.spriteSheetUrl"
        :sprite-meta="c?.spriteMeta"
      />
      <p class="empty-copy muted">오늘 도착한 퀴즈가 없어요..</p>
      <button
        type="button"
        class="cg-btn cg-btn--primary"
        :disabled="creatingDemoQuizzes || !c"
        @click="createQuizzes"
      >
        {{ creatingDemoQuizzes ? '퀴즈 생성 중...' : '퀴즈 만들기' }}
      </button>
    </div>

    <article v-for="(q, qi) in quizzes" :key="q.id" class="cg-card col qcard">
      <div class="row between">
        <span class="cg-tag">{{ STAT_LABELS[q.tag] }}</span>
        <span v-if="q.scored" class="cg-badge" :class="q.correct ? 'cg-badge--ok' : 'cg-badge--fire'">
          {{ q.correct ? '충분' : '보완' }} · {{ STAT_LABELS[q.deltaStat] }} +{{ q.deltaAmount }}
        </span>
        <span v-else-if="grading[q.id]" class="cg-badge cg-badge--warn">{{ LOADING.quiz }}</span>
        <span v-else-if="q.gradeFailed" class="cg-badge cg-badge--warn">재시도 필요</span>
        <span v-else class="cg-badge cg-badge--warn">미제출</span>
      </div>

      <h2 class="qtext">Q{{ qi + 1 }}. {{ q.question }}</h2>

      <div class="cg-field">
        <label class="cg-label" :for="`quiz-answer-${q.id}`">내 답변</label>
        <textarea
          :id="`quiz-answer-${q.id}`"
          v-model="answers[q.id]"
          class="cg-textarea answer"
          maxlength="800"
          placeholder="핵심 개념과 이유를 문장으로 적어 주세요."
          :disabled="q.submitted || grading[q.id]"
        />
      </div>

      <div v-if="q.scored" class="feedback" :class="q.correct ? 'feedback--ok' : 'feedback--no'">
        <strong>{{ q.correct ? '좋은 답변' : '보완할 답변' }}</strong>
        <p class="submitted">내 답변: {{ q.userAnswer }}</p>
        <p>{{ q.feedback }}</p>
      </div>

      <!-- 상태 패턴: 답변 생성 중(로딩) / 실패(Fallback) / 미제출(제출 버튼) / 채점완료 -->
      <button
        v-if="(!q.submitted && !q.gradeFailed) || grading[q.id]"
        type="button"
        class="cg-btn cg-btn--primary quiz-submit"
        :disabled="grading[q.id] || !answerFor(q) || !isQuizInScope(q)"
        :aria-busy="grading[q.id] ? 'true' : 'false'"
        @click="submit(q)"
      >
        <span v-if="grading[q.id]" class="quiz-submit__spinner" aria-hidden="true" />
        <span>{{ grading[q.id] ? LOADING.quiz : '답안 제출' }}</span>
      </button>

      <CgState v-if="grading[q.id]" tone="loading" inline :message="LOADING.quiz" />

      <CgState v-else-if="q.gradeFailed" tone="fallback" inline :message="FALLBACK.quiz">
        <button type="button" class="cg-btn cg-btn--sm cg-btn--primary"
                :disabled="!answerFor(q)" @click="submit(q)">{{ FALLBACK.quizRetry }}</button>
      </CgState>

      <p v-else-if="q.scored" class="tiny faint">채점 완료 · 점수는 한 번만 반영됐어요.</p>
    </article>
  </div>
</template>

<style scoped>
.quiz { max-width: 760px; margin: 0 auto; display: flex; flex-direction: column; gap: var(--sp-4); }
.empty-quiz {
  min-height: 300px;
  padding: var(--sp-6) var(--sp-5);
  gap: var(--sp-4);
  text-align: center;
}
.empty-copy {
  font-family: var(--font-head);
  font-size: 16px;
  margin: 0;
}
.qcard { gap: var(--sp-3); }
.qtext { font-family: var(--font-head); font-size: 17px; line-height: 1.6; }
.answer { min-height: 132px; resize: vertical; }
.quiz-submit { align-self: flex-start; min-width: 150px; }
.quiz-submit[aria-busy="true"] { cursor: progress; opacity: .9; }
.quiz-submit__spinner {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: 2px solid color-mix(in srgb, currentColor 35%, transparent);
  border-top-color: currentColor;
  animation: quiz-submit-spin .8s linear infinite;
}
.feedback { border-radius: var(--r); padding: 12px 14px; border: 2px solid var(--surface-edge); }
.feedback p { margin-top: 4px; line-height: 1.7; }
.submitted { color: var(--muted); }
.feedback--ok { background: var(--badge-ok-bg); border-color: var(--badge-ok-edge); color: var(--badge-ok-fg); }
.feedback--no { background: var(--surface-2); }
.err { color: var(--angry); }
@keyframes quiz-submit-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) {
  .quiz-submit__spinner { animation-duration: 2s; }
}
</style>
