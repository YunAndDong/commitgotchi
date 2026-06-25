<script setup>
/*
 * Screen #3 — 도감.
 * Catalog characters are loaded separately from the user's game state.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import CgSprite from '../components/CgSprite.vue'
import {
  CODEX_REVIEW_PAGE_SIZE,
  codexState,
  deleteCodexReview,
  ensureCodexLoaded,
  loadCodexReviews,
  loadNextCodexPage,
  prepareCodexWindow,
  raiseCodexCharacter,
  submitCodexReview,
  updateCodexReview,
} from '../stores/codex.js'
import { gameState, loadGameState, MAX_CHARACTERS } from '../stores/game.js'

const route = useRoute()
const router = useRouter()
const items = computed(() => codexState.items)
const idx = ref(0)
const reviewPage = ref(0)
const reviewStars = ref(5)
const reviewText = ref('')
const reviewError = ref('')
const editingReview = ref(false)
const editReviewStars = ref(5)
const editReviewText = ref('')
const current = computed(() => items.value[idx.value] || null)
const loadedImageCount = computed(() => Object.keys(codexState.imageCacheById).length)
const currentKey = computed(() => current.value ? String(current.value.id) : '')
const reviewState = computed(() => currentKey.value ? codexState.reviewsById[currentKey.value] || null : null)
const reviewLoading = computed(() => !!(currentKey.value && codexState.loadingReviewIds[currentKey.value]))
const reviewSubmitting = computed(() => !!(currentKey.value && codexState.submittingReviewIds[currentKey.value]))
const reviewRaising = computed(() => !!(currentKey.value && codexState.raisingCharacterIds[currentKey.value]))
const reviewLoadError = computed(() => currentKey.value ? codexState.reviewErrorById[currentKey.value] || null : null)
const canRaiseMore = computed(() => gameState.characters.length < MAX_CHARACTERS)
const averageStarsLabel = computed(() => {
  const value = Number(reviewState.value?.averageStars || 0)
  return value.toFixed(1)
})
const myReviewTitle = computed(() => reviewState.value?.myReview ? '나의 리뷰' : '리뷰 쓰기')

const REVIEW_DATE = new Intl.DateTimeFormat('ko-KR', { month: '2-digit', day: '2-digit' })

function imageFor(item) {
  if (!item) return null
  return codexState.imageCacheById[String(item.id)] || null
}

function isImageLoading(item) {
  return !!(item && codexState.loadingImageIds[String(item.id)])
}

function hasImageError(item) {
  return !!(item && codexState.imageErrorById[String(item.id)])
}

async function move(delta) {
  if (!items.value.length) return
  const next = idx.value + delta
  if (next < 0) {
    idx.value = 0
    return
  }
  if (next >= items.value.length) {
    if (codexState.hasMore) {
      try { await loadNextCodexPage() } catch { /* page error is reflected in state */ }
      idx.value = Math.min(next, Math.max(0, items.value.length - 1))
      return
    }
    idx.value = items.value.length - 1
    return
  }
  idx.value = next
}

function offset(i) {
  return i - idx.value
}

function syncRouteCharacter() {
  const characterId = route.query.characterId
  if (characterId == null) return
  const next = items.value.findIndex(item => String(item.id) === String(characterId))
  if (next >= 0) idx.value = next
}

function prepareCurrentWindow() {
  void prepareCodexWindow(idx.value).catch(() => {
    // The visible placeholder remains usable; retry happens on the next move.
  })
}

function formatReviewDate(value) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : REVIEW_DATE.format(date)
}

function starText(stars) {
  const score = Math.min(5, Math.max(0, Number(stars) || 0))
  return `${'★'.repeat(score)}${'☆'.repeat(5 - score)}`
}

async function loadCurrentReviews(page = reviewPage.value) {
  if (!current.value) return
  reviewError.value = ''
  try {
    const state = await loadCodexReviews(current.value.id, { page, size: CODEX_REVIEW_PAGE_SIZE })
    reviewPage.value = state?.page || 0
  } catch {
    // The store keeps the error for the inline retry state.
  }
}

async function changeReviewPage(delta) {
  const next = Math.max(0, reviewPage.value + delta)
  if (next === reviewPage.value && delta < 0) return
  await loadCurrentReviews(next)
}

