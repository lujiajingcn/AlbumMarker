# Add project specific ProGuard rules here.
# MediaPipe LLM inference keeps its API via reflection-free JNI bindings;
# the library ships its own consumer rules, so no extra keeps are required.
-dontwarn com.google.mediapipe.**
