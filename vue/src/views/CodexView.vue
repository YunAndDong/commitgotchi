<script setup>
/*
 * Screen #3 — 도감.
 * Catalog characters are loaded separately from the user's game state.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import CgSprite from '../components/CgSprite.vue'
import {
  codexState,
  ensureCodexLoaded,
  loadNextCodexPage,
  prepareCodexWindow,
} from '../stores/codex.js'

const route = useRoute()
const items = computed(() => codexState.items)
const idx = ref(0)
const current = computed(() => items.value[idx.value] || null)
const currentImage = computed(() => imageFor(current.value))
const loadedImageCount = computed(() => Object.keys(codexState.imageCacheById).length)

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
})
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
          <span class="cg-section-title">특성</span>
          <span class="tiny mono">{{ idx + 1 }} / {{ items.length }}</span>
        </div>

        <dl class="data-list">
          <div class="data-row">
            <dt>성격</dt>
            <dd>{{ current.personality || '성격 정보 준비 중' }}</dd>
          </div>
          <div class="data-row">
            <dt>디자인 키워드</dt>
            <dd>{{ current.designKeyword || '디자인 키워드 준비 중' }}</dd>
          </div>
          <div class="data-row">
            <dt>이미지</dt>
            <dd v-if="currentImage">준비됨</dd>
            <dd v-else-if="isImageLoading(current)">불러오는 중</dd>
            <dd v-else-if="hasImageError(current)">대기 중</dd>
            <dd v-else>대기 중</dd>
          </div>
        </dl>

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