async function submitReview() {
  if (!current.value) return
  reviewError.value = ''
  try {
    await submitCodexReview(current.value.id, {
      stars: reviewStars.value,
      text: reviewText.value,
    })
    reviewText.value = ''
    reviewStars.value = 5
    reviewPage.value = 0
  } catch (error) {
    reviewError.value = error?.message || '리뷰를 저장하지 못했어요.'
  }
}

function beginReviewEdit() {
  const mine = reviewState.value?.myReview
  if (!mine) return
  editingReview.value = true
  editReviewStars.value = mine.stars
  editReviewText.value = mine.text
}

function cancelReviewEdit() {
  editingReview.value = false
  editReviewStars.value = 5
  editReviewText.value = ''
  reviewError.value = ''
}

async function saveReviewEdit() {
  const mine = reviewState.value?.myReview
  if (!current.value || !mine) return
  reviewError.value = ''
  try {
    await updateCodexReview(current.value.id, mine.id, {
      stars: editReviewStars.value,
      text: editReviewText.value,
    })
    editingReview.value = false
    editReviewText.value = ''
    editReviewStars.value = 5
    reviewPage.value = 0
  } catch (error) {
    reviewError.value = error?.message || '리뷰를 수정하지 못했어요.'
  }
}

async function removeMyReview() {
  const mine = reviewState.value?.myReview
  if (!current.value || !mine) return
  reviewError.value = ''
  try {
    await deleteCodexReview(current.value.id, mine.id)
    editingReview.value = false
    editReviewText.value = ''
    editReviewStars.value = 5
    reviewPage.value = 0
  } catch (error) {
    reviewError.value = error?.message || '리뷰를 삭제하지 못했어요.'
  }
}

async function raiseCurrent() {
  if (!current.value || !canRaiseMore.value) return
  reviewError.value = ''
  try {
    const response = await raiseCodexCharacter(current.value.id)
    await loadGameState()
    if (response?.userCharacterId) {
      router.push({ name: 'character', params: { id: response.userCharacterId } })
    }
  } catch (error) {
    reviewError.value = error?.message || '커밋고치를 키우기 시작하지 못했어요.'
  }
}

async function retryInitialLoad() {
  try {
    await loadNextCodexPage({ reset: true })
    syncRouteCharacter()
    prepareCurrentWindow()
  } catch {
    // pageError is displayed below.
  }
}

async function loadMore() {
  try {
    await loadNextCodexPage()
    syncRouteCharacter()
    prepareCurrentWindow()
  } catch {
    // pageError is displayed when relevant.
  }
}

onMounted(async () => {
  try {
    await ensureCodexLoaded()
    syncRouteCharacter()
    prepareCurrentWindow()
  } catch {
    // pageError is displayed below.
  }
})

watch([items, () => route.query.characterId], () => {
  syncRouteCharacter()
})

watch(() => items.value.length, length => {
  if (!length) idx.value = 0
  else if (idx.value >= length) idx.value = length - 1
  syncRouteCharacter()
  prepareCurrentWindow()
})

watch(() => current.value?.id, () => {
  prepareCurrentWindow()
  reviewPage.value = 0
  reviewStars.value = 5
  reviewText.value = ''
  editingReview.value = false
  editReviewStars.value = 5
  editReviewText.value = ''
  reviewError.value = ''
  void loadCurrentReviews(0)
}, { immediate: true })
</script>

