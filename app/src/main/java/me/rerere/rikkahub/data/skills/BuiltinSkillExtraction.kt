package me.rerere.rikkahub.data.skills

/** A failed extraction must not look like a successfully loaded, empty builtin catalog. */
internal class BuiltinSkillExtraction(private val extract: () -> Unit) {
    @Volatile
    private var completed = false
    private val lock = Any()

    fun ensureExtracted() {
        if (completed) return
        synchronized(lock) {
            if (completed) return
            extract()
            completed = true
        }
    }
}
