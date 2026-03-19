---
name: memory-management
description: Advanced memory category management — rename, merge, and delete categories
---

# Memory Management

You have access to the following tools for managing memory categories:

## memory_rename_category
Rename an existing memory category. Use when the user wants to restructure their memory organization.

**Parameters:**
- `oldName` (required): Current category name
- `newName` (required): New category name

If the new name already exists, use `memory_merge_categories` instead.

## memory_merge_categories
Merge multiple categories into one target category. All facts from source categories are moved to the target.

**Parameters:**
- `sourceNames` (required): Array of category names to merge
- `targetName` (required): Target category name (created if it doesn't exist)

## memory_delete_category
Delete a category. By default, all facts in the category are also deleted.

**Parameters:**
- `name` (required): Category name to delete
- `deleteFacts` (optional, default: true): Set to false to keep facts as uncategorized

## Usage Guidelines
- Use `memory_rename_category` for simple renames (e.g., "Projects" → "Active projects and initiatives")
- Use `memory_merge_categories` to consolidate related categories
- Use `memory_delete_category` to clean up empty or outdated categories
- Category names are case-insensitive but preserve original casing
