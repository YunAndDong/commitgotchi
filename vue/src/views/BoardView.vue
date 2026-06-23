<script setup>
/*
 * Screen #8 — 커밋고치 리뷰 작성.
 */
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  activeCharacter, addReview, createBoardPost, nurtureScore,
} from '../stores/game.js'
import CgSprite from '../components/CgSprite.vue'

const router = useRouter()
const draft = ref('')
const rating = ref(5)
const previewEmotion = ref('joy')
const saving = ref(false)
const savedDialogOpen = ref(false)
const savedTargetId = ref(null)
const emotions = [
  { value: 'joy', label: '기쁨' },
  { value: 'sad', label: '슬픔' },
  { value: 'angry', label: '화남' },
]
const character = computed(() => activeCharacter.value)
const score = computed(() => character.value ? nurtureScore(character.value) : 0)

watch(character, value => {
  if (value?.emotion) previewEmotion.value = value.emotion
}, { immediate: true })

function goBack() {
  if (character.value?.id) {
    router.push({ name: 'codex', query: { characterId: character.value.id } })
    return
  }
  router.back()
}

async function create() {
  const target = character.value
  if (!target || !draft.value.trim() || saving.value) return
  saving.value = true
  try {
    const post = await createBoardPost(draft.value)
    if (!post) return
    const review = await addReview(post.id, rating.value, draft.value)
    if (!review) return
    draft.value = ''
    savedTargetId.value = target.id
    savedDialogOpen.value = true
  } finally {
    saving.value = false
  }
}
function closeSavedDialog() {
  savedDialogOpen.value = false
  const targetId = savedTargetId.value
  savedTargetId.value = null
  if (targetId) router.push({ name: 'codex', query: { characterId: targetId } })
}
</script>

<template>
  <div class="board col">
    <div class="board-head row">
      <button type="button" class="back-btn cg-btn cg-btn--sm" aria-label="이전 화면으로 돌아가기" @click="goBack">←</button>
      <h1 class="cg-section-title big">커밋고치 리뷰</h1>
    </div>

    <div class="review-layout">
      <section class="gotchi-card cg-card col">
        <div class="emotion-tabs row center" aria-label="커밋고치 감정 선택">
          <button v-for="emotion in emotions" :key="emotion.value" type="button"
                  class="emotion-chip"
                  :class="{ active: previewEmotion === emotion.value }"
                  @click="previewEmotion = emotion.value">
            {{ emotion.label }}
          </button>
        </div>

        <div class="gotchi-screen cg-screen center">
          <CgSprite v-if="character" :size="132" :emotion="previewEmotion" :evolved="character.isEvolved"
                    :sprite-sheet-url="character.spriteSheetUrl" :sprite-meta="character.spriteMeta" />
          <span v-else class="tiny muted">활성 커밋고치 없음</span>
        </div>

        <div class="gotchi-meta col center">
          <strong class="gotchi-name">{{ character?.name || '커밋고치' }}</strong>
          <p class="personality tiny muted">
            {{ character?.personality || '함께 성장할 커밋고치를 선택해 주세요.' }}
          </p>
          <span class="cg-tag">육아점수 {{ score.toLocaleString() }}</span>
        </div>
      </section>

      <section class="review-card cg-card col">
        <div class="review-card__head row between">
          <label class="cg-label review-title" for="post-desc">리뷰 작성하기</label>
          <div class="rating-picker row" aria-label="별점 선택">
            <button v-for="star in 5" :key="star" type="button"
                    class="star-btn"
                    :class="{ active: star <= rating }"
                    :aria-label="`${star}점`"
                    @click="rating = star">
              ★
            </button>
          </div>
        </div>
        <textarea id="post-desc" v-model="draft" class="cg-textarea"
                  placeholder="커밋고치와 함께한 학습 여정을 리뷰로 남겨 주세요."></textarea>
        <button class="cg-btn cg-btn--primary" :disabled="saving || !character || !draft.trim()" @click="create">
          {{ saving ? '저장 중...' : '리뷰 작성' }}
        </button>
      </section>
    </div>

    <Transition name="fade">
      <div v-if="savedDialogOpen" class="modal-backdrop" @click.self="closeSavedDialog">
        <section class="save-dialog cg-card col" role="dialog" aria-modal="true" aria-labelledby="save-dialog-title">
          <div class="col dialog-copy">
            <h2 id="save-dialog-title" class="cg-section-title">리뷰 저장 완료</h2>
            <p class="tiny muted">작성한 리뷰가 저장되었습니다.</p>
          </div>
          <div class="row dialog-actions">
            <button type="button" class="cg-btn cg-btn--sm cg-btn--primary" @click="closeSavedDialog">확인</button>
          </div>
        </section>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.board {
  width: 100%;
  max-width: 920px;
  gap: var(--sp-4);
}
.board-head {
  min-width: 0;
  gap: var(--sp-2);
}
.back-btn {
  width: 36px;
  min-width: 36px;
  padding: 0;
  font-size: 18px;
}
.review-layout {
  display: grid;
  grid-template-columns: minmax(210px, 240px) minmax(0, 1fr);
  gap: var(--sp-4);
  align-items: stretch;
}
.gotchi-card,
.review-card {
  min-width: 0;
}
.gotchi-card {
  gap: var(--sp-3);
}
.emotion-tabs {
  gap: 6px;
  flex-wrap: wrap;
}
.emotion-chip {
  min-height: 30px;
  padding: 4px 10px;
  border: 1.5px solid var(--surface-edge);
  border-radius: var(--r-pill);
  background: var(--surface-2);
  color: var(--ink-soft);
  font-family: var(--font-head);
  font-size: 12px;
}
.emotion-chip.active {
  background: var(--primary);
  border-color: var(--primary-d);
  color: var(--on-primary);
}
.gotchi-screen {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 166px;
  padding: var(--sp-3);
}
.gotchi-meta {
  gap: 6px;
  text-align: center;
}
.gotchi-name {
  max-width: 100%;
  overflow-wrap: anywhere;
  font-family: var(--font-head);
  font-size: 17px;
}
.personality {
  min-height: 36px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.review-card {
  gap: var(--sp-3);
}
.review-card__head {
  gap: var(--sp-3);
  align-items: center;
}
.review-title {
  color: var(--ink);
  font-size: 15px;
}
.rating-picker {
  gap: 2px;
  flex: 0 0 auto;
}
.star-btn {
  width: 28px;
  height: 30px;
  border: 0;
  background: transparent;
  color: var(--surface-edge);
  font-size: 22px;
  line-height: 1;
}
.star-btn.active {
  color: var(--gold);
}
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: grid;
  place-items: center;
  padding: var(--sp-4);
  background: rgba(20, 20, 20, .42);
}
.save-dialog {
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
@media (max-width: 680px) {
  .review-layout { grid-template-columns: 1fr; }
  .review-card__head { align-items: flex-start; flex-direction: column; }
}
</style>
