<script setup>
/*
 * Screen #4 — 캐릭터 상세.
 * 좌: 캐릭터 + 리포트 작성 진입 / 우: 5각형 레이더 + 공부 기록.
 */
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { gameState, nurtureScore, STAT_LABELS, updateCharacter, deleteCharacter, setActive } from '../stores/game.js'
import CgSprite from '../components/CgSprite.vue'
import CgRadar from '../components/CgRadar.vue'
import CgGauge from '../components/CgGauge.vue'
import CgEmo from '../components/CgEmo.vue'

const route = useRoute()
const router = useRouter()
const c = computed(() => gameState.characters.find(x => String(x.id) === String(route.params.id)) || null)

const PAGE = 3
const studyPage = ref(1)
const studyFilter = ref('all')
const studyFilterOptions = [
  { value: 'all', label: '전체' },
  { value: 'report', label: '리포트' },
  { value: 'quiz', label: '퀴즈' },
]
const reports = computed(() => gameState.reports.filter(r => String(r.characterId) === String(c.value?.id)))
const quizzes = computed(() => gameState.quizzes.filter(q => q.scored && String(q.characterId) === String(c.value?.id)))
function paged(list, page) { return list.slice((page - 1) * PAGE, page * PAGE) }
function pages(list) { return Math.max(1, Math.ceil(list.length / PAGE)) }
function tagsText(tags = []) {
  return tags.map(t => STAT_LABELS[t]).filter(Boolean).join(', ')
}
const studyRecords = computed(() => {
  const reportRecords = reports.value.map((r, index) => ({
    key: `report-${r.id}`,
    type: 'report',
    to: { name: 'report-detail', params: { id: r.id } },
    order: index,
    date: r.date,
    title: r.title,
    badge: r.status === 'analyzing' ? '분석 대기' : '반영됨',
    badgeClass: r.status === 'analyzing' ? 'cg-badge--warn' : 'cg-badge--ok',
    meta: tagsText(r.tags),
  }))
  const quizRecords = quizzes.value.map((q, index) => ({
    key: `quiz-${q.id}`,
    type: 'quiz',
    to: { name: 'quiz-detail', params: { id: q.id } },
    order: reports.value.length + index,
    date: q.date,
    title: q.question,
    badge: q.correct ? '충분' : '보완',
    badgeClass: q.correct ? 'cg-badge--ok' : 'cg-badge--fire',
    meta: `${STAT_LABELS[q.deltaStat] || q.deltaStat} +${q.deltaAmount}`,
  }))
  return [...reportRecords, ...quizRecords]
    .filter(record => studyFilter.value === 'all' || record.type === studyFilter.value)
    .sort((a, b) => b.date.localeCompare(a.date) || a.order - b.order)
})
function statValue(key) {
  const value = Number(c.value?.stats?.[key])
  return Number.isFinite(value) ? value : 0
}
const radarStats = computed(() => [
  { key: 'algo', label: '알고리즘', value: statValue('algo') },
  { key: 'fw', label: '프레임워크', value: statValue('fw') },
  { key: 'cs', label: 'CS', value: statValue('cs') },
  { key: 'net', label: '네트워크', value: statValue('net') },
  { key: 'db', label: 'DB', value: statValue('db') },
])

const editing = ref(false)
const editError = ref('')
const deleteDialogOpen = ref(false)
const deletePending = ref(false)
const deleteError = ref('')
const form = reactive({ name: '' })
watch(c, value => {
  if (!value) return
  Object.assign(form, { name: value.name })
  studyPage.value = 1
}, { immediate: true })
watch(studyFilter, () => { studyPage.value = 1 })

async function saveEdit() {
  editError.value = ''
  try {
    await updateCharacter(c.value.id, {
      name: form.name,
      keyword: c.value.keyword,
      personality: c.value.personality,
    })
    editing.value = false
  } catch (e) {
    editError.value = e.message
  }
}
function beginNameEdit() {
  form.name = c.value?.name || ''
  editError.value = ''
  editing.value = true
}
function cancelNameEdit() {
  form.name = c.value?.name || ''
  editError.value = ''
  editing.value = false
}
async function remove() {
  if (!c.value) return
  deleteError.value = ''
  deleteDialogOpen.value = true
}
function cancelRemove() {
  if (deletePending.value) return
  deleteDialogOpen.value = false
  deleteError.value = ''
}
async function confirmRemove() {
  if (!c.value || deletePending.value) return
  deletePending.value = true
  deleteError.value = ''
  try {
    await deleteCharacter(c.value.id)
    deleteDialogOpen.value = false
    router.push('/')
  } catch (e) {
    deleteError.value = e?.message || '캐릭터를 삭제하지 못했어요.'
  } finally {
    deletePending.value = false
  }
}

async function activate() {
  await setActive(c.value.id)
}
</script>

