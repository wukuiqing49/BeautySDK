package com.beautysdk.distribution

/** 统一发行入口版本，供宿主诊断使用；不创建模型、不接管相机。 */
object BeautySdkDistribution {
    const val VERSION: String = BuildConfig.SDK_VERSION
}
