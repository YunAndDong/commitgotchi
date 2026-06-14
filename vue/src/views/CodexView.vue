<script setup>
/*
 * Screen #3 — 도감 / 보관함 (Codex).
 * 커버플로우. 컬러=수집 / 회색=미획득. 하단 새싹이 배회(App Mascot).
 */
import { ref, computed } from 'vue'
import { codex } from '../stores/game.js'
import CgSprite from '../components/CgSprite.vue'

const items = computed(() => codex())
const idx = ref(0)
const current = computed(() => items.value[idx.value])
function move(d) { idx.value = (idx.value + d + items.value.length) % items.value.length }
function offset(i) { return i - idx.value }
</script>

<template>
  <div class="codex col">
    <div class="row between">
      <h1 class="cg-section-title big">도감 · 보관함</h1>
      <span class="tiny muted">{{ items.filter(i => i.owned).length }} / {{ items.length }} 수집</span>
    </div>

    <section class="flow cg-screen">
      <button class="navbtn left" @click="move(-1)" aria-label="이전">‹</button>
      <div class="track">
        <div v-for="(it, i) in items" :key="it.id" class="card"
             :class="{ active: i === idx, owned: it.owned }"
             :style="{ transform: `translateX(${offset(i) * 130}px) scale(${i === idx ? 1 : 0.78})`, zIndex: 100 - Math.abs(offset(i)), opacity: Math.abs(offset(i)) > 2 ? 0 : 1 }"
             @click="idx = i">
          <CgSprite :size="120" :emotion="it.emotion" :evolved="it.isEvolved" :grey="!it.owned" :bob="i === idx && it.owned" />
        </div>
      </div>
      <button class="navbtn right" @click="move(1)" aria-label="다음">›</button>
    </section>

    <div class="cg-card center col cap">
      <strong class="big">{{ current.owned ? current.name : '??? (미획득)' }}</strong>
      <span class="cg-badge" :class="current.owned ? 'cg-badge--ok' : 'cg-badge--warn'">
        {{ current.owned ? '수집 완료' : '🔒 잠김' }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.codex { gap: var(--sp-4); max-width: 760px; margin: 0 auto; }
.flow { position: relative; height: 280px; overflow: hidden; display: flex; align-items: center; justify-content: center; }
.track { position: relative; width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; }
.card {
  position: absolute; transition: transform .3s ease, opacity .3s ease; cursor: pointer;
  background: var(--surface); border: 2px solid var(--surface-edge); border-radius: var(--r);
  padding: 12px;
}
.card.active { border-color: var(--primary-d); box-shadow: 4px 4px 0 var(--shadow-hard); }
.navbtn {
  position: absolute; top: 50%; transform: translateY(-50%); z-index: 200;
  width: 40px; height: 40px; border-radius: 50%; font-size: 20px;
  background: var(--surface); border: 2px solid var(--surface-edge); color: var(--ink);
}
.navbtn.left { left: 12px; } .navbtn.right { right: 12px; }
.cap { gap: 8px; }
</style>
