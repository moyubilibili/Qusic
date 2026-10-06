# Qusic 的 R8 保留规则
#
# 需要保留的只有「系统按名字反射创建」的类：
# Activity / Service / BroadcastReceiver。
# 其余全部允许裁剪和改名，压下来的体积很可观。

-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Application

# 自定义 View 若被 XML 引用需保留构造器；本项目 View 全部代码创建，无需保留。
# 但保险起见保留带 (Context, AttributeSet) 构造的 View
-keepclasseswithmembers class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# 序列化/枚举相关（本项目没用到，写上不影响）
-keepclassmembers enum * { *; }

# 不因为找不到可选类而报错
-dontwarn **
-dontnote **

# 保留行号，崩溃栈仍可定位（配合 mapping 文件）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
