package com.beautysdk

import kotlin.math.*

/** 有界、不透明顶点色三角网格。xyz/rgba 分别每顶点3/4个值，indices每3个值构成三角形。
 * 局部原点为双眼外角中点，x向模型右、y向头顶、z向观众，单位为两眼外角三维距离。
 * 只在资源准备线程创建；复制调用方数组，不持有可变外部缓冲。不支持纹理/骨骼/GLB。
 */
class StickerMesh3D(positions: FloatArray, colors: FloatArray, indices: IntArray) {
    private var headVolume=false
    /** 帽类网格使用眼距坐标及额头贴合，并额外写入估算头部深度；不是扫描头模。 */
    val usesHeadVolume:Boolean get()=headVolume
    constructor(positions:FloatArray,colors:FloatArray,indices:IntArray,headOcclusion:Boolean):this(positions,colors,indices){headVolume=headOcclusion}
    private val xyz = positions.copyOf()
    private val rgba = colors.copyOf()
    private val triangles = indices.copyOf()
    val vertexCount get() = xyz.size / 3
    val triangleCount get() = triangles.size / 3
    init {
        require(xyz.size % 3 == 0 && vertexCount in 3..32768)
        require(rgba.size == vertexCount * 4 && rgba.all { it.isFinite() && it in 0f..1f })
        require((3 until rgba.size step 4).all { rgba[it] == 1f }) { "Only opaque vertex colors are supported" }
        require(xyz.all { it.isFinite() && abs(it) <= 4f }) { "Mesh must fit the +/-4 eye-span budget" }
        require(triangles.isNotEmpty() && triangles.size % 3 == 0 && triangleCount <= 32768)
        require(triangles.all { it in 0 until vertexCount })
    }
    fun copyPositions() = xyz.copyOf()
    fun copyColors() = rgba.copyOf()
    fun copyIndices() = triangles.copyOf()
}

/** 一次启用一个刚性饰品；脸部遮挡为关键点扇形近似，不能遮挡头发、手或后脑勺。 */
data class Sticker3D @JvmOverloads constructor(
    val mesh: StickerMesh3D,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val offsetZ: Float = 0f,
    val faceOcclusion: Boolean = true,
) {
    init {
        require(scale.isFinite() && scale in .1f..2f)
        require(listOf(offsetX,offsetY,offsetZ).all { it.isFinite() && it in -2f..2f })
    }
}

/** 按真实三维关键点建立弱透视刚性基底；不将相对z当成公制深度。 */
internal class Sticker3DPoseCache(private val maxAgeNs: Long) {
    private var geometry: FaceGeometry3D? = null
    private var frame: FrameInfo? = null
    private var sticker: Sticker3D? = null
    private var pose: Sticker3DGeometry.Pose? = null

    fun get(face: FaceObservation?, info: FrameInfo, value: Sticker3D): Sticker3DGeometry.Pose? {
        val current = face?.geometry3D
        // 每帧先验真实采样年龄；缓存不延长姿态寿命，也不跨无脸/未来结果复活。
        if (current == null || info.timestampNs-current.timestampNs !in 0..maxAgeNs) {
            clear()
            return null
        }
        if (current === geometry && frame?.sameGeometry(info) == true && sticker == value) return pose
        geometry = current
        frame = info
        sticker = value
        return Sticker3DGeometry.pose(face,info,value,maxAgeNs).also { pose = it }
    }

    fun clear() { geometry=null; frame=null; sticker=null; pose=null }
}

