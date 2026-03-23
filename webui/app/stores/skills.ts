import { defineStore } from 'pinia'
import type { SkillInfo } from '~/types/config'

export const useSkillsStore = defineStore('skills', () => {
  const skills = ref<SkillInfo[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchSkills() {
    const { api } = useApi()
    loading.value = true
    error.value = null
    try {
      const response = await api<{ skills: SkillInfo[] }>('/skills')
      skills.value = response.skills
    }
    catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to fetch skills'
    }
    finally {
      loading.value = false
    }
  }

  async function validateSkill(name: string): Promise<{ valid: boolean, errors?: string[] }> {
    const { api } = useApi()
    try {
      return await api<{ valid: boolean, errors?: string[] }>(`/skills/${encodeURIComponent(name)}/validate`, {
        method: 'POST',
      })
    }
    catch (e) {
      return { valid: false, errors: [e instanceof Error ? e.message : 'Validation failed'] }
    }
  }

  return {
    skills,
    loading,
    error,
    fetchSkills,
    validateSkill,
  }
})
