# Xposed / LSPosed 相关：这些类运行时由框架提供，保留其名字便于排查
-keep class de.robv.android.xposed.** { *; }
-keep class com.mo.fakeloc.xposed.** { *; }

# 被 hook 的框架类引用（仅字符串/反射）
-dontwarn android.location.**
-dontwarn com.android.server.**

# 入口类必须保留（xposed_init 里按名字加载）
-keep class com.mo.fakeloc.xposed.XposedEntry { *; }
