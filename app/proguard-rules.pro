# 小组件点击动作通过类名反射实例化，release 构建下不能被混淆/裁剪掉
-keep class com.lighttodo.app.widget.** { *; }
