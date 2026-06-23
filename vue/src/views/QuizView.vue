<script setup>
/*
 * 퀴즈 풀이 · 즉시 채점 결과 (스파인 보강, 흐름 B).
 * 답안 제출 → "채점 중…" → 점수·피드백·점수 변화량·감정 갱신을 그 자리에서 표시.
 * FE-10: 채점 실패 시 Fallback(답안 보존 + 잠시 후 재시도, 점수 미반영) — 흐름 유지.
 */
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { activeCharacter, activeQuizzes, submitQuiz, STAT_LABELS } from '../stores/game.js'
import { LOADING, FALLBACK } from '../constants/aiStates.js'
import CgConfetti from '../components/CgConfetti.vue'
import CgState from '../components/CgState.vue'
import CgSprite from '../components/CgSprite.vue'

const quizzes = activeQuizzes
const c = activeCharacter
const router = useRouter()
const grading = ref({})      // quizId -> bool
const answers = ref({})
const evolved = ref(false)
const failNext = ref(false)  // 데모: 다음 채점을 강제로 실패시켜 Fallback 재현

watch(quizzes, items => {
  answers.value = Object.fromEntries(items.map(q => [
    q.id,
    answers.value[q.id] ?? q.userAnswer ?? '',
  ]))
}, { immediate: true })

function answerFor(q) {
  return String(answers.value[q.id] || '').trim()
}

function goBack() {
  if (router.options.history.state.back) {
    router.back()
    return
  }
  router.push({ name: 'dashboard' })
}

async function submit(q) {
  const answer = answerFor(q)
  if (!answer) return
  grading.value[q.id] = true
  const res = await submitQuiz(q.id, answer, { fail: failNext.value })
  grading.value[q.id] = false
  if (res?.ok === false) { failNext.value = false; return }  // Fallback: 답안 보존·재시도 가능
  if (res?._evolvedNow) evolved.value = true
}
</script>

<template>
  <div class="quiz">
    <CgConfetti v-if="evolved" :count="80" />
    <header class="quiz-head row between wrap">
      <div class="quiz-title row">
        <button type="button" class="quiz-back cg-btn cg-btn--sm" aria-label="이전 화면으로 돌아가기" @click="goBack">←</button>
        <h1 class="cg-section-title big">오늘의 추천 퀴즈</h1>
      </div>
      <span class="quiz-subcopy tiny muted">제출 즉시 채점돼요 · 채점 완료 후 결과가 고정돼요</span>
    </header>

    <label class="demo tiny muted">
      <input type="checkbox" v-model="failNext" /> 데모: 다음 채점 실패 재현(Fallback)
    </label>

    <div v-if="!quizzes.length" class="empty-quiz cg-card center col" role="status" aria-live="polite">
      <CgSprite
        :size="148"
        emotion="sad"
        :evolved="c?.isEvolved"
        :sprite-sheet-url="c?.spriteSheetUrl"
        :sprite-meta="c?.spriteMeta"
      />
      <p class="empty-copy muted">오늘 도착한 퀴즈가 없어요..</p>
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

      <!-- 상태 패턴: 채점 중(로딩) / 실패(Fallback) / 미제출(제출 버튼) / 채점완료 -->
      <CgState v-if="grading[q.id]" tone="loading" inline :message="LOADING.quiz" />

      <CgState v-else-if="q.gradeFailed" tone="fallback" inline :message="FALLBACK.quiz">
        <button class="cg-btn cg-btn--sm cg-btn--primary"
                :disabled="!answerFor(q)" @click="submit(q)">{{ FALLBACK.quizRetry }}</button>
      </CgState>

      <button v-else-if="!q.submitted" class="cg-btn cg-btn--primary"
              :disabled="!answerFor(q)" @click="submit(q)">답안 제출</button>

      <p v-else-if="q.scored" class="tiny faint">채점 완료 · 점수는 한 번만 반영됐어요.</p>
    </article>
  </div>
</template>

<style scoped>
.quiz { max-width: 760px; margin: 0 auto; display: flex; flex-direction: column; gap: var(--sp-4); }
.quiz-head { align-items: center; }
.quiz-title { min-width: 0; gap: var(--sp-2); }
.quiz-back {
  width: 36px;
  min-width: 36px;
  padding: 0;
  font-size: 18px;
}
.quiz-subcopy {
  margin-left: auto;
  text-align: right;
}
.demo { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; align-self: flex-start; }
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
.feedback { border-radius: var(--r); padding: 12px 14px; border: 2px solid var(--surface-edge); }
.feedback p { margin-top: 4px; line-height: 1.7; }
.submitted { color: var(--muted); }
.feedback--ok { background: var(--badge-ok-bg); border-color: var(--badge-ok-edge); color: var(--badge-ok-fg); }
.feedback--no { background: var(--surface-2); }
@media (max-width: 560px) {
  .quiz-head { align-items: flex-start; }
  .quiz-subcopy {
    width: 100%;
    margin-left: 44px;
    text-align: left;
  }
}
</style>
