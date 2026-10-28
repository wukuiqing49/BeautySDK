package com.beautysdk.demo

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import com.beautysdk.StickerAnchor
import com.beautysdk.StickerLayerSpec
import com.beautysdk.StickerPack
import com.beautysdk.demo.databinding.ViewStickerWorkshopBinding
import kotlin.math.roundToInt

/** 宿主工坊UI绑定；资源IO与编辑状态交给ViewModel，当前相机只接收不可变贴纸包。 */
internal class StickerWorkshopController(
    private val activity: CameraTestActivity,
    private val binding: ViewStickerWorkshopBinding,
    private val onPack: (StickerPack?) -> Unit,
    private val onExpanded: (Boolean) -> Unit,
    private val onSelected: () -> Unit = {},
) {
    private val model=ViewModelProvider(activity)[StickerWorkshopViewModel::class.java]
    private var selectionVersion=model.selectionVersion
    private var updating=false
    private var advanced=false
    private var layerIds=emptyList<String>()
    private var lastPreview: android.graphics.Bitmap? = null
    private var hasResource = false
    private var lastPack: StickerPack? = null
    private val importZip=activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) model.importPack(uri)
    }
    private val exportZip=activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { model.export(it) }
    private val exportJson=activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { model.export(it) }

    init {
        binding.resourceAdvanced.setOnClickListener {
            advanced=!advanced;binding.workshopNavigation.visibility=if(advanced) View.VISIBLE else View.GONE
            showSection(if(advanced) model.editorSection else 0)
        }
        binding.workshopSamples.useDemoEntries(R.array.workshop_samples)
        binding.workshopAnchor.useDemoEntries(R.array.workshop_anchors)
        // 此处是同一编辑器的工具分区，不再折叠整个工作区。
        binding.workshopTabMaterial.setOnClickListener { showSection(0) }
        binding.workshopTabLayout.setOnClickListener { showSection(1) }
        binding.workshopTabExport.setOnClickListener { showSection(2) }
        binding.workshopParameter.useDemoEntries(R.array.workshop_parameters)
        binding.workshopParameter.setSelection(model.editorParameter)
        binding.workshopParameter.onItemSelectedListener=selection { showParameter(it) }
        showParameter(model.editorParameter)
        binding.workshopLoadSample.setOnClickListener { model.loadSample(binding.workshopSamples.selectedItemPosition) }
        // 使用系统文件选择器，无存储权限；SDK会严格校验文件内容，MIME仅用于筛选。
        binding.workshopImport.setOnClickListener { importZip.launch(arrayOf("application/zip","application/x-zip-compressed","application/octet-stream")) }
        binding.workshopSaveLoad.setOnClickListener { model.saveAndReload() }
        binding.workshopExportZip.setOnClickListener { model.prepareExport(false)?.let(exportZip::launch) }
        binding.workshopExportJson.setOnClickListener { model.prepareExport(true)?.let(exportJson::launch) }
        binding.workshopReset.setOnClickListener { model.resetLayer() }
        binding.workshopEnabled.setOnCheckedChangeListener { _,value -> if(!updating) model.setEnabled(value) }
        binding.workshopLayers.onItemSelectedListener=selection { if(!updating) model.selectLayer(it) }
        binding.workshopAnchor.onItemSelectedListener=selection { index ->
            if(!updating) edit { copy(anchor=StickerAnchor.entries[index]) }
        }
        slider(binding.workshopWidth) { copy(widthInAnchorSpans=(it+10)/100f) }
        slider(binding.workshopOffsetX) { copy(offsetX=(it-400)/100f) }
        slider(binding.workshopOffsetY) { copy(offsetY=(it-400)/100f) }
        slider(binding.workshopRotation) { copy(rotationDegrees=(it-180).toFloat()) }
        slider(binding.workshopPivotX) { copy(pivotX=(it-400)/100f) }
        slider(binding.workshopPivotY) { copy(pivotY=(it-400)/100f) }
        model.state.observe(activity) { state ->
            if(selectionVersion!=model.selectionVersion) { selectionVersion=model.selectionVersion;onSelected() }
            updating=true
            try {
                binding.workshopLoadSample.isEnabled=!state.busy
                binding.workshopImport.isEnabled=!state.busy
                binding.workshopEnabled.isEnabled=state.resource!=null && !state.busy
                binding.workshopEnabled.isChecked=state.enabled
                binding.workshopStatus.text=when {
                    state.busy -> activity.getString(R.string.workshop_busy)
                    state.error!=null -> activity.getString(R.string.workshop_error,state.error)
                    state.savedAndLoaded -> activity.getString(R.string.workshop_saved_loaded)
                    state.exported -> activity.getString(R.string.workshop_exported)
                    state.resource!=null -> activity.getString(R.string.workshop_loaded,state.resource.manifest.name,state.resource.manifest.layers.size)
                    else -> activity.getString(R.string.workshop_empty)
                }
                // 调参只更新布局；缩略图引用未变化时不重复绑定 Bitmap。
                if(lastPreview !== state.preview) {
                    lastPreview=state.preview
                    binding.workshopPreview.setImageBitmap(state.preview)
                }
                hasResource=state.resource!=null
                showSection(if(advanced) model.editorSection else 0)
                binding.workshopTabLayout.isEnabled=hasResource
                binding.workshopTabExport.isEnabled=hasResource
                binding.workshopSaveLoad.isEnabled=hasResource && !state.busy
                binding.workshopExportZip.isEnabled=hasResource && !state.busy
                binding.workshopExportJson.isEnabled=hasResource && !state.busy
                listOf<View>(binding.workshopLayers,binding.workshopAnchor,binding.workshopWidth,binding.workshopOffsetX,
                    binding.workshopOffsetY,binding.workshopRotation,binding.workshopPivotX,binding.workshopPivotY,
                    binding.workshopReset,binding.workshopParameter).forEach { it.isEnabled=!state.busy }
                val layers=state.resource?.manifest?.layers.orEmpty()
                val ids=layers.map { it.id }
                if(ids!=layerIds) {
                    layerIds=ids
                    binding.workshopLayers.adapter=ArrayAdapter(activity,R.layout.item_demo_spinner,ids).apply { setDropDownViewResource(R.layout.item_demo_spinner) }
                }
                if(layers.isNotEmpty()) {
                    if(binding.workshopLayers.selectedItemPosition!=state.layerIndex) binding.workshopLayers.setSelection(state.layerIndex)
                    val layer=layers[state.layerIndex]
                    if(binding.workshopAnchor.selectedItemPosition!=layer.anchor.ordinal) binding.workshopAnchor.setSelection(layer.anchor.ordinal)
                    value(binding.workshopWidth,binding.workshopWidthValue,R.string.workshop_width,layer.widthInAnchorSpans,((layer.widthInAnchorSpans-.1f)*100).roundToInt())
                    value(binding.workshopOffsetX,binding.workshopOffsetXValue,R.string.workshop_offset_x,layer.offsetX,(layer.offsetX*100).roundToInt()+400)
                    value(binding.workshopOffsetY,binding.workshopOffsetYValue,R.string.workshop_offset_y,layer.offsetY,(layer.offsetY*100).roundToInt()+400)
                    value(binding.workshopRotation,binding.workshopRotationValue,R.string.workshop_rotation,layer.rotationDegrees,layer.rotationDegrees.roundToInt()+180)
                    value(binding.workshopPivotX,binding.workshopPivotXValue,R.string.workshop_pivot_x,layer.pivotX,(layer.pivotX*100).roundToInt()+400)
                    value(binding.workshopPivotY,binding.workshopPivotYValue,R.string.workshop_pivot_y,layer.pivotY,(layer.pivotY*100).roundToInt()+400)
                }
            } finally { updating=false }
            if(lastPack !== state.renderPack) { lastPack=state.renderPack; onPack(state.renderPack) }
        }
    }

    /** 打开即进入工作区，不再自动加载并覆盖用户正在使用的贴纸。 */
    fun importFile(file:java.io.File) = model.importFile(file)

    fun expand() {
        binding.workshopBody.visibility=View.VISIBLE
        showSection(if(advanced) model.editorSection else 0)
        onExpanded(true)
        model.restoreDraft()
    }

    /** 工具分区只控制展示，不重建图片、检测器或渲染资源。 */
    private fun showSection(section: Int) {
        val selected=if(section!=0 && !hasResource) 0 else section
        model.editorSection=selected
        binding.workshopMaterialSection.visibility=if(selected==0) View.VISIBLE else View.GONE
        binding.workshopEditor.visibility=if(selected==1) View.VISIBLE else View.GONE
        binding.workshopExportSection.visibility=if(selected==2) View.VISIBLE else View.GONE
        listOf(binding.workshopTabMaterial,binding.workshopTabLayout,binding.workshopTabExport)
            .forEachIndexed { index, view -> view.isSelected=index==selected }
    }

    private fun showParameter(index: Int) {
        model.editorParameter=index
        listOf(binding.workshopParameterWidth,binding.workshopParameterOffsetX,binding.workshopParameterOffsetY,
            binding.workshopParameterRotation,binding.workshopParameterPivotX,binding.workshopParameterPivotY)
            .forEachIndexed { position,view -> view.visibility=if(position==index) View.VISIBLE else View.GONE }
    }

    /** 页面进入即恢复上次私有素材，不依赖用户再次打开工坊。 */
    fun restoreDraft() { model.restoreDraft() }
    fun disable() { model.setEnabled(false) }
    fun flushDraft() { model.flushDraft() }

    private fun edit(change: StickerLayerSpec.() -> StickerLayerSpec) {
        val state=model.state.value ?: return
        state.resource?.manifest?.layers?.getOrNull(state.layerIndex)?.let { model.updateLayer(change(it)) }
    }

    private fun slider(bar: SeekBar,change: StickerLayerSpec.(Int) -> StickerLayerSpec) {
        bar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(view: SeekBar?,progress: Int,fromUser: Boolean) {
                if(fromUser && !updating) edit { change(progress) }
            }
            override fun onStartTrackingTouch(view: SeekBar?) = Unit
            override fun onStopTrackingTouch(view: SeekBar?) = Unit
        })
    }

    private fun value(bar: SeekBar,label: TextView,title: Int,number: Float,progress: Int) {
        if(bar.progress!=progress) bar.progress=progress
        label.text=activity.getString(R.string.workshop_value,activity.getString(title),number)
    }

    private fun selection(action: (Int) -> Unit)=object: AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,id: Long) { if(position>=0) action(position) }
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
}
