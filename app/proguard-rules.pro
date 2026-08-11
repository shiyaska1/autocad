# Keep Room entities/DAO metadata (Room's generated *_Impl classes live here too)
-keep class com.sketchdxf.app.data.** { *; }

# ViewModels are constructed reflectively by androidx's AndroidViewModelFactory
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(...); }
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }

# ML Kit loads its models and native pipelines reflectively.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.odml.** { *; }
-dontwarn com.google.mlkit.**

# Coroutines
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**
