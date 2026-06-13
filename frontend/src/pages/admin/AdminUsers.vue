<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import OButton from '@/components/base/OButton.vue'
import OInput from '@/components/base/OInput.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import OBadge from '@/components/base/OBadge.vue'
import { useFormatters } from '@/composables/useFormatters'
import * as adminApi from '@/api/admin'
import { toast } from 'vue-sonner'
import type { UserEntity } from '@/types/user'

const { formatSize, formatDate } = useFormatters()
const router = useRouter()
const users = ref<UserEntity[]>([])
const loading = ref(false)
const search = ref('')
const editingQuota = ref<{ id: number; val: string } | null>(null)

async function load() {
  loading.value = true
  try {
    const { data } = await adminApi.listUsers(1, 100, search.value || undefined)
    users.value = data.data.records
  } finally { loading.value = false }
}

async function toggleActive(user: UserEntity) {
  const nextStatus = user.status === 1 ? 0 : 1
  await adminApi.updateUserStatus(user.id, nextStatus)
  toast.success(nextStatus === 1 ? '用户已启用' : '用户已禁用')
  load()
}

async function saveQuota(user: UserEntity) {
  if (!editingQuota.value) return
  const quotaGb = Number(editingQuota.value.val)
  if (!Number.isFinite(quotaGb) || quotaGb <= 0) {
    toast.error('请输入有效的存储配额')
    return
  }
  const bytes = Math.round(quotaGb * 1024 * 1024 * 1024)
  await adminApi.updateUserQuota(user.id, bytes)
  toast.success('配额已更新')
  editingQuota.value = null
  load()
}

onMounted(load)
</script>

<template>
  <div class="page-enter">
    <div class="page-header">
      <h2>用户管理</h2>
      <div class="users-toolbar">
        <OInput v-model="search" placeholder="搜索用户名..." class="users-toolbar__search" @keyup.enter="load">
          <template #icon>
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
            </svg>
          </template>
        </OInput>
        <OButton variant="ghost" size="sm" @click="load">搜索</OButton>
      </div>
    </div>

    <div v-if="loading" style="display:flex;justify-content:center;padding:60px"><OSpinner /></div>
    <div v-else class="users-table">
      <div class="users-table__header">
        <div>用户名</div><div>邮箱</div><div>存储</div><div>角色</div><div>状态</div><div>注册时间</div><div>操作</div>
      </div>
      <div v-for="user in users" :key="user.id" class="users-table__row">
        <div class="users-table__cell users-table__name" data-label="用户名" :title="user.username">{{ user.username }}</div>
        <div class="users-table__cell users-table__email font-mono" data-label="邮箱" :title="user.email || '—'">{{ user.email || '—' }}</div>
        <div class="users-table__cell users-table__storage" data-label="存储">
          <template v-if="editingQuota?.id === user.id">
            <div class="quota-editor">
              <input v-model="editingQuota.val" class="quota-editor__input" type="number" min="0.01" step="0.01" @keyup.enter="saveQuota(user)" @keyup.escape="editingQuota=null" />
              <span class="quota-editor__unit">GB</span>
              <OButton variant="primary" size="sm" @click="saveQuota(user)">✓</OButton>
            </div>
          </template>
          <button v-else class="quota-btn" :title="`${formatSize(user.storageUsed)} / ${formatSize(user.storageQuota)}`" @click="editingQuota = { id: user.id, val: (user.storageQuota / 1073741824).toFixed(0) }">
            {{ formatSize(user.storageUsed) }} / {{ formatSize(user.storageQuota) }}
          </button>
        </div>
        <div class="users-table__cell" data-label="角色">
          <OBadge :variant="user.role === 'ROLE_ADMIN' ? 'accent' : 'default'">
            {{ user.role === 'ROLE_ADMIN' ? '管理员' : '用户' }}
          </OBadge>
        </div>
        <div class="users-table__cell" data-label="状态">
          <OBadge :variant="user.status === 1 ? 'success' : 'danger'">{{ user.status === 1 ? '正常' : '禁用' }}</OBadge>
        </div>
        <div class="users-table__cell users-table__date" data-label="注册时间" :title="formatDate(user.createdAt)">{{ formatDate(user.createdAt) }}</div>
        <div class="users-table__cell users-table__actions" data-label="操作">
          <OButton variant="subtle" size="sm" @click="router.push(`/admin/users/${user.id}/files`)">文件</OButton>
          <OButton variant="ghost" size="sm" @click="toggleActive(user)">{{ user.status === 1 ? '禁用' : '启用' }}</OButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}
.users-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
}
.users-toolbar__search {
  width: 220px;
  max-width: 42vw;
}
.users-table {
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow-x: auto;
  overflow-y: hidden;
}
.users-table__header {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) minmax(190px, 1.35fr) minmax(170px, 1fr) 82px 78px 130px 118px;
  gap: 10px;
  min-width: 900px;
  padding: 8px 12px;
  font-size: 11px;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  background: var(--bg-elevated);
  border-bottom: 1px solid var(--border);
}
.users-table__row {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) minmax(190px, 1.35fr) minmax(170px, 1fr) 82px 78px 130px 118px;
  gap: 10px;
  min-width: 900px;
  padding: 10px 12px;
  align-items: center;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
  transition: background var(--fast);
}
.users-table__row:last-child { border-bottom: none; }
.users-table__row:hover { background: var(--bg-elevated); }
.users-table__cell {
  min-width: 0;
  display: flex;
  align-items: center;
}
.users-table__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}
.users-table__email {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--text-secondary);
}
.users-table__storage { font-size: 12px; }
.users-table__date {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--text-secondary);
}
.users-table__actions {
  display: flex;
  gap: 4px;
  justify-content: flex-start;
}
.quota-editor {
  display: flex;
  gap: 4px;
  align-items: center;
  min-width: 0;
}
.quota-editor__input {
  width: 64px;
  min-width: 0;
  background: var(--bg-overlay);
  border: 1px solid var(--border-focus);
  border-radius: 4px;
  padding: 2px 6px;
  color: var(--text-primary);
  font-size: 12px;
  font-family: var(--font-mono);
  font-variant-numeric: lining-nums tabular-nums;
}
.quota-editor__unit { color: var(--text-muted); }
.quota-btn {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  background: none;
  border: 1px dashed var(--border);
  border-radius: 4px;
  padding: 2px 6px;
  cursor: pointer;
  color: var(--text-secondary);
  font-size: 11px;
  font-family: var(--font-mono);
  font-variant-numeric: lining-nums tabular-nums;
  transition: all var(--fast);
}
.quota-btn:hover { border-color: var(--accent); color: var(--accent); }

@media (max-width: 720px) {
  .page-header {
    align-items: stretch;
    flex-direction: column;
  }
  .users-toolbar,
  .users-toolbar__search {
    width: 100%;
    max-width: none;
  }
  .users-table {
    overflow: visible;
  }
  .users-table__header {
    display: none;
  }
  .users-table__row {
    grid-template-columns: 1fr;
    gap: 8px;
    min-width: 0;
    padding: 12px;
  }
  .users-table__cell {
    display: grid;
    grid-template-columns: 72px minmax(0, 1fr);
    gap: 12px;
    align-items: center;
  }
  .users-table__cell::before {
    content: attr(data-label);
    color: var(--text-muted);
    font-size: 11px;
  }
  .users-table__actions {
    justify-content: start;
  }
  .users-table__actions::before {
    align-self: center;
  }
}
</style>