<template>
  <div class="codex col">
    <header class="cg-pagehead">
      <div class="cg-pagehead__main">
        <RouterLink to="/select" class="cg-btn cg-btn--sm cg-back" aria-label="캐릭터 선택 화면으로 돌아가기">←</RouterLink>
        <h1 class="cg-page-title">도감</h1>
      </div>
      <div class="cg-pagehead__actions">
        <span class="tiny muted">카탈로그 {{ items.length }}개 · 이미지 {{ loadedImageCount }}개</span>
      </div>
    </header>

    <section v-if="codexState.loadingPage && !items.length" class="empty cg-card center col">
      <CgSprite :size="118" emotion="joy" pending />
      <h2 class="cg-section-title big">도감을 불러오는 중</h2>
    </section>

    <section v-else-if="codexState.pageError && !items.length" class="empty cg-card center col">
      <CgSprite :size="118" emotion="sad" grey />
      <h2 class="cg-section-title big">도감을 불러오지 못했어요</h2>
      <button type="button" class="cg-btn cg-btn--primary" @click="retryInitialLoad">다시 시도</button>
    </section>

    <section v-else-if="!current" class="empty cg-card center col">
      <CgSprite :size="118" emotion="joy" grey />
      <h2 class="cg-section-title big">표시할 커밋고치가 없어요</h2>
      <p class="muted">준비된 카탈로그 캐릭터가 생기면 이곳에 나타나요.</p>
    </section>

    <div v-else class="codex-layout">
      <section class="showcase cg-card col">
        <div class="flow cg-screen">
          <button class="navbtn left" :disabled="idx === 0" @click="move(-1)" aria-label="이전">‹</button>
          <div class="track">
            <button
              v-for="(it, i) in items"
              :key="it.id"
              type="button"
              class="carousel-item"
              :class="{
                active: i === idx,
                loaded: !!imageFor(it),
                loading: isImageLoading(it),
                errored: hasImageError(it),
              }"
              :style="{
                transform: `translateX(${offset(i) * 86}px) scale(${i === idx ? 1 : 0.72})`,
                zIndex: 100 - Math.abs(offset(i)),
                opacity: Math.abs(offset(i)) > 2 ? 0 : 1,
              }"
              :aria-label="`도감 캐릭터 ${it.id} 선택`"
              @click="idx = i"
            >
              <CgSprite
                :size="104"
                emotion="joy"
                evolved
                :grey="!imageFor(it) && !isImageLoading(it)"
                :pending="isImageLoading(it)"
                :image-status="imageFor(it)?.imageStatus || it.imageStatus"
                :sprite-sheet-url="imageFor(it)?.spriteSheetUrl || ''"
                :sprite-meta="imageFor(it)?.spriteMeta || it.spriteMeta"
                :bob="i === idx"
              />
            </button>
          </div>
          <button
            class="navbtn right"
            :disabled="!codexState.hasMore && idx >= items.length - 1"
            @click="move(1)"
            aria-label="다음"
          >›</button>
        </div>

        <div class="current center col">
          <strong class="big current-code">Catalog #{{ current.id }}</strong>
          <p class="current-personality tiny muted">
            {{ current.personality || '성격 정보 준비 중' }}
          </p>
          <p class="current-keyword tiny">
            {{ current.designKeyword || '디자인 키워드 준비 중' }}
          </p>
        </div>
      </section>

      <section class="details cg-card col">
        <div class="details-head row between">
          <span class="cg-section-title">리뷰</span>
          <span class="tiny mono">{{ idx + 1 }} / {{ items.length }}</span>
        </div>

        <div class="rating-summary">
          <div>
            <span class="tiny muted">총 별점</span>
            <strong class="rating-score">{{ averageStarsLabel }}</strong>
          </div>
          <span class="rating-stars" aria-hidden="true">{{ starText(Math.round(reviewState?.averageStars || 0)) }}</span>
          <span class="tiny muted">{{ reviewState?.totalReviews || 0 }}개 리뷰</span>
        </div>

        <div v-if="reviewLoading && !reviewState" class="review-status tiny muted">리뷰를 불러오는 중</div>
        <div v-else-if="reviewLoadError && !reviewState" class="review-status col">
          <span class="tiny muted">리뷰를 불러오지 못했어요.</span>
          <button type="button" class="cg-btn cg-btn--sm" @click="loadCurrentReviews(0)">다시 시도</button>
        </div>
        <template v-else>
          <section class="my-review col">
            <span class="tiny muted">{{ myReviewTitle }}</span>

            <div v-if="reviewState?.myReview" class="review-item mine">
              <template v-if="editingReview">
                <div class="star-input" role="radiogroup" aria-label="내 리뷰 별점 수정">
                  <button
                    v-for="score in 5"
                    :key="score"
                    type="button"
                    class="star-button"
                    :class="{ active: score <= editReviewStars }"
                    :aria-pressed="score === editReviewStars"
                    :disabled="reviewSubmitting"
                    @click="editReviewStars = score"
                  >★</button>
                </div>
                <textarea
                  v-model="editReviewText"
                  class="cg-textarea review-textarea"
                  maxlength="1000"
                  placeholder="이 커밋고치를 키워본 느낌"
                  :disabled="reviewSubmitting"
                />
                <div class="review-actions row">
                  <button
                    type="button"
                    class="review-action-link"
                    :disabled="reviewSubmitting || !editReviewText.trim()"
                    @click="saveReviewEdit"
                  >저장</button>
                  <button
                    type="button"
                    class="review-action-link"
                    :disabled="reviewSubmitting"
                    @click="cancelReviewEdit"
                  >취소</button>
                </div>
              </template>
              <template v-else>
                <div class="row between review-top">
                  <strong class="review-stars">{{ starText(reviewState.myReview.stars) }}</strong>
                  <div class="review-meta row">
                    <span class="tiny muted">{{ formatReviewDate(reviewState.myReview.createdAt) }}</span>
                    <button
                      type="button"
                      class="review-action-link"
                      :disabled="reviewSubmitting"
                      @click="beginReviewEdit"
                    >수정</button>
                    <button
                      type="button"
                      class="review-action-link danger"
                      :disabled="reviewSubmitting"
                      @click="removeMyReview"
                    >삭제</button>
                  </div>
                </div>
                <p>{{ reviewState.myReview.text }}</p>
              </template>
            </div>

            <form v-else-if="reviewState?.canReview" class="review-form col" @submit.prevent="submitReview">
              <div class="star-input" role="radiogroup" aria-label="별점">
                <button
                  v-for="score in 5"
                  :key="score"
                  type="button"
                  class="star-button"
                  :class="{ active: score <= reviewStars }"
                  :aria-pressed="score === reviewStars"
                  @click="reviewStars = score"
                >★</button>
              </div>
              <textarea
                v-model="reviewText"
                class="cg-textarea review-textarea"
                maxlength="1000"
                placeholder="이 커밋고치를 키워본 느낌"
              />
              <button type="submit" class="cg-btn cg-btn--primary" :disabled="reviewSubmitting || !reviewText.trim()">
                리뷰 쓰기
              </button>
            </form>

            <div v-else class="raise-panel col">
              <button
                type="button"
                class="cg-btn cg-btn--primary"
                :disabled="reviewRaising || !canRaiseMore"
                @click="raiseCurrent"
              >
                해당 커밋고치 키우기
              </button>
              <span v-if="!canRaiseMore" class="tiny muted">보유 슬롯이 가득 찼어요.</span>
            </div>

            <p v-if="reviewError" class="review-error" role="alert">{{ reviewError }}</p>
          </section>

          <section class="other-reviews col">
            <div class="row between">
              <span class="tiny muted">다른 사람들의 리뷰</span>
              <div class="review-pager row">
                <button
                  type="button"
                  class="pager-btn"
                  :disabled="reviewPage <= 0 || reviewLoading"
                  aria-label="이전 리뷰"
                  @click="changeReviewPage(-1)"
                >‹</button>
                <span class="tiny mono">{{ reviewPage + 1 }}</span>
                <button
                  type="button"
                  class="pager-btn"
                  :disabled="!reviewState?.hasMore || reviewLoading"
                  aria-label="다음 리뷰"
                  @click="changeReviewPage(1)"
                >›</button>
              </div>
            </div>

            <div v-if="reviewState?.reviews?.length" class="review-list col">
              <article v-for="review in reviewState.reviews" :key="review.id" class="review-item">
                <div class="row between">
                  <strong class="review-stars">{{ starText(review.stars) }}</strong>
                  <span class="tiny muted">{{ formatReviewDate(review.createdAt) }}</span>
                </div>
                <p>{{ review.text }}</p>
              </article>
            </div>
            <p v-else class="tiny muted empty-reviews">아직 다른 리뷰가 없어요.</p>
          </section>
        </template>

        <button
          v-if="codexState.hasMore"
          type="button"
          class="cg-btn cg-btn--sm load-more"
          :disabled="codexState.loadingPage"
          @click="loadMore"
        >
          더 보기
        </button>
      </section>
    </div>
  </div>
