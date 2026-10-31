package com.beautysdk.resources

/** 无IO的设置变更。只接收操作目标，禁止用旧全量快照覆盖其他类型。主题须先拆分。 */
object BeautyResourceEdits {
    @JvmStatic fun parameters(state:BeautyResourceState,category:ResourceCategory,changes:Map<String,Float>):BeautyResourceState {
        BeautyParameters.validate(changes)
        require(changes.keys.all { BeautyParameters.category(it)==category }) { "PARAMETER_CATEGORY_MISMATCH" }
        return state.copy(parameters=state.parameters+changes)
    }
    @JvmStatic fun disable(state:BeautyResourceState,slot:String,installed:List<InstalledResource>):BeautyResourceState {
        val descriptor=installed.firstOrNull { it.hash==state.selections[slot] && it.descriptor.slot==slot }?.descriptor
        val neutral=BeautyParameters.neutral.filterKeys { it in descriptor?.scope.orEmpty() }
        return state.copy(parameters=state.parameters+neutral,selections=state.selections+(slot to null))
    }
    @JvmStatic fun remove(state:BeautyResourceState,hash:String,installed:List<InstalledResource>):BeautyResourceState =
        state.selections.filterValues { it==hash }.keys.fold(state) { next,slot->disable(next,slot,installed) }
    @JvmStatic fun disableCategory(state:BeautyResourceState,category:ResourceCategory,installed:List<InstalledResource>):BeautyResourceState {
        val slots=installed.filter {it.descriptor.category==category}.map {it.descriptor.slot}
        val neutral=BeautyParameters.neutral.filterKeys {BeautyParameters.category(it)==category}
        return state.copy(parameters=state.parameters+neutral,selections=state.selections+slots.associateWith {null})
    }
}
