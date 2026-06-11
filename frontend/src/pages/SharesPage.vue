<script setup lang="ts">
import { onMounted, ref } from 'vue'
import OButton from '@/components/base/OButton.vue'
import OEmptyState from '@/components/base/OEmptyState.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import OBadge from '@/components/base/OBadge.vue'
import { useFormatters } from '@/composables/useFormatters'
import { useConfirm } from '@/composables/useConfirm'
import * as sharesApi from '@/api/shares'
import { toast } from 'vue-sonner'
import type { ShareLinkEntity } from '@/types/share'

const { formatDate, formatDateFull } = useFormatters()
const { confirm } = useConfirm()
const shares = ref<ShareLinkEntity[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const { data } = await sharesApi.listShares(1, 100)
    shares.value = data.data.records
  } finally { loading.value = false }
}

async function copyLink(share: ShareLinkEntity) {
  await navigator.clipboard.writeText(share.shareUrl)
  toast.success('链接已复制')
}

async function deleteShare(share: ShareLinkEntity) {
  const ok = await confirm('取消分享', '取消后分享链接将立即失效。')
  if (!ok) return
  await sharesApi.deleteShare(share.id)
  toast.success('分享已取消')
  load()
}

function isExpired(share: ShareLinkEntity) {
  return share.expiresAt && new Date(share.expiresAt) < new Date()
}

onMounted(load)
</script>

<template>
  <div class="page-enter">
    <div class="page-header">
      <div>
        <h2>我的分享</h2>
        <p class="text-muted" style="font-size:12px;margin-top:2px">{{ shares.length }} 个分享链接</p>
      </div>
    </div>

    <div v-if="loading" class="page-loading"><OSpinner /></div>
    <OEmptyState v-else-if="shares.length === 0" title="暂无分享" description="在文件浏览器中右键文件可创建分享链接" />
    <div v-else class="shares-list">
      <div v-for="share in shares" :key="share.id" class="share-card">
        <div class="share-card__main">
          <div class="share-card__url font-mono truncate">{{ share.shareUrl }}</div>
          <div class="share-card__meta">
            <span v-if="share.expiresAt">
              <span :style="isExpired(share) ? 'color:var(--danger)' : 'color:var(--text-secondary)'">
                {{ isExpired(share) ? '已过期' : `过期：${formatDateFull(share.expiresAt)}` }}
              </span>
            </span>
            <span v-else class="text-muted">永不过期</span>
            <span class="text-muted">· 访问 {{ share.visitCount }} 次</span>
            <span v-if="share.isPasswordProtected" class="share-card__pw">🔒 有密码</span>
          </div>
        </div>
        <div class="share-card__actions">
          <OButton variant="ghost" size="sm" @click="copyLink(share)">复制链接</OButton>
          <OButton variant="danger" size="sm" @click="deleteShare(share)">取消分享</OButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 20px; }
.page-loading { display: flex; justify-content: center; padding: 60px; }
.shares-list { display: flex; flex-direction: column; gap: 8px; }
.share-card {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 14px 16px; border: 1px solid var(--border); border-radius: 10px;
  background: var(--bg-elevated); transition: border-color var(--fast);
}
.share-card:hover { border-color: var(--border-hover); }
.share-card__main { flex: 1; min-width: 0; }
.share-card__url { font-size: 12px; color: var(--accent); margin-bottom: 4px; }
.share-card__meta { font-size: 12px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.share-card__pw { font-size: 11px; }
.share-card__actions { display: flex; gap: 6px; flex-shrink: 0; }
</style>
