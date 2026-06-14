<script setup>
/*
 * Screen #5 — 일일 리포트 작성.
 * 기분(😊/😢/😠) · 제목 · 내용 · 태그 + 캐릭터 반응 미리보기. 명시적 저장, 하루 1개·덮어쓰기.
 */
import { reactive, ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { saveReport, todayReport, activeCharacter, STAT_KEYS, STAT_LABELS } from '../stores/game.js'
import CgSprite from '../components/CgSprite.vue'

const router = useRouter()
const form = reactive({ mood: 'joy', title: '', content: '', tags: [] })
const saved = ref(false)
const c = activeCharacter

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

function submit() {
  if (!form.title.trim()) return
  saveReport({ ...form })
  saved.value = true
  setTimeout(() => router.push('/'), 900)
}
</script>

<template>
  <div class="report">
    <section class="cg-card col">
      <div class="row between">
        <h1 class="cg-section-title big">오늘의 학습 리포트</h1>
        <span v-if="todayReport" class="cg-badge cg-badge--warn">오늘 작성분 덮어쓰기</span>
      </div>

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

      <button class="cg-btn cg-btn--primary cg-btn--block" :disabled="!form.title.trim() || saved" @click="submit">
        {{ saved ? '저장됨 ✓' : '리포트 저장' }}
      </button>
      <p class="tiny faint">저장하면 자정에 분석돼요. 내일 오전 9시 일일 레포트가 도착합니다.</p>
    </section>

    <aside class="cg-screen col center preview">
      <span class="tiny muted">캐릭터 반응 미리보기</span>
      <CgSprite :size="150" :emotion="form.mood" :evolved="c?.isEvolved" />
      <p class="bubble">“{{ reaction }}”</p>
    </aside>
  </div>
</template>

<style scoped>
.report { display: grid; grid-template-columns: 1fr 300px; gap: var(--sp-4); align-items: start; max-width: 940px; margin: 0 auto; }
.preview { padding: var(--sp-5); gap: var(--sp-3); position: sticky; top: 90px; }
.bubble { background: var(--surface-2); border: 2px solid var(--surface-edge); border-radius: var(--r); padding: 8px 14px; font-family: var(--font-head); text-align: center; }
.chip { cursor: pointer; }
.chip.on { background: var(--primary); color: var(--on-primary); border-color: var(--primary-d); }
@media (max-width: 760px) { .report { grid-template-columns: 1fr; } .preview { position: static; } }
</style>
