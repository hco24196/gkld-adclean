# 模块自身不需要混淆；如果改成 minifyEnabled true，请保留下面规则
-keep class com.gkld.adclean.MainHook { *; }
-keep class com.gkld.adclean.hook.** { *; }
