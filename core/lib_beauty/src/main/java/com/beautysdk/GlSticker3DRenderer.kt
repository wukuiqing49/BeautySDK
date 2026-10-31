package com.beautysdk

import android.opengl.EGL14
import android.opengl.GLES30 as GL
import com.beautysdk.license.BeautyLicensing
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 可选刚性三维饰品合成器，放在BeautySceneRenderer内部、最终运镜之前。
 * delegate与本实例共用创建ES3线程/context；接受OES/2D，输出借用2D纹理至下次render/close。
 * 关闭时同时关闭delegate。不做GPU读回。首版明确拒绝与二维美型同时开启，避免拉弯模型/错位。
 */
class GlSticker3DRenderer(private val delegate: BeautyRenderer,
                         private val maxPoseAgeNs: Long = BeautyPipelineDefaults.MAX_RESULT_AGE_NS) : BeautyRenderer {
    private val owner=Thread.currentThread()
    private val context=EGL14.eglGetCurrentContext()
    private var closed=false
    private var pass: SceneGlPass?=null
    private var program=0
    private var fbo=0
    private var depth=0
    private var width=0
    private var height=0
    private var vao=0
    private var vbo=0
    private var ebo=0
    private var occluderVao=0
    private var occluderVbo=0
    private var headVao=0
    private var headVbo=0
    private var headEbo=0
    private var headUploaded=false
    private var cached: StickerMesh3D?=null
    private val poseCache=Sticker3DPoseCache(maxPoseAgeNs)
    private var uploadedOccluderPose: Sticker3DGeometry.Pose?=null
    private val identityScene=BeautyScene()
    private val fullCrop=floatArrayOf(0f,0f,1f,1f)
    private var settingsSource: BeautySettings?=null
    private var delegateSettings: BeautySettings?=null
    private val occluder=ByteBuffer.allocateDirect(Sticker3DGeometry.oval.size*3*10*4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    init {
        require(maxPoseAgeNs in 0..BeautyPipelineDefaults.MAX_RESULT_AGE_NS)
        check(context!=EGL14.EGL_NO_CONTEXT) { "A current ES3 context is required" }
    }
    override fun render(frame: VideoFrame, settings: BeautySettings, face: FaceObservation?): VideoFrame {
        checkOwner(); check(!closed) { "3D renderer is closed" }
        if(!BeautyLicensing.allowsSettings(settings)) return frame
        require(settings.scene.isIdentity) { "Place GlSticker3DRenderer inside BeautySceneRenderer" }
        val sticker=settings.sticker3D
        require(sticker==null || !settings.adjustments.hasReshape) { "3D stickers do not support 2D reshape; disable reshape explicitly" }
        if(sticker!=null && settingsSource !== settings) {
            delegateSettings=settings.copy(sticker3D=null)
            settingsSource=settings
        }
        val output=delegate.render(frame,if(sticker==null) settings else checkNotNull(delegateSettings),face)
        if(sticker==null) {
            settingsSource=null;delegateSettings=null
            poseCache.clear(); return output
        }
        require(output is TextureFrame) { "3D stickers require texture input/output" }
        val pose=poseCache.get(face,frame.info,sticker) ?: return output
        // 独立复制避免原地采样；即使基础效果关闭，也将OES与transform统一为identity 2D。
        val normalized=(pass ?: SceneGlPass().also { pass=it }).render(output,identityScene,null,fullCrop)
        val state=State()
        try {
            initialize()
            GL.glBindFramebuffer(GL.GL_DRAW_FRAMEBUFFER,fbo)
            GL.glFramebufferTexture2D(GL.GL_DRAW_FRAMEBUFFER,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,normalized.textureId,0)
            if(width!=frame.info.width || height!=frame.info.height) {
                GL.glBindRenderbuffer(GL.GL_RENDERBUFFER,depth)
                GL.glRenderbufferStorage(GL.GL_RENDERBUFFER,GL.GL_DEPTH_COMPONENT24,frame.info.width,frame.info.height)
                width=frame.info.width;height=frame.info.height
            }
            GL.glFramebufferRenderbuffer(GL.GL_DRAW_FRAMEBUFFER,GL.GL_DEPTH_ATTACHMENT,GL.GL_RENDERBUFFER,depth)
            check(GL.glCheckFramebufferStatus(GL.GL_DRAW_FRAMEBUFFER)==GL.GL_FRAMEBUFFER_COMPLETE) { "Incomplete 3D framebuffer" }
            GL.glViewport(0,0,width,height)
            State.flags.forEach { GL.glDisable(it) }
            GL.glEnable(GL.GL_DEPTH_TEST);GL.glDepthFunc(GL.GL_LEQUAL);GL.glDepthMask(true)
            GL.glDepthRangef(0f,1f);GL.glClearDepthf(1f);GL.glClear(GL.GL_DEPTH_BUFFER_BIT)
            GL.glUseProgram(program)
            if(sticker.faceOcclusion) {
                // 同一检测结果跨视频帧复用时，不重复生成/上传遮挡顶点。
                if(uploadedOccluderPose !== pose) {
                    uploadOccluder(checkNotNull(face?.geometry3D),pose,frame.info)
                    uploadedOccluderPose=pose
                }
                GL.glColorMask(false,false,false,false)
                GL.glUniformMatrix4fv(GL.glGetUniformLocation(program,"matrix"),1,false,IDENTITY,0)
                GL.glBindVertexArray(occluderVao)
                GL.glDrawArrays(GL.GL_TRIANGLES,0,Sticker3DGeometry.oval.size*3)
                pose.headMatrix?.let { matrix ->
                    if(!headUploaded){uploadMesh(BuiltInStickers3D.headOccluder,headVao,headVbo,headEbo);headUploaded=true}
                    GL.glUniformMatrix4fv(GL.glGetUniformLocation(program,"matrix"),1,false,matrix,0)
                    GL.glBindVertexArray(headVao)
                    GL.glDrawElements(GL.GL_TRIANGLES,BuiltInStickers3D.headOccluder.triangleCount*3,GL.GL_UNSIGNED_SHORT,0)
                }
            }
            GL.glColorMask(true,true,true,false) // 保留输入视频alpha，深度遮挡不修改颜色。
            upload(sticker.mesh)
            GL.glUniformMatrix4fv(GL.glGetUniformLocation(program,"matrix"),1,false,pose.matrix,0)
            GL.glUniform3f(GL.glGetUniformLocation(program,"metric"),width*.5f,height*.5f,pose.span*16f)
            GL.glBindVertexArray(vao)
            GL.glDrawElements(GL.GL_TRIANGLES,sticker.mesh.triangleCount*3,GL.GL_UNSIGNED_SHORT,0)
            return normalized
        } finally { state.restore() }
    }

    private fun upload(mesh: StickerMesh3D) {
        if(cached===mesh) return
        uploadMesh(mesh,vao,vbo,ebo);cached=mesh
    }
    private fun uploadMesh(mesh:StickerMesh3D,targetVao:Int,targetVbo:Int,targetEbo:Int) {
        val xyz=mesh.copyPositions();val rgba=mesh.copyColors();val indices=mesh.copyIndices()
        // 仅换素材时生成面积加权平滑法线；不增加逐帧CPU网格计算。
        val normals=FloatArray(xyz.size)
        for(t in indices.indices step 3){
            val a=indices[t]*3;val b=indices[t+1]*3;val c=indices[t+2]*3
            val ux=xyz[b]-xyz[a];val uy=xyz[b+1]-xyz[a+1];val uz=xyz[b+2]-xyz[a+2]
            val vx=xyz[c]-xyz[a];val vy=xyz[c+1]-xyz[a+1];val vz=xyz[c+2]-xyz[a+2]
            val nx=uy*vz-uz*vy;val ny=uz*vx-ux*vz;val nz=ux*vy-uy*vx
            for(i in intArrayOf(a,b,c)){normals[i]+=nx;normals[i+1]+=ny;normals[i+2]+=nz}
        }
        val vertices=ByteBuffer.allocateDirect(mesh.vertexCount*10*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for(i in 0 until mesh.vertexCount) {vertices.put(xyz,i*3,3);vertices.put(rgba,i*4,4);vertices.put(normals,i*3,3)};vertices.flip()
        val elements=ByteBuffer.allocateDirect(indices.size*2).order(ByteOrder.nativeOrder()).asShortBuffer()
        indices.forEach { elements.put(it.toShort()) };elements.flip()
        GL.glBindVertexArray(targetVao)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER,targetVbo);GL.glBufferData(GL.GL_ARRAY_BUFFER,vertices.remaining()*4,vertices,GL.GL_STATIC_DRAW)
        GL.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER,targetEbo);GL.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER,indices.size*2,elements,GL.GL_STATIC_DRAW)
        attributes()
    }
    private fun uploadOccluder(geometry: FaceGeometry3D,pose: Sticker3DGeometry.Pose,info: FrameInfo) {
        occluder.clear();val points=geometry.points;val oval=Sticker3DGeometry.oval
        fun vertex(p:FacePoint3D) {
            occluder.put(p.x*2-1).put(p.y*2-1).put((p.z*info.width-pose.centerZ)/(pose.span*16f))
                .put(0f).put(0f).put(0f).put(1f).put(0f).put(0f).put(-1f)
        }
        for(i in oval.indices) {vertex(points[1]);vertex(points[oval[i]]);vertex(points[oval[(i+1)%oval.size]])}
        occluder.flip()
        GL.glBindVertexArray(occluderVao);GL.glBindBuffer(GL.GL_ARRAY_BUFFER,occluderVbo)
        GL.glBufferData(GL.GL_ARRAY_BUFFER,occluder.remaining()*4,occluder,GL.GL_DYNAMIC_DRAW);attributes()
    }
    private fun attributes() {
        GL.glEnableVertexAttribArray(0);GL.glVertexAttribPointer(0,3,GL.GL_FLOAT,false,40,0)
        GL.glEnableVertexAttribArray(1);GL.glVertexAttribPointer(1,4,GL.GL_FLOAT,false,40,12)
        GL.glEnableVertexAttribArray(2);GL.glVertexAttribPointer(2,3,GL.GL_FLOAT,false,40,28)
    }
    private fun initialize() {
        if(program!=0) return
        program=createProgram()
        val ids=IntArray(5)
        GL.glGenFramebuffers(1,ids,0);fbo=ids[0]
        GL.glGenRenderbuffers(1,ids,0);depth=ids[0]
        GL.glGenVertexArrays(3,ids,0);vao=ids[0];occluderVao=ids[1];headVao=ids[2]
        GL.glGenBuffers(5,ids,0);vbo=ids[0];ebo=ids[1];occluderVbo=ids[2];headVbo=ids[3];headEbo=ids[4]
    }
    private fun checkOwner() {
        check(Thread.currentThread()===owner && EGL14.eglGetCurrentContext()==context) { "Use the creating EGL context/thread" }
    }
    override fun close() {
        checkOwner();if(closed)return;closed=true
        settingsSource=null;delegateSettings=null
        try {
            pass?.close();pass=null
            GL.glDeleteBuffers(5,intArrayOf(vbo,ebo,occluderVbo,headVbo,headEbo),0)
            GL.glDeleteVertexArrays(3,intArrayOf(vao,occluderVao,headVao),0)
            GL.glDeleteFramebuffers(1,intArrayOf(fbo),0);GL.glDeleteRenderbuffers(1,intArrayOf(depth),0)
            if(program!=0)GL.glDeleteProgram(program)
            cached=null
        } finally {delegate.close()}
    }
    private fun createProgram():Int {
        fun shader(type:Int,source:String):Int {
            val id=GL.glCreateShader(type);GL.glShaderSource(id,source);GL.glCompileShader(id)
            val status=IntArray(1);GL.glGetShaderiv(id,GL.GL_COMPILE_STATUS,status,0)
            if(status[0]==0) {val log=GL.glGetShaderInfoLog(id);GL.glDeleteShader(id);error("3D shader: $log")}
            return id
        }
        val vertex=shader(GL.GL_VERTEX_SHADER,VERTEX);var fragment=0;var result=0
        try {
            fragment=shader(GL.GL_FRAGMENT_SHADER,FRAGMENT);result=GL.glCreateProgram()
            GL.glAttachShader(result,vertex);GL.glAttachShader(result,fragment);GL.glLinkProgram(result)
            val status=IntArray(1);GL.glGetProgramiv(result,GL.GL_LINK_STATUS,status,0)
            check(status[0]!=0){"3D program: ${GL.glGetProgramInfoLog(result)}"};return result
        }catch(e:Exception){if(result!=0)GL.glDeleteProgram(result);throw e}
        finally{GL.glDeleteShader(vertex);if(fragment!=0)GL.glDeleteShader(fragment)}
    }
    /** 不改变宿主VAO/EBO、FBO、depth、viewport、mask或功能开关。 */
    private class State {
        fun int(name:Int)=IntArray(1).also{GL.glGetIntegerv(name,it,0)}[0]
        val program=int(GL.GL_CURRENT_PROGRAM);val fbo=int(GL.GL_DRAW_FRAMEBUFFER_BINDING)
        val vao=int(GL.GL_VERTEX_ARRAY_BINDING);val vbo=int(GL.GL_ARRAY_BUFFER_BINDING)
        val renderbuffer=int(GL.GL_RENDERBUFFER_BINDING);val depthFunc=int(GL.GL_DEPTH_FUNC)
        val viewport=IntArray(4).also{GL.glGetIntegerv(GL.GL_VIEWPORT,it,0)}
        val mask=BooleanArray(4).also{GL.glGetBooleanv(GL.GL_COLOR_WRITEMASK,it,0)}
        val depthMask=BooleanArray(1).also{GL.glGetBooleanv(GL.GL_DEPTH_WRITEMASK,it,0)}[0]
        val range=FloatArray(2).also{GL.glGetFloatv(GL.GL_DEPTH_RANGE,it,0)}
        val clearDepth=FloatArray(1).also{GL.glGetFloatv(GL.GL_DEPTH_CLEAR_VALUE,it,0)}[0]
        val enabled=flags.map{GL.glIsEnabled(it)}
        fun restore() {
            GL.glUseProgram(program);GL.glBindFramebuffer(GL.GL_DRAW_FRAMEBUFFER,fbo)
            GL.glBindVertexArray(vao);GL.glBindBuffer(GL.GL_ARRAY_BUFFER,vbo)
            GL.glBindRenderbuffer(GL.GL_RENDERBUFFER,renderbuffer)
            GL.glViewport(viewport[0],viewport[1],viewport[2],viewport[3])
            GL.glColorMask(mask[0],mask[1],mask[2],mask[3]);GL.glDepthMask(depthMask)
            GL.glDepthFunc(depthFunc);GL.glDepthRangef(range[0],range[1]);GL.glClearDepthf(clearDepth)
            flags.forEachIndexed{i,flag->if(enabled[i])GL.glEnable(flag)else GL.glDisable(flag)}
        }
        companion object {val flags=intArrayOf(GL.GL_BLEND,GL.GL_DEPTH_TEST,GL.GL_STENCIL_TEST,GL.GL_SCISSOR_TEST,
            GL.GL_CULL_FACE,GL.GL_RASTERIZER_DISCARD,GL.GL_DITHER,GL.GL_POLYGON_OFFSET_FILL,GL.GL_SAMPLE_ALPHA_TO_COVERAGE,GL.GL_SAMPLE_COVERAGE)}
    }
    companion object {
        private val IDENTITY=floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
        private val VERTEX="""#version 300 es
            layout(location=0) in vec3 position;layout(location=1) in vec4 tint;layout(location=2) in vec3 normal;
            uniform mat4 matrix;uniform vec3 metric;out vec4 paint;out vec3 surface;out vec3 smoothNormal;
            void main(){gl_Position=matrix*vec4(position,1.0);surface=gl_Position.xyz*metric;paint=tint;
                vec3 n=(matrix*vec4(normal,0.0)).xyz*metric;
                smoothNormal=length(n)>0.000001?normalize(n):vec3(0.0,0.0,-1.0);}
        """.trimIndent()
        private val FRAGMENT="""#version 300 es
            precision highp float;in vec4 paint;in vec3 surface;in vec3 smoothNormal;out vec4 color;
            void main(){vec3 n=normalize(smoothNormal);if(n.z>0.0)n=-n;
                vec3 key=normalize(vec3(-0.65,-0.8,-1.0));vec3 view=vec3(0.0,0.0,-1.0);
                float diffuse=max(dot(n,key),0.0);
                float fill=max(dot(n,normalize(vec3(0.8,0.2,-0.5))),0.0);
                float spec=pow(max(dot(n,normalize(key+view)),0.0),48.0);
                float edge=pow(1.0-max(dot(n,view),0.0),3.0);
                vec3 lit=paint.rgb*(0.25+0.65*diffuse+0.18*fill)+vec3(1.0,0.94,0.82)*spec*0.42;
                lit+=vec3(0.25,0.40,0.58)*edge*0.18;
                color=vec4(clamp(lit,0.0,1.0),1.0);}
        """.trimIndent()
    }
}
