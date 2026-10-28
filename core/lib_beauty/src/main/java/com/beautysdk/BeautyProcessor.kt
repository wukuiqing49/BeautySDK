package com.beautysdk

/** 帧处理契约：同线程启停与处理；具体实现声明输出所有权。实际美颜请使用 BeautySession。 */
interface FrameProcessor : AutoCloseable {
    /** 绑定调用线程并进入运行态；关闭后不能重新启动。 */
    fun start()
    /** 检查运行态和严格递增时间戳，原样返回借用帧，不复制像素或释放输入资源。 */
    fun process(frame: VideoFrame): VideoFrame
    /** 退出运行态并重置时间戳，允许下一次 start 从新时间线开始。 */
    fun stop()
    /** 永久关闭处理器并清空时间戳；同线程重复关闭安全。 */
    override fun close()
}

/** 可选同采样帧重绘契约：只更新显示参数，不推进视频时间线或重复提交分析。 */
interface RedrawFrameProcessor : FrameProcessor {
    fun redraw(frame: VideoFrame): VideoFrame
}

/**
 * 工程初始化用的旁路处理器，不包含美颜、人脸检测或 GL 渲染。
 * start/process/stop/close 由同一线程调用；start 后允许重复 start，stop 后可重启。
 * close 幂等且不可恢复；未 start 或已关闭时 process 抛出 IllegalStateException。
 * 不持有 Context，不申请权限，不创建线程、不复制或释放帧。
 */
class BeautyProcessor : FrameProcessor {
    /** 旁路处理器生命周期状态：未运行、运行中、永久关闭。 */
    private enum class State { IDLE, RUNNING, CLOSED }
    private var state = State.IDLE
    private var owner: Thread? = null
    private var lastTimestampNs = -1L

    /** 绑定调用线程并进入运行态；关闭后不能重新启动。 */
    @Synchronized
    override fun start() {
        checkThread()
        check(state != State.CLOSED) { "Processor is closed" }
        state = State.RUNNING
    }

    /** 检查运行态和严格递增时间戳，原样返回借用帧，不复制像素或释放输入资源。 */
    @Synchronized
    override fun process(frame: VideoFrame): VideoFrame {
        checkThread()
        check(state == State.RUNNING) { "Call start before processing frames" }
        require(frame.info.timestampNs > lastTimestampNs) { "Timestamps must strictly increase within a session" }
        lastTimestampNs = frame.info.timestampNs
        return frame
    }

    /** 退出运行态并重置时间戳，允许下一次 start 从新时间线开始。 */
    @Synchronized
    override fun stop() {
        checkThread()
        check(state != State.CLOSED) { "Processor is closed" }
        state = State.IDLE
        lastTimestampNs = -1L
    }

    /** 永久关闭处理器并清空时间戳；同线程重复关闭安全。 */
    @Synchronized
    override fun close() {
        checkThread()
        state = State.CLOSED
        lastTimestampNs = -1L
    }

    /** 首次调用记录所有者线程，后续跨线程调用立即失败。 */
    private fun checkThread() {
        val current = Thread.currentThread()
        if (owner == null) owner = current
        check(owner === current) { "Processor lifecycle and frames must use one thread" }
    }

    /** 供公共适配入口在释放资源前检查线程，不改变生命周期。 */
    @Synchronized internal fun requireOwnerThread() { checkThread() }

    /** 供会话内部检查线程和运行状态，不处理图像。 */
    @Synchronized
    internal fun requireRunning() {
        checkThread()
        check(state == State.RUNNING) { "Call start before processing frames" }
    }
}
