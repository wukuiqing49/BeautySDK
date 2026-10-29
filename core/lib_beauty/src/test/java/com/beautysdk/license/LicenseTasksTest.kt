package com.beautysdk.license

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class LicenseTasksTest {
    @Test fun repeatedRefreshesUseOneQueuedTask() {
        val queue = ArrayDeque<Runnable>()
        var ticks = 0
        val tasks = LicenseTasks(Executor { queue.add(it) }) { ticks++ }
        repeat(1000) { tasks.request() }
        assertEquals(1, queue.size)
        queue.remove().run()
        assertEquals(1, ticks)
        assertEquals(0, queue.size)
    }

    @Test fun initializationDuringRefreshIsNotLostAndLatestPendingWins() {
        val queue = ArrayDeque<Runnable>()
        val applied = mutableListOf<Int>()
        var ticks = 0
        lateinit var tasks: LicenseTasks
        tasks = LicenseTasks(Executor { queue.add(it) }) {
            ticks++
            if (ticks == 1) {
                tasks.initialize { applied.add(1) }
                tasks.initialize { applied.add(2) }
                repeat(1000) { tasks.request() }
            }
        }
        tasks.request()
        queue.remove().run()
        assertEquals(1, queue.size)
        queue.remove().run()
        assertEquals(listOf(2), applied)
        assertEquals(2, ticks)
        assertEquals(0, queue.size)
    }
}
