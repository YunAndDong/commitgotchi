<script setup>
/*
 * Screen #5 — 일일 리포트 작성.
 * 기분(😊/😢/😠) · 제목 · 내용 · 태그 + 캐릭터 반응 미리보기. 명시적 저장, 하루 1개·덮어쓰기.
 */
import { reactive, ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  saveReport,
  deliverDailyReport,
  runReportNow,
  showReportSubmittedNotice,
  todayReport,
  activeCharacter,
  STAT_KEYS,
  STAT_LABELS,
} from '../stores/game.js'
import CgSprite from '../components/CgSprite.vue'

const router = useRouter()
const form = reactive({ mood: 'joy', title: '', content: '', tags: [] })
const saved = ref(false)
const submitting = ref(false)
const instantSubmitting = ref(false)
const instantPhase = ref('')
const error = ref('')
const c = activeCharacter
const busy = computed(() => saved.value || submitting.value || instantSubmitting.value)
const instantSubmitLabel = computed(() => instantSubmitting.value ? instantPhase.value : '즉시 제출')

const moods = [
  { key: 'joy', face: '😊', label: '기쁨' },
  { key: 'sad', face: '😢', label: '슬픔' },
  { key: 'angry', face: '😠', label: '화남' },
]
function toggleTag(k) {
  const i = form.tags.indexOf(k)
  if (i === -1) form.tags.push(k); else form.tags.splice(i, 1)
}
const reaction = computed(() => {
  if (form.mood === 'joy') return '오 좋은데! 그 기세 그대로 가자.'
  if (form.mood === 'sad') return '힘든 날도 기록하면 한 뼘 자라는 거야.'
  return '막힌 부분, 내일 같이 부숴보자!'
})

onMounted(() => {
  const r = todayReport.value
  if (r) Object.assign(form, { mood: r.mood, title: r.title, content: r.content, tags: [...r.tags] })
})

async function submit() {
  if (!form.title.trim() || submitting.value) return
  error.value = ''
  submitting.value = true
  try {
    await saveReport({ ...form })
    await deliverDailyReport()
    showReportSubmittedNotice()
    saved.value = true
    setTimeout(() => router.push('/'), 900)
  } catch {
    error.value = '리포트 제출에 실패했어요. 잠시 후 다시 시도해 주세요.'
  } finally {
    submitting.value = false
  }
}

async function submitNow() {
  if (!form.title.trim() || instantSubmitting.value) return
  error.value = ''
  instantSubmitting.value = true
  instantPhase.value = '제출 중...'
  try {
    await saveReport({ ...form })
    instantPhase.value = '답변 생성 중...'
    await runReportNow()
    saved.value = true
    router.push('/report/result')
  } catch {
    error.value = '즉시 제출 요청에 실패했어요. 잠시 후 다시 시도해 주세요.'
  } finally {
    instantSubmitting.value = false
    instantPhase.value = ''
  }
}
</script>

<template>
  <div class="report-page">
    <header class="cg-pagehead">
      <div class="cg-pagehead__main">
        <button type="button" class="cg-btn cg-btn--sm cg-back" aria-label="대시보드로 돌아가기" @click="router.push('/')">←</button>
        <h1 class="cg-page-title">오늘의 학습 리포트</h1>
      </div>
      <div class="cg-pagehead__actions">
        <span v-if="todayReport" class="cg-badge cg-badge--warn">오늘 작성분 덮어쓰기</span>
      </div>
    </header>

    <div class="report">
      <section class="cg-card col">
        <div class="cg-field">
          <span class="cg-label">오늘 기분</span>
          <div class="row" style="gap:8px">
            <button v-for="m in moods" :key="m.key" type="button"
                    class="cg-btn" :class="{ 'cg-btn--primary': form.mood === m.key }"
                    @click="form.mood = m.key">{{ m.face }} {{ m.label }}</button>
          </div>
        </div>

        <div class="cg-field">
          <label class="cg-label" for="t">제목</label>
          <input id="t" class="cg-input" v-model="form.title" maxlength="60"
                 placeholder="예: Spring JPA N+1 문제와 해결법" />
        </div>

        <div class="cg-field">
          <label class="cg-label" for="ct">내용</label>
          <textarea id="ct" class="cg-textarea" v-model="form.content"
                    placeholder="오늘 무엇을 어떻게 공부했는지 적어주세요. AI가 자정에 분석해 점수에 반영해요."></textarea>
        </div>

        <div class="cg-field">
          <span class="cg-label">태그 (능력치)</span>
          <div class="row wrap" style="gap:6px">
            <button v-for="k in STAT_KEYS" :key="k" type="button" class="cg-tag chip"
                    :class="{ on: form.tags.includes(k) }" @click="toggleTag(k)">{{ STAT_LABELS[k] }}</button>
          </div>
        </div>

        <div class="submit-actions">
          <button type="button" class="cg-btn cg-btn--primary cg-btn--block" :disabled="!form.title.trim() || busy" @click="submit">
            {{ saved ? '제출됨 ✓' : (submitting ? '제출 중...' : '리포트 제출') }}
          </button>
          <button type="button" class="cg-btn cg-btn--accent cg-btn--block" :disabled="!form.title.trim() || busy" @click="submitNow">
            {{ instantSubmitLabel }}
          </button>
        </div>
        <p v-if="error" class="tiny report-error">{{ error }}</p>
        <p class="tiny faint">제출하면 worker 분석 요청을 보냅니다. 즉시 제출은 결과 화면으로 바로 이동해요.</p>
      </section>

      <aside class="cg-screen col center preview">
        <span class="tiny muted">캐릭터 반응 미리보기</span>
        <CgSprite :size="150" :emotion="form.mood" :evolved="c?.isEvolved"
                  :sprite-sheet-url="c?.spriteSheetUrl" :sprite-meta="c?.spriteMeta" />
        <p class="bubble">“{{ reaction }}”</p>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.report-page { max-width: 960px; margin: 0 auto; display: flex; flex-direction: column; gap: var(--sp-4); }
.report { display: grid; grid-template-columns: 1fr 300px; gap: var(--sp-4); align-items: start; }
.preview { padding: var(--sp-5); gap: var(--sp-3); position: sticky; top: 90px; }
.bubble { background: var(--surface-2); border: 2px solid var(--surface-edge); border-radius: var(--r); padding: 8px 14px; font-family: var(--font-head); text-align: center; }
.chip { cursor: pointer; }
.chip.on { background: var(--primary); color: var(--on-primary); border-color: var(--primary-d); }
.submit-actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--sp-2); }
.report-error { color: var(--angry); }
@media (max-width: 760px) { .report { grid-template-columns: 1fr; } .preview { position: static; } }
@media (max-width: 520px) { .submit-actions { grid-template-columns: 1fr; } }
</style>
