<script setup>
/*
 * 캐릭터 생성 입력 폼 (스파인 보강, FR-3) → 완료 화면 #11로 연결.
 * 이름 · 디자인 키워드 · 성격 입력. 사용자당 최대 3개.
 */
import { reactive, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { createCharacter, gameState, MAX_CHARACTERS } from '../stores/game.js'
import CgSprite from '../components/CgSprite.vue'
import CgCreationSpinner from '../components/CgCreationSpinner.vue'

const router = useRouter()
const form = reactive({ name: '', keyword: '', personality: '' })
const error = ref('')
const creating = ref(false)
const previewEmotion = ref('joy')
const full = computed(() => gameState.characters.length >= MAX_CHARACTERS)
const KEYWORD_MAX_LENGTH = 100

const keywordChips = ['연두색 새싹', '동그란 눈', '흙갈색 화분', '작은 공룡', '픽셀 별', '몽실몽실 구름']
function addChip(ch) {
  const nextKeyword = form.keyword ? `${form.keyword} + ${ch}` : ch
  form.keyword = nextKeyword.slice(0, KEYWORD_MAX_LENGTH)
}

async function submit() {
  error.value = ''
  if (!form.name.trim()) { error.value = '이름을 입력해 주세요.'; return }
  if (!form.keyword.trim()) { error.value = '디자인 키워드를 입력해 주세요.'; return }
  creating.value = true
  try {
    const c = await createCharacter({
      name: form.name.trim(),
      keyword: form.keyword.trim(),
      personality: form.personality.trim() || '칭찬은 많이, 틀린 건 명확히 짚어주는',
    })
    router.push(`/complete/${c.id}`)
  } catch (e) {
    creating.value = false
    error.value = e.message
  }
}
</script>

<template>
  <div v-if="creating" class="create-pending">
    <div class="create-pending__layout">
      <RouterLink to="/select" class="cg-btn cg-btn--sm cg-back create-pending__back" aria-label="캐릭터 선택 화면으로 돌아가기">←</RouterLink>
      <CgCreationSpinner :name="form.name" :keyword="form.keyword" />
      <span class="create-pending__spacer" aria-hidden="true" />
    </div>
  </div>

  <div v-else class="create">
    <header class="cg-pagehead create-heading">
      <div class="cg-pagehead__main">
        <RouterLink to="/select" class="cg-btn cg-btn--sm cg-back" aria-label="캐릭터 선택 화면으로 돌아가기">←</RouterLink>
        <h1 class="cg-page-title">새 커밋고치 생성하기</h1>
      </div>
    </header>

    <aside class="cg-screen col center preview">
      <span class="tiny muted">미리보기</span>
      <CgSprite :size="160" :emotion="previewEmotion" />
      <strong class="mono">{{ form.name || '이름 없음' }}</strong>
      <div class="row" style="gap:6px">
        <button v-for="e in ['joy','sad','angry']" :key="e"
                class="cg-btn cg-btn--sm" :class="{ 'cg-btn--primary': previewEmotion === e }"
                @click="previewEmotion = e">{{ e === 'joy' ? '😊' : e === 'sad' ? '😢' : '😠' }}</button>
      </div>
      <p class="tiny faint preview-note">
        <span>이미지 생성은 약 1분 정도 걸려요!</span>
        <span>잠시만 기다려주세요</span>
      </p>
    </aside>

    <section class="cg-card col">
      <p class="muted tiny">캐릭터당 5개 스탯은 0에서 시작해요. 보유 {{ gameState.characters.length }}/{{ MAX_CHARACTERS }}.</p>

      <div v-if="full" class="cg-badge cg-badge--warn">캐릭터는 최대 {{ MAX_CHARACTERS }}개까지 만들 수 있어요.</div>

      <form class="col" @submit.prevent="submit">
        <div class="cg-field">
          <label class="cg-label" for="nm">이름</label>
          <input id="nm" class="cg-input" v-model="form.name" maxlength="16" placeholder="예: 새싹이" :disabled="full" />
        </div>

        <div class="cg-field">
          <div class="field-heading">
            <label class="cg-label" for="kw">디자인 키워드</label>
            <span class="tiny faint">{{ form.keyword.length }}/{{ KEYWORD_MAX_LENGTH }}자</span>
          </div>
          <textarea id="kw" class="cg-textarea keyword-textarea" v-model="form.keyword" :maxlength="KEYWORD_MAX_LENGTH" :disabled="full"
                    placeholder="AI가 이 키워드로 캐릭터 이미지를 생성해요."></textarea>
          <div class="row wrap" style="gap:6px">
            <button v-for="ch in keywordChips" :key="ch" type="button" class="cg-tag chipbtn"
                    @click="addChip(ch)" :disabled="full">+ {{ ch }}</button>
          </div>
        </div>

        <div class="cg-field">
          <label class="cg-label" for="ps">성격</label>
          <input id="ps" class="cg-input" v-model="form.personality" :disabled="full"
                 placeholder="예: 칭찬은 많지만 틀린 건 명확히 짚는" />
          <p class="tiny faint">성격은 캐릭터의 말풍선 톤에 반영돼요 (FR-23).</p>
        </div>

        <p v-if="error" class="err" role="alert">{{ error }}</p>
        <button class="cg-btn cg-btn--primary cg-btn--block" :disabled="full || creating">✨ 분신 생성하기</button>
      </form>
    </section>
  </div>
</template>

<style scoped>
.create-pending {
  display: grid;
  place-items: center;
  width: 100%;
  max-width: 960px;
  min-height: 70vh;
  margin: 0 auto;
  padding: var(--sp-3);
}
.create-pending__layout {
  display: grid;
  grid-template-columns: 40px minmax(0, 440px) 40px;
  align-items: start;
  justify-content: center;
  gap: var(--sp-3);
  width: min(100%, 544px);
}
.create-pending__back {
  align-self: start;
  justify-self: start;
}
.create-pending__spacer {
  display: block;
  width: 40px;
  height: 40px;
}
.create { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: var(--sp-4); align-items: start; max-width: 960px; margin: 0 auto; }
.create-heading { grid-column: 1 / -1; }
.preview { padding: var(--sp-5); gap: var(--sp-3); position: sticky; top: 90px; }
.preview-note { display: flex; flex-direction: column; text-align: center; }
.field-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--sp-2); }
.keyword-textarea { min-height: 84px; height: 84px; }
.chipbtn { cursor: pointer; }
.err { color: var(--angry); font-family: var(--font-head); font-size: 13px; }
:global(html.is-ext-popup) .create-pending {
  min-height: calc(var(--cg-ext-popup-height) - 72px);
  padding: 0 var(--sp-2);
}
:global(html.is-ext-popup) .create-pending__layout {
  grid-template-columns: 40px minmax(0, 380px) 40px;
  gap: var(--sp-2);
  width: min(100%, 476px);
}
@media (max-width: 760px) { .create { grid-template-columns: 1fr; } .preview { position: static; } }
@media (max-width: 520px) {
  .create-pending__layout {
    grid-template-columns: 40px minmax(0, 1fr);
    width: 100%;
  }
  .create-pending__spacer { display: none; }
}
</style>
