<script setup lang="ts">
import type { PendingApproval } from '~/stores/chat'

defineProps<{
  approval: PendingApproval
}>()

const emit = defineEmits<{
  approve: [approvalId: string]
  deny: [approvalId: string]
}>()
</script>

<template>
  <div
    class="mx-4 my-2 p-4 rounded-lg border-2 border-yellow-400 dark:border-yellow-600 bg-yellow-50 dark:bg-yellow-950"
    data-testid="approval-banner"
  >
    <div class="flex items-start gap-3">
      <UIcon
        name="i-lucide-shield-alert"
        class="size-5 text-yellow-600 dark:text-yellow-400 shrink-0 mt-0.5"
      />
      <div class="flex-1 min-w-0">
        <p class="text-sm font-medium text-yellow-800 dark:text-yellow-200 mb-1">
          Approval Required
        </p>
        <p class="text-sm text-yellow-700 dark:text-yellow-300 mb-3">
          {{ approval.content }}
        </p>
        <div
          v-if="approval.riskScore !== undefined"
          class="mb-3"
        >
          <UBadge
            :color="approval.riskScore > 7 ? 'error' : approval.riskScore > 4 ? 'warning' : 'success'"
            variant="subtle"
            size="sm"
            data-testid="approval-risk-score"
          >
            Risk Score: {{ approval.riskScore }}/10
          </UBadge>
        </div>
        <div class="flex gap-2">
          <UButton
            color="primary"
            size="sm"
            data-testid="approval-approve-button"
            @click="emit('approve', approval.approvalId)"
          >
            Approve
          </UButton>
          <UButton
            color="error"
            variant="outline"
            size="sm"
            data-testid="approval-deny-button"
            @click="emit('deny', approval.approvalId)"
          >
            Deny
          </UButton>
        </div>
      </div>
    </div>
  </div>
</template>
