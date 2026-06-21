<script setup>
/*
 * Screen #8 — 캐릭터 공유 게시판.
 * 카드 상하 2분할(위=캐릭터 / 아래=설명+최신 리뷰), 1행 3열 + 페이지네이션.
 */
import { ref, computed, watch } from 'vue'
import { RouterLink } from 'vue-router'
import {
  board, createBoardPost, updateBoardPost, deleteBoardPost, CURRENT_OWNER,
} from '../stores/game.js'
import CgSprite from '../components/CgSprite.vue'

const posts = computed(() => board())
const PAGE = 3
const page = ref(1)
const draft = ref('')
const editing = ref(null)
const editDraft = ref('')
const pages = computed(() => Math.max(1, Math.ceil(posts.value.length / PAGE)))
const shown = computed(() => posts.value.slice((page.value - 1) * PAGE, page.value * PAGE))
function stars(n) { return '★'.repeat(Math.round(n)) + '☆'.repeat(5 - Math.round(n)) }
async function create() {
  if (!await createBoardPost(draft.value)) return
  draft.value = ''
  page.value = 1
}
function beginEdit(post) {
  editing.value = post.id
  editDraft.value = post.desc
}
async function saveEdit(post) {
  if (await updateBoardPost(post.id, editDraft.value)) editing.value = null
}
async function remove(post) {
  if (!await deleteBoardPost(post.id)) return
  page.value = Math.min(page.value, pages.value)
}
watch(pages, value => { page.value = Math.min(page.value, value) })
</script>

<template>
  <div class="board col">
    <h1 class="cg-section-title big">캐릭터 공유 게시판</h1>

    <section class="cg-card col">
      <label class="cg-label" for="post-desc">내 활성 캐릭터 공유하기</label>
      <textarea id="post-desc" v-model="draft" class="cg-textarea"
                placeholder="캐릭터와 학습 여정을 소개해 주세요."></textarea>
      <button class="cg-btn cg-btn--primary" :disabled="!draft.trim()" @click="create">게시글 작성</button>
    </section>

    <section class="grid">
      <article v-for="p in shown" :key="p.id" class="bcard cg-card col">
        <RouterLink :to="`/board/${p.id}`" class="col">
          <div class="bcard__top cg-screen center">
            <CgSprite :size="120" :emotion="p.emotion" :evolved="p.isEvolved" :bob="false" />
          </div>
          <div class="bcard__bot col">
            <div class="row between">
              <strong>{{ p.name }}</strong>
              <span class="tiny faint">@{{ p.owner }}</span>
            </div>
            <p class="desc tiny">{{ p.desc }}</p>
            <div class="row between">
              <span class="stars" :aria-label="`평점 ${p.rating}`">{{ stars(p.rating) }} <span class="mono tiny">{{ p.rating }}</span></span>
              <span class="cg-tag">육아점수 {{ p.score.toLocaleString() }}</span>
            </div>
            <p v-if="p.reviews[0]" class="review tiny">“{{ p.reviews[0].text }}” — {{ p.reviews[0].author }}</p>
          </div>
        </RouterLink>
        <div v-if="p.owner === CURRENT_OWNER" class="col owner-actions">
          <textarea v-if="editing === p.id" v-model="editDraft" class="cg-textarea"></textarea>
          <div class="row wrap">
            <button v-if="editing !== p.id" class="cg-btn cg-btn--sm" @click="beginEdit(p)">수정</button>
            <button v-else class="cg-btn cg-btn--sm cg-btn--primary" :disabled="!editDraft.trim()" @click="saveEdit(p)">저장</button>
            <button v-if="editing === p.id" class="cg-btn cg-btn--sm" @click="editing = null">취소</button>
            <button class="cg-btn cg-btn--sm cg-btn--ghost" @click="remove(p)">삭제</button>
          </div>
        </div>
      </article>
    </section>

    <div class="pager row center" v-if="pages > 1">
      <button class="cg-btn cg-btn--sm" :disabled="page === 1" @click="page--">‹</button>
      <span class="tiny mono">{{ page }} / {{ pages }}</span>
      <button class="cg-btn cg-btn--sm" :disabled="page === pages" @click="page++">›</button>
    </div>
  </div>
</template>

<style scoped>
.board { gap: var(--sp-4); }
.grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--sp-4); }
.bcard { gap: var(--sp-3); transition: transform .08s ease; }
.bcard:hover { transform: translate(-1px,-1px); box-shadow: 4px 4px 0 var(--shadow-hard); }
.bcard__top { padding: var(--sp-4); border-radius: var(--r); }
.bcard__bot { gap: 8px; }
.desc { color: var(--ink-soft); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.stars { color: var(--gold); font-size: 14px; }
.review { color: var(--ink-soft); border-top: 1.5px dashed var(--surface-edge); padding-top: 6px; }
.owner-actions { border-top: 1.5px dashed var(--surface-edge); padding-top: 8px; }
.owner-actions .cg-textarea { min-height: 80px; }
.pager { gap: 10px; }
@media (max-width: 820px) { .grid { grid-template-columns: 1fr 1fr; } }
@media (max-width: 540px) { .grid { grid-template-columns: 1fr; } }
</style>
