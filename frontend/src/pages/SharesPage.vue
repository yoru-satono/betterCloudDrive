<script setup lang="ts">
import { onMounted, ref } from 'vue'
import OButton from '@/components/base/OButton.vue'
import OEmptyState from '@/components/base/OEmptyState.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import OModal from '@/components/base/OModal.vue'
import OInput from '@/components/base/OInput.vue'
import { useFormatters } from '@/composables/useFormatters'
import { useConfirm } from '@/composables/useConfirm'
import * as sharesApi from '@/api/shares'
import { toast } from 'vue-sonner'
import type { ShareLinkEntity } from '@/types/share'
import { buildShareUrl } from '@/config/runtime'

const { formatDateFull } = useFormatters()
const { confirm } = useConfirm()
const shares = ref<ShareLinkEntity[]>([])
const loading = ref(false)
const detailOpen = ref(false)
const detailShare = ref<ShareLinkEntity | null>(null)
const editOpen = ref(false)
const editShare = ref<ShareLinkEntity | null>(null)
const editMaxVisits = ref('')
const editExpireAt = ref('')
const editPassword = ref('')

async function load() {
  loading.value = true
  try {
    const { data } = await sharesApi.listShares(1, 100)
    shares.value = data.data.records
  } finally { loading.value = false }
}

async function copyLink(share: ShareLinkEntity) {
  await navigator.clipboard.writeText(buildShareUrl(share.shareCode))
  toast.success('链接已复制')
}

async function deleteShare(share: ShareLinkEntity) {
  const ok = await confirm('取消分享', '取消后分享链接将立即失效。')
  if (!ok) return
  await sharesApi.deleteShare(share.id)
  toast.success('分享已取消')
  load()
}

async function openDetail(share: ShareLinkEntity) {
  const { data } = await sharesApi.getShare(share.id)
  detailShare.value = data.data
  detailOpen.value = true
}

function openEdit(share: ShareLinkEntity) {
  editShare.value = share
  editMaxVisits.value = share.maxVisits ? String(share.maxVisits) : ''
  editExpireAt.value = share.expireAt ? new Date(share.expireAt).toISOString().slice(0, 16) : ''
  editPassword.value = ''
  editOpen.value = true
}

async function submitEdit() {
  if (!editShare.value) return
  const maxVisits = editMaxVisits.value.trim() ? Number(editMaxVisits.value) : undefined
  if (maxVisits !== undefined && (!Number.isInteger(maxVisits) || maxVisits < 1)) {
    toast.error('访问次数限制必须是正整数')
    return
  }
  const password = editPassword.value.trim()
  if (password && (password.length < 4 || password.length > 16)) {
    toast.error('分享密码长度必须为 4-16 位')
    return
  }
  await sharesApi.updateShare(editShare.value.id, {
    maxVisits,
    expireAt: editExpireAt.value ? new Date(editExpireAt.value).getTime() : 0,
    password: password || undefined,
  })
  toast.success('分享已更新')
  editOpen.value = false
  load()
}

function isExpired(share: ShareLinkEntity) {
  return share.expireAt && new Date(share.expireAt) < new Date()
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
          <div class="share-card__url font-mono truncate">{{ buildShareUrl(share.shareCode) }}</div>
          <div class="share-card__meta">
            <span v-if="share.expireAt">
              <span :style="isExpired(share) ? 'color:var(--danger)' : 'color:var(--text-secondary)'">
                {{ isExpired(share) ? '已过期' : `过期：${formatDateFull(share.expireAt)}` }}
              </span>
            </span>
            <span v-else class="text-muted">永不过期</span>
            <span class="text-muted">· 访问 {{ share.visitCount }}{{ share.maxVisits ? `/${share.maxVisits}` : '' }} 次</span>
            <span class="text-muted">· 下载 {{ share.downloadCount }} 次</span>
            <span v-if="share.passwordHash" class="share-card__pw">有密码</span>
          </div>
        </div>
        <div class="share-card__actions">
          <OButton variant="subtle" size="sm" @click="openDetail(share)">详情</OButton>
          <OButton variant="ghost" size="sm" @click="openEdit(share)">编辑</OButton>
          <OButton variant="ghost" size="sm" @click="copyLink(share)">复制链接</OButton>
          <OButton variant="danger" size="sm" @click="deleteShare(share)">取消分享</OButton>
        </div>
      </div>
    </div>

    <OModal title="分享详情" :open="detailOpen" @close="detailOpen = false">
      <div v-if="detailShare" class="share-info">
        <div><span>链接</span><strong class="font-mono">{{ buildShareUrl(detailShare.shareCode) }}</strong></div>
        <div><span>文件 ID</span><strong class="font-mono">{{ detailShare.fileId }}</strong></div>
        <div><span>访问</span><strong class="font-mono">{{ detailShare.visitCount }}{{ detailShare.maxVisits ? `/${detailShare.maxVisits}` : '' }}</strong></div>
        <div><span>下载</span><strong class="font-mono">{{ detailShare.downloadCount }}</strong></div>
        <div><span>密码</span><strong>{{ detailShare.passwordHash ? '有密码' : '无密码' }}</strong></div>
        <div><span>过期</span><strong>{{ detailShare.expireAt ? formatDateFull(detailShare.expireAt) : '永不过期' }}</strong></div>
        <div><span>状态</span><strong>{{ detailShare.isCanceled ? '已取消' : isExpired(detailShare) ? '已过期' : '有效' }}</strong></div>
      </div>
    </OModal>

    <OModal title="编辑分享" :open="editOpen" @close="editOpen = false">
      <div class="share-edit">
        <OInput v-model="editMaxVisits" label="访问次数限制" type="number" placeholder="留空表示不限" />
        <OInput v-model="editExpireAt" label="过期时间" type="datetime-local" />
        <OInput v-model="editPassword" label="新访问密码" type="text" placeholder="留空表示不修改，输入空格不可用" />
        <p class="share-edit__hint">清除过期时间会设为永不过期；密码留空表示不修改。</p>
      </div>
      <template #footer>
        <OButton variant="ghost" @click="editOpen = false">取消</OButton>
        <OButton variant="primary" @click="submitEdit">保存</OButton>
      </template>
    </OModal>
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
.share-info { display: flex; flex-direction: column; gap: 8px; }
.share-info div {
  display: grid;
  grid-template-columns: 70px 1fr;
  gap: 10px;
  font-size: 13px;
}
.share-info span,
.share-edit__hint { color: var(--text-secondary); font-size: 12px; }
.share-info strong { font-weight: 500; overflow-wrap: anywhere; }
.share-edit { display: flex; flex-direction: column; gap: 12px; }
</style>
