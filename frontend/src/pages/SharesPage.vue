<script setup lang="ts">
import { i18n as __i18n } from '@/i18n'
const __t = __i18n.global.t

import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Eye } from '@lucide/vue'
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
const route = useRoute()
const shares = ref<ShareLinkEntity[]>([])
const loading = ref(false)
const detailOpen = ref(false)
const detailShare = ref<ShareLinkEntity | null>(null)
const detailPassword = ref('')
const loadingPasswordShareId = ref<number | null>(null)
const editOpen = ref(false)
const editShare = ref<ShareLinkEntity | null>(null)
const editMaxVisits = ref('')
const editExpireAt = ref('')
const editPassword = ref('')
const editClearPassword = ref(false)

async function load() {
  loading.value = true
  try {
    const { data } = await sharesApi.listShares(1, 100)
    shares.value = data.data.records
    await openShareFromQuery()
  } finally { loading.value = false }
}

async function openShareFromQuery() {
  const shareId = Number(route.query.shareId)
  if (!Number.isInteger(shareId) || shareId <= 0) return
  const share = shares.value.find(item => item.id === shareId)
  if (share) {
    await openDetail(share)
    return
  }
  const { data } = await sharesApi.getShare(shareId)
  detailShare.value = data.data
  detailPassword.value = ''
  detailOpen.value = true
}

async function copyLink(share: ShareLinkEntity) {
  await navigator.clipboard.writeText(buildShareUrl(share.shareCode))
  toast.success(__t('share.searchoverlay.linkCopied'))
}

async function deleteShare(share: ShareLinkEntity) {
  const ok = await confirm(__t('share.shares.unshare'), __t('share.shares.theSharedLinkWillBecomeInvalidImmediatelyAfterCancellation'))
  if (!ok) return
  await sharesApi.deleteShare(share.id)
  toast.success(__t('share.shares.sharingCanceled'))
  load()
}

async function openDetail(share: ShareLinkEntity) {
  const { data } = await sharesApi.getShare(share.id)
  detailShare.value = data.data
  detailPassword.value = ''
  detailOpen.value = true
}

async function loadSharePassword(share: ShareLinkEntity) {
  loadingPasswordShareId.value = share.id
  try {
    const { data } = await sharesApi.getSharePassword(share.id)
    detailShare.value = share
    detailPassword.value = data.data.password ?? ''
    detailOpen.value = true
  } finally {
    loadingPasswordShareId.value = null
  }
}

function openEdit(share: ShareLinkEntity) {
  editShare.value = share
  editMaxVisits.value = share.maxVisits ? String(share.maxVisits) : ''
  editExpireAt.value = share.expireAt ? new Date(share.expireAt).toISOString().slice(0, 16) : ''
  editPassword.value = ''
  editClearPassword.value = false
  editOpen.value = true
}

async function submitEdit() {
  if (!editShare.value) return
  const maxVisits = editMaxVisits.value.trim() ? Number(editMaxVisits.value) : undefined
  if (maxVisits !== undefined && (!Number.isInteger(maxVisits) || maxVisits < 1)) {
    toast.error(__t('commonUi.filebrowser.theNumberOfVisitsLimitMustBeAPositiveInteger'))
    return
  }
  const password = editPassword.value.trim()
  if (!editClearPassword.value && password && (password.length < 4 || password.length > 16)) {
    toast.error(__t('commonUi.filebrowser.sharePasswordLengthMustBe416Characters'))
    return
  }
  const clearMaxVisits = !editMaxVisits.value.trim()
  const clearExpireAt = !editExpireAt.value
  await sharesApi.updateShare(editShare.value.id, {
    maxVisits,
    expireAt: clearExpireAt ? undefined : new Date(editExpireAt.value).getTime(),
    password: editClearPassword.value ? undefined : password || undefined,
    clearPassword: editClearPassword.value,
    clearExpireAt,
    clearMaxVisits,
  })
  toast.success(__t('share.shares.shareUpdated'))
  editOpen.value = false
  load()
}

function isExpired(share: ShareLinkEntity) {
  return share.expireAt && new Date(share.expireAt) < new Date()
}

onMounted(load)
watch(() => route.query.shareId, () => openShareFromQuery())
</script>

