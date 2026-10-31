# 测量完整可用SDK，防止空宿主没有调用功能而被R8裁掉，数字为保守全量口径。
-keep,allowoptimization class com.beautysdk.** { *; }
