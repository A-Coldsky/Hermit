<script setup lang="ts">
defineProps<{ title: string; description?: string }>()
</script>

<template>
  <div class="page-header">
    <div>
      <div class="crumbs"><slot name="crumbs" /></div>
      <h1>{{ title }}</h1>
      <p v-if="description" class="hm-muted">{{ description }}</p>
    </div>
    <div class="actions"><slot /></div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 650;
}

p {
  margin: 6px 0 0;
}

.crumbs:empty {
  display: none;
}

.crumbs {
  margin-bottom: 6px;
  font-size: 13px;
}

.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