</template>

<style scoped>
.codex { gap: var(--sp-4); width: 100%; max-width: 1100px; margin: 0 auto; }
.empty {
  min-height: clamp(280px, calc(100vh - 160px), 430px);
  text-align: center;
  gap: var(--sp-3);
}
.codex-layout {
  display: grid;
  grid-template-columns: minmax(250px, 1fr) minmax(0, 1.2fr);
  gap: var(--sp-4);
  align-items: start;
}
.showcase { gap: var(--sp-3); min-width: 0; }
.flow {
  position: relative;
  height: 226px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}
.track {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.carousel-item {
  position: absolute;
  width: 126px;
  height: 138px;
  display: grid;
  place-items: center;
  transition: transform .3s ease, opacity .3s ease, border-color .2s ease;
  cursor: pointer;
  background: var(--surface);
  border: 2px solid var(--surface-edge);
  border-radius: var(--r);
  padding: 8px;
}
.carousel-item.active {
  border-color: var(--primary-d);
  box-shadow: 4px 4px 0 var(--shadow-hard);
}
.carousel-item.loading {
  border-style: dashed;
}
.carousel-item.errored:not(.loaded) {
  border-color: var(--badge-warn-edge);
}
.navbtn {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  z-index: 200;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  font-size: 18px;
  background: var(--surface);
  border: 2px solid var(--surface-edge);
  color: var(--ink);
}
.navbtn:disabled {
  opacity: .45;
  cursor: not-allowed;
}
.navbtn.left { left: 8px; }
.navbtn.right { right: 8px; }
.current {
  gap: 8px;
  min-height: 102px;
  padding-top: var(--sp-1);
}
.current-code,
.current-personality,
.current-keyword {
  max-width: 100%;
  text-align: center;
  overflow-wrap: anywhere;
}
.current-personality,
.current-keyword {
  line-height: 1.5;
}
.current-keyword {
  color: var(--primary-d);
  font-family: var(--font-head);
}
.details {
  min-width: 0;
  gap: var(--sp-4);
}
.details-head {
  align-items: center;
  gap: var(--sp-2);
}
.rating-summary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: var(--sp-2);
  align-items: center;
  padding-bottom: var(--sp-3);
  border-bottom: 1.5px dashed var(--surface-edge);
}
.rating-score {
  display: block;
  margin-top: 4px;
  color: var(--ink);
  font-family: var(--font-head);
  font-size: 30px;
  line-height: 1;
}
.rating-stars,
.review-stars {
  color: var(--primary-d);
  letter-spacing: 0;
}
.review-status {
  min-height: 108px;
  justify-content: center;
  align-items: flex-start;
  gap: var(--sp-2);
}
.my-review,
.other-reviews {
  gap: var(--sp-2);
}
.review-form {
  gap: var(--sp-2);
}
.review-top {
  align-items: flex-start;
  gap: var(--sp-2);
}
.review-meta,
.review-actions {
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.review-meta {
  justify-content: flex-end;
}
.review-action-link {
  display: inline;
  border: 0;
  background: none;
  color: var(--ink-muted);
  font-family: var(--font-head);
  font-size: 12px;
  line-height: 1.4;
  padding: 0;
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}
.review-action-link:hover:not(:disabled) {
  color: var(--primary-d);
}
.review-action-link.danger:hover:not(:disabled) {
  color: var(--angry);
}
.review-action-link:disabled {
  opacity: .45;
  cursor: not-allowed;
}
.star-input {
  display: flex;
  gap: 4px;
}
.star-button,
.pager-btn {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 1.5px solid var(--surface-edge);
  border-radius: var(--r-sm);
  background: var(--surface);
  color: var(--ink-muted);
  line-height: 1;
}
.star-button.active {
  color: var(--primary-d);
  border-color: var(--primary-d);
}
.star-button:disabled {
  opacity: .55;
  cursor: not-allowed;
}
.pager-btn:disabled {
  opacity: .45;
  cursor: not-allowed;
}
.review-textarea {
  min-height: 84px;
  height: 84px;
}
.raise-panel {
  align-items: flex-start;
  gap: 8px;
}
.review-error {
  margin: 0;
  color: var(--angry);
  font-family: var(--font-head);
  font-size: 12px;
}
.review-pager {
  align-items: center;
  gap: 6px;
}
.review-list {
  gap: var(--sp-2);
}
.review-item {
  display: grid;
  gap: 6px;
  padding: var(--sp-2) 0;
  border-bottom: 1.5px dashed var(--surface-edge);
}
.review-item.mine {
  border-top: 1.5px dashed var(--surface-edge);
}
.review-item p,
.empty-reviews {
  margin: 0;
  line-height: 1.55;
  overflow-wrap: anywhere;
}
.data-list {
  display: grid;
  gap: var(--sp-3);
  margin: 0;
}
.data-row {
  display: grid;
  gap: 6px;
  padding-bottom: var(--sp-3);
  border-bottom: 1.5px dashed var(--surface-edge);
}
.data-row:last-child {
  border-bottom: none;
  padding-bottom: 0;
}
.data-row dt {
  color: var(--ink-muted);
  font-size: 12px;
  font-family: var(--font-head);
}
.data-row dd {
  margin: 0;
  color: var(--ink-soft);
  line-height: 1.55;
  overflow-wrap: anywhere;
}
.load-more {
  align-self: flex-start;
}
@media (max-width: 720px) {
  .codex-layout { grid-template-columns: 1fr; }
  .flow { height: 220px; }
}
</style>
