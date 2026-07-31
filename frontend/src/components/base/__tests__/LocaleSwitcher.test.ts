import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import LocaleSwitcher from '@/components/base/LocaleSwitcher.vue'
import { initializeLocale, SUPPORTED_LOCALES } from '@/i18n'

describe('LocaleSwitcher', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    await initializeLocale()
  })

  it('renders every locale discovered from the locale modules', () => {
    const wrapper = mount(LocaleSwitcher)
    const values = wrapper.findAll('option').map(option => option.attributes('value'))

    expect(values).toEqual(SUPPORTED_LOCALES)
    expect([...values].sort()).toEqual(['en-US', 'zh-Hans'])
  })
})
