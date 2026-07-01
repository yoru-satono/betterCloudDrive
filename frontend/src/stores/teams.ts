import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as teamsApi from '@/api/teams'
import type { Team } from '@/types/team'

export const useTeamsStore = defineStore('teams', () => {
  const teams = ref<Team[]>([])
  const loading = ref(false)

  async function fetchTeams() {
    loading.value = true
    try {
      const { data } = await teamsApi.listTeams()
      teams.value = data.data
    } finally {
      loading.value = false
    }
  }

  async function createTeam(name: string) {
    const { data } = await teamsApi.createTeam(name)
    teams.value = [...teams.value.filter(team => team.id !== data.data.id), data.data]
      .sort((a, b) => a.name.localeCompare(b.name))
    return data.data
  }

  function upsertTeam(team: Team) {
    teams.value = [...teams.value.filter(item => item.id !== team.id), team]
      .sort((a, b) => a.name.localeCompare(b.name))
  }

  function removeTeam(teamId: number) {
    teams.value = teams.value.filter(team => team.id !== teamId)
  }

  return {
    teams,
    loading,
    fetchTeams,
    createTeam,
    upsertTeam,
    removeTeam,
  }
})
