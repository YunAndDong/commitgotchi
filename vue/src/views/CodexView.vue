<script setup>
/*
 * Screen #3 — 도감 / 보관함 (Codex).
 * 커버플로우. 컬러=수집 / 회색=미획득.
 */
import { ref, computed, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { board, codex, CURRENT_OWNER, deleteReview } from '../stores/game.js'
import CgSprite from '../components/CgSprite.vue'

const route = useRoute()
const items = computed(() => codex())
const posts = computed(() => board())
const idx = ref(0)
const current = computed(() => items.value[idx.value] || null)
const REVIEW_PAGE_SIZE = 3
const reviewPage = ref(1)
function move(d) {
  if (!items.value.length) return
  idx.value = (idx.value + d + items.value.length) % items.value.length
}
function offset(i) { return i - idx.value }
function starline(n) {
  const score = Math.max(0, Math.min(5, Math.round(Number(n) || 0)))
  return '★'.repeat(score) + '☆'.repeat(5 - score)
}
function formatReviewDate(review) {
  const raw = review?.createdAt || review?.created_at || review?.date
  if (!raw) return '작성일 미상'
  const date = new Date(raw)
  if (Number.isNaN(date.getTime())) return String(raw)
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'short',
    timeStyle: 'short',
  }).format(date)
}

const selectedPosts = computed(() => {
  const target = current.value
  if (!target) return []
  return posts.value.filter(post => (
    String(post.characterId) === String(target.id) || post.name === target.name
  ))
})
const selectedReviews = computed(() => selectedPosts.value.flatMap(post => (
  post.reviews.map(review => ({
    ...review,
    postId: post.id,
  }))
)))
const reviewCount = computed(() => selectedReviews.value.length)
const myReview = computed(() => selectedReviews.value.find(review => review.author === CURRENT_OWNER) || null)
const reviewWritePath = computed(() => {
  const targetPost = selectedPosts.value[0]
  return targetPost ? `/board/${targetPost.id}` : '/board'
})
const rating = computed(() => {
  if (!reviewCount.value) return 0
  const sum = selectedReviews.value.reduce((total, review) => total + review.stars, 0)
  return Number((sum / reviewCount.value).toFixed(1))
})
const topReview = computed(() => selectedReviews.value.reduce((best, review) => {
  if (!best || review.stars > best.stars) return review
  return best
}, null))
const reviewPages = computed(() => Math.max(1, Math.ceil(reviewCount.value / REVIEW_PAGE_SIZE)))
const pagedReviews = computed(() => selectedReviews.value.slice(
  (reviewPage.value - 1) * REVIEW_PAGE_SIZE,
  reviewPage.value * REVIEW_PAGE_SIZE,
))

async function removeMyReview() {
  if (!myReview.value) return
  await deleteReview(myReview.value.postId, myReview.value.id)
}

watch([items, () => route.query.characterId], ([list, characterId]) => {
  if (characterId == null) return
  const next = list.findIndex(item => String(item.id) === String(characterId))
  if (next >= 0) idx.value = next
}, { immediate: true })
watch(items, list => {
  if (!list.length) idx.value = 0
  else if (idx.value >= list.length) idx.value = list.length - 1
})
watch(() => current.value?.id, () => { reviewPage.value = 1 })
watch(reviewPages, pages => { reviewPage.value = Math.min(reviewPage.value, pages) })
</script>

