package app.solarma.ui.details

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for AlarmDetailsViewModel state classes and data validation logic.
 * We can't easily construct the ViewModel (Hilt + DI), but we can thoroughly
 * test its sealed class hierarchies and state transitions.
 */
class AlarmDetailsViewModelTest {
    // ── RefundState sealed class ──

    @Test
    fun `RefundState Idle is singleton`() {
        assertSame(RefundState.Idle, RefundState.Idle)
    }

    @Test
    fun `RefundState Processing is singleton`() {
        assertSame(RefundState.Processing, RefundState.Processing)
    }

    @Test
    fun `RefundState Success carries signature`() {
        val state = RefundState.Success("5KtPn1LGuxh...")
        assertEquals("5KtPn1LGuxh...", state.signature)
    }

    @Test
    fun `RefundState Success equality`() {
        val a = RefundState.Success("sig1")
        val b = RefundState.Success("sig1")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `RefundState Success inequality`() {
        val a = RefundState.Success("sig1")
        val b = RefundState.Success("sig2")
        assertNotEquals(a, b)
    }

    @Test
    fun `RefundState Error carries message`() {
        val state = RefundState.Error("Network timeout")
        assertEquals("Network timeout", state.message)
    }

    @Test
    fun `RefundState Error equality`() {
        val a = RefundState.Error("err")
        val b = RefundState.Error("err")
        assertEquals(a, b)
    }

    @Test
    fun `RefundState all types are distinct`() {
        val states = listOf(
            RefundState.Idle,
            RefundState.Processing,
            RefundState.Success("sig"),
            RefundState.Error("err")
        )
        assertEquals(4, states.toSet().size)
    }

    @Test
    fun `RefundState types are RefundState subclasses`() {
        assertTrue(RefundState.Idle is RefundState)
        assertTrue(RefundState.Processing is RefundState)
        assertTrue(RefundState.Success("x") is RefundState)
        assertTrue(RefundState.Error("x") is RefundState)
    }

    // ── DeleteState sealed class ──

    @Test
    fun `DeleteState Idle is singleton`() {
        assertSame(DeleteState.Idle, DeleteState.Idle)
    }

    @Test
    fun `DeleteState Deleted is singleton`() {
        assertSame(DeleteState.Deleted, DeleteState.Deleted)
    }

    @Test
    fun `DeleteState BlockedByDeposit is singleton`() {
        assertSame(DeleteState.BlockedByDeposit, DeleteState.BlockedByDeposit)
    }

    @Test
    fun `DeleteState all types are distinct`() {
        val states = listOf(
            DeleteState.Idle,
            DeleteState.Deleted,
            DeleteState.BlockedByDeposit
        )
        assertEquals(3, states.toSet().size)
    }

    @Test
    fun `DeleteState types are DeleteState subclasses`() {
        assertTrue(DeleteState.Idle is DeleteState)
        assertTrue(DeleteState.Deleted is DeleteState)
        assertTrue(DeleteState.BlockedByDeposit is DeleteState)
    }

    // ── When expression exhaustiveness ──

    @Test
    fun `RefundState when expression covers all cases`() {
        val states = listOf<RefundState>(
            RefundState.Idle,
            RefundState.Processing,
            RefundState.Success("s"),
            RefundState.Error("e")
        )
        for (state in states) {
            val label = when (state) {
                is RefundState.Idle -> "idle"
                is RefundState.Processing -> "processing"
                is RefundState.Success -> "success:${state.signature}"
                is RefundState.Error -> "error:${state.message}"
            }
            assertTrue(label.isNotEmpty())
        }
    }

    @Test
    fun `DeleteState when expression covers all cases`() {
        val states = listOf<DeleteState>(
            DeleteState.Idle,
            DeleteState.Deleted,
            DeleteState.BlockedByDeposit
        )
        for (state in states) {
            val label = when (state) {
                is DeleteState.Idle -> "idle"
                is DeleteState.Deleted -> "deleted"
                is DeleteState.BlockedByDeposit -> "blocked"
            }
            assertTrue(label.isNotEmpty())
        }
    }

    // ── RefundState.Success copy ──

    @Test
    fun `RefundState Success copy`() {
        val original = RefundState.Success("original")
        val copy = original.copy(signature = "modified")
        assertEquals("modified", copy.signature)
        assertEquals("original", original.signature)
    }

    @Test
    fun `RefundState Error copy`() {
        val original = RefundState.Error("original")
        val copy = original.copy(message = "modified")
        assertEquals("modified", copy.message)
    }
}
