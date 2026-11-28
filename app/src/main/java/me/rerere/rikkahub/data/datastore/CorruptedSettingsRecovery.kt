package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import java.io.FileOutputStream

/** Empty replacement is permitted only after preserving the original bytes in a separate durable file. */
internal fun recoverCorruptedSettings(source: File, backup: File): Preferences {
    source.copyTo(backup, overwrite = false)
    FileOutputStream(backup, true).use { it.fd.sync() }
    return emptyPreferences()
}
