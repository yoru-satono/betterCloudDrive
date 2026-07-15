<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import OButton from '@/components/base/OButton.vue'
import OEmptyState from '@/components/base/OEmptyState.vue'
import OInput from '@/components/base/OInput.vue'
import OModal from '@/components/base/OModal.vue'
import OSpinner from '@/components/base/OSpinner.vue'
import { useTeamsStore } from '@/stores/teams'
import { useFormatters } from '@/composables/useFormatters'

const teams = useTeamsStore()
const router = useRouter()
const { formatSize } = useFormatters()
const showCreate = ref(false)
const teamName = ref('')

onMounted(() => void teams.fetchTeams())

async function createTeam() {
  const name = teamName.value.trim()
  if (!name) return
  const team = await teams.createTeam(name)
  teamName.value = ''
  showCreate.value = false
  toast.success('团队已创建')
  await router.push({ name: 'TeamFiles', params: { teamId: team.id } })
}
</script>

<template>
  <section class="teams-page">
    <header class="teams-page__header">
      <div>
        <h1>团队空间</h1>
        <p>选择团队以访问共享文件和协作功能。</p>
      </div>
      <div class="teams-page__actions">
        <OButton variant="primary" @click="showCreate = true">新建团队</OButton>
      </div>
    </header>

    <div v-if="teams.loading" class="teams-page__loading"><OSpinner /></div>
    <OEmptyState v-else-if="teams.teams.length === 0" title="暂无团队" description="创建团队空间后即可与成员共享文件">
      <OButton variant="primary" @click="showCreate = true">新建团队</OButton>
    </OEmptyState>
    <div v-else class="teams-page__grid" data-testid="teams-list">
      <button
        v-for="team in teams.teams"
        :key="team.id"
        type="button"
        class="team-card"
        @click="router.push({ name: 'TeamFiles', params: { teamId: team.id } })"
      >
        <span class="team-card__name">{{ team.name }}</span>
        <span class="team-card__role">{{ team.role }}</span>
        <span class="team-card__quota">{{ formatSize(team.storageUsed) }} / {{ formatSize(team.storageQuota) }}</span>
        <span class="team-card__bar"><span :style="{ width: `${Math.min(100, team.storageQuota ? team.storageUsed / team.storageQuota * 100 : 0)}%` }" /></span>
      </button>
    </div>

    <OModal title="新建团队" :open="showCreate" @close="showCreate = false">
      <OInput v-model="teamName" label="团队名称" placeholder="输入团队名称" @keyup.enter="createTeam" />
      <template #footer>
        <OButton variant="ghost" @click="showCreate = false">取消</OButton>
        <OButton variant="primary" @click="createTeam">创建</OButton>
      </template>
    </OModal>
  </section>
</template>

<style scoped>
.teams-page { display: flex; flex-direction: column; gap: 24px; }
.teams-page__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.teams-page__header h1 { margin: 0 0 6px; font-size: 24px; }
.teams-page__header p { margin: 0; color: var(--text-muted); font-size: 13px; }
.teams-page__actions { display: flex; gap: 8px; }
.teams-page__loading { min-height: 240px; display: grid; place-items: center; }
.teams-page__grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 14px; }
.team-card { display: flex; flex-direction: column; gap: 8px; padding: 18px; text-align: left; color: var(--text-primary); background: var(--bg-surface); border: 1px solid var(--border); border-radius: 12px; cursor: pointer; transition: border-color var(--fast), transform var(--fast); }
.team-card:hover { border-color: var(--border-hover); transform: translateY(-1px); }
.team-card__name { font-size: 16px; font-weight: 600; }
.team-card__role, .team-card__quota { color: var(--text-muted); font-size: 12px; }
.team-card__bar { height: 4px; overflow: hidden; background: var(--bg-overlay); border-radius: 99px; }
.team-card__bar span { display: block; height: 100%; background: var(--accent); border-radius: inherit; }
@media (max-width: 640px) { .teams-page__header { flex-direction: column; } .teams-page__actions { width: 100%; flex-wrap: wrap; } }
</style>
