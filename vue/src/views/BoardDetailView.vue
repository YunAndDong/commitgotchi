<script setup>
/*
 * Screen #9 — 공유 캐릭터 상세 + 리뷰.
 * 좌: 캐릭터 상세 / 우: 리뷰(평점 분포 + 페이지네이션 + 작성).
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import {
  boardPost, addReview, updateReview, deleteReview, updateBoardPost, deleteBoardPost, CURRENT_OWNER,
} from '../stores/game.js'
import CgSprite from '../components/CgSprite.vue'
import CgEmo from '../components/CgEmo.vue'

const route = useRoute()
const router = useRouter()
const p = computed(() => boardPost(route.params.id))

const PAGE = 4
const page = ref(1)
const pages = computed(() => Math.max(1, Math.ceil((p.value?.reviews.length || 0) / PAGE)))
const shown = computed(() => (p.value?.reviews || []).slice((page.value - 1) * PAGE, page.value * PAGE))

const dist = computed(() => {
  const d = [0, 0, 0, 0, 0]
  ;(p.value?.reviews || []).forEach(r => { d[r.stars - 1]++ })
  return d
})
const total = computed(() => p.value?.reviews.length || 0)

const draft = ref({ stars: 5, text: '' })
const editingReview = ref(null)
const reviewDraft = ref({ stars: 5, text: '' })
const editingPost = ref(false)
const postDraft = ref('')
async function submit() {
  if (!draft.value.text.trim()) return
  await addReview(p.value.id, draft.value.stars, draft.value.text.trim())
  draft.value = { stars: 5, text: '' }
  page.value = 1
}
function starline(n) { return '★'.repeat(n) + '☆'.repeat(5 - n) }
function beginReviewEdit(review) {
  editingReview.value = review.id
  reviewDraft.value = { stars: review.stars, text: review.text }
}
async function saveReview(review) {
  if (await updateReview(p.value.id, review.id, reviewDraft.value.stars, reviewDraft.value.text)) editingReview.value = null
}
async function removeReview(review) {
  await deleteReview(p.value.id, review.id)
  page.value = Math.min(page.value, pages.value)
}
function beginPostEdit() {
  editingPost.value = true
  postDraft.value = p.value.desc
}
async function savePost() {
  if (await updateBoardPost(p.value.id, postDraft.value)) editingPost.value = false
}
async function removePost() {
  if (await deleteBoardPost(p.value.id)) router.push('/board')
}
watch(() => route.params.id, () => { page.value = 1; editingReview.value = null; editingPost.value = false })
watch(pages, value => { page.value = Math.min(page.value, value) })
</script>

<template>
  <div v-if="p" class="bd">
    <header class="cg-pagehead">
      <div class="cg-pagehead__main">
        <RouterLink to="/board" class="cg-btn cg-btn--sm cg-back" aria-label="게시판으로 돌아가기">←</RouterLink>
        <h1 class="cg-page-title">공유 커밋고치</h1>
      </div>
    </header>
    <div class="cols">
      <!-- left: character -->
      <section class="cg-screen col center cchar">
        <CgEmo :emotion="p.emotion" />
        <CgSprite :size="180" :emotion="p.emotion" :evolved="p.isEvolved"
                  :sprite-sheet-url="p.spriteSheetUrl" :sprite-meta="p.spriteMeta" />
        <h2 class="cg-section-title big">{{ p.name }}</h2>
        <span class="tiny faint">@{{ p.owner }}</span>
        <span class="cg-tag">육아점수 {{ p.score.toLocaleString() }}</span>
        <textarea v-if="editingPost" v-model="postDraft" class="cg-textarea"></textarea>
        <p v-else class="desc">{{ p.desc }}</p>
        <div v-if="p.owner === CURRENT_OWNER" class="row wrap">
          <button v-if="!editingPost" class="cg-btn cg-btn--sm" @click="beginPostEdit">게시글 수정</button>
          <button v-else class="cg-btn cg-btn--sm cg-btn--primary" :disabled="!postDraft.trim()" @click="savePost">저장</button>
          <button v-if="editingPost" class="cg-btn cg-btn--sm" @click="editingPost = false">취소</button>
          <button class="cg-btn cg-btn--sm cg-btn--ghost" @click="removePost">게시글 삭제</button>
        </div>
      </section>

      <!-- right: reviews -->
      <section class="col">
        <div class="cg-card col">
          <div class="row between">
            <span class="cg-section-title">리뷰 {{ total }}개</span>
            <strong class="gold">★ {{ p.rating }}</strong>
          </div>
          <div v-for="s in [5,4,3,2,1]" :key="s" class="distrow row">
            <span class="tiny mono dlbl">{{ s }}★</span>
            <div class="dbar"><div class="dbar__f" :style="{ width: (total ? dist[s-1] / total * 100 : 0) + '%' }" /></div>
            <span class="tiny faint mono">{{ dist[s - 1] }}</span>
          </div>
        </div>

        <div class="cg-card col">
          <span class="cg-section-title">리뷰 남기기</span>
          <div class="row" style="gap:6px">
            <button v-for="s in 5" :key="s" class="starbtn" :class="{ on: s <= draft.stars }" @click="draft.stars = s">★</button>
          </div>
          <textarea class="cg-textarea" v-model="draft.text" placeholder="이 캐릭터에 대한 리뷰를 남겨보세요." style="min-height:80px"></textarea>
          <button class="cg-btn cg-btn--primary" :disabled="!draft.text.trim()" @click="submit">리뷰 등록</button>
        </div>

        <div class="cg-card col">
          <p v-if="!shown.length" class="muted tiny">아직 리뷰가 없어요. 첫 리뷰를 남겨보세요!</p>
          <div v-for="r in shown" :key="r.id" class="rev col">
            <div class="row between">
              <strong class="tiny">{{ r.author }}</strong>
              <span class="gold tiny">{{ starline(r.stars) }}</span>
            </div>
            <template v-if="editingReview === r.id">
              <div class="row" style="gap:6px">
                <button v-for="s in 5" :key="s" class="starbtn" :class="{ on: s <= reviewDraft.stars }"
                        @click="reviewDraft.stars = s">★</button>
              </div>
              <textarea v-model="reviewDraft.text" class="cg-textarea"></textarea>
            </template>
            <p v-else class="tiny">{{ r.text }}</p>
            <div v-if="r.author === CURRENT_OWNER" class="row wrap">
              <button v-if="editingReview !== r.id" class="cg-btn cg-btn--sm" @click="beginReviewEdit(r)">수정</button>
              <button v-else class="cg-btn cg-btn--sm cg-btn--primary" :disabled="!reviewDraft.text.trim()" @click="saveReview(r)">저장</button>
              <button v-if="editingReview === r.id" class="cg-btn cg-btn--sm" @click="editingReview = null">취소</button>
              <button class="cg-btn cg-btn--sm cg-btn--ghost" @click="removeReview(r)">삭제</button>
            </div>
          </div>
          <div class="pager row center" v-if="pages > 1">
            <button class="cg-btn cg-btn--sm" :disabled="page === 1" @click="page--">‹</button>
            <span class="tiny mono">{{ page }} / {{ pages }}</span>
            <button class="cg-btn cg-btn--sm" :disabled="page === pages" @click="page++">›</button>
          </div>
        </div>
      </section>
    </div>
  </div>

  <div v-else class="cg-card center col">
    <p>게시글을 찾을 수 없어요.</p>
    <RouterLink to="/board" class="cg-btn">게시판으로</RouterLink>
  </div>
</template>

<style scoped>
.bd { display: flex; flex-direction: column; gap: var(--sp-4); max-width: 1100px; margin: 0 auto; }
.cols { display: grid; grid-template-columns: 340px 1fr; gap: var(--sp-4); align-items: start; }
.cchar { padding: var(--sp-5); gap: var(--sp-2); position: sticky; top: 90px; }
.desc { text-align: center; color: var(--ink-soft); line-height: 1.7; }
.distrow { gap: 8px; }
.dlbl { width: 24px; }
.dbar { flex: 1; height: 8px; background: var(--track); border-radius: var(--r-pill); overflow: hidden; }
.dbar__f { height: 100%; background: var(--gold); }
.gold { color: var(--gold); }
.starbtn { font-size: 22px; color: var(--surface-edge); background: none; border: none; }
.starbtn.on { color: var(--gold); }
.rev { padding: 8px 0; border-bottom: 1.5px dashed var(--surface-edge); gap: 4px; }
.rev:last-of-type { border-bottom: none; }
.pager { gap: 10px; }
@media (max-width: 820px) { .cols { grid-template-columns: 1fr; } .cchar { position: static; } }
</style>
