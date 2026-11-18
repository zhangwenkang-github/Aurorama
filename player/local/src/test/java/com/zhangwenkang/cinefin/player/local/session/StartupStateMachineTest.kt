package com.zhangwenkang.cinefin.player.local.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupStateMachineTest {

    @Test
    fun `初值为 IDLE 且不归属任何代次`() {
        val machine = StartupStateMachine()

        assertEquals(StartupPhase.IDLE, machine.phase)
        assertEquals(0L, machine.generation)
        assertNull(machine.sourceIntentId)
        assertFalse(machine.startupInProgress)
        assertFalse(machine.isCurrent(0L))
    }

    @Test
    fun `合法迁移表覆盖全部相位组合`() {
        val legal =
            mapOf(
                StartupPhase.IDLE to setOf(StartupPhase.RESOLVING),
                StartupPhase.RESOLVING to
                    setOf(StartupPhase.PREPARING, StartupPhase.FAILED, StartupPhase.IDLE),
                StartupPhase.PREPARING to
                    setOf(StartupPhase.READY, StartupPhase.FAILED, StartupPhase.IDLE),
                StartupPhase.READY to setOf(StartupPhase.IDLE),
                StartupPhase.FAILED to setOf(StartupPhase.IDLE),
            )

        for (from in StartupPhase.entries) {
            for (to in StartupPhase.entries) {
                if (to == StartupPhase.RESOLVING) continue
                val machine = machineIn(from)
                val applied = applyPrimitive(machine, to)
                val expected = to in legal.getValue(from)
                assertEquals("$from -> $to", expected, applied)
                assertEquals("$from -> $to", if (expected) to else from, machine.phase)
            }
        }
    }

    @Test
    fun `非法迁移拒绝且保持原相位`() {
        val rejected =
            listOf(
                { m: StartupStateMachine -> m.markPreparing() } to StartupPhase.IDLE,
                { m: StartupStateMachine -> m.markReady() } to StartupPhase.IDLE,
                { m: StartupStateMachine -> m.markFailed(m.generation) } to StartupPhase.IDLE,
                { m: StartupStateMachine -> m.resetToIdle() } to StartupPhase.IDLE,
                { m: StartupStateMachine -> m.markReady() } to StartupPhase.RESOLVING,
                { m: StartupStateMachine -> m.markPreparing() } to StartupPhase.PREPARING,
                { m: StartupStateMachine -> m.markFailed(m.generation) } to StartupPhase.READY,
                { m: StartupStateMachine -> m.markReady() } to StartupPhase.READY,
                { m: StartupStateMachine -> m.markPreparing() } to StartupPhase.FAILED,
                { m: StartupStateMachine -> m.markFailed(m.generation) } to StartupPhase.FAILED,
            )

        for ((action, from) in rejected) {
            val machine = machineIn(from)

            assertFalse("$from 上的非法迁移必须被拒绝", action(machine))
            assertEquals("$from 的相位不许被非法迁移改写", from, machine.phase)
        }
    }

    @Test
    fun `新起播从任意相位经 IDLE 复位到 RESOLVING`() {
        for (phase in StartupPhase.entries) {
            val machine = machineIn(phase)
            val before = machine.generation

            val generation = machine.begin("next-$phase")

            assertEquals(StartupPhase.RESOLVING, machine.phase)
            assertEquals(before + 1L, generation)
            assertEquals(machine.generation, generation)
            assertEquals("next-$phase", machine.sourceIntentId)
            assertTrue(machine.startupInProgress)
        }
    }

    @Test
    fun `取消归 IDLE`() {
        val resolving = machineIn(StartupPhase.RESOLVING)

        assertTrue(resolving.resetToIdle())

        assertEquals(StartupPhase.IDLE, resolving.phase)
        assertFalse(resolving.startupInProgress)
        // IDLE 上重复复位同样被拒绝
        assertFalse(resolving.resetToIdle())

        val preparing = machineIn(StartupPhase.PREPARING)

        assertTrue(preparing.resetToIdle())

        assertEquals(StartupPhase.IDLE, preparing.phase)
        assertFalse(preparing.startupInProgress)
    }

    @Test
    fun `代次归属`() {
        val machine = StartupStateMachine()
        val first = machine.begin("intent-1")

        assertEquals(1L, first)
        assertEquals("intent-1", machine.sourceIntentId)
        assertTrue(machine.isCurrent(first))

        assertTrue(machine.markPreparing())
        val second = machine.begin("intent-2")
        assertEquals(first + 1L, second)
        assertEquals(StartupPhase.RESOLVING, machine.phase)
        assertFalse(machine.isCurrent(first))
        assertTrue(machine.isCurrent(second))

        // 旧协程的失败结果不许把新一次起播标成 FAILED
        assertFalse(machine.markFailed(first))
        assertEquals(StartupPhase.RESOLVING, machine.phase)
        assertTrue(machine.markFailed(second))
        assertEquals(StartupPhase.FAILED, machine.phase)

        // 复位后当前代次也不再归属
        assertTrue(machine.resetToIdle())
        assertFalse(machine.isCurrent(second))
    }

    @Test
    fun `startupInProgress 只覆盖起播窗口`() {
        val expected =
            mapOf(
                StartupPhase.IDLE to false,
                StartupPhase.RESOLVING to true,
                StartupPhase.PREPARING to true,
                StartupPhase.READY to false,
                StartupPhase.FAILED to false,
            )

        for ((phase, expectedValue) in expected) {
            assertEquals(phase.toString(), expectedValue, machineIn(phase).startupInProgress)
        }
    }

    private fun machineIn(phase: StartupPhase): StartupStateMachine {
        val machine = StartupStateMachine()
        if (phase == StartupPhase.IDLE) return machine
        machine.begin("intent")
        when (phase) {
            StartupPhase.RESOLVING -> Unit
            StartupPhase.PREPARING -> assertTrue(machine.markPreparing())
            StartupPhase.READY -> {
                assertTrue(machine.markPreparing())
                assertTrue(machine.markReady())
            }
            StartupPhase.FAILED -> assertTrue(machine.markFailed(machine.generation))
            StartupPhase.IDLE -> Unit
        }
        assertEquals(phase, machine.phase)
        return machine
    }

    private fun applyPrimitive(machine: StartupStateMachine, to: StartupPhase): Boolean =
        when (to) {
            StartupPhase.PREPARING -> machine.markPreparing()
            StartupPhase.READY -> machine.markReady()
            StartupPhase.FAILED -> machine.markFailed(machine.generation)
            StartupPhase.IDLE -> machine.resetToIdle()
            StartupPhase.RESOLVING -> error("RESOLVING 只能经新起播 begin 进入")
        }
}
