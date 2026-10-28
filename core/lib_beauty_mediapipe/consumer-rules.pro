# MediaPipe 的 JNI 保留规则由其 AAR 提供。

# JNI 使用固定类名与方法名，防止 R8 重命名原生入口。
-keep class com.beautysdk.mediapipe.NativeRgbaConverter { native <methods>; }

-keep class com.beautysdk.mediapipe.NativeFaceMotion { native <methods>; }

-keep class com.beautysdk.mediapipe.NativeMaskConverter { native <methods>; }
-keep class com.beautysdk.mediapipe.NativeMaskMotion { native <methods>; }
