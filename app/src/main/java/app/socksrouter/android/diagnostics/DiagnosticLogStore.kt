package app.socksrouter.android.diagnostics

enum class DiagnosticLevel {
    INFO,
    WARNING,
    ERROR,
}

data class DiagnosticLogEntry(
    val timestampMillis: Long,
    val level: DiagnosticLevel,
    val message: String,
)

/** Short-lived, in-memory diagnostics. Destination details and payloads are never stored. */
object DiagnosticLogStore {
    private const val MAX_ENTRIES = 200
    private val entries = ArrayDeque<DiagnosticLogEntry>()

    @Synchronized
    fun record(level: DiagnosticLevel, message: String) {
        entries.addLast(DiagnosticLogEntry(System.currentTimeMillis(), level, message))
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    fun info(message: String) = record(DiagnosticLevel.INFO, message)
    fun warning(message: String) = record(DiagnosticLevel.WARNING, message)
    fun error(message: String) = record(DiagnosticLevel.ERROR, message)

    @Synchronized
    fun snapshot(): List<DiagnosticLogEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()
}
