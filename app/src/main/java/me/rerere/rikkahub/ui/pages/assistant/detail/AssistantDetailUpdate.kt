package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/** Merge media intent against the snapshot actually rendered by the caller, not a newer VM emission. */
internal fun mergeAssistantDetailUpdate(
    routeId: Uuid,
    before: Assistant,
    updated: Assistant,
    latest: Assistant?,
): Assistant? {
    if (before.id != routeId || updated.id != routeId || latest == null || latest.id != routeId) return null
    return updated.copy(
        avatar = if (updated.avatar == before.avatar) latest.avatar else updated.avatar,
        background = if (updated.background == before.background) latest.background else updated.background,
    )
}
