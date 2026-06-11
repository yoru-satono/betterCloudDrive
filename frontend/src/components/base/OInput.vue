<script setup lang="ts">
defineProps<{
  modelValue?: string
  placeholder?: string
  type?: string
  label?: string
  error?: string
  disabled?: boolean
  prefix?: string
}>()
defineEmits<{ 'update:modelValue': [string] }>()
</script>

<template>
  <div class="o-input-wrap">
    <label v-if="label" class="o-input__label">{{ label }}</label>
    <div class="o-input__field" :class="{ 'o-input__field--error': error, 'o-input__field--disabled': disabled }">
      <span v-if="prefix" class="o-input__prefix">{{ prefix }}</span>
      <slot name="icon" />
      <input
        :type="type || 'text'"
        :value="modelValue"
        :placeholder="placeholder"
        :disabled="disabled"
        class="o-input__el"
        @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
      />
      <slot name="suffix" />
    </div>
    <p v-if="error" class="o-input__error">{{ error }}</p>
  </div>
</template>

<style scoped>
.o-input-wrap { display: flex; flex-direction: column; gap: 5px; }
.o-input__label { font-size: 12px; color: var(--text-secondary); letter-spacing: 0.02em; }
.o-input__field {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 0 12px;
  transition: border-color var(--fast), box-shadow var(--fast);
}
.o-input__field:focus-within {
  border-color: var(--border-focus);
  box-shadow: 0 0 0 3px rgba(0, 212, 170, 0.08);
}
.o-input__field--error { border-color: var(--danger) !important; }
.o-input__field--disabled { opacity: 0.5; }
.o-input__prefix { color: var(--text-muted); font-family: var(--font-mono); font-size: 12px; }
.o-input__el {
  flex: 1;
  background: transparent;
  border: none;
  outline: none;
  color: var(--text-primary);
  font-family: var(--font-sans);
  font-size: 13px;
  height: 38px;
}
.o-input__el::placeholder { color: var(--text-muted); }
.o-input__error { font-size: 11px; color: var(--danger); }
</style>