<template>
  <div class="page-enter">
    <div class="page-header">
      <div>
        <h2>{{ __t('share.shares.myShare') }}</h2>
        <p class="text-muted" style="font-size:12px;margin-top:2px">{{ shares.length }}{{ __t('share.shares.sharedLinks') }}</p>
      </div>
    </div>

    <div v-if="loading" class="page-loading"><OSpinner /></div>
    <OEmptyState v-else-if="shares.length === 0" :title="__t('share.shares.noSharingYet')" :description="__t('share.shares.rightClickOnAFileInTheFileBrowserTo')" />
    <div v-else class="shares-list">
      <div v-for="share in shares" :key="share.id" class="share-card">
        <div class="share-card__main">
          <div class="share-card__url font-mono truncate">{{ buildShareUrl(share.shareCode) }}</div>
          <div class="share-card__meta">
            <span v-if="share.expireAt">
              <span :style="isExpired(share) ? 'color:var(--danger)' : 'color:var(--text-secondary)'">
                {{ isExpired(share) ? __t('commonUi.searchoverlay.expiredStatus') : __t('commonUi.searchoverlay.expired', { p0: formatDateFull(share.expireAt) }) }}
              </span>
            </span>
            <span v-else class="text-muted">{{ __t('commonUi.searchoverlay.neverExpires') }}</span>
            <span class="text-muted">{{ __t('share.shares.visit') }}{{ share.visitCount }}{{ share.maxVisits ? `/${share.maxVisits}` : '' }}{{ __t('share.shares.secondRate') }}</span>
            <span class="text-muted">{{ __t('share.shares.download') }}{{ share.downloadCount }}{{ __t('share.shares.secondRate') }}</span>
            <span v-if="share.hasPassword" class="share-card__pw">{{ __t('commonUi.searchoverlay.havePassword') }}</span>
          </div>
        </div>
        <div class="share-card__actions">
          <OButton variant="subtle" size="sm" @click="openDetail(share)">{{ __t('commonUi.adminlogs.details') }}</OButton>
          <OButton
            v-if="share.hasPassword"
            variant="ghost"
            size="sm"
            icon
            :loading="loadingPasswordShareId === share.id"
            :aria-label="__t('team.shares.viewPassword')"
            @click="loadSharePassword(share)"
          >
            <Eye :size="14" />
          </OButton>
          <OButton variant="ghost" size="sm" @click="openEdit(share)">{{ __t('commonUi.shares.edit') }}</OButton>
          <OButton variant="ghost" size="sm" @click="copyLink(share)">{{ __t('share.shares.copyLink') }}</OButton>
          <OButton variant="danger" size="sm" @click="deleteShare(share)">{{ __t('share.shares.unshare') }}</OButton>
        </div>
      </div>
    </div>

    <OModal :title="__t('team.shares.shareDetails')" :open="detailOpen" @close="detailOpen = false">
      <div v-if="detailShare" class="share-info">
        <div><span>{{ __t('team.shares.link') }}</span><strong class="font-mono">{{ buildShareUrl(detailShare.shareCode) }}</strong></div>
        <div><span>{{ __t('team.shares.fileId') }}</span><strong class="font-mono">{{ detailShare.fileId }}</strong></div>
        <div><span>{{ __t('team.shares.access') }}</span><strong class="font-mono">{{ detailShare.visitCount }}{{ detailShare.maxVisits ? `/${detailShare.maxVisits}` : '' }}</strong></div>
        <div><span>{{ __t('commonUi.filepreview.download') }}</span><strong class="font-mono">{{ detailShare.downloadCount }}</strong></div>
        <div><span>{{ __t('commonUi.desktopsettings.password') }}</span><strong>{{ detailShare.hasPassword ? (detailPassword || __t('commonUi.searchoverlay.havePassword')) : __t('commonUi.searchoverlay.noPassword') }}</strong></div>
        <div><span>{{ __t('team.shares.expired') }}</span><strong>{{ detailShare.expireAt ? formatDateFull(detailShare.expireAt) : __t('commonUi.searchoverlay.neverExpires') }}</strong></div>
        <div><span>{{ __t('commonUi.adminlogs.state') }}</span><strong>{{ detailShare.isCanceled ? __t('commonUi.transfer.canceled') : isExpired(detailShare) ? __t('commonUi.searchoverlay.expiredStatus') : __t('team.shares.efficient') }}</strong></div>
      </div>
    </OModal>

    <OModal :title="__t('share.shares.editorShare')" :open="editOpen" @close="editOpen = false">
      <div class="share-edit">
        <div class="share-edit__field">
          <OInput v-model="editMaxVisits" :label="__t('commonUi.filebrowser.visitLimit')" type="number" :placeholder="__t('commonUi.filebrowser.leaveBlankToIndicateNoLimit')" />
          <OButton variant="ghost" size="sm" @click="editMaxVisits = ''">{{ __t('team.shares.clearVisits') }}</OButton>
        </div>
        <div class="share-edit__field">
          <OInput v-model="editExpireAt" :label="__t('team.shares.expirationTime')" type="datetime-local" />
          <OButton variant="ghost" size="sm" @click="editExpireAt = ''">{{ __t('team.shares.clearExpirationTime') }}</OButton>
        </div>
        <OInput v-model="editPassword" :label="__t('team.shares.newAccessPassword')" type="text" :placeholder="__t('share.shares.leaveBlankToIndicateNoModificationEnteringSpacesIsNot')" />
        <label class="share-edit__check">
          <input v-model="editClearPassword" type="checkbox" />
          <span>{{ __t('team.shares.removeAccessCode') }}</span>
        </label>
        <p class="share-edit__hint">{{ __t('team.shares.theNumberOfClearAccessesAndExpirationTimeWillBe') }}</p>
      </div>
      <template #footer>
        <OButton variant="ghost" @click="editOpen = false">{{ __t('commonUi.oconfirmdialog.cancel') }}</OButton>
        <OButton variant="primary" @click="submitEdit">{{ __t('commonUi.desktopsettings.save') }}</OButton>
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
.share-edit__field {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: end;
}
.share-edit__check { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text-secondary); }
</style>
