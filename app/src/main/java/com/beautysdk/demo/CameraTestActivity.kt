package com.beautysdk.demo

import androidx.core.view.doOnLayout
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.GridLayout
import android.graphics.BitmapFactory
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import android.widget.AdapterView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.beautysdk.*
import com.beautysdk.camera.CameraLens
import com.beautysdk.camera.CameraStatus
import com.beautysdk.demo.databinding.ActivityCameraTestBinding
import com.beautysdk.demo.databinding.ItemCameraStickerBinding
import com.beautysdk.demo.feature.app.*
import com.wkq.base.activity.BaseActivity

class CameraTestActivity : BaseActivity<ActivityCameraTestBinding>() {
    private lateinit var controller: GpuBeautyPreview
    private lateinit var resourceCenter: ResourceCenterController
    private var applyingResource=false
    private lateinit var effectWorkshop: EffectWorkshopController
    private lateinit var workshop: StickerWorkshopController
    private var stickerPreferencesReady = false
    private val stickerPreferences by lazy { getSharedPreferences("camera_stickers",MODE_PRIVATE) }
    // 演示背景仅初始化一次，非视频帧转换；SDK也接受宿主解码后的任意图片。
    private val sceneBackground by lazy {
        val w=256;val h=256
        StickerImage(w,h,IntArray(w*h) { i ->
            val x=i%w;val y=i/w
            val ridge=155+(30*kotlin.math.sin(x/36.0)).toInt()
            if(y>ridge) 0xff293b50.toInt() else android.graphics.Color.rgb(55+y/3,75+y/4,130+y/4)
        })
    }
    private var extended=BeautyAdjustments()
    private var updatingExtended=false
    private var requested = true
    private var lens = CameraLens.FRONT
    private var trackingText = ""
    // 仅保留最近事件；页面限频统计不是逐帧事件总线，不把超时/错误当作离开。
    private var facePresent = false
    private var faceCallbackCount = 0
    private var lastFaceCallback = ""
    private var lastGestureCallback = ""
    private var lastGestureEventId = 0L
    private var settingsDialog: BottomSheetDialog? = null
    private var moreMenu: androidx.appcompat.widget.PopupMenu? = null
    private var callbackDialog: BottomSheetDialog? = null
    private val callbackHistory=java.util.ArrayDeque<String>()
    private var beautyParameter=0
    private var resetDialog: androidx.appcompat.app.AlertDialog? = null
    private var restoredGestureEnabled = true
    private var faceDetectionEnabled = true
    private var effectIsMain = true
    private var effectCategory=0
    private var adjusting=true
    private var parameterCategory=-1
    private var restorePanelSection:Int?=null
    private val parameterViews=mutableMapOf<Int,android.widget.TextView>()
    private val categoryViews=mutableListOf<android.widget.TextView>()
    private val stickerCards = mutableListOf<Pair<View, () -> Boolean>>()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && requested) startCamera()
        else if (!granted) {
            requested = false
            binding.cameraStatus.setText(R.string.camera_permission_denied)
            setTracking(getString(R.string.camera_permission_denied))
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        requested = savedInstanceState?.getBoolean("camera_requested") ?: true
        lens = if (savedInstanceState?.getBoolean("back_camera") == true) CameraLens.BACK else CameraLens.FRONT
        effectCategory=savedInstanceState?.getInt("effect_category",0)?.coerceIn(0,7)?:0
        adjusting=savedInstanceState?.getBoolean("effect_adjusting",true)?:true
        restorePanelSection=savedInstanceState?.takeIf{it.getBoolean("effect_panel_open",false)}?.getInt("effect_panel_section",0)
        restoredGestureEnabled=savedInstanceState?.getBoolean("gesture_detection_enabled",true)?:true
        faceDetectionEnabled=savedInstanceState?.getBoolean("face_detection_enabled",true)?:true
        super.onCreate(savedInstanceState)
    }
    override fun initView() {
        enableEdgeToEdge()
        // 根容器消费系统栏与挖孔；弹框使用自己的 Window Insets。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            insets
        }
        WindowInsetsControllerCompat(window,binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        binding.cameraBack.setOnClickListener { finish() }
        binding.cameraEffectContainer.setOnClickListener { swapPreviews() }
        binding.cameraCompare.setText(R.string.camera_hold_original)
        binding.cameraCompare.setOnTouchListener { view, event ->
            when(event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    controller.showOriginal=true
                    controller.requestRender()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    controller.showOriginal=binding.cameraShowRaw.isChecked
                    controller.requestRender()
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    if(event.actionMasked==android.view.MotionEvent.ACTION_UP)view.performClick()
                }
            }
            true
        }
        binding.cameraResetAll.setOnClickListener {
            resetDialog?.dismiss()
            resetDialog=androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.camera_reset_all)
                .setMessage(R.string.camera_reset_all_message)
                .setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.camera_reset_all) { _,_ ->
                    workshop.disable()
                    effectWorkshop.clear()
                    binding.cameraGlasses.isChecked=false
                    binding.cameraMoustache.isChecked=false
                    binding.cameraHat.isChecked=false
                    binding.cameraShowRaw.isChecked=false
                    controller.showOriginal=false
                    resourceCenter.resetAll()
                }.create().also { dialog ->
                    dialog.setOnDismissListener { resetDialog=null }
                    dialog.show()
                }
        }
        binding.cameraMore.setOnClickListener { showMore() }
        binding.cameraGestureEnabled.isChecked=restoredGestureEnabled
        binding.cameraSmoothingChoice.setOnClickListener { selectBeautyParameter(0) }
        binding.cameraWhiteningChoice.setOnClickListener { selectBeautyParameter(1) }
        selectBeautyParameter(0)
        controller = binding.cameraGpuPreview
        controller.effectIsMain=true
        controller.onSceneStatus={enabled,ready -> binding.cameraSceneStatus.setText(if(!enabled)R.string.scene_background_off else if(ready)R.string.scene_background_ready else R.string.scene_background_waiting)}
        workshop = StickerWorkshopController(this,binding.stickerWorkshop,{ pack ->
            if(pack!=null) binding.cameraBackend.setSelection(DemoBackend.MEDIAPIPE.ordinal)
            controller.setCustomStickerPack(pack)
        },{ _ -> },{ if(::effectWorkshop.isInitialized && effectWorkshop.theme!=null) effectWorkshop.clear() })
        controller.onImage = { image ->
            lens = image.lens
            val fps=image.performance?.processedFps ?: 0.0
            binding.cameraPreviewHint.visibility = View.GONE
            val snapshot = image.analysis
            val state = when {
                snapshot.state == DemoTrackingState.ERROR -> getString(R.string.camera_tracking_failed)
                image.analysis.state == DemoTrackingState.DISABLED -> getString(R.string.camera_tracking_disabled)
                snapshot.tracked -> getString(R.string.camera_tracking_motion)
                snapshot.state == DemoTrackingState.READY -> getString(R.string.camera_tracking_ready,image.faceCount,image.anchoredLayers)
                snapshot.state == DemoTrackingState.NO_FACE -> getString(R.string.camera_no_face)
                snapshot.state == DemoTrackingState.STALE -> getString(R.string.camera_tracking_stale)
                else -> getString(R.string.camera_loading)
            }
            binding.cameraFaceSummary.setText(when(snapshot.state) {
                DemoTrackingState.READY -> R.string.camera_face_present
                DemoTrackingState.NO_FACE -> R.string.camera_face_absent
                DemoTrackingState.DISABLED -> R.string.camera_face_off
                DemoTrackingState.STALE,DemoTrackingState.ERROR -> R.string.camera_face_pending
                else -> R.string.camera_face_idle
            })
            val faceEvent = faceCallbackText(snapshot.state,image.faceCount)
            setTracking(getString(R.string.camera_callback_face_display,state,faceEvent))
            fun milliseconds(value: Long?) = value?.let { (it/1_000_000).toString() } ?: getString(R.string.demo_unavailable)
            binding.cameraStatus.text = getString(R.string.camera_diagnostics,
                getString(R.string.camera_pipeline_name,
                    resources.getStringArray(R.array.beauty_renderers)[image.beautyBackend.ordinal],
                    resources.getStringArray(R.array.beauty_backends)[image.backend.ordinal]),
                image.width,image.height,image.processingMs,milliseconds(snapshot.durationNs),
                milliseconds(snapshot.resultAgeNs),snapshot.landmarkCount)
            binding.cameraPerformance.text=getString(R.string.camera_performance_compact,fps,image.processingMs)
            val error = snapshot.error
            if (error != null) showError(error)
            else if (snapshot.state == DemoTrackingState.READY || snapshot.state == DemoTrackingState.NO_FACE)
                binding.cameraErrorDetail.visibility = View.GONE
        }
        androidx.lifecycle.ViewModelProvider(this)[ResourceCenterModel::class.java].ui.observe(this) { ui ->
            controller.setGestureEffects(ui.gestureEffects)
        }
        controller.onGestures = { snapshot, events ->
            val names=resources.getStringArray(R.array.gesture_names)
            val phases=resources.getStringArray(R.array.gesture_phases)
            // 同批事件取最近一条；空批次保留上次回调，避免下一次UI刷新立刻覆盖。
            events.filter { it.id > lastGestureEventId }.lastOrNull()?.let { latest ->
                lastGestureEventId=latest.id
                lastGestureCallback=getString(R.string.camera_callback_gesture_event,
                    names[latest.kind.ordinal],phases[latest.phase.ordinal],latest.id)
                recordCallback(lastGestureCallback)
            }
            val gestureState=when(snapshot.state) {
                GestureAnalysisState.DISABLED -> getString(R.string.camera_gesture_off)
                GestureAnalysisState.ERROR -> getString(R.string.camera_gesture_error)
                GestureAnalysisState.STALE -> getString(R.string.camera_gesture_stale)
                GestureAnalysisState.PROCESSING -> getString(R.string.camera_gesture_loading)
                GestureAnalysisState.NO_HANDS -> getString(R.string.camera_gesture_no_hands)
                GestureAnalysisState.READY -> getString(R.string.camera_gesture_ready,
                    snapshot.result?.hands?.size ?: 0,snapshot.result?.hands?.mapNotNull { it.modelGesture }?.distinct()
                        ?.joinToString(" / "){names[it.ordinal]}?.ifEmpty { getString(R.string.camera_gesture_unknown) }
                        ?: getString(R.string.camera_gesture_unknown))
            }
            binding.cameraHandSummary.text=when(snapshot.state) {
                GestureAnalysisState.DISABLED -> getString(R.string.camera_hand_off)
                GestureAnalysisState.READY -> getString(R.string.camera_hand_value,
                    snapshot.result?.hands?.mapNotNull {it.modelGesture}?.firstOrNull()?.let {names[it.ordinal]}
                        ?: getString(R.string.camera_gesture_unknown))
                GestureAnalysisState.NO_HANDS -> getString(R.string.camera_hand_value,getString(R.string.camera_gesture_no_hands))
                else -> getString(R.string.camera_hand_wait)
            }
            val callback=lastGestureCallback.ifEmpty { getString(R.string.camera_callback_gesture_wait) }
            binding.cameraGestureStatus.text=getString(R.string.camera_callback_gesture_display,gestureState,callback)
        }
        binding.cameraGestureEnabled.setOnCheckedChangeListener { _,checked ->
            lastGestureCallback="";lastGestureEventId=0L
            binding.cameraHandSummary.setText(if(checked)R.string.camera_hand_wait else R.string.camera_hand_off)
            binding.cameraGestureStatus.setText(if(checked)R.string.camera_gesture_loading else R.string.camera_gesture_off)
            updateEffects()
        }
        controller.onStatus = { status ->
            if(status==CameraStatus.STARTING || status==CameraStatus.STOPPED || status==CameraStatus.ERROR) {
                facePresent=false;faceCallbackCount=0;lastFaceCallback=""
                binding.cameraFaceSummary.setText(R.string.camera_face_idle)
                binding.cameraHandSummary.setText(if(status==CameraStatus.STARTING && binding.cameraGestureEnabled.isChecked)R.string.camera_hand_wait else R.string.camera_hand_off)
                lastGestureCallback="";lastGestureEventId=0L
                binding.cameraGestureStatus.setText(if(status==CameraStatus.STARTING && binding.cameraGestureEnabled.isChecked)R.string.camera_gesture_loading else R.string.camera_gesture_off)
            }
            setTracking(getString(when (status) {
                CameraStatus.STARTING -> R.string.camera_starting
                CameraStatus.BOUND -> R.string.camera_bound
                CameraStatus.STOPPED -> R.string.camera_stopped
                CameraStatus.ERROR -> R.string.camera_failed
            }))
        }
        controller.onError = { showError(it.message ?: it.javaClass.simpleName) }
        binding.cameraBackend.useDemoEntries(R.array.beauty_backends)
        binding.cameraBeautyBackend.useDemoEntries(R.array.beauty_renderers)
        // Demo统一使用GPUPixel；基础OpenGL保留为SDK内部对照，不再向用户展示切换。
        binding.cameraBeautyBackend.setSelection(DemoBeautyBackend.GPUPIXEL.ordinal)
        binding.cameraGlassesStyle.useDemoEntries(R.array.sticker_glasses_styles)
        binding.cameraHeadStyle.useDemoEntries(R.array.sticker_head_styles)
        binding.cameraBackend.setSelection(1)
        binding.cameraGlassesStyle.setSelection(1)
        binding.cameraHeadStyle.setSelection(1)
        // 在注册监听器之前恢复，避免初始化默认值覆盖上次选择。
        binding.cameraGlassesStyle.setSelection(stickerPreferences.getInt("glasses_style",1).coerceIn(0,2))
        binding.cameraHeadStyle.setSelection(stickerPreferences.getInt("head_style",1).coerceIn(0,3))
        binding.cameraGlasses.isChecked=stickerPreferences.getBoolean("glasses",false)
        binding.cameraMoustache.isChecked=stickerPreferences.getBoolean("moustache",false)
        binding.cameraHat.isChecked=stickerPreferences.getBoolean("hat",false)
        stickerPreferencesReady=true
        binding.cameraStart.setOnClickListener {
            requested = true
            binding.cameraErrorDetail.visibility = View.GONE
            if (hasPermission()) startCamera() else permission.launch(Manifest.permission.CAMERA)
        }
        binding.cameraStop.setOnClickListener {
            requested = false; controller.stop()
            binding.cameraPreviewHint.visibility = View.VISIBLE
        }
        binding.cameraSwitch.setOnClickListener {
            binding.cameraPreviewHint.visibility = View.VISIBLE
            controller.switchCamera()
        }
        binding.cameraBackend.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) = updateEffects()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.cameraBeautyBackend.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) = updateEffects()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val styleListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) = updateEffects()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.cameraGlassesStyle.onItemSelectedListener = styleListener
        binding.cameraHeadStyle.onItemSelectedListener = styleListener
        binding.cameraFrameTracking.setOnCheckedChangeListener { _,_ -> updateEffects() }
        binding.cameraMediapipeGpu.setOnCheckedChangeListener { _,_ -> updateEffects() }
        binding.cameraBeauty.setOnCheckedChangeListener { _,checked -> if(!applyingResource && ::resourceCenter.isInitialized)resourceCenter.enabled(checked);updateEffects() }
        binding.cameraGlasses.setOnCheckedChangeListener { _,_ -> updateEffects() }
        binding.cameraMoustache.setOnCheckedChangeListener { _,_ -> updateEffects() }
        binding.cameraHat.setOnCheckedChangeListener { _,_ -> updateEffects() }
        binding.cameraShowRaw.setOnCheckedChangeListener { _,checked ->
            controller.showOriginal = checked
            controller.requestRender()
            setTracking(trackingText)
        }
        // 弹框复用设置视图，关闭后保留当前参数。
        setupEffectCategories()
        binding.cameraThemeExit.setOnClickListener { effectWorkshop.clear() }
        binding.cameraSettings.setOnClickListener { openSettings(if(effectCategory==4)1 else 0) }
        binding.cameraStickers.setOnClickListener { effectCategory=4;adjusting=false;openSettings(1) }
        binding.cameraPresets.setOnClickListener { adjusting=false;openSettings(if(effectCategory==4)1 else 0) }
        binding.cameraAdjust.setOnClickListener { adjusting=true;openSettings(0) }
        binding.cameraDebug.setOnClickListener { openSettings(2) }
        binding.cameraWorkshop.setOnClickListener { startActivity(android.content.Intent(this,ResourceCenterActivity::class.java)) }
        // 显式功能入口，不要求用户先理解并手动切换渲染后端。
        binding.cameraMakeupEntry.setOnClickListener {
            binding.cameraBeautyBackend.setSelection(DemoBeautyBackend.GPUPIXEL.ordinal)
            selectEffectCategory(2)
            binding.cameraExtendedParameter.setSelection(7) // 首先展示口红，其他美型/美妆由同一选择器切换。
            binding.cameraBeauty.isChecked=true
            updateEffects()
            binding.cameraExtendedSection.doOnLayout { section ->
                section.requestRectangleOnScreen(android.graphics.Rect(0,0,section.width,section.height),true)
            }
        }
        binding.cameraExtendedParameter.useDemoEntries(R.array.camera_extended_parameters)
        binding.cameraExtendedParameter.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) = showExtended()
            override fun onNothingSelected(parent:AdapterView<*>?) = Unit
        }
        binding.cameraExtendedStrength.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar:SeekBar?) = Unit
            override fun onStopTrackingTouch(bar:SeekBar?) = Unit
            override fun onProgressChanged(bar:SeekBar?,progress:Int,fromUser:Boolean) {
                if(updatingExtended || !fromUser) return
                val value=progress/100f
                extended=when(binding.cameraExtendedParameter.selectedItemPosition) {
                    0 -> extended.copy(rosy=value)
                    1 -> extended.copy(brightness=value-1f)
                    2 -> extended.copy(contrast=value)
                    3 -> extended.copy(saturation=value)
                    4 -> extended.copy(sharpening=value)
                    5 -> extended.copy(slimFace=value)
                    6 -> extended.copy(bigEyes=value)
                    7 -> extended.copy(lipstick=value)
                    8 -> extended.copy(blusher=value)
                    9 -> extended.copy(slimNose=value)
                    10 -> extended.copy(chinLength=value-1f)
                    11 -> extended.copy(mouthSize=value-1f)
                    else -> extended.copy(eyelashes=value)
                }
                if(extended.needsFace) binding.cameraBackend.setSelection(DemoBackend.MEDIAPIPE.ordinal)
                binding.cameraBeauty.isChecked=true
                showExtended();updateEffects()
            }
        })
        showExtended()
        binding.cameraBackground.useDemoEntries(R.array.camera_backgrounds)
        binding.cameraMotion.useDemoEntries(R.array.camera_motions)
        val sceneListener=object:AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                if(applyingResource)return
                val current=if(::resourceCenter.isInitialized)resourceCenter.settings?.scene else null
                val bgChanged=parent?.id==R.id.camera_background && current!=null && position!=current.background.ordinal
                val motionChanged=parent?.id==R.id.camera_motion && current!=null && position!=current.motion.ordinal
                if(bgChanged || motionChanged)submitScene(bgChanged,motionChanged)
                if(binding.cameraMotion.selectedItemPosition==CameraMotion.FACE_FOLLOW.ordinal)
                    binding.cameraBackend.setSelection(DemoBackend.MEDIAPIPE.ordinal)
                updateEffects()
            }
            override fun onNothingSelected(parent:AdapterView<*>?) = Unit
        }
        binding.cameraBackground.onItemSelectedListener=sceneListener
        binding.cameraMotion.onItemSelectedListener=sceneListener
        binding.cameraMotionZoom.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar:SeekBar?) = Unit
            override fun onStopTrackingTouch(bar:SeekBar?) = Unit
            override fun onProgressChanged(bar:SeekBar?,progress:Int,fromUser:Boolean) { if(fromUser) {submitScene(false,true);updateEffects()} }
        })
        binding.cameraReset.setOnClickListener {
            resetDialog?.dismiss()
            resetDialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.camera_reset_confirm)
                .setMessage(if(effectCategory==7)R.string.camera_reset_theme_message else R.string.camera_reset_confirm_message)
                .setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.camera_reset_category_short){_,_->if(::resourceCenter.isInitialized)resourceCenter.resetCategory()}.create().also { dialog ->
                    dialog.setOnDismissListener { resetDialog = null }
                    dialog.show()
                }
        }
        binding.cameraClearStickers.setOnClickListener {
            if(::resourceCenter.isInitialized)resourceCenter.clearStickers()
            workshop.disable()
            if(::effectWorkshop.isInitialized && effectWorkshop.theme!=null) effectWorkshop.clear()
            binding.cameraGlasses.isChecked = false
            binding.cameraMoustache.isChecked = false
            binding.cameraHat.isChecked = false
        }
        buildStickerCards()
        binding.cameraStickerGrid.visibility=View.GONE
        binding.cameraSettingsClose.setOnClickListener { settingsDialog?.dismiss() }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = updateEffects()
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        }
        binding.cameraSmoothing.setOnSeekBarChangeListener(listener)
        binding.cameraWhitening.setOnSeekBarChangeListener(listener)
        binding.cameraStage.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> updateEffectWindow() }
        updateEffects()
        effectWorkshop = EffectWorkshopController(this,binding.stickerWorkshop) { packs ->
            if(packs.any { it.requiresExtendedBeauty }) binding.cameraBeautyBackend.setSelection(DemoBeautyBackend.GPUPIXEL.ordinal)
            if(packs.any { it.applyTo(BeautySettings()).requiresFaceAnalysis }) binding.cameraBackend.setSelection(DemoBackend.MEDIAPIPE.ordinal)
            updateEffects()
        }
        resourceCenter=ResourceCenterController(this,binding.resourceCenter,orderChanged=::applyResourceCategoryOrder) { settings,enabled ->
            applyingResource=true
            binding.cameraBeauty.isChecked=enabled
            binding.cameraSmoothing.progress=(settings.smoothing*100).toInt()
            binding.cameraWhitening.progress=(settings.whitening*100).toInt()
            extended=settings.adjustments;showExtended()
            binding.cameraBackground.setSelection(settings.scene.background.ordinal,false)
            binding.cameraMotion.setSelection(settings.scene.motion.ordinal,false)
            binding.cameraMotionZoom.progress=((settings.scene.zoom-1f)*100).toInt()
            binding.cameraMotionZoom.isEnabled=settings.scene.motion!=CameraMotion.KEYFRAMES
            applyingResource=false
            updateEffects()
        }
        workshop.restoreDraft()
    }
    /** 底部面板只保留一份，分类切换不重建相机、检测器或渲染资源。 */
    private fun setupEffectCategories() {
        val titles=listOf(R.string.effect_category_beauty,R.string.effect_category_reshape,R.string.effect_category_makeup,R.string.effect_category_color,R.string.effect_category_sticker,R.string.effect_category_background,R.string.effect_category_motion,R.string.resources_theme)
        titles.forEachIndexed { index,title ->
            val tab=android.widget.TextView(this).apply {
                setText(title);setTextColor(ContextCompat.getColor(this@CameraTestActivity,R.color.camera_text))
                textSize=14f;gravity=android.view.Gravity.CENTER
                setTextColor(ContextCompat.getColorStateList(this@CameraTestActivity,R.color.effect_label))
                setBackgroundResource(R.drawable.bg_effect_category);isClickable=true;isFocusable=true
                val dp=resources.displayMetrics.density;setPadding((14*dp).toInt(),0,(14*dp).toInt(),0)
                layoutParams=android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,(48*dp).toInt())
                setOnClickListener { selectEffectCategory(index) }
            }
            categoryViews+=tab;binding.effectCategories.addView(tab)
        }
    }
    /** 排序来自默认JSON；稳定参数索引与点击语义保持不变。 */
    fun applyResourceCategoryOrder(order:List<com.beautysdk.resources.ResourceCategory>) {
        val canonical=listOf(com.beautysdk.resources.ResourceCategory.BEAUTY,com.beautysdk.resources.ResourceCategory.RESHAPE,com.beautysdk.resources.ResourceCategory.MAKEUP,com.beautysdk.resources.ResourceCategory.FILTER,com.beautysdk.resources.ResourceCategory.STICKER,com.beautysdk.resources.ResourceCategory.BACKGROUND,com.beautysdk.resources.ResourceCategory.MOTION,com.beautysdk.resources.ResourceCategory.THEME)
        if(categoryViews.size!=8)return
        canonical.forEachIndexed {i,c->if(::resourceCenter.isInitialized)resourceCenter.categoryName(c)?.let{categoryViews[i].text=it}}
        val desired=order.map{categoryViews[canonical.indexOf(it)]}
        if(desired.indices.all{binding.effectCategories.getChildAt(it)===desired[it]})return
        binding.effectCategories.removeAllViews();desired.forEach{binding.effectCategories.addView(it)}
    }
    private fun selectEffectCategory(index:Int) {
        if(effectCategory!=index)adjusting=index in 0..3
        effectCategory=index;openSettings(if(index==4) 1 else 0);binding.controlsScroll.scrollTo(0,0)
        categoryViews[index].requestRectangleOnScreen(android.graphics.Rect(0,0,categoryViews[index].width,categoryViews[index].height),true)
    }
    private fun applyEffectCategory() {
        if(::resourceCenter.isInitialized) resourceCenter.category(listOf(com.beautysdk.resources.ResourceCategory.BEAUTY,com.beautysdk.resources.ResourceCategory.RESHAPE,com.beautysdk.resources.ResourceCategory.MAKEUP,com.beautysdk.resources.ResourceCategory.FILTER,com.beautysdk.resources.ResourceCategory.STICKER,com.beautysdk.resources.ResourceCategory.BACKGROUND,com.beautysdk.resources.ResourceCategory.MOTION,com.beautysdk.resources.ResourceCategory.THEME)[effectCategory])
        categoryViews.forEachIndexed { i,v -> v.alpha=1f;v.isSelected=i==effectCategory }
        binding.cameraCategoryHint.visibility=View.GONE
        binding.basicBeautyControls.visibility=if(effectCategory==0) View.VISIBLE else View.GONE
        binding.cameraMakeupEntry.visibility=View.GONE
        binding.cameraExtendedSection.visibility=if(effectCategory in 1..3) View.VISIBLE else View.GONE
        binding.backgroundControls.visibility=if(effectCategory==5) View.VISIBLE else View.GONE
        binding.motionControls.visibility=if(effectCategory==6) View.VISIBLE else View.GONE
        val indices=when(effectCategory){1->listOf(5,6,9,10,11);2->listOf(7,8,12);3->listOf(0,1,2,3,4);else->emptyList()}
        // 保留公共参数索引，分类按钮仅改变选择，不改变参数含义。
        if(parameterCategory!=effectCategory) {
            binding.cameraExtendedSection.findViewWithTag<View>("category_parameters")?.let{binding.cameraExtendedSection.removeView(it)}
            parameterViews.clear();parameterCategory=effectCategory
        }
        if(indices.isNotEmpty()&&parameterViews.isEmpty()) {
            binding.cameraExtendedParameter.visibility=View.GONE
            val row=android.widget.LinearLayout(this).apply { orientation=android.widget.LinearLayout.HORIZONTAL }
            val labels=resources.getStringArray(R.array.camera_extended_parameters)
            indices.forEach { parameter -> row.addView(android.widget.TextView(this,null,0,R.style.EffectParameter).apply {
                // 分类使用统一线性图标，图标和名称共同表达参数；选中只改变轻底色。
                setCompoundDrawablesWithIntrinsicBounds(0,when(parameter){6,12->R.drawable.ic_effect_eye;7,11->R.drawable.ic_effect_makeup;8->R.drawable.ic_effect_cheek;5,9,10->R.drawable.ic_effect_face;else->R.drawable.ic_effect_light},0,0)
                text=labels[parameter];tag="parameter_$parameter"
                isSelected=parameter==binding.cameraExtendedParameter.selectedItemPosition
                parameterViews[parameter]=this
                setOnClickListener { binding.cameraExtendedParameter.setSelection(parameter);showExtended() }
            }) }
            val scroll=android.widget.HorizontalScrollView(this).apply {tag="category_parameters";isHorizontalScrollBarEnabled=false;addView(row)}
            binding.cameraExtendedSection.addView(scroll,0)
            if(binding.cameraExtendedParameter.selectedItemPosition !in indices) binding.cameraExtendedParameter.setSelection(indices.first())
        }
        if(indices.isNotEmpty())showExtended()
    }
    private fun openSettings(section: Int) {
        if(section==3) {startActivity(android.content.Intent(this,ResourceCenterActivity::class.java));return}
        val editable=effectCategory in 0..3 || effectCategory in 5..6
        val jsonParameters=resourceCenter.hasLibrary&&effectCategory in 0..3
        resourceCenter.parameterMode(adjusting&&effectCategory in 0..3)
        binding.beautySection.visibility = if (section == 0 && adjusting && editable && !jsonParameters) View.VISIBLE else View.GONE
        binding.stickerSection.visibility = if (section == 1) View.VISIBLE else View.GONE
        binding.debugSection.visibility = if (section == 2) View.VISIBLE else View.GONE
        binding.workshopSection.visibility = if (section == 3) View.VISIBLE else View.GONE
        binding.resourceCenter.root.visibility=if(section!=2 && (!adjusting || !editable || jsonParameters)) View.VISIBLE else View.GONE
        binding.cameraModeBar.visibility=if(section!=2)View.VISIBLE else View.GONE
        binding.cameraPresets.visibility=if(editable)View.VISIBLE else View.GONE
        binding.cameraAdjust.visibility=if(editable)View.VISIBLE else View.GONE
        binding.cameraPresets.isSelected=!adjusting;binding.cameraAdjust.isSelected=adjusting
        binding.cameraBeauty.visibility=View.GONE
        binding.cameraClearStickers.visibility=if(effectCategory==4)View.VISIBLE else View.GONE
        binding.cameraPanelHint.visibility=View.GONE
        binding.cameraPanelHint.setText(if(adjusting&&editable)R.string.camera_adjust_hint else R.string.camera_presets_hint)
        if(section==3) binding.workshopSection.visibility=View.GONE
        binding.effectCategoryScroll.visibility=if(section in 0..1) View.VISIBLE else View.GONE
        if(section in 0..1) applyEffectCategory()
        binding.cameraPanelTitle.visibility=if(section==2)View.VISIBLE else View.GONE
        binding.cameraPanelTitle.setText(if(section==2)R.string.camera_tab_debug else listOf(R.string.effect_category_beauty,R.string.effect_category_reshape,R.string.effect_category_makeup,R.string.effect_category_color,R.string.effect_category_sticker,R.string.effect_category_background,R.string.effect_category_motion,R.string.resources_theme)[effectCategory])
        if(section!=2)resourceCenter.categoryName(com.beautysdk.resources.ResourceCategory.entries[effectCategory])?.let{binding.cameraPanelTitle.text=it}
        listOf(binding.cameraSettings,binding.cameraStickers,binding.cameraDebug,binding.cameraWorkshop).forEachIndexed { index, view -> view.isSelected = index == section }
        if (settingsDialog?.isShowing == true) {resizeEffectPanel();return}
        val panel = binding.cameraControlsPanel
        (panel.parent as? ViewGroup)?.removeView(panel)
        panel.visibility = View.VISIBLE
        binding.controlsScroll.visibility=View.VISIBLE
        val dialog = BottomSheetDialog(this)
        settingsDialog = dialog
        dialog.setContentView(panel)
        dialog.setOnShowListener {
            // 长内容内部滚动，弹框上方仍可观察相机。
            resizeEffectPanel()
            // 设置面板采用固定展开高度；禁止BottomSheet抢占内容区/滑杆的拖动手势。
            // ScrollView自己消费上下滚动，关闭仍使用按钮、返回键或外部点击。
            dialog.behavior.isDraggable = false
            binding.controlsScroll.isNestedScrollingEnabled = false
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
        }
        dialog.setOnDismissListener {
            (panel.parent as? ViewGroup)?.removeView(panel)
            panel.visibility = View.GONE
            binding.controlsScroll.visibility=View.GONE
            binding.root.addView(panel)
            settingsDialog = null
            listOf(binding.cameraSettings,binding.cameraStickers,binding.cameraDebug,binding.cameraWorkshop).forEach { it.isSelected = false }
        }
        dialog.window?.setDimAmount(.12f)
        dialog.show()
        dialog.window?.navigationBarColor = ContextCompat.getColor(this,R.color.camera_surface)
        // 弹框独立消费导航栏，固定分类/模式栏不会随内容滚动离屏。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(panel){view,insets->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft,view.paddingTop,view.paddingRight,bars.bottom+8*resources.displayMetrics.density.toInt());insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(panel)
    }
    override fun initData() {
        if (requested && !hasPermission()) permission.launch(Manifest.permission.CAMERA)
        val initial = intent.getIntExtra("initial_section", -1)
        intent.removeExtra("initial_section")
        if(initial==1){effectCategory=4;adjusting=false}
        val reopen=if(initial in 0..3)initial else restorePanelSection
        if(reopen!=null)binding.root.post {openSettings(reopen)}
        restorePanelSection=null
    }
    // 只交换预览的父容器，不修改分析帧方向和镜像约定。
    private fun swapPreviews() {
        effectIsMain = !effectIsMain
        controller.effectIsMain = effectIsMain
        controller.requestRender()
        binding.cameraEffectLabel.setText(if (effectIsMain) R.string.camera_original_short else R.string.camera_result_short)
        binding.cameraMainLabel.setText(if (effectIsMain) R.string.camera_result_short else R.string.camera_original_short)
        binding.cameraCompare.setText(R.string.camera_hold_original)
    }
    private fun buildStickerCards() {
        data class Sample(val file: String,val title: Int,val active: () -> Boolean,val select: () -> Unit)
        fun glasses(style: Int) {
            val selected = binding.cameraGlasses.isChecked && binding.cameraGlassesStyle.selectedItemPosition == style
            binding.cameraGlassesStyle.setSelection(style)
            binding.cameraGlasses.isChecked = !selected
            updateEffects()
        }
        fun head(style: Int) {
            val selected = binding.cameraHat.isChecked && binding.cameraHeadStyle.selectedItemPosition == style
            binding.cameraHeadStyle.setSelection(style)
            binding.cameraHat.isChecked = !selected
            updateEffects()
        }
        val samples = listOf(
            Sample("round_glasses",R.string.sticker_round_glasses,{ binding.cameraGlasses.isChecked && binding.cameraGlassesStyle.selectedItemPosition == 1 },{ glasses(1) }),
            Sample("heart_glasses",R.string.sticker_heart_glasses,{ binding.cameraGlasses.isChecked && binding.cameraGlassesStyle.selectedItemPosition == 2 },{ glasses(2) }),
            Sample("curled_moustache",R.string.moustache,{ binding.cameraMoustache.isChecked },{ binding.cameraMoustache.toggle() }),
            Sample("blue_top_hat",R.string.sticker_top_hat,{ binding.cameraHat.isChecked && binding.cameraHeadStyle.selectedItemPosition == 1 },{ head(1) }),
            Sample("gold_crown",R.string.sticker_crown,{ binding.cameraHat.isChecked && binding.cameraHeadStyle.selectedItemPosition == 2 },{ head(2) }),
            Sample("cat_ears",R.string.sticker_cat_ears,{ binding.cameraHat.isChecked && binding.cameraHeadStyle.selectedItemPosition == 3 },{ head(3) }))
        val dp = resources.displayMetrics.density
        samples.forEach { sample ->
            val item = ItemCameraStickerBinding.inflate(layoutInflater,binding.cameraStickerGrid,false)
            val card = item.root
            card.contentDescription = getString(sample.title)
            card.layoutParams = GridLayout.LayoutParams().apply {
                width = 0; height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED,1f)
                setMargins((3*dp).toInt(),(6*dp).toInt(),(3*dp).toInt(),0)
            }
            val bitmap = assets.open("beauty_stickers/${sample.file}.png").use { BitmapFactory.decodeStream(it) }
            item.stickerImage.setImageBitmap(bitmap)
            item.stickerName.setText(sample.title)
            card.setOnClickListener { workshop.disable();if(::effectWorkshop.isInitialized && effectWorkshop.theme!=null) effectWorkshop.clear();sample.select() }
            stickerCards += card to sample.active
            binding.cameraStickerGrid.addView(card)
        }
    }
    private fun setTracking(text: String) {
        trackingText = text
        val displayed = if (binding.cameraShowRaw.isChecked) getString(R.string.camera_raw_mode) else text
        if (binding.cameraTracking.text.toString() != displayed) binding.cameraTracking.text = displayed
    }
    private fun showError(message: String) {
        binding.cameraErrorDetail.text = getString(R.string.camera_error,message)
        binding.cameraErrorDetail.visibility = View.VISIBLE
        setTracking(getString(R.string.camera_tracking_failed))
    }
    /** 仅设置变化时保存，不在相机帧回调执行；apply异步落盘。 */
    private fun saveStickerSelection() {
        if(!stickerPreferencesReady) return
        val values=mapOf("glasses" to binding.cameraGlasses.isChecked,
            "moustache" to binding.cameraMoustache.isChecked,"hat" to binding.cameraHat.isChecked)
        val glasses=binding.cameraGlassesStyle.selectedItemPosition
        val head=binding.cameraHeadStyle.selectedItemPosition
        if(values.all { (key,value) -> stickerPreferences.getBoolean(key,false)==value } &&
            stickerPreferences.getInt("glasses_style",1)==glasses && stickerPreferences.getInt("head_style",1)==head) return
        stickerPreferences.edit().apply {
            values.forEach { (key,value) -> putBoolean(key,value) }
            putInt("glasses_style",glasses);putInt("head_style",head)
        }.apply()
    }
    private fun sceneFromControls():BeautyScene {
        val old=if(::resourceCenter.isInitialized)resourceCenter.settings?.scene?:BeautyScene() else BeautyScene()
        val bg=BackgroundMode.entries[binding.cameraBackground.selectedItemPosition.coerceAtLeast(0)]
        val mode=CameraMotion.entries[binding.cameraMotion.selectedItemPosition.coerceAtLeast(0)]
        val timeline=if(mode==CameraMotion.KEYFRAMES)old.timeline?:MotionTimeline(listOf(
            MotionKeyframe(0,.5f,.5f,1f),MotionKeyframe(2000,.5f,.5f,1.25f),MotionKeyframe(4000,.5f,.5f,1f))) else null
        return old.copy(background=bg,backgroundImage=if(bg==BackgroundMode.IMAGE)old.backgroundImage?:sceneBackground else null,
            motion=mode,zoom=1f+binding.cameraMotionZoom.progress/100f,timeline=timeline)
    }
    private fun submitScene(background:Boolean,motion:Boolean) {
        if(applyingResource || !::resourceCenter.isInitialized)return
        resourceCenter.scene(sceneFromControls(),background,motion)
    }
    private fun updateEffects() {
        if(applyingResource)return
        if(::resourceCenter.isInitialized) resourceCenter.manual(com.beautysdk.BeautySettings(smoothing=binding.cameraSmoothing.progress/100f,whitening=binding.cameraWhitening.progress/100f,adjustments=extended))
        saveStickerSelection()
        binding.cameraSmoothingValue.text = getString(R.string.camera_intensity,binding.cameraSmoothing.progress)
        binding.cameraWhiteningValue.text = getString(R.string.camera_intensity,binding.cameraWhitening.progress)
        stickerCards.forEach { (view,active) -> view.isSelected = active() }
        binding.cameraMediapipeGpu.isEnabled = binding.cameraBackend.selectedItemPosition == DemoBackend.MEDIAPIPE.ordinal
        binding.cameraFrameTracking.isEnabled = binding.cameraBackend.selectedItemPosition == DemoBackend.MEDIAPIPE.ordinal
        binding.cameraExtendedSection.visibility=if(effectCategory in 1..3) View.VISIBLE else View.GONE
        val beauty = binding.cameraBeauty.isChecked
        // 总美颜开关同时控制导入的美颜预设，背景和运镜保持独立。
        val theme=if(::effectWorkshop.isInitialized) effectWorkshop.theme else null
                val legacySettings=theme?.prepare(ThemeCapabilities(faceTracking=true,segmentation=true,extendedBeauty=true))?.let { if(beauty) it else it.copy(smoothing=0f,whitening=0f,filter=BeautyFilter.NONE,adjustments=BeautyAdjustments()) }
        val unifiedSettings=if(::resourceCenter.isInitialized) resourceCenter.settings else null
        val themeSettings=unifiedSettings?.let {if(beauty)it else it.copy(smoothing=0f,whitening=0f,filter=BeautyFilter.NONE,adjustments=BeautyAdjustments(),eyeMakeup=null)}?:legacySettings
        val resourcePacks=if(::effectWorkshop.isInitialized) effectWorkshop.packs.filter { beauty || it.type!=EffectResourceType.BEAUTY } else emptyList()
        val resourceActive=theme!=null || (::effectWorkshop.isInitialized && effectWorkshop.packs.isNotEmpty())
        binding.cameraThemeExit.visibility=if(resourceActive && unifiedSettings==null) View.VISIBLE else View.GONE
        listOf(binding.cameraSmoothing,binding.cameraWhitening,binding.cameraExtendedStrength,binding.cameraBackground,binding.cameraMotion).forEach { it.isEnabled=!resourceActive || unifiedSettings!=null }

        controller.options = CameraDemoOptions(if(themeSettings?.requiresFaceAnalysis==true || resourcePacks.any { it.applyTo(BeautySettings()).requiresFaceAnalysis }) DemoBackend.MEDIAPIPE else DemoBackend.entries[binding.cameraBackend.selectedItemPosition],
            DemoEffects(if (beauty) binding.cameraSmoothing.progress / 100f else 0f,if (beauty) binding.cameraWhitening.progress / 100f else 0f,false,
                binding.cameraGlasses.isChecked,binding.cameraMoustache.isChecked,binding.cameraHat.isChecked,binding.cameraGlassesStyle.selectedItemPosition,binding.cameraHeadStyle.selectedItemPosition,
                if(beauty) extended else BeautyAdjustments(),
                themeSettings?.scene?:sceneFromControls(),
                resources=resourcePacks,themeSettings=themeSettings),
            if(themeSettings?.adjustments?.isIdentity==false || resourcePacks.any { it.requiresExtendedBeauty }) DemoBeautyBackend.GPUPIXEL else DemoBeautyBackend.entries[binding.cameraBeautyBackend.selectedItemPosition],
            mediaPipeGpu = binding.cameraMediapipeGpu.isChecked,
            frameTracking = binding.cameraFrameTracking.isChecked,
            gestureEnabled = binding.cameraGestureEnabled.isChecked,
            observeFaces = true, faceAnalysisEnabled = faceDetectionEnabled)
        controller.requestRender()
    }
    /** 单一参数滑杆避免面板堆叠；中性值按参数分别显示，未选项的强度保持不变。 */
    private fun showExtended() {
        parameterViews.forEach{(index,view)->view.isSelected=index==binding.cameraExtendedParameter.selectedItemPosition}
        updatingExtended=true
        try {
            val index=binding.cameraExtendedParameter.selectedItemPosition.coerceAtLeast(0)
            val value=when(index) {
                0 -> extended.rosy
                1 -> extended.brightness+1f
                2 -> extended.contrast
                3 -> extended.saturation
                4 -> extended.sharpening
                5 -> extended.slimFace
                6 -> extended.bigEyes
                7 -> extended.lipstick
                8 -> extended.blusher
                9 -> extended.slimNose
                10 -> extended.chinLength+1f
                11 -> extended.mouthSize+1f
                else -> extended.eyelashes
            }
            val progress=kotlin.math.round(value*100).toInt()
            binding.cameraExtendedStrength.max=if((index in 1..3 || index in 10..11)) 200 else 100
            binding.cameraExtendedStrength.progress=progress
            binding.cameraExtendedValue.text=getString(R.string.camera_extended_value,
                resources.getStringArray(R.array.camera_extended_parameters)[index],if(index==1 || index in 10..11) progress-100 else progress)
        } finally { updatingExtended=false }
    }
    /** 单人脸状态变化展示：仅有效检测确认进入/离开，重复快照不重复计数。 */
    private fun faceCallbackText(state: DemoTrackingState, count: Int): String {
        when(state) {
            DemoTrackingState.READY -> if(count>0 && !facePresent) {
                facePresent=true
                lastFaceCallback=getString(R.string.camera_callback_face_enter,++faceCallbackCount)
                recordCallback(lastFaceCallback)
            }
            DemoTrackingState.NO_FACE -> if(facePresent) {
                facePresent=false
                lastFaceCallback=getString(R.string.camera_callback_face_leave,++faceCallbackCount)
                recordCallback(lastFaceCallback)
            }
            DemoTrackingState.DISABLED -> {
                facePresent=false;lastFaceCallback=""
                return getString(R.string.camera_callback_face_disabled)
            }
            else -> Unit
        }
        val last=lastFaceCallback.ifEmpty {getString(R.string.camera_callback_face_wait)}
        return if(state==DemoTrackingState.STALE || state==DemoTrackingState.ERROR)
            getString(R.string.camera_callback_face_uncertain,last) else last
    }
    /** 主页面只有一个效果入口；低频管理操作放进更多菜单。 */
    private fun showMore() {
        moreMenu=androidx.appcompat.widget.PopupMenu(this,binding.cameraMore).apply {
            menu.add(0,R.id.camera_workshop,0,R.string.resources_page_title)
            menu.add(0,R.id.camera_debug,1,R.string.camera_tab_debug)
            menu.add(0,R.id.camera_callback_details,2,R.string.camera_callbacks)
            menu.add(0,R.id.camera_beauty,3,R.string.camera_portrait_master).apply {
                isCheckable=true;isChecked=binding.cameraBeauty.isChecked
            }
            menu.add(0,R.id.camera_effect_container,4,R.string.camera_inset).apply {
                isCheckable=true;isChecked=binding.cameraEffectContainer.visibility==View.VISIBLE
            }
            menu.add(0,R.id.camera_face_summary,5,R.string.camera_face_toggle).apply {
                isCheckable=true;isChecked=faceDetectionEnabled
            }
            menu.add(0,R.id.camera_gesture_enabled,6,R.string.camera_gesture_toggle).apply {
                isCheckable=true;isChecked=binding.cameraGestureEnabled.isChecked
            }
            setOnMenuItemClickListener {item ->
                when(item.itemId) {
                    R.id.camera_face_summary -> {
                        faceDetectionEnabled=!faceDetectionEnabled
                        binding.cameraFaceSummary.setText(if(faceDetectionEnabled)R.string.camera_face_idle else R.string.camera_face_off)
                        updateEffects()
                    }
                    R.id.camera_gesture_enabled -> {binding.cameraGestureEnabled.isChecked=!binding.cameraGestureEnabled.isChecked}
                    R.id.camera_workshop -> binding.cameraWorkshop.performClick()
                    R.id.camera_debug -> binding.cameraDebug.performClick()
                    R.id.camera_callback_details -> openCallbacks()
                    R.id.camera_beauty -> {binding.cameraBeauty.isChecked=!binding.cameraBeauty.isChecked}
                    R.id.camera_effect_container -> {
                        binding.cameraEffectContainer.visibility=if(item.isChecked)View.GONE else View.VISIBLE
                        binding.cameraStage.post {updateEffectWindow()}
                    }
                };true
            };show()
        }
    }
    /** 基础美颜与美型/美妆统一为“选项目，再调一根滑杆”。 */
    private fun selectBeautyParameter(index:Int) {
        beautyParameter=index
        binding.cameraSmoothingChoice.isSelected=index==0
        binding.cameraWhiteningChoice.isSelected=index==1
        binding.cameraSmoothingControl.visibility=if(index==0)View.VISIBLE else View.GONE
        binding.cameraWhiteningControl.visibility=if(index==1)View.VISIBLE else View.GONE
    }
    /** 参数页较矮，资源页稍高；横屏窗口靠右显示，保留左侧预览。 */
    private fun resizeEffectPanel() {
        val dialog=settingsDialog?:return
        val landscape=binding.root.width>binding.root.height
        val dp=resources.displayMetrics.density
        val ratio=if(landscape).92f else if(adjusting && binding.debugSection.visibility!=View.VISIBLE).36f else .48f
        val height=maxOf((binding.root.height*ratio).toInt(),(240*dp).toInt()).coerceAtMost(binding.root.height)
        binding.cameraControlsPanel.layoutParams=binding.cameraControlsPanel.layoutParams.apply {this.height=height}
        if(landscape) {
            dialog.window?.setLayout(minOf((420*dp).toInt(),(binding.root.width*.56f).toInt()),ViewGroup.LayoutParams.MATCH_PARENT)
            dialog.window?.setGravity(android.view.Gravity.END or android.view.Gravity.BOTTOM)
        }
    }
    /** 只保存有界的文字事件，不保存人脸、手部图像或逐帧检测数据。 */
    private fun recordCallback(message:String) {
        val time=android.text.format.DateFormat.format("HH:mm:ss",System.currentTimeMillis()).toString()
        callbackHistory.addFirst(getString(R.string.camera_history_entry,time,message))
        while(callbackHistory.size>12)callbackHistory.removeLast()
        binding.cameraEventHistory.text=callbackHistory.joinToString("\n")
    }
    private fun openCallbacks() {
        if(callbackDialog?.isShowing==true)return
        settingsDialog?.dismiss()
        val details=binding.cameraCallbackDetails
        (details.parent as? ViewGroup)?.removeView(details);details.visibility=View.VISIBLE
        if(callbackHistory.isEmpty())binding.cameraEventHistory.setText(R.string.camera_history_empty)
        val scroll=android.widget.ScrollView(this).apply {isVerticalScrollBarEnabled=false;addView(details)}
        val container=android.widget.LinearLayout(this).apply {orientation=android.widget.LinearLayout.VERTICAL}
        val header=android.widget.LinearLayout(this).apply {gravity=android.view.Gravity.CENTER_VERTICAL}
        header.addView(android.widget.TextView(this).apply {
            setText(R.string.camera_callbacks);textSize=18f
            setTextColor(ContextCompat.getColor(this@CameraTestActivity,R.color.camera_text))
            setPadding(16,16,16,16)
        },android.widget.LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        header.addView(android.widget.TextView(this,null,0,R.style.CameraQuiet).apply {
            setText(R.string.demo_settings_close);setOnClickListener {callbackDialog?.dismiss()}
        })
        container.addView(header);container.addView(scroll,android.widget.LinearLayout.LayoutParams(-1,0,1f))
        val dialog=BottomSheetDialog(this);callbackDialog=dialog;dialog.setContentView(container)
        dialog.setOnDismissListener {
            (details.parent as? ViewGroup)?.removeView(details);details.visibility=View.GONE
            binding.root.addView(details);callbackDialog=null
        }
        dialog.setOnShowListener {
            container.layoutParams=container.layoutParams.apply {height=(binding.root.height*.6f).toInt()}
            dialog.behavior.isDraggable=false;dialog.behavior.state=BottomSheetBehavior.STATE_EXPANDED
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(details){view,insets->
            val bottom=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(view.paddingLeft,view.paddingTop,view.paddingRight,bottom+(16*resources.displayMetrics.density).toInt());insets
        }
        dialog.show();androidx.core.view.ViewCompat.requestApplyInsets(details)
    }
    private fun hasPermission() = ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun startCamera() {
        if (lifecycle.currentState != androidx.lifecycle.Lifecycle.State.DESTROYED)
            controller.start(this,lens)
    }
    override fun onStart() {
        super.onStart()
        controller.onResume()
        if(::resourceCenter.isInitialized) resourceCenter.refresh()
        binding.cameraStage.post { updateEffectWindow() }
        if (requested && hasPermission()) startCamera()
    }
    private fun updateEffectWindow() {
        val window = android.graphics.Rect()
        if(binding.cameraEffectContainer.visibility==View.VISIBLE)binding.cameraEffectContainer.getDrawingRect(window)
        if(!window.isEmpty)binding.cameraStage.offsetDescendantRectToMyCoords(binding.cameraEffectContainer,window)
        controller.setEffectWindow(window)
    }
    override fun onStop() {
        controller.showOriginal=binding.cameraShowRaw.isChecked
        if (::workshop.isInitialized) workshop.flushDraft()
        moreMenu?.dismiss()
        callbackDialog?.dismiss()
        settingsDialog?.dismiss()
        if (::controller.isInitialized) { controller.stop(); controller.onPause() }
        binding.cameraPreviewHint.visibility = View.VISIBLE
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("face_detection_enabled",faceDetectionEnabled)
        outState.putBoolean("gesture_detection_enabled",binding.cameraGestureEnabled.isChecked)
        outState.putBoolean("camera_requested",requested)
        outState.putBoolean("back_camera",lens == CameraLens.BACK)
        outState.putInt("effect_category",effectCategory)
        outState.putBoolean("effect_adjusting",adjusting)
        outState.putBoolean("effect_panel_open",settingsDialog?.isShowing==true)
        outState.putInt("effect_panel_section",if(binding.debugSection.visibility==View.VISIBLE)2 else if(effectCategory==4)1 else 0)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        // 息屏后由测试/恢复流程打开弹框时，onStop可能已发生；销毁仍须兜底释放Window。
        moreMenu?.dismiss();moreMenu=null
        callbackDialog?.dismiss();callbackDialog=null
        resetDialog?.dismiss();resetDialog=null
        settingsDialog?.dismiss();settingsDialog=null
        if (::controller.isInitialized) controller.close()
        super.onDestroy()
    }
}