<template>
  <div v-if="c" class="detail">
    <header class="cg-pagehead">
      <div class="cg-pagehead__main">
        <RouterLink to="/select" class="cg-btn cg-btn--sm cg-back" aria-label="캐릭터 선택 화면으로 돌아가기">←</RouterLink>
        <h1 class="cg-page-title">캐릭터 상세</h1>
      </div>
    </header>

    <section class="detail-grid">
      <div class="left-col col">
        <div class="cg-screen col center charcol">
          <CgEmo :emotion="c.emotion" />
          <CgSprite :size="136" :emotion="c.emotion" :evolved="c.isEvolved"
                    :sprite-sheet-url="c.spriteSheetUrl" :sprite-meta="c.spriteMeta"
                    :pending="c.imageStatus === 'PENDING'" :failed="['FALLBACK', 'FAILED'].includes(c.imageStatus)" />
          <form v-if="editing" class="name-edit row" @submit.prevent="saveEdit">
            <input id="edit-name" v-model="form.name" class="cg-input name-input" maxlength="16" aria-label="이름" />
            <button class="cg-btn cg-btn--sm name-icon" aria-label="이름 저장">✓</button>
            <button type="button" class="cg-btn cg-btn--sm name-icon" aria-label="이름 편집 취소" @click="cancelNameEdit">×</button>
          </form>
          <div v-else class="name-row row center">
            <h1 class="cg-section-title big">{{ c.name }}</h1>
            <button type="button" class="cg-btn cg-btn--sm name-icon" aria-label="이름 편집" @click="beginNameEdit">✎</button>
          </div>
          <p v-if="editError" class="err tiny" role="alert">{{ editError }}</p>
          <p class="tiny faint mono">{{ c.keyword || '기본 디자인' }}</p>
          <span class="cg-badge" :class="c.active ? 'cg-badge--ok' : 'cg-badge--warn'">
            {{ c.active ? '활성 캐릭터' : '비활성 캐릭터' }}
          </span>
          <RouterLink to="/report" class="cg-btn cg-btn--sm cg-btn--primary cg-btn--block">📓 리포트 작성</RouterLink>
          <RouterLink to="/quiz" class="cg-btn cg-btn--sm cg-btn--accent cg-btn--block">🧩 퀴즈 풀기</RouterLink>
        </div>

        <div class="char-actions col">
          <button v-if="!c.active" class="cg-btn cg-btn--sm cg-btn--accent cg-btn--block" @click="activate">활성 캐릭터로 지정</button>
          <button class="cg-btn cg-btn--sm cg-btn--block danger" @click="remove">캐릭터 삭제</button>
        </div>
      </div>

      <div class="side col">
        <div class="cg-card col radar-card">
          <div class="radar-head">
            <div class="cg-section-title">세부 능력치</div>
            <CgGauge class="radar-gauge" :value="nurtureScore(c)" :max="1000" label="육아점수" />
          </div>
          <div class="radar-body">
            <ul class="stat-list">
              <li v-for="item in radarStats" :key="item.key">
                <span>{{ item.label }}</span>
                <strong>{{ item.value }}</strong>
              </li>
            </ul>
            <div class="radar-visual"><CgRadar :stats="c.stats" :size="220" /></div>
          </div>
        </div>

        <section class="lists">
          <div class="cg-card col study-card">
            <div class="study-head row between">
              <div class="cg-section-title">공부 기록</div>
              <select v-model="studyFilter" class="cg-select study-filter" aria-label="공부 기록 필터">
                <option v-for="option in studyFilterOptions" :key="option.value" :value="option.value">
                  {{ option.label }}
                </option>
              </select>
            </div>
            <p v-if="!studyRecords.length" class="muted tiny">해당 기록이 없어요.</p>
            <ul class="rows col">
              <li v-for="record in paged(studyRecords, studyPage)" :key="record.key" class="rowitem">
                <RouterLink :to="record.to" class="rowitem-link" :aria-label="`${record.title} 상세 보기`">
                  <div class="row between">
                    <strong :class="{ qline: record.type === 'quiz' }">{{ record.title }}</strong>
                    <span class="cg-badge" :class="record.badgeClass">{{ record.badge }}</span>
                  </div>
                  <div class="tiny faint mono">
                    {{ record.date }}<span v-if="record.meta"> · {{ record.meta }}</span>
                  </div>
                </RouterLink>
              </li>
            </ul>
            <div class="pager row center" v-if="pages(studyRecords) > 1">
              <button class="cg-btn cg-btn--sm" :disabled="studyPage === 1" @click="studyPage--">‹</button>
              <span class="tiny mono">{{ studyPage }} / {{ pages(studyRecords) }}</span>
              <button class="cg-btn cg-btn--sm" :disabled="studyPage === pages(studyRecords)" @click="studyPage++">›</button>
            </div>
          </div>
        </section>
      </div>
    </section>

    <Transition name="fade">
      <div v-if="deleteDialogOpen" class="modal-backdrop" @click.self="cancelRemove">
        <section class="delete-dialog cg-card col" role="dialog" aria-modal="true" aria-labelledby="delete-dialog-title">
          <div class="col dialog-copy">
            <h2 id="delete-dialog-title" class="cg-section-title">캐릭터 삭제</h2>
            <p class="tiny muted">{{ c.name }}을(를) 삭제할까요?</p>
          </div>
          <p v-if="deleteError" class="err tiny" role="alert">{{ deleteError }}</p>
          <div class="row dialog-actions">
            <button type="button" class="cg-btn cg-btn--sm cg-btn--ghost" :disabled="deletePending" @click="cancelRemove">취소</button>
            <button type="button" class="cg-btn cg-btn--sm danger-btn" :disabled="deletePending" @click="confirmRemove">
              {{ deletePending ? '삭제 중...' : '삭제' }}
            </button>
          </div>
        </section>
      </div>
    </Transition>
  </div>

  <div v-else class="cg-card center col">
    <p>캐릭터를 찾을 수 없어요.</p>
    <RouterLink to="/" class="cg-btn">대시보드로</RouterLink>
  </div>
