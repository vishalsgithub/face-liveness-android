# JNI functions are looked up by class and method name.
-keep class com.xpertnest.liveness.NativeEngine { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
