<script setup lang="ts">
defineProps<{ type?: string; options?: string[] | null; placeholder?: string; disabled?: boolean }>()
const model = defineModel<string>({ default: '' })
</script>

<template>
  <el-select
    v-if="type === 'select' && options?.length"
    v-model="model"
    :placeholder="placeholder ?? '请选择'"
    :disabled="disabled"
    clearable
  >
    <el-option v-for="option in options" :key="option" :label="option" :value="option" />
  </el-select>
  <el-input
    v-else-if="type === 'secret'"
    v-model="model"
    type="password"
    show-password
    autocomplete="new-password"
    :placeholder="placeholder"
    :disabled="disabled"
  />
  <el-input v-else v-model="model" :placeholder="placeholder" :disabled="disabled" autocomplete="off" />
</template>
