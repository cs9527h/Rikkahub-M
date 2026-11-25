package me.rerere.rikkahub.ui.pages.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.datastore.BackupReminderConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSettingsLoadingTest {
    @Test
    fun `backup selection waits for delayed disk preferences instead of dummy settings`() = runBlocking {
        withTimeout(10_000) {
            val diskGate = CompletableDeferred<Unit>()
            val selected = listOf(WebDavConfig.BackupItem.DATABASE)
            val preferences = preferencesOf(
                SettingsStore.BACKUP_REMINDER_CONFIG to JsonInstant.encodeToString(
                    BackupReminderConfig(localBackupItems = selected)
                )
            )
            val disk = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flow {
                    diskGate.await()
                    emit(preferences)
                }

                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                    error("Reading backup selection must not write preferences")
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = SettingsStore(disk, scope)
                assertTrue(store.settingsFlow.value.init)
                val loading = async(start = CoroutineStart.UNDISPATCHED) { awaitLocalBackupItems(store) }
                assertFalse(loading.isCompleted)
                diskGate.complete(Unit)
                assertEquals(selected, loading.await())
                assertFalse(store.settingsFlow.value.init)
                assertEquals(selected, awaitLocalBackupItems(store))
            } finally {
                scope.cancel()
            }
        }
    }
}
