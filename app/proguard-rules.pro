# Droidance ProGuard 规则

# ADB shell app_process 通过完整类名启动后台虚拟空间 sidecar，发布构建不能混淆入口类。
-keep class com.droidance.core.device.agent.device.VirtualSpaceSidecarMain { *; }
-keep class com.droidance.core.device.agent.device.ShizukuShellService { *; }
-keep class com.droidance.core.device.agent.device.IShizukuShellService$Stub { *; }
-keep class com.droidance.core.device.agent.device.IShizukuShellService$Stub$Proxy { *; }
-keep class com.droidance.core.device.agent.device.VirtualSpaceSidecarService { *; }
-keep class com.droidance.core.device.agent.device.IVirtualSpaceSidecar { *; }
-keep class com.droidance.core.device.agent.device.IVirtualSpaceSidecar$Stub { *; }
-keep class com.droidance.core.device.agent.device.IVirtualSpaceSidecar$Stub$Proxy { *; }

# 保留 kotlinx.serialization 相关类
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.droidance.**$$serializer { *; }
-keepclassmembers class com.droidance.** {
    *** Companion;
}
-keepclasseswithmembers class com.droidance.** {
    kotlinx.serialization.KSerializer serializer(...);
}


# Resident Agent entrypoint invoked by the shell service.
-keep class com.droidance.runtime.RuntimeProcessHost { public static android.os.IBinder start(...); }
-keep class com.droidance.core.device.agent.device.IAgentRuntimeOwner$Stub { *; }
-keep class com.droidance.core.device.agent.device.IAgentRuntimeOwner$Stub$Proxy { *; }
-keep class com.droidance.agent.device.RuntimeDriverBinderReceiver { *; }

# OkHttp

-dontwarn okhttp3.**
-dontwarn okio.**

# Rhino initializes JavaScript built-ins through reflection. Keep the isolated interpreter intact.
-keep class org.mozilla.javascript.** { *; }
# Rhino also ships JVM-only JIT/JavaBeans integration. ProgramEngine always uses
# interpreted mode and safe objects without LiveConnect; these paths are unavailable.
-dontwarn jdk.dynalink.**
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
