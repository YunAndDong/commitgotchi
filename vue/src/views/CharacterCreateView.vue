<script setup>
/*
 * 캐릭터 생성 입력 폼 (스파인 보강, FR-3) → 완료 화면 #11로 연결.
 * 이름 · 디자인 키워드 · 성격 입력. 사용자당 최대 3개.
 */
import { reactive, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { createCharacter, gameState, MAX_CHARACTERS } from '../stores/game.js'
import CgSprite from '../components/CgSprite.vue'

const router = useRouter()
const form = reactive({ name: '', keyword: '', personality: '' })
const error = ref('')
const previewEmotion = ref('joy')
const failImage = ref(false) // 데모: 이미지 생성 실패(Fallback) 재현
const full = computed(() => gameState.characters.length >= MAX_CHARACTERS)

const keywordChips = ['연두색 새싹', '동그란 눈', '흙갈색 화분', '작은 공룡', '픽셀 별', '몽실몽실 구름']
function addChip(ch) {
  form.keyword = form.keyword ? `${form.keyword} + ${ch}` : ch
}

async function submit() {
  error.value = ''
  if (!form.name.trim()) { error.value = '이름을 입력해 주세요.'; return }
  try {
    const c = await createCharacter({
      name: form.name.trim(),
      keyword: form.keyword.trim(),
      personality: form.personality.trim() || '칭찬은 많이, 틀린 건 명확히 짚어주는',
    }, { failImage: failImage.value })
    router.push(`/complete/${c.id}`)
  } catch (e) {
    error.value = e.message
  }
}
</script>

<template>
  <div class="create">
    <section class="cg-card col">
      <h1 class="cg-section-title big">새 분신 만들기</h1>
      <p class="muted tiny">캐릭터당 5개 스탯은 0에서 시작해요. 보유 {{ gameState.characters.length }}/{{ MAX_CHARACTERS }}.</p>

      <div v-if="full" class="cg-badge cg-badge--warn">캐릭터는 최대 {{ MAX_CHARACTERS }}개까지 만들 수 있어요.</div>

      <form class="col" @submit.prevent="submit">
        <div class="cg-field">
          <label class="cg-label" for="nm">이름</label>
          <input id="nm" class="cg-input" v-model="form.name" maxlength="16" placeholder="예: 새싹이" :disabled="full" />
        </div>

        <div class="cg-field">
          <label class="cg-label" for="kw">디자인 키워드</label>
          <textarea id="kw" class="cg-textarea" v-model="form.keyword" :disabled="full"
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

        <label class="demo tiny muted">
          <input type="checkbox" v-model="failImage" :disabled="full" /> 데모: 이미지 생성 실패 재현(Fallback)
        </label>

        <p v-if="error" class="err" role="alert">{{ error }}</p>
        <button class="cg-btn cg-btn--primary cg-btn--block" :disabled="full">✨ 분신 생성하기</button>
      </form>
    </section>

    <aside class="cg-screen col center preview">
      <span class="tiny muted">미리보기</span>
      <CgSprite :size="160" :emotion="previewEmotion" />
      <strong class="mono">{{ form.name || '이름 없음' }}</strong>
      <div class="row" style="gap:6px">
        <button v-for="e in ['joy','sad','angry']" :key="e"
                class="cg-btn cg-btn--sm" :class="{ 'cg-btn--primary': previewEmotion === e }"
                @click="previewEmotion = e">{{ e === 'joy' ? '😊' : e === 'sad' ? '😢' : '😠' }}</button>
      </div>
      <p class="tiny faint" style="text-align:center">실제 이미지는 생성 직후 비동기로 만들어져요 (흐름 C).</p>
    </aside>
  </div>
</template>

<style scoped>
.create { display: grid; grid-template-columns: 1fr 320px; gap: var(--sp-4); align-items: start; max-width: 900px; margin: 0 auto; }
.preview { padding: var(--sp-5); gap: var(--sp-3); position: sticky; top: 90px; }
.chipbtn { cursor: pointer; }
.demo { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; }
.err { color: var(--angry); font-family: var(--font-head); font-size: 13px; }
@media (max-width: 760px) { .create { grid-template-columns: 1fr; } .preview { position: static; } }
</style>
