<script setup>
import { ref, onMounted } from 'vue'

const apiBase = import.meta.env.VITE_API_BASE_URL || ''
const backendStatus = ref('확인 중...')

onMounted(async () => {
  try {
    const res = await fetch(`${apiBase}/api/health`)
    backendStatus.value = res.ok ? '연결됨 ✅' : `오류 (${res.status})`
  } catch (e) {
    backendStatus.value = '연결 실패 ❌'
  }
})
</script>

<template>
  <main>
    <h1>🥚 Commit-Gotchi</h1>
    <p>혼자 CS를 공부하는 사람의 매일 학습을 가상 캐릭터의 성장으로.</p>
    <p>Backend 상태: <strong>{{ backendStatus }}</strong></p>
  </main>
</template>

<style>
body { font-family: system-ui, sans-serif; margin: 0; }
main { max-width: 640px; margin: 4rem auto; padding: 0 1rem; text-align: center; }
</style>
