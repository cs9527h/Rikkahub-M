plugins { kotlin("jvm") version "2.4.21" }
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
sourceSets {
    main {
        kotlin.srcDir("../../app/src/main/java")
        kotlin.include("me/rerere/rikkahub/data/sync/BackupCrypto.kt")
        kotlin.include("me/rerere/rikkahub/data/sync/BackupArchiveFormat.kt")
        kotlin.include("me/rerere/rikkahub/data/sync/BackupManifest.kt")
        kotlin.include("me/rerere/rikkahub/data/sync/BackupFileUris.kt")
        kotlin.include("me/rerere/rikkahub/data/sync/EncryptedBackupSession.kt")
        kotlin.include("me/rerere/rikkahub/data/sync/BackupKind.kt")
        kotlin.include("me/rerere/rikkahub/data/sync/BackupFingerprint.kt")
        kotlin.include("me/rerere/rikkahub/data/sync/AutoBackupPolicy.kt")
        kotlin.include("me/rerere/rikkahub/data/sync/MemoryBackupCodec.kt")
        kotlin.include("me/rerere/rikkahub/data/model/MemoryPaths.kt")
    }
    test {
        kotlin.srcDir("../../app/src/test/java")
        kotlin.include("me/rerere/rikkahub/data/sync/BackupCryptoTest.kt")
        kotlin.include("me/rerere/rikkahub/data/sync/BackupArchiveFormatTest.kt")
        kotlin.include("me/rerere/rikkahub/data/sync/BackupManifestTest.kt")
        kotlin.include("me/rerere/rikkahub/data/sync/BackupFileUrisTest.kt")
        kotlin.include("me/rerere/rikkahub/data/sync/EncryptedBackupSessionTest.kt")
        kotlin.include("me/rerere/rikkahub/data/sync/BackupFingerprintTest.kt")
        kotlin.include("me/rerere/rikkahub/data/sync/AutoBackupPolicyTest.kt")
        kotlin.include("me/rerere/rikkahub/data/sync/MemoryBackupCodecTest.kt")
    }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { testLogging { events("passed", "failed", "skipped") } }
