# compose.ui.graphics gradient internals read reflectively by BrushSpec.from
# These rules are consumed automatically by AGP's R8/minification pipeline.

-keepclassmembers class androidx.compose.ui.graphics.LinearGradient {
    <fields>;
}
-keepclassmembers class androidx.compose.ui.graphics.RadialGradient {
    <fields>;
}
-keepclassmembers class androidx.compose.ui.graphics.SweepGradient {
    <fields>;
}
