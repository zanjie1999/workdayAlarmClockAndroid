# 语音唤醒插件

# 宿主用 DexClassLoader 按类名反射实例化，混淆后就找不到了
-keep class com.zyyme.workdayalarmclock.voicewake.WakeWordEngine { *; }

# 宿主和插件共用的接口/常量
-keep interface com.zyyme.workdayalarmclock.wake.WakeWordHost { *; }
-keep interface com.zyyme.workdayalarmclock.wake.WakeWordHost$Listener { *; }
-keep class com.zyyme.workdayalarmclock.wake.WakeWordDefaults { *; }

# sherpa-onnx 的 JNI 是 Java_com_k2fsa_sherpa_onnx_xxx 名字绑定，
# 类名/方法名一旦被混淆就会 UnsatisfiedLinkError
-keep class com.k2fsa.sherpa.onnx.** { *; }
