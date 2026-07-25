# Preserve generic signatures and runtime annotations consumed by Hilt, Room, and WorkManager.
# Their libraries provide the component-specific consumer rules; avoid broad app-level keeps.
-keepattributes Signature,*Annotation*

# MediaPipe's generated option types retain references to source-only AutoValue annotations. The
# concrete generated classes are packaged; these annotation classes are not needed at runtime.
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.auto.value.AutoValue$Builder
