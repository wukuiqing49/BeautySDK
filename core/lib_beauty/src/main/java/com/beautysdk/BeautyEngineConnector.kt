package com.beautysdk

/** 引擎专属薄封装：只翻译帧和写回，不包含检测、美颜或线程队列。
 * read借用资源仅需在onFrame返回前有效；不得在此启动异步任务后继续使用借用输入。
 * write必须同步完成消费，或复制/租用输出并将释放绑定到引擎真实消费结束。
 * write返回true表示引擎接受本帧，false表示拒绝；不得在已经提交后抛异常。
 * passthrough用于处理前失败时保留原始引擎帧，不能假设每家引擎的旁路都是return true。
 */
interface BeautyEngineFrameAdapter<F> {
    fun read(frame: F): VideoFrame
    fun write(frame: F, output: VideoFrame): Boolean
    fun passthrough(frame: F): Boolean
}

/** 写回结果，不等同于模型推理状态或远端已经显示。 */
enum class BeautyEngineFrameResult(val accepted: Boolean) {
    PROCESSED(true), BYPASSED(true), REJECTED(false)
}

/** 统一同步回调入口：取引擎帧→标准帧→processor→引擎写回。
 * 拥有processor生命周期；onFrame/start/stop/close同线程调用，GL由processor另校验。
 * 不创建无界队列、不等待模型、不替引擎决定同步/异步写回方式。
 * 声网等回调可能换线程，适配层必须在进入本入口前固定执行线程和有效EGL环境。
 */
class BeautyEngineConnector<F> @JvmOverloads constructor(
    private val adapter: BeautyEngineFrameAdapter<F>,
    private val processor: FrameProcessor,
    private val onError: (Exception) -> Unit = {},
) : AutoCloseable {
    private val lifecycle = BeautyProcessor()
    private var closed = false
    private var processing = false
    private var lastFrameInfo: FrameInfo? = null

    /** 在选定线程启动；processor启动失败时恢复入口停止态。 */
    fun start() {
        check(!processing) { "Do not change lifecycle inside a frame callback" }
        lifecycle.start()
        try { processor.start() } catch (e: Exception) { lifecycle.stop(); throw e }
    }
    /** 每次引擎回调只调用此方法。写回一旦尝试，失败也不再旁路重试，避免重复提交。
     * 生命周期/线程错误直接抛出，不在错误线程触发引擎操作；处理错误可诊断地旁路。
     */
    fun onFrame(engineFrame: F): BeautyEngineFrameResult = deliver(engineFrame, false)
    /** 原采样帧调参重绘，仍统一read/处理/write；仅支持RedrawFrameProcessor，不伪造新时间戳。 */
    fun onRedraw(engineFrame: F): BeautyEngineFrameResult = deliver(engineFrame, true)
    private fun deliver(engineFrame: F, redraw: Boolean): BeautyEngineFrameResult {
        lifecycle.requireRunning()
        check(!processing) { "Reentrant engine callbacks are not supported" }
        processing = true
        try {
            val input: VideoFrame
            val output: VideoFrame
            try {
                input = adapter.read(engineFrame)
                output = if(redraw) {
                    require(input.info == lastFrameInfo) { "Redraw requires the latest processed frame" }
                    val target=processor as? RedrawFrameProcessor ?: error("Processor does not support redraw")
                    target.redraw(input)
                } else {
                    lifecycle.process(input)
                    processor.process(input).also { lastFrameInfo=input.info }
                }
            } catch (e: Exception) {
                report(e)
                return try {
                    if (adapter.passthrough(engineFrame)) BeautyEngineFrameResult.BYPASSED else BeautyEngineFrameResult.REJECTED
                } catch (fallback: Exception) { report(fallback); BeautyEngineFrameResult.REJECTED }
            }
            return try {
                if (!adapter.write(engineFrame, output)) BeautyEngineFrameResult.REJECTED
                else if (output === input) BeautyEngineFrameResult.BYPASSED else BeautyEngineFrameResult.PROCESSED
            } catch (e: Exception) { report(e); BeautyEngineFrameResult.REJECTED }
        } finally { processing = false }
    }
    /** 停止后可以start新时间线，不销毁GL资源。 */
    fun stop() {
        lifecycle.requireRunning(); check(!processing)
        try { processor.stop() } finally { lastFrameInfo=null; lifecycle.stop() }
    }
    /** 同尺寸换镜头/视频源时重启处理器与入口时间线，不销毁资源。 */
    fun resetSource() { stop(); start() }
    /** 在执行线程释放processor；不注销厂商回调，宿主须先停止厂商送帧。 */
    override fun close() {
        // 以生命周期检查线程，即使已关闭也拒绝从另一线程调用。
        lifecycle.requireOwnerThread(); check(!processing)
        if (!closed) { processor.close(); lifecycle.close(); lastFrameInfo=null; closed = true }
        else lifecycle.close()
    }
    private fun report(error: Exception) { try { onError(error) } catch (_: Exception) { } }
}
