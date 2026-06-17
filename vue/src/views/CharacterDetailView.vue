<script setup>
/*
 * Screen #4 — 캐릭터 상세.
 * 좌: 캐릭터 + 리포트 작성 진입 / 우: 5각형 레이더.
 * 하단: 독립 페이지네이션 2-리스트 ① 리포트 기록 ② 퀴즈 기록 (섞지 않음).
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
const reportPage = ref(1)
const quizPage = ref(1)
const reports = computed(() => gameState.reports.filter(r => String(r.characterId) === String(c.value?.id)))
const quizzes = computed(() => gameState.quizzes.filter(q => q.scored && String(q.characterId) === String(c.value?.id)))
function paged(list, page) { return list.slice((page - 1) * PAGE, page * PAGE) }
function pages(list) { return Math.max(1, Math.ceil(list.length / PAGE)) }

const editing = ref(false)
const editError = ref('')
const form = reactive({ name: '', keyword: '', personality: '' })
watch(c, value => {
  if (!value) return
  Object.assign(form, { name: value.name, keyword: value.keyword, personality: value.personality })
  reportPage.value = 1
  quizPage.value = 1
}, { immediate: true })

async function saveEdit() {
  editError.value = ''
  try {
    await updateCharacter(c.value.id, form)
    editing.value = false
  } catch (e) {
    editError.value = e.message
  }
}
async function remove() {
  if (!window.confirm(`${c.value.name}을(를) 삭제할까요?`)) return
  await deleteCharacter(c.value.id)
  router.push('/')
}

async function activate() {
  await setActive(c.value.id)
}
</script>

<template>
  <div v-if="c" class="detail col">
    <section class="top">
      <div class="cg-screen col center charcol">
        <CgEmo :emotion="c.emotion" />
        <CgSprite :size="190" :emotion="c.emotion" :evolved="c.isEvolved"
                  :pending="c.imageStatus === 'PENDING'" :failed="c.imageStatus === 'FAILED'" />
        <h1 class="cg-section-title big">{{ c.name }}</h1>
        <p class="tiny faint mono">{{ c.keyword || '기본 디자인' }}</p>
        <span class="cg-badge" :class="c.active ? 'cg-badge--ok' : 'cg-badge--warn'">
          {{ c.active ? '활성 캐릭터' : '비활성 캐릭터' }}
        </span>
        <button v-if="!c.active" class="cg-btn cg-btn--accent" @click="activate">활성 캐릭터로 지정</button>
        <button class="cg-btn" @click="editing = !editing">{{ editing ? '편집 취소' : '캐릭터 편집' }}</button>
        <button class="cg-btn danger" @click="remove">캐릭터 삭제</button>
        <RouterLink to="/report" class="cg-btn cg-btn--primary">📓 리포트 작성</RouterLink>
      </div>

      <div class="cg-card col">
        <div class="cg-section-title">능력치 레이더</div>
        <div class="center"><CgRadar :stats="c.stats" :size="260" /></div>
        <CgGauge :value="nurtureScore(c)" :max="1000" label="육아점수" />
        <form v-if="editing" class="col edit" @submit.prevent="saveEdit">
          <div class="cg-field">
            <label class="cg-label" for="edit-name">이름</label>
            <input id="edit-name" v-model="form.name" class="cg-input" maxlength="16" />
          </div>
          <div class="cg-field">
            <label class="cg-label" for="edit-keyword">디자인 키워드</label>
            <textarea id="edit-keyword" v-model="form.keyword" class="cg-textarea" />
          </div>
          <div class="cg-field">
            <label class="cg-label" for="edit-personality">성격</label>
            <input id="edit-personality" v-model="form.personality" class="cg-input" />
          </div>
          <p class="tiny muted">능력치, 육아점수, 진화 상태는 학습 결과로만 변경됩니다.</p>
          <p v-if="editError" class="err" role="alert">{{ editError }}</p>
          <button class="cg-btn cg-btn--primary">편집 저장</button>
        </form>
      </div>
    </section>

    <section class="lists">
      <!-- report history -->
      <div class="cg-card col">
        <div class="cg-section-title">① 리포트 기록</div>
        <p v-if="!reports.length" class="muted tiny">아직 리포트가 없어요.</p>
        <ul class="rows col">
          <li v-for="r in paged(reports, reportPage)" :key="r.id" class="rowitem">
            <div class="row between">
              <strong>{{ r.title }}</strong>
              <span class="cg-badge" :class="r.status === 'analyzing' ? 'cg-badge--warn' : 'cg-badge--ok'">
                {{ r.status === 'analyzing' ? '분석 대기' : '반영됨' }}
              </span>
            </div>
            <div class="tiny faint mono">{{ r.date }} · {{ (r.tags || []).map(t => STAT_LABELS[t]).join(', ') }}</div>
          </li>
        </ul>
        <div class="pager row center" v-if="pages(reports) > 1">
          <button class="cg-btn cg-btn--sm" :disabled="reportPage === 1" @click="reportPage--">‹</button>
          <span class="tiny mono">{{ reportPage }} / {{ pages(reports) }}</span>
          <button class="cg-btn cg-btn--sm" :disabled="reportPage === pages(reports)" @click="reportPage++">›</button>
        </div>
      </div>

      <!-- quiz history -->
      <div class="cg-card col">
        <div class="cg-section-title">② 퀴즈 기록</div>
        <p v-if="!quizzes.length" class="muted tiny">제출한 퀴즈가 없어요.</p>
        <ul class="rows col">
          <li v-for="q in paged(quizzes, quizPage)" :key="q.id" class="rowitem">
            <div class="row between">
              <strong class="qline">{{ q.question }}</strong>
              <span class="cg-badge" :class="q.correct ? 'cg-badge--ok' : 'cg-badge--fire'">
                {{ q.correct ? '정답' : '오답' }}
              </span>
            </div>
            <div class="tiny faint mono">{{ q.date }} · {{ STAT_LABELS[q.deltaStat] }} +{{ q.deltaAmount }}</div>
          </li>
        </ul>
        <div class="pager row center" v-if="pages(quizzes) > 1">
          <button class="cg-btn cg-btn--sm" :disabled="quizPage === 1" @click="quizPage--">‹</button>
          <span class="tiny mono">{{ quizPage }} / {{ pages(quizzes) }}</span>
          <button class="cg-btn cg-btn--sm" :disabled="quizPage === pages(quizzes)" @click="quizPage++">›</button>
        </div>
      </div>
    </section>
  </div>

  <div v-else class="cg-card center col">
    <p>캐릭터를 찾을 수 없어요.</p>
    <RouterLink to="/" class="cg-btn">대시보드로</RouterLink>
  </div>
</template>

<style scoped>
.detail { gap: var(--sp-4); }
.top { display: grid; grid-template-columns: 320px 1fr; gap: var(--sp-4); align-items: start; }
.charcol { padding: var(--sp-5); gap: var(--sp-3); }
.lists { display: grid; grid-template-columns: 1fr 1fr; gap: var(--sp-4); }
.rows { gap: 8px; list-style: none; }
.rowitem { border: 2px solid var(--surface-edge); border-radius: var(--r); padding: 10px 12px; background: var(--surface-2); }
.qline { font-size: 13px; line-height: 1.4; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.pager { gap: 10px; margin-top: 4px; }
.edit { border-top: 2px solid var(--surface-edge); padding-top: var(--sp-3); }
.danger { color: var(--angry); }
.err { color: var(--angry); }
@media (max-width: 860px) { .top, .lists { grid-template-columns: 1fr; } }
</style>
