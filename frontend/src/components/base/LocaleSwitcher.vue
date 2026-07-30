<script setup lang="ts">
import { computed } from 'vue'
import { i18n, locale, localeLoading, setLocale, SUPPORTED_LOCALES, type SupportedLocale } from '@/i18n'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const t = i18n.global.t
const localeOptions = computed(() => {
  let displayNames: Intl.DisplayNames | undefined
  try {
    displayNames = new Intl.DisplayNames([locale.value], { type: 'language' })
  } catch {
    // Older embedded webviews can still use the canonical locale code.
  }
  return SUPPORTED_LOCALES.map(value => ({
    value,
    label: displayNames?.of(value) || value,
  }))
})

async function change(event: Event) {
  const next = (event.target as HTMLSelectElement).value as SupportedLocale
  if (auth.isLoggedIn) await auth.updateLocale(next)
  else await setLocale(next)
}
</script>

<template>
  <label class="locale-switcher">
    <span class="sr-only">{{ t('ui.language') }}</span>
    <select :value="locale" :aria-label="t('ui.language')" :disabled="localeLoading" data-testid="locale-switcher" @change="change">
      <option v-for="option in localeOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
    </select>
  </label>
</template>

<style scoped>
.locale-switcher select {
  min-height: 34px; padding: 0 28px 0 10px; border: 1px solid var(--border); border-radius: 7px;
  color: var(--text-secondary); background: var(--bg-elevated); font: inherit; cursor: pointer;
}
.locale-switcher select:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 1px; }
</style>
