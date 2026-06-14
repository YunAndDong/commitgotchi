<script setup>
// cg-stattile + cg-delta — individual stat with optional change amount.
defineProps({
  label: { type: String, required: true },
  value: { type: Number, default: 0 },
  delta: { type: Number, default: 0 },
})
</script>

<template>
  <div class="cg-stattile">
    <div class="cg-stattile__v">{{ value }}</div>
    <div class="cg-stattile__k">{{ label }}</div>
    <div v-if="delta" :class="['cg-delta', { 'cg-delta--neg': delta < 0 }]">
      {{ delta > 0 ? '+' : '' }}{{ delta }}
    </div>
  </div>
</template>
