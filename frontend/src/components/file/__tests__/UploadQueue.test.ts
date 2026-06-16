import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import UploadQueue from '@/components/file/UploadQueue.vue'
import { useUploadStore } from '@/stores/upload'

describe('UploadQueue', () => {
  it('renders upload progress with two decimal places', () => {
    setActivePinia(createPinia())
    const store = useUploadStore()
    store.isOpen = true
    store.queue.push({
      id: 'upload-1',
      file: new File(['x'], 'demo.bin'),
      fileName: 'demo.bin',
      displayName: 'demo.bin',
      parentId: null,
      status: 'hashing',
      progress: 3.3333333333333335,
      chunkProgress: '',
      error: null,
    })

    const wrapper = mount(UploadQueue)

    expect(wrapper.text()).toContain('3.33%')
    expect(wrapper.text()).not.toContain('3.3333333333333335%')
  })
})
