package app.socksrouter.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticLogStoreTest {
    @Before
    fun clearBeforeTest() {
        DiagnosticLogStore.clear()
    }

    @Test
    fun recordsEntriesAndCanClearThem() {
        DiagnosticLogStore.info("服务已创建")
        DiagnosticLogStore.warning("网络已断开")

        assertEquals(2, DiagnosticLogStore.snapshot().size)
        assertEquals(DiagnosticLevel.WARNING, DiagnosticLogStore.snapshot().last().level)

        DiagnosticLogStore.clear()
        assertTrue(DiagnosticLogStore.snapshot().isEmpty())
    }

    @Test
    fun keepsOnlyTheMostRecentEntries() {
        repeat(210) { index -> DiagnosticLogStore.info("事件 $index") }

        val entries = DiagnosticLogStore.snapshot()
        assertEquals(200, entries.size)
        assertEquals("事件 10", entries.first().message)
        assertEquals("事件 209", entries.last().message)
    }
}