</template>

<style scoped>
.detail { width: 100%; max-width: 1100px; margin: 0 auto; display: flex; flex-direction: column; gap: var(--sp-4); }
.detail-grid {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: var(--sp-4);
  align-items: start;
}
.left-col {
  justify-self: start;
  width: 220px;
  gap: var(--sp-2);
}
.charcol {
  width: 220px;
  padding: var(--sp-3);
  gap: 8px;
}
.char-actions { width: 100%; gap: 8px; }
.name-row {
  width: 100%;
  gap: 6px;
}
.name-row h1 {
  min-width: 0;
  overflow-wrap: anywhere;
}
.name-icon {
  width: 32px;
  min-width: 32px;
  min-height: 32px;
  padding: 0;
}
.name-edit {
  width: 100%;
  gap: 6px;
}
.name-input {
  min-width: 0;
  height: 36px;
  padding: 6px 8px;
  font-family: var(--font-head);
  font-size: 16px;
  text-align: center;
}
.side { align-items: flex-end; width: 100%; }
.radar-card, .lists { width: min(100%, 520px); }
.radar-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-3);
}
.radar-gauge {
  flex: 0 1 260px;
  width: 260px;
}
.radar-body {
  display: grid;
  grid-template-columns: 140px minmax(0, 1fr);
  gap: var(--sp-3);
  align-items: center;
}
.radar-visual {
  display: flex;
  justify-content: flex-end;
  width: 100%;
}
.stat-list {
  display: grid;
  gap: 8px;
  list-style: none;
  padding: 10px 12px;
  border: 2px solid var(--surface-edge);
  border-radius: var(--r);
  background: var(--surface-2);
}
.stat-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  font-family: var(--font-head);
  font-size: 13px;
}
.stat-list strong { font-family: var(--font-mono); font-weight: 400; color: var(--ink); }
.lists { display: grid; gap: var(--sp-3); }
.study-card { gap: var(--sp-3); }
.study-head { gap: var(--sp-3); align-items: center; }
.study-filter { width: 116px; min-height: 36px; padding: 6px 10px; font-size: 13px; }
.rows { gap: 8px; list-style: none; }
.rowitem {
  border: 2px solid var(--surface-edge);
  border-radius: var(--r);
  background: var(--surface-2);
  transition: transform .06s ease, box-shadow .06s ease, border-color .15s ease;
}
.rowitem:hover {
  transform: translate(-1px, -1px);
  border-color: var(--primary-d);
  box-shadow: 3px 3px 0 var(--shadow-hard);
}
.rowitem:focus-within {
  border-color: var(--primary-d);
}
.rowitem-link {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
}
.qline { font-size: 13px; line-height: 1.4; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.pager { gap: 10px; margin-top: 4px; }
.danger { color: var(--angry); }
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: grid;
  place-items: center;
  padding: var(--sp-4);
  background: var(--overlay);
}
.delete-dialog {
  width: min(100%, 360px);
  gap: var(--sp-3);
  background: var(--popup-bg);
  border-color: var(--popup-edge);
  box-shadow: 6px 6px 0 var(--shadow-hard);
}
.dialog-copy { gap: 6px; }
.dialog-actions {
  justify-content: flex-end;
  gap: var(--sp-2);
}
.danger-btn {
  background: var(--badge-fire-bg);
  border-color: var(--badge-fire-edge);
  color: var(--badge-fire-fg);
}
.err { color: var(--angry); }
@media (max-width: 680px) {
  .detail-grid { grid-template-columns: 1fr; }
  .left-col, .charcol, .radar-card, .lists { width: 100%; }
  .side { align-items: stretch; }
}
@media (max-width: 440px) {
  .radar-head {
    flex-direction: column;
    align-items: stretch;
  }
  .radar-gauge { width: 100%; }
  .radar-body { grid-template-columns: 1fr; }
}
</style>