internal object Sticker3DGeometry {
    val oval = intArrayOf(10,338,297,332,284,251,389,356,454,323,361,288,397,365,379,378,400,377,
        152,148,176,149,150,136,172,58,132,93,234,127,162,21,54,103,67,109)
    data class Pose(val matrix: FloatArray, val centerZ: Float, val span: Float,val headMatrix:FloatArray?=null)
    fun pose(face: FaceObservation?, info: FrameInfo, sticker: Sticker3D, maxAgeNs: Long): Pose? {
        val geometry = face?.geometry3D ?: return null
        if (info.timestampNs-geometry.timestampNs !in 0..maxAgeNs) return null
        val p = geometry.points
        fun pixels(i: Int) = p[i].let { floatArrayOf(it.x*info.width,it.y*info.height,it.z*info.width) }
        fun sub(a:FloatArray,b:FloatArray)=FloatArray(3){a[it]-b[it]}
        fun dot(a:FloatArray,b:FloatArray)=(0..2).sumOf { (a[it]*b[it]).toDouble() }.toFloat()
        fun norm(a:FloatArray)=sqrt(dot(a,a))
        val left=pixels(33); val right=pixels(263)
        val x=sub(right,left); val span=norm(x)
        if (!span.isFinite() || span < 2f) return null
        for(i in 0..2) x[i]/=span
        val y=sub(pixels(10),pixels(152)); val projection=dot(y,x)
        for(i in 0..2) y[i]-=projection*x[i]
        val yn=norm(y); if(!yn.isFinite() || yn < 2f) return null
        for(i in 0..2) y[i]/=yn
        val sign=if(info.mirrored) -1f else 1f
        val z=floatArrayOf((x[1]*y[2]-x[2]*y[1])*sign,
            (x[2]*y[0]-x[0]*y[2])*sign,(x[0]*y[1]-x[1]*y[0])*sign)
        val center=FloatArray(3){(left[it]+right[it])*.5f}
        // 不翻转画面：与现有纹理渲染一致，raw y=0 对应 FBO uv.y=0。
        val m=FloatArray(16); val axes=arrayOf(x,y,z)
        val fitted=sticker.mesh.usesHeadVolume
        val fitWidth=if(fitted)(max(abs(dot(sub(pixels(234),center),x)),abs(dot(sub(pixels(454),center),x)))/span/.8f).coerceIn(.75f,1.4f)else 1f
        val forehead=if(fitted)(dot(sub(pixels(10),center),y)/span).coerceIn(.25f,1.35f)-.72f else 0f
        for(c in 0..2) {
            val fit=if(c==1)1f else fitWidth
            m[c*4]=axes[c][0]*span*sticker.scale*fit*2/info.width
            m[c*4+1]=axes[c][1]*span*sticker.scale*fit*2/info.height
            m[c*4+2]=axes[c][2]*sticker.scale*fit/16f
        }
        val headMatrix=if(fitted)FloatArray(16).apply{
            for(c in 0..2){val fit=if(c==1)1f else fitWidth
                this[c*4]=axes[c][0]*span*fit*2/info.width
                this[c*4+1]=axes[c][1]*span*fit*2/info.height
                this[c*4+2]=axes[c][2]*fit/16f
            }
            this[12]=(center[0]+y[0]*span*forehead)*2/info.width-1
            this[13]=(center[1]+y[1]*span*forehead)*2/info.height-1
            this[14]=y[2]*forehead/16f;this[15]=1f
        }else null
        val offsets=floatArrayOf(sticker.offsetX,sticker.offsetY+forehead,sticker.offsetZ)
        val origin=FloatArray(3){ r -> center[r]+span*(0..2).sumOf { (axes[it][r]*offsets[it]).toDouble() }.toFloat() }
        m[12]=origin[0]*2/info.width-1; m[13]=origin[1]*2/info.height-1
        m[14]=(origin[2]-center[2])/(span*16f); m[15]=1f
        return if(m.all { it.isFinite() }) Pose(m,center[2],span,headMatrix) else null
    }
}

