plugins { kotlin("jvm") version "2.4.21" }
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
sourceSets {
    main {
        kotlin.srcDir("../../app/src/main/java")
        kotlin.include("me/rerere/rikkahub/data/sync/BackupCrypto.kt")
        kotlin.include("me/rerere/rikkahub/data/sync/BackupArchiveFormat.kt")
    }
    test {
        kotlin.srcDir("../../app/src/test/java")
        kotlin.include("me/rerere/rikkahub/data/sync/BackupCryptoTest.kt")
        kotlin.include("me/rerere/rikkahub/data/sync/BackupArchiveFormatTest.kt")
    }
}
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test { testLogging { events("passed", "failed", "skipped") } }
