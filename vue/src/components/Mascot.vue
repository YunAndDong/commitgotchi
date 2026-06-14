<script setup>
// 새싹이 mascot — wanders along the bottom to add life (never over key info).
import CgSprite from './CgSprite.vue'
</script>

<template>
  <div class="mascot" aria-hidden="true">
    <CgSprite :size="56" emotion="joy" :bob="true" />
  </div>
</template>

<style scoped>
.mascot { position: fixed; bottom: 8px; left: 0; z-index: 10; animation: stroll 26s linear infinite; pointer-events: none; }
@keyframes stroll {
  0% { transform: translateX(-80px) scaleX(1); }
  48% { transform: translateX(calc(100vw - 40px)) scaleX(1); }
  50% { transform: translateX(calc(100vw - 40px)) scaleX(-1); }
  98% { transform: translateX(-80px) scaleX(-1); }
  100% { transform: translateX(-80px) scaleX(1); }
}
@media (prefers-reduced-motion: reduce) { .mascot { display: none; } }
</style>
