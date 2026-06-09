<template>
  <div class="animate-fade-in" style="max-width:600px">
    <h2>个人设置</h2>
    <div class="card" style="margin-top:20px">
      <div class="profile-section">
        <div class="avatar-lg">{{ auth.user?.username?.charAt(0)?.toUpperCase() }}</div>
        <div>
          <h3>{{ auth.user?.username }}</h3>
          <p class="text-muted">{{ auth.user?.email || '未设置邮箱' }}</p>
          <p class="text-muted">{{ auth.user?.role === 'ROLE_ADMIN' ? '🔧 管理员' : '👤 普通用户' }}</p>
        </div>
      </div>
    </div>
    <div class="card" style="margin-top:16px">
      <h3 style="margin-bottom:8px">存储用量</h3>
      <div class="storage-bar" style="height:8px;background:var(--bg-hover);border-radius:4px;overflow:hidden;margin-bottom:8px">
        <div :style="{width:auth.storagePercent+'%',height:'100%',background:'var(--accent)',borderRadius:'4px',transition:'width .4s'}"></div>
      </div>
      <p class="text-mono text-muted">{{ formatSize(auth.user?.storageUsed) }} / {{ formatSize(auth.user?.storageQuota) }} ({{ auth.storagePercent }}%)</p>
    </div>
    <div class="card" style="margin-top:16px">
      <h3 style="margin-bottom:12px">邮箱验证</h3>
      <p v-if="auth.user?.emailVerified" class="text-accent">✅ 已验证</p>
      <template v-else>
        <button class="btn btn-primary btn-sm" @click="sendCode" :disabled="sent">发送验证码</button>
        <div v-if="sent" style="display:flex;gap:8px;margin-top:12px">
          <input v-model="code" class="input" placeholder="6位验证码" maxlength="6" style="width:140px" />
          <button class="btn btn-primary btn-sm" @click="verifyCode">确认</button>
        </div>
        <p v-if="msg" class="text-muted" style="margin-top:8px">{{ msg }}</p>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
const auth = useAuthStore()
const sent = ref(false); const code = ref(''); const msg = ref('')
async function sendCode() { try { await auth.sendVerificationCode(); sent.value = true; msg.value = '验证码已发送' } catch { msg.value = '发送失败' } }
async function verifyCode() { try { await auth.verifyEmail(code.value); await auth.fetchMe(); msg.value = '邮箱验证成功！' } catch { msg.value = '验证失败' } }
function formatSize(b?: number) { if (!b) return '0 B'; if (b < 1073741824) return (b/1048576).toFixed(1)+' MB'; return (b/1073741824).toFixed(2)+' GB' }
</script>
<style scoped>
.profile-section { display: flex; align-items: center; gap: 16px; }
.avatar-lg { width: 56px; height: 56px; border-radius: 50%; background: var(--accent); color: var(--text-inverse); display: flex; align-items: center; justify-content: center; font-size: 22px; font-weight: 700; font-family: var(--font-display); }
</style>
