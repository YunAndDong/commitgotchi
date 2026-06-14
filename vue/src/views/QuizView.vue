<script setup>
/*
 * 퀴즈 풀이 · 즉시 채점 결과 (스파인 보강, 흐름 B).
 * 답안 제출 → "채점 중…" → 점수·피드백·점수 변화량·감정 갱신을 그 자리에서 표시.
 * FE-10: 채점 실패 시 Fallback(답안 보존 + 잠시 후 재시도, 점수 미반영) — 흐름 유지.
 */
import { ref } from 'vue'
import { activeQuizzes, submitQuiz, STAT_LABELS } from '../stores/game.js'
import { LOADING, FALLBACK } from '../constants/aiStates.js'
import CgConfetti from '../components/CgConfetti.vue'
import CgState from '../components/CgState.vue'

const quizzes = activeQuizzes
const grading = ref({})      // quizId -> bool
const selected = ref(Object.fromEntries(quizzes.value.map(q => [q.id, q.selected])))
const evolved = ref(false)
const failNext = ref(false)  // 데모: 다음 채점을 강제로 실패시켜 Fallback 재현

function pick(q, i) { if (!q.submitted && !grading.value[q.id]) selected.value[q.id] = i }

async function submit(q) {
  const sel = selected.value[q.id]
  if (sel == null) return
  grading.value[q.id] = true
  const res = await submitQuiz(q.id, sel, { fail: failNext.value })
  grading.value[q.id] = false
  if (res?.ok === false) { failNext.value = false; return }  // Fallback: 답안 보존·재시도 가능
  if (res?._evolvedNow) evolved.value = true
}
</script>

<template>
  <div class="quiz">
    <CgConfetti v-if="evolved" :count="80" />
    <header class="row between">
      <h1 class="cg-section-title big">오늘의 추천 퀴즈</h1>
      <span class="tiny muted">제출 즉시 채점돼요 · 채점 완료 후 결과가 고정돼요</span>
    </header>

    <label class="demo tiny muted">
      <input type="checkbox" v-model="failNext" /> 데모: 다음 채점 실패 재현(Fallback)
    </label>

    <p v-if="!quizzes.length" class="cg-card muted">오늘 도착한 추천 퀴즈가 없어요. 리포트를 쓰면 내일 새 퀴즈가 도착해요.</p>

    <article v-for="(q, qi) in quizzes" :key="q.id" class="cg-card col qcard">
      <div class="row between">
        <span class="cg-tag">{{ STAT_LABELS[q.tag] }}</span>
        <span v-if="q.scored" class="cg-badge" :class="q.correct ? 'cg-badge--ok' : 'cg-badge--fire'">
          {{ q.correct ? '정답' : '오답' }} · {{ STAT_LABELS[q.deltaStat] }} +{{ q.deltaAmount }}
        </span>
        <span v-else-if="grading[q.id]" class="cg-badge cg-badge--warn">{{ LOADING.quiz }}</span>
        <span v-else-if="q.gradeFailed" class="cg-badge cg-badge--warn">재시도 필요</span>
        <span v-else class="cg-badge cg-badge--warn">미제출</span>
      </div>

      <h2 class="qtext">Q{{ qi + 1 }}. {{ q.question }}</h2>

      <ul class="opts col">
        <li v-for="(opt, i) in q.options" :key="i">
          <button class="opt" :class="{
                    on: selected[q.id] === i,
                    correct: q.scored && i === q.answer,
                    wrong: q.scored && q.selected === i && i !== q.answer,
                  }"
                  :disabled="q.submitted || grading[q.id]" @click="pick(q, i)">
            <span class="opt__k">{{ ['A','B','C','D'][i] }}</span>{{ opt }}
          </button>
        </li>
      </ul>

      <div v-if="q.scored" class="feedback" :class="q.correct ? 'feedback--ok' : 'feedback--no'">
        <strong>{{ q.correct ? '🎯 정답!' : '💡 해설' }}</strong>
        <p>{{ q.feedback }}</p>
      </div>

      <!-- 상태 패턴: 채점 중(로딩) / 실패(Fallback) / 미제출(제출 버튼) / 채점완료 -->
      <CgState v-if="grading[q.id]" tone="loading" inline :message="LOADING.quiz" />

      <CgState v-else-if="q.gradeFailed" tone="fallback" inline :message="FALLBACK.quiz">
        <button class="cg-btn cg-btn--sm cg-btn--primary"
                :disabled="selected[q.id] == null" @click="submit(q)">{{ FALLBACK.quizRetry }}</button>
      </CgState>

      <button v-else-if="!q.submitted" class="cg-btn cg-btn--primary"
              :disabled="selected[q.id] == null" @click="submit(q)">답안 제출</button>

      <p v-else-if="q.scored" class="tiny faint">채점 완료 · 점수는 한 번만 반영됐어요.</p>
    </article>
  </div>
</template>

<style scoped>
.quiz { max-width: 760px; margin: 0 auto; display: flex; flex-direction: column; gap: var(--sp-4); }
.demo { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; align-self: flex-start; }
.qcard { gap: var(--sp-3); }
.qtext { font-family: var(--font-head); font-size: 17px; line-height: 1.6; }
.opts { gap: 8px; list-style: none; }
.opt {
  display: flex; align-items: center; gap: 10px; width: 100%; text-align: left;
  font-family: var(--font-body); font-size: 15px; color: var(--ink);
  background: var(--surface-2); border: 2px solid var(--surface-edge); border-radius: var(--r);
  padding: 12px 14px; transition: transform .06s ease;
}
.opt:hover:not(:disabled) { transform: translate(-1px,-1px); box-shadow: 3px 3px 0 var(--shadow-hard); }
.opt__k { font-family: var(--font-head); width: 22px; height: 22px; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--surface); border: 1.5px solid var(--surface-edge); font-size: 12px; }
.opt.on { border-color: var(--primary-d); background: color-mix(in srgb, var(--primary) 16%, var(--surface-2)); }
.opt.correct { border-color: var(--primary-d); background: var(--badge-ok-bg); color: var(--badge-ok-fg); }
.opt.wrong { border-color: var(--accent-d); background: var(--badge-fire-bg); color: var(--badge-fire-fg); }
.feedback { border-radius: var(--r); padding: 12px 14px; border: 2px solid var(--surface-edge); }
.feedback p { margin-top: 4px; line-height: 1.7; }
.feedback--ok { background: var(--badge-ok-bg); border-color: var(--badge-ok-edge); color: var(--badge-ok-fg); }
.feedback--no { background: var(--surface-2); }
</style>
