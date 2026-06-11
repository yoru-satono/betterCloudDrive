<script setup lang="ts">
import { onMounted, ref } from 'vue'
import OButton from '@/components/base/OButton.vue'
import OInput from '@/components/base/OInput.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import OBadge from '@/components/base/OBadge.vue'
import { useFormatters } from '@/composables/useFormatters'
import { useConfirm } from '@/composables/useConfirm'
import * as adminApi from '@/api/admin'
import { toast } from 'vue-sonner'
import type { UserEntity } from '@/types/user'

const { formatSize, formatDate } = useFormatters()
const { confirm } = useConfirm()
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
  await adminApi.updateUser(user.id, { isActive: !user.isActive })
  toast.success(user.isActive ? '用户已禁用' : '用户已启用')
  load()
}

async function saveQuota(user: UserEntity) {
  if (!editingQuota.value) return
  const bytes = parseFloat(editingQuota.value.val) * 1024 * 1024 * 1024
  await adminApi.updateUser(user.id, { storageQuota: bytes })
  toast.success('配额已更新')
  editingQuota.value = null
  load()
}

async function deleteUser(user: UserEntity) {
  const ok = await confirm('删除用户', `删除用户「${user.username}」及其所有数据？此操作不可撤销。`)
  if (!ok) return
  await adminApi.deleteUser(user.id)
  toast.success('用户已删除')
  load()
}

onMounted(load)
</script>

<template>
  <div class="page-enter">
    <div class="page-header">
      <h2>用户管理</h2>
      <div style="display:flex;gap:8px;align-items:center">
        <OInput v-model="search" placeholder="搜索用户名..." style="width:200px" @keyup.enter="load">
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
        <div class="users-table__name">{{ user.username }}</div>
        <div class="users-table__email font-mono">{{ user.email || '—' }}</div>
        <div style="font-size:12px">
          <template v-if="editingQuota?.id === user.id">
            <div style="display:flex;gap:4px;align-items:center">
              <input v-model="editingQuota.val" style="width:60px;background:var(--bg-overlay);border:1px solid var(--border-focus);border-radius:4px;padding:2px 6px;color:var(--text-primary);font-size:12px;font-family:var(--font-mono)" @keyup.enter="saveQuota(user)" @keyup.escape="editingQuota=null" />
              <span style="color:var(--text-muted)">GB</span>
              <OButton variant="primary" size="sm" @click="saveQuota(user)">✓</OButton>
            </div>
          </template>
          <button v-else class="quota-btn" @click="editingQuota = { id: user.id, val: (user.storageQuota / 1073741824).toFixed(0) }">
            {{ formatSize(user.storageUsed) }} / {{ formatSize(user.storageQuota) }}
          </button>
        </div>
        <div>
          <OBadge :variant="user.role === 'ROLE_ADMIN' ? 'accent' : 'default'">
            {{ user.role === 'ROLE_ADMIN' ? '管理员' : '用户' }}
          </OBadge>
        </div>
        <div>
          <OBadge :variant="user.isActive ? 'success' : 'danger'">{{ user.isActive ? '正常' : '禁用' }}</OBadge>
        </div>
        <div style="font-size:11px;color:var(--text-secondary)">{{ formatDate(user.createdAt) }}</div>
        <div style="display:flex;gap:4px">
          <OButton variant="ghost" size="sm" @click="toggleActive(user)">{{ user.isActive ? '禁用' : '启用' }}</OButton>
          <OButton variant="danger" size="sm" @click="deleteUser(user)">删除</OButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; }
.users-table { border: 1px solid var(--border); border-radius: 10px; overflow: hidden; }
.users-table__header {
  display: grid;
  grid-template-columns: 120px 160px 140px 80px 70px 100px 120px;
  gap: 8px; padding: 8px 12px;
  font-size: 11px; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.06em;
  background: var(--bg-elevated); border-bottom: 1px solid var(--border);
}
.users-table__row {
  display: grid;
  grid-template-columns: 120px 160px 140px 80px 70px 100px 120px;
  gap: 8px; padding: 10px 12px; align-items: center;
  border-bottom: 1px solid var(--border); font-size: 13px;
  transition: background var(--fast);
}
.users-table__row:last-child { border-bottom: none; }
.users-table__row:hover { background: var(--bg-elevated); }
.users-table__name { font-weight: 500; }
.users-table__email { font-size: 11px; color: var(--text-secondary); }
.quota-btn {
  background: none; border: 1px dashed var(--border); border-radius: 4px;
  padding: 2px 6px; cursor: pointer; color: var(--text-secondary); font-size: 11px;
  font-family: var(--font-mono); transition: all var(--fast);
}
.quota-btn:hover { border-color: var(--accent); color: var(--accent); }
</style>
