package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSpaceTest {
    @Test fun exactNeedPlusMarginFitsAndOneByteLessDoesNot() {
        val needed = 100L
        val margin = BackupSpace.MARGIN_BYTES
        assertTrue(BackupSpace.fits(needed, needed + margin))
        assertFalse(BackupSpace.fits(needed, needed + margin - 1))
    }

    @Test fun hugeButValidArchiveNeedsItsFullSizePlusMargin() {
        val needed = 3L * 1024 * 1024 * 1024
        assertFalse(BackupSpace.fits(needed, needed))
        assertTrue(BackupSpace.fits(needed, needed + BackupSpace.MARGIN_BYTES))
        // The caller must skip the check when the platform reports zero free space.
        assertFalse(BackupSpace.fits(needed, 0))
    }

    @Test fun invalidValuesAreRejectedInsteadOfDeletingOrBlockingSilently() {
        assertTrue(runCatching { BackupSpace.fits(-1, 100) }.isFailure)
        assertTrue(runCatching { BackupSpace.fits(1, -1) }.isFailure)
        assertTrue(runCatching { BackupSpace.fits(1, 100, -1) }.isFailure)
    }

    @Test fun overflowIsRejected() {
        assertTrue(runCatching { BackupSpace.requiredBytes(Long.MAX_VALUE) }.isFailure)
        assertTrue(runCatching { BackupSpace.fits(Long.MAX_VALUE, Long.MAX_VALUE) }.isFailure)
    }
}
