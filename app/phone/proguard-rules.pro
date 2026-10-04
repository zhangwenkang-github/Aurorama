# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep class names of all classes for easy debugging (and fix navigation route checking)
-keepnames class com.zhangwenkang.cinefin.** { *; }

# W71 · PdfBox 的 JPX（JPEG2000）滤镜引用可选编解码器 com.gemalto.jp2.*，本工程不打包该依赖
# （debug 同样不存在，属已知可选路径）→ 只静默 R8 缺类告警，不影响其它 PDF 页面解码。
-dontwarn com.gemalto.jp2.**

# These classes are from okhttp and are not used in Android
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.*
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
