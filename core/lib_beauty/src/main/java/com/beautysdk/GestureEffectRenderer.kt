package com.beautysdk

/** 在美颜、贴纸和运镜完成后，将触发素材合成进输出纹理。与delegate共用所属ES3线程。 */
class GestureEffectRenderer(private val delegate:BeautyRenderer,private val player:GestureEffectPlayer):BeautyRenderer {
    private var pass:SceneGlPass?=null
    override fun render(frame:VideoFrame,settings:BeautySettings,face:FaceObservation?):VideoFrame {
        val output=delegate.render(frame,settings,face)
        val active=player.frame(frame.info.timestampNs)?:return output
        require(output is TextureFrame)
        return (pass?:SceneGlPass().also{pass=it}).render(output,BeautyScene(backgroundImage=active.rule.image),null,floatArrayOf(0f,0f,1f,1f),active)
    }
    override fun close(){try{pass?.close();pass=null;player.reset()}finally{delegate.close()}}
}
