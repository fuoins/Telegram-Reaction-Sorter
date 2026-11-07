# The module is deliberately not minified (see build.gradle). Keep everything if a
# downstream build enables R8, otherwise the Xposed entry class would be renamed.
-keep class io.github.fuoins.reactionsorter.HookEntry { *; }
-keep class io.github.fuoins.reactionsorter.** { *; }
-keepattributes *Annotation*
-dontwarn de.robv.android.xposed.**
