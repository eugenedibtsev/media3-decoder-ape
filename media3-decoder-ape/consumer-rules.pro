# Copyright (C) 2026 The media3-decoder-ape Authors
# Licensed under the Apache License, Version 2.0.
#
# The native library binds to these classes strictly by name:
#  - JNI_OnLoad (ape_jni.cc) looks both classes up with FindClass and binds the
#    native methods via RegisterNatives, so the class names and the native
#    method names must survive shrinking;
#  - the IO bridge (ape_io_bridge.cc) resolves the read callback with
#    GetMethodID("read", "(Ljava/nio/ByteBuffer;)I") on the ApeDecoderJni
#    instance, so that method must survive as well.
# Without these rules, R8-enabled consumers fail at native library load.

-keep class io.github.eugenedibtsev.media3.ape.ApeDecoderJni {
    native <methods>;
    int read(java.nio.ByteBuffer);
}

-keep class io.github.eugenedibtsev.media3.ape.ApeLibrary {
    native <methods>;
}

# The public API surface, kept by name for consumers that reach it through
# JNI name lookup rather than compiled references -- .NET for Android
# bindings resolve every bound Java type and member by name at runtime, and
# the .NET toolchain runs R8 over the app. Java/Kotlin consumers pay nothing:
# these classes are small and referenced code survives shrinking anyway.

-keep class io.github.eugenedibtsev.media3.ape.ApeSupport {
    public <methods>;
}

# Returned by ApeSupport as plain ExtractorsFactory; kept whole so the
# interface dispatch from bound code cannot be stripped mid-hierarchy.
-keep class io.github.eugenedibtsev.media3.ape.ApeExtractorsFactory {
    public <methods>;
}

-keep class io.github.eugenedibtsev.media3.ape.ApeExtractor {
    public <fields>;
    public <methods>;
}

-keep class io.github.eugenedibtsev.media3.ape.ApeStreamMetadata {
    public <fields>;
    public <methods>;
}

-keep class io.github.eugenedibtsev.media3.ape.ApeDecoderException {
    public <fields>;
    public <methods>;
}
