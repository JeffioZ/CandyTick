package io.candy.tick.ui.list

import io.candy.tick.data.model.RepoStatus
import io.candy.tick.data.model.statusAt
import io.candy.tick.ui.list.ListViewModel.RepoListItem
import java.time.Instant

enum class SortMode(val defaultAscending: Boolean = false) {
    LAST_PUSH, UPDATABLE, ADDED, STATUS, NAME(true);

    internal val usesSyncData: Boolean
        get() = when (this) {
            LAST_PUSH, UPDATABLE, STATUS -> true
            ADDED, NAME -> false
        }
}

data class SortOrder(val mode: SortMode = SortMode.LAST_PUSH, val ascending: Boolean = mode.defaultAscending) {
    fun select(mode: SortMode): SortOrder =
        if (mode == this.mode) copy(ascending = !ascending) else SortOrder(mode)

    // Bare mode names and NAME_DESC are also the values written by earlier versions.
    internal val storageKey: String
        get() = when {
            ascending == mode.defaultAscending -> mode.name
            ascending -> "${mode.name}_ASC"
            else -> "${mode.name}_DESC"
        }

    internal fun comparator(): Comparator<RepoListItem> = when (mode) {
        SortMode.LAST_PUSH -> compareBy(nullsLast(valueOrder<Instant>())) { it.activityAt }
        SortMode.ADDED -> compareBy(valueOrder<Instant>()) { it.repo.addedAt }
        SortMode.NAME -> compareBy(valueOrder<String>()) { (it.repo.noteName ?: it.repo.repo).lowercase() }
        SortMode.UPDATABLE -> compareBy<RepoListItem, Boolean>(valueOrder()) { it.updateAvailable }
            .thenByDescending { it.activityAt }
        SortMode.STATUS -> {
            val now = Instant.now()
            // ACTIVE is first in the enum and represents the highest activity level.
            compareBy<RepoListItem, RepoStatus?>(nullsLast(valueOrder<RepoStatus>().reversed())) {
                it.snapshot?.statusAt(now)?.takeUnless { status -> status == RepoStatus.UNKNOWN }
            }.thenBy(nullsLast(valueOrder<Instant>())) { it.snapshot?.pushedAt }
        }
    }

    private fun <T : Comparable<T>> valueOrder(): Comparator<T> =
        if (ascending) naturalOrder() else reverseOrder()

    companion object {
        private val storedOrders = SortMode.entries.flatMap { mode ->
            listOf(SortOrder(mode, true), SortOrder(mode, false))
        }.associateBy { it.storageKey }

        internal fun fromStorage(key: String): SortOrder = storedOrders[key] ?: SortOrder()
    }
}
