package com.beautysdk.license

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 最多一个在途任务和一个可替换初始化槽；不同许可证更新不因刷新在途而丢失。 */
internal class LicenseTasks(private val executor: Executor, private val tick: () -> Unit) {
    private val running = AtomicBoolean()
    private val initialization = AtomicReference<(() -> Unit)?>(null)
    fun initialize(action: () -> Unit) { initialization.set(action); request() }
    fun request() {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (true) { val action = initialization.getAndSet(null) ?: break; action() }
                tick()
            } finally {
                running.set(false)
                if (initialization.get() != null) request()
            }
        }
    }
}