/** SDK自有的顶点色饰品，不需要下载额外模型；惰性创建且不含第三方素材。 */
object BuiltInStickers3D {
    @JvmStatic fun glasses() = glassesValue
    @JvmStatic fun topHat() = hatValue
    @JvmStatic fun crown() = crownValue
    @JvmStatic fun pearlCatEars() = catValue
    @JvmStatic fun headphones() = headphonesValue
    @JvmStatic fun baseballCap() = capValue
    internal val headOccluder by lazy {Builder().apply{sphere(0f,.30f,-.35f,.76f,1.03f,.68f,0xffffffff.toInt())}.build()}
    private val capValue by lazy {
        val b=Builder();val navy=0xff264a62.toInt();val underside=0xff182d3e.toInt();val cream=0xffeeddb6.toInt()
        // 帽冠为有厚度的开放壳，帽口不加实心底盘，保留真正可见的内部。
        b.dome(.85f,.92f,.79f,.58f,-.35f,navy)
        b.dome(.80f,.87f,.74f,.58f,-.35f,underside)
        for(i in 0 until 64){val a=i*2*PI/64;val next=(i+1)*2*PI/64
            val p=b.vertex(.85f*cos(a).toFloat(),.58f,-.35f+.79f*sin(a).toFloat(),navy)
            val q=b.vertex(.85f*cos(next).toFloat(),.58f,-.35f+.79f*sin(next).toFloat(),navy)
            val r=b.vertex(.80f*cos(a).toFloat(),.58f,-.35f+.74f*sin(a).toFloat(),underside)
            val t=b.vertex(.80f*cos(next).toFloat(),.58f,-.35f+.74f*sin(next).toFloat(),underside)
            b.tri(p,q,t);b.tri(p,t,r)
        }
        b.visor(navy,underside)
        b.sphere(0f,1.515f,-.35f,.075f,.035f,.075f,cream)
        // 前徽章、侧标与后扣采用不同造型，侧转时呈现不同的细节。
        b.sphere(0f,.91f,.405f,.13f,.13f,.025f,cream)
        b.box(-.023f,.83f,.427f,.023f,.99f,.448f,navy)
        b.box(.79f,.68f,-.38f,.87f,.78f,-.12f,cream)
        b.box(-.24f,.59f,-1.16f,.24f,.68f,-1.12f,underside)
        b.box(.10f,.58f,-1.19f,.23f,.70f,-1.15f,cream)
        Sticker3D(b.build(head=true))
    }
    private val crownValue by lazy {
        val b=Builder();val gold=0xffe8bd73.toInt();val pearl=0xfffff1df.toInt()
        b.cylinder(.73f,.09f,.51f,.38f,gold)
        for(i in 0 until 10){
            val a=i*2*PI/10;val x=.50f*cos(a).toFloat();val z=.37f*sin(a).toFloat()
            val top=floatArrayOf(x*.92f,if(i%2==0)1.08f else .98f,z*.92f)
            for(delta in listOf(-.22,.22))b.tube(floatArrayOf(.50f*cos(a+delta).toFloat(),.76f,.37f*sin(a+delta).toFloat()),top,.018f,gold)
            b.sphere(top[0],top[1],top[2],.039f,.039f,.039f,pearl)
        }
        b.sphere(0f,.80f,.395f,.065f,.08f,.025f,0xff75c8bd.toInt())
        Sticker3D(b.build(),offsetY=.17f)
    }
    private val catValue by lazy {
        val b=Builder();val rose=0xffd8a493.toInt();val pearl=0xffffebdf.toInt()
        for(s in listOf(-1f,1f)){
            val points=arrayOf(floatArrayOf(s*.18f,.67f,0f),floatArrayOf(s*.51f,1.15f,-.04f),floatArrayOf(s*.64f,.66f,-.08f))
            for(i in 0..2)b.tube(points[i],points[(i+1)%3],.032f,rose)
            for(i in 0..7){val t=i/7f;b.sphere(s*(.18f+.33f*t),.67f+.48f*t,-.04f*t,.035f,.035f,.035f,pearl)}
        }
        for(i in 0..16){val a=i*PI/16;b.sphere(.61f*cos(a).toFloat(),.31f+.37f*sin(a).toFloat(),-.22f,.031f,.031f,.031f,pearl)}
        Sticker3D(b.build(),offsetY=.15f)
    }
    private val headphonesValue by lazy {
        val b=Builder();val mint=0xff83cfc4.toInt();val dark=0xff304b57.toInt();val gold=0xffe5c57b.toInt()
        for(i in 0 until 32){val a=i*PI/32;val next=(i+1)*PI/32
            b.tube(floatArrayOf(.72f*cos(a).toFloat(),.12f+1.08f*sin(a).toFloat(),.05f),floatArrayOf(.72f*cos(next).toFloat(),.12f+1.08f*sin(next).toFloat(),.05f),.052f,mint)
        }
        for(s in listOf(-1f,1f)){
            b.sphere(s*.70f,.02f,-.10f,.12f,.24f,.19f,dark)
            b.sphere(s*.77f,.02f,-.09f,.10f,.20f,.16f,mint)
            b.sphere(s*.82f,.02f,.015f,.026f,.072f,.065f,gold)
        }
        Sticker3D(b.build())
    }
    private val glassesValue by lazy {
        val b=Builder()
        // 镂空双层圆框保留眼睛；前后包边与弯曲镜腿在侧脸时显露实际深度。
        for(cx in listOf(-.29f,.29f)) {
            b.ring(cx,0f,.17f,.235f,.185f,.043f,0xff243b48.toInt())
            b.ring(cx,0f,.208f,.237f,.187f,.014f,0xffe5c57b.toInt())
        }
        b.tube(floatArrayOf(-.06f,.035f,.19f),floatArrayOf(0f,.065f,.23f),.018f,0xffe5c57b.toInt())
        b.tube(floatArrayOf(0f,.065f,.23f),floatArrayOf(.06f,.035f,.19f),.018f,0xffe5c57b.toInt())
        for(s in listOf(-1f,1f)) {
            b.sphere(s*.55f,.015f,.17f,.045f,.038f,.045f,0xffe5c57b.toInt())
            b.tube(floatArrayOf(s*.55f,.015f,.17f),floatArrayOf(s*.61f,.015f,-.33f),.025f,0xff243b48.toInt())
            b.tube(floatArrayOf(s*.61f,.015f,-.33f),floatArrayOf(s*.54f,-.12f,-.65f),.028f,0xff243b48.toInt())
        }
        Sticker3D(b.build())
    }
    private val hatValue by lazy {
        val b=Builder()
        // 连续旋转曲面构成卷边帽檐和收腰帽冠，侧转能看到前后轮廓。
        b.profile(floatArrayOf(.58f,.62f,.66f,.70f,.73f,.75f),floatArrayOf(.48f,.70f,.75f,.73f,.55f,.46f),.79f,0xff253d59.toInt())
        b.profile(floatArrayOf(.69f,.74f,.90f,1.12f,1.23f,1.26f),floatArrayOf(.46f,.45f,.42f,.43f,.48f,.46f),.86f,0xff365779.toInt())
        b.cylinder(.77f,.105f,.457f,.396f,0xffa84767.toInt())
        b.box(.09f,.70f,.39f,.25f,.72f,.425f,0xffe5c57b.toInt())
        b.box(.09f,.82f,.39f,.25f,.84f,.425f,0xffe5c57b.toInt())
        b.box(.09f,.72f,.39f,.11f,.82f,.425f,0xffe5c57b.toInt())
        b.box(.23f,.72f,.39f,.25f,.82f,.425f,0xffe5c57b.toInt())
        b.sphere(.18f,.96f,.36f,.055f,.055f,.022f,0xffe5c57b.toInt())
        Sticker3D(b.build(),scale=.9f,offsetY=.25f)
    }
    private class Builder {
        val xyz=ArrayList<Float>(); val rgba=ArrayList<Float>(); val indices=ArrayList<Int>()
        fun vertex(x:Float,y:Float,z:Float,color:Int):Int {
            val index=xyz.size/3; xyz.add(x);xyz.add(y);xyz.add(z)
            rgba.add((color ushr 16 and 255)/255f);rgba.add((color ushr 8 and 255)/255f);rgba.add((color and 255)/255f);rgba.add(1f)
            return index
        }
        fun tri(a:Int,b:Int,c:Int){indices.add(a);indices.add(b);indices.add(c)}
        fun dome(rx:Float,ry:Float,rz:Float,base:Float,cz:Float,color:Int){
            val n=xyz.size/3;val rows=20;val columns=64
            for(j in 0..rows)for(i in 0 until columns){val p=j*PI/(2*rows);val a=i*2*PI/columns
                vertex(rx*(sin(p)*cos(a)).toFloat(),base+ry*cos(p).toFloat(),cz+rz*(sin(p)*sin(a)).toFloat(),color)
            }
            for(j in 0 until rows)for(i in 0 until columns){val a=n+j*columns+i;val b=n+j*columns+(i+1)%columns
                tri(a,b,b+columns);tri(a,b+columns,a+columns)
            }
        }
        fun visor(top:Int,bottom:Int){
            val n=xyz.size/3;val columns=40;val rows=10
            for(layer in 0..1)for(j in 0..rows)for(i in 0..columns){
                val x=-.73f+1.46f*i/columns;val t=j.toFloat()/rows
                val curve=sqrt((1-x*x/(.76f*.76f)).coerceAtLeast(0f))
                val z=.05f+.33f*curve+t*(.40f+.40f*curve)
                vertex(x,.59f-.13f*t*t-.08f*(x/.73f).pow(2)-(if(layer==0)0f else .045f),z,if(layer==0)top else bottom)
            }
            val stride=columns+1;val plane=(rows+1)*stride
            for(layer in 0..1)for(j in 0 until rows)for(i in 0 until columns){val a=n+layer*plane+j*stride+i
                tri(a,a+1,a+stride+1);tri(a,a+stride+1,a+stride)
            }
            for(i in 0 until columns){val a=n+rows*stride+i;tri(a,a+plane,a+plane+1);tri(a,a+plane+1,a+1)}
            for(j in 0 until rows)for(i in listOf(0,columns)){val a=n+j*stride+i;tri(a,a+stride,a+stride+plane);tri(a,a+stride+plane,a+plane)}
        }
        fun box(x0:Float,y0:Float,z0:Float,x1:Float,y1:Float,z1:Float,c:Int) {
            val n=xyz.size/3
            for(z in listOf(z0,z1)) for(y in listOf(y0,y1)) for(x in listOf(x0,x1))vertex(x,y,z,c)
            val faces=intArrayOf(0,2,3,0,3,1,4,5,7,4,7,6,0,1,5,0,5,4,2,6,7,2,7,3,0,4,6,0,6,2,1,3,7,1,7,5)
            faces.forEach{indices.add(n+it)}
        }
        fun ring(cx:Float,cy:Float,cz:Float,rx:Float,ry:Float,t:Float,c:Int) {
            val n=xyz.size/3; val around=64;val tube=12
            for(i in 0 until around) for(j in 0 until tube) {
                val a=i*2*PI/around;val b=j*2*PI/tube
                vertex(cx+(rx+t*cos(b)).toFloat()*cos(a).toFloat(),
                    cy+(ry+t*cos(b)).toFloat()*sin(a).toFloat(),cz+t*sin(b).toFloat(),c)
            }
            for(i in 0 until around)for(j in 0 until tube) {
                val a=n+i*tube+j;val b=n+((i+1)%around)*tube+j
                val d=n+i*tube+(j+1)%tube;val e=n+((i+1)%around)*tube+(j+1)%tube
                tri(a,b,e);tri(a,e,d)
            }
        }
        fun sphere(cx:Float,cy:Float,cz:Float,rx:Float,ry:Float,rz:Float,c:Int) {
            val n=xyz.size/3;val rows=12;val columns=24
            for(j in 0..rows)for(i in 0 until columns){
                val a=i*2*PI/columns;val v=j*PI/rows
                vertex(cx+rx*(sin(v)*cos(a)).toFloat(),cy+ry*cos(v).toFloat(),cz+rz*(sin(v)*sin(a)).toFloat(),c)
            }
            for(j in 0 until rows)for(i in 0 until columns){
                val a=n+j*columns+i;val b=n+j*columns+(i+1)%columns
                tri(a,b,b+columns);tri(a,b+columns,a+columns)
            }
        }
        fun tube(start:FloatArray,end:FloatArray,r:Float,c:Int){
            val axis=FloatArray(3){end[it]-start[it]};val len=sqrt(axis.sumOf{(it*it).toDouble()}).toFloat()
            for(i in 0..2)axis[i]/=len
            val reference=if(abs(axis[1])<.9f)floatArrayOf(0f,1f,0f)else floatArrayOf(1f,0f,0f)
            fun cross(a:FloatArray,b:FloatArray)=floatArrayOf(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
            val u=cross(axis,reference);val ul=sqrt(u.sumOf{(it*it).toDouble()}).toFloat();for(i in 0..2)u[i]/=ul
            val v=cross(axis,u);val n=xyz.size/3;val segments=16
            for(p in listOf(start,end))for(i in 0 until segments){val a=i*2*PI/segments
                val point=FloatArray(3){p[it]+r*(u[it]*cos(a)+v[it]*sin(a)).toFloat()};vertex(point[0],point[1],point[2],c)
            }
            val bottom=vertex(start[0],start[1],start[2],c);val top=vertex(end[0],end[1],end[2],c)
            for(i in 0 until segments){val a=n+i;val b=n+(i+1)%segments
                tri(a,b,b+segments);tri(a,b+segments,a+segments);tri(bottom,b,a);tri(top,a+segments,b+segments)
            }
        }
        fun profile(ys:FloatArray,radii:FloatArray,zRatio:Float,c:Int){
            val n=xyz.size/3;val segments=64
            for(j in ys.indices)for(i in 0 until segments){val a=i*2*PI/segments
                vertex(radii[j]*cos(a).toFloat(),ys[j],radii[j]*zRatio*sin(a).toFloat(),c)
            }
            for(j in 0 until ys.lastIndex)for(i in 0 until segments){val a=n+j*segments+i;val b=n+j*segments+(i+1)%segments
                tri(a,b,b+segments);tri(a,b+segments,a+segments)
            }
            val bottom=vertex(0f,ys.first(),0f,c);val top=vertex(0f,ys.last(),0f,c)
            for(i in 0 until segments){tri(bottom,n+(i+1)%segments,n+i)
                tri(top,n+ys.lastIndex*segments+i,n+ys.lastIndex*segments+(i+1)%segments)}
        }
        fun cylinder(y:Float,h:Float,rx:Float,rz:Float,c:Int) {
            val n=xyz.size/3;val segments=32
            for(level in 0..1)for(i in 0 until segments) {
                val a=i*2*PI/segments
                vertex(rx*cos(a).toFloat(),y+(level-.5f)*h,rz*sin(a).toFloat(),c)
            }
            val bottom=vertex(0f,y-h*.5f,0f,c);val top=vertex(0f,y+h*.5f,0f,c)
            for(i in 0 until segments) {
                val a=n+i;val b=n+(i+1)%segments
                tri(a,b,b+segments);tri(a,b+segments,a+segments)
                tri(bottom,b,a);tri(top,a+segments,b+segments)
            }
        }
        fun build(head:Boolean=false)=StickerMesh3D(xyz.toFloatArray(),rgba.toFloatArray(),indices.toIntArray(),head)
    }
}
