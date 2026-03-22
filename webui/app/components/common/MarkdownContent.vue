<script setup lang="ts">
import MarkdownIt from 'markdown-it'

const props = defineProps<{
  content: string
}>()

const md = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: true,
  breaks: true,
})

const rendered = computed(() => md.render(props.content))
</script>

<template>
  <div
    class="prose dark:prose-invert prose-sm max-w-none break-words"
    data-testid="markdown-content"
    v-html="rendered"
  />
</template>