<template>
  <div class="codex col">
    <header class="cg-pagehead">
      <div class="cg-pagehead__main">
        <RouterLink to="/select" class="cg-btn cg-btn--sm cg-back" aria-label="캐릭터 선택 화면으로 돌아가기">←</RouterLink>
        <h1 class="cg-page-title">도감 · 보관함</h1>
      </div>
      <div class="cg-pagehead__actions">
        <span class="tiny muted">{{ items.filter(i => i.owned).length }} / {{ items.length }} 수집</span>
      </div>
    </header>

    <section v-if="!current" class="empty cg-card center col">
      <CgSprite :size="118" emotion="joy" grey />
      <h2 class="cg-section-title big">보유한 커밋고치가 없어요</h2>
      <p class="muted">새 커밋고치를 만들면 이곳에 보관돼요.</p>
      <RouterLink to="/create" class="cg-btn cg-btn--primary">커밋고치 생성</RouterLink>
    </section>

    <div v-else class="codex-layout">
      <section class="showcase cg-card col">
        <div class="flow cg-screen">
          <button class="navbtn left" @click="move(-1)" aria-label="이전">‹</button>
          <div class="track">
            <button v-for="(it, i) in items" :key="it.id" type="button" class="carousel-item"
                    :class="{ active: i === idx, owned: it.owned }"
                    :style="{ transform: `translateX(${offset(i) * 86}px) scale(${i === idx ? 1 : 0.72})`, zIndex: 100 - Math.abs(offset(i)), opacity: Math.abs(offset(i)) > 2 ? 0 : 1 }"
                    :aria-label="`${it.name} 선택`"
                    @click="idx = i">
              <CgSprite :size="104" :emotion="it.emotion" :evolved="it.isEvolved"
                        :sprite-sheet-url="it.spriteSheetUrl" :sprite-meta="it.spriteMeta"
                        :bob="i === idx" />
            </button>
          </div>
          <button class="navbtn right" @click="move(1)" aria-label="다음">›</button>
        </div>

        <div class="current center col">
          <strong class="big current-name">{{ current.name }}</strong>
          <p class="current-personality tiny muted">
            {{ current.personality || '차분히 함께 성장하는' }}
          </p>
          <span class="cg-badge cg-badge--ok">수집 완료</span>
        </div>
      </section>

      <section class="reviews cg-card col">
        <div class="review-head row between">
          <div class="review-title col">
            <span class="cg-section-title">{{ current.name }}</span>
            <span class="tiny muted">리뷰 {{ reviewCount }}개</span>
          </div>
          <strong class="rating" :aria-label="`평점 ${rating}`">
            <span aria-hidden="true">★</span> {{ rating.toFixed(1) }}
          </strong>
        </div>

        <div class="my-review review-summary">
          <template v-if="myReview">
            <div class="review-summary__meta row between">
              <div class="review-summary__meta-left row">
                <span class="cg-label">나의 리뷰</span>
                <RouterLink :to="`/board/${myReview.postId}`" class="review-action">수정</RouterLink>
                <button type="button" class="review-action review-action--button" @click="removeMyReview">삭제</button>
              </div>
              <time class="tiny faint" :datetime="myReview.createdAt || undefined">
                {{ formatReviewDate(myReview) }}
              </time>
            </div>
            <div class="review-summary__body row between">
              <p>{{ myReview.text }}</p>
              <span class="gold tiny review-stars">{{ starline(myReview.stars) }}</span>
            </div>
          </template>

          <RouterLink v-else :to="reviewWritePath" class="cg-btn cg-btn--primary cg-btn--sm">
            리뷰 작성
          </RouterLink>
        </div>

        <div v-if="topReview" class="top-review review-summary">
          <div class="review-summary__meta row between">
            <div class="review-summary__meta-left row">
              <span class="cg-label">최고 별점 리뷰</span>
              <span class="tiny faint">@{{ topReview.author }}</span>
            </div>
            <time class="tiny faint" :datetime="topReview.createdAt || undefined">
              {{ formatReviewDate(topReview) }}
            </time>
          </div>
          <div class="review-summary__body row between">
            <p>{{ topReview.text }}</p>
            <span class="gold tiny review-stars">{{ starline(topReview.stars) }}</span>
          </div>
        </div>

        <div class="row between">
          <span class="cg-section-title">리뷰</span>
          <span class="tiny mono">{{ reviewPage }} / {{ reviewPages }}</span>
        </div>
        <ul class="review-list col">
          <li v-for="review in pagedReviews" :key="`${review.postId}-${review.id}`" class="review-row col">
            <div class="row between">
              <strong class="tiny">{{ review.author }}</strong>
              <span class="gold tiny">{{ starline(review.stars) }}</span>
            </div>
            <p class="tiny">{{ review.text }}</p>
          </li>
        </ul>
        <div v-if="!pagedReviews.length" class="review-row review-row--empty center">
          <span class="tiny muted">표시할 리뷰가 없어요.</span>
        </div>
        <div class="pager row center" v-if="reviewPages > 1">
          <button class="cg-btn cg-btn--sm" :disabled="reviewPage === 1" @click="reviewPage--">‹</button>
          <span class="tiny mono">{{ reviewPage }} / {{ reviewPages }}</span>
          <button class="cg-btn cg-btn--sm" :disabled="reviewPage === reviewPages" @click="reviewPage++">›</button>
        </div>
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
  grid-template-columns: minmax(230px, 1fr) minmax(0, 2fr);
  gap: var(--sp-4);
  align-items: start;
}
.showcase { gap: var(--sp-3); min-width: 0; }
.flow { position: relative; height: 206px; overflow: hidden; display: flex; align-items: center; justify-content: center; }
.track { position: relative; width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; }
.carousel-item {
  position: absolute; transition: transform .3s ease, opacity .3s ease; cursor: pointer;
  background: var(--surface); border: 2px solid var(--surface-edge); border-radius: var(--r);
  padding: 8px;
}
.carousel-item.active { border-color: var(--primary-d); box-shadow: 4px 4px 0 var(--shadow-hard); }
.navbtn {
  position: absolute; top: 50%; transform: translateY(-50%); z-index: 200;
  width: 34px; height: 34px; border-radius: 50%; font-size: 18px;
  background: var(--surface); border: 2px solid var(--surface-edge); color: var(--ink);
}
.navbtn.left { left: 8px; } .navbtn.right { right: 8px; }
.current {
  gap: 8px;
  min-height: 74px;
  padding-top: var(--sp-1);
}
.current-name {
  max-width: 100%;
  text-align: center;
  overflow-wrap: anywhere;
}
.current-personality {
  max-width: 100%;
  min-height: 18px;
  text-align: center;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.reviews {
  min-width: 0;
  gap: var(--sp-4);
}
.review-head { align-items: flex-start; }
.review-title { gap: 2px; min-width: 0; }
.rating {
  flex: 0 0 auto;
  color: var(--gold);
  font-family: var(--font-head);
  font-size: 22px;
  line-height: 1;
}
.my-review,
.top-review {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: var(--sp-3);
  background: var(--surface-2);
  border: 1.5px dashed var(--surface-edge);
  border-radius: var(--r);
}
.my-review .cg-btn {
  align-self: flex-start;
}
.review-summary__meta,
.review-summary__body {
  min-width: 0;
  gap: var(--sp-2);
  align-items: flex-start;
}
.review-summary__meta-left {
  min-width: 0;
  gap: 8px;
  flex-wrap: wrap;
}
.review-summary__meta time {
  flex: 0 0 auto;
  text-align: right;
  line-height: 1.4;
}
.review-action {
  font-family: var(--font-head);
  font-size: 12px;
  line-height: 1.4;
  color: var(--primary-d);
}
.review-action--button {
  appearance: none;
  border: 0;
  background: transparent;
  padding: 0;
}
.review-summary__body p {
  flex: 1 1 auto;
  min-width: 0;
  margin: 0;
}
.review-stars {
  flex: 0 0 auto;
  line-height: 1.4;
}
.my-review p,
.top-review p,
.review-row p {
  color: var(--ink-soft);
  overflow-wrap: anywhere;
}
.review-list {
  gap: 0;
  list-style: none;
}
.review-row {
  padding: 10px 0;
  border-bottom: 1.5px dashed var(--surface-edge);
  gap: 4px;
}
.review-row:last-child { border-bottom: none; }
.review-row--empty {
  min-height: 52px;
  border-bottom: none;
  padding: 0;
}
.gold { color: var(--gold); }
.pager { gap: 10px; }
@media (max-width: 720px) {
  .codex-layout { grid-template-columns: 1fr; }
  .flow { height: 220px; }
}
</style>
