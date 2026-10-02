#
# Copyright (C) 2026 The media3-decoder-ape Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

cmake_minimum_required(VERSION 3.21.0 FATAL_ERROR)

project(libapeJNI CXX)

set(CMAKE_CXX_STANDARD 11)

set(ape_jni_root "${CMAKE_CURRENT_SOURCE_DIR}")
# Populated by download_maclib.sh; not vendored.
set(maclib_root "${ape_jni_root}/maclib")

set(maclib_sources
    "${maclib_root}/Source/MACLib/APECompress.cpp"
    "${maclib_root}/Source/MACLib/APECompressCore.cpp"
    "${maclib_root}/Source/MACLib/APECompressCreate.cpp"
    "${maclib_root}/Source/MACLib/APEDecompress.cpp"
    "${maclib_root}/Source/MACLib/APEDecompressCore.cpp"
    "${maclib_root}/Source/MACLib/APEHeader.cpp"
    "${maclib_root}/Source/MACLib/APEInfo.cpp"
    "${maclib_root}/Source/MACLib/APELink.cpp"
    "${maclib_root}/Source/MACLib/APETag.cpp"
    "${maclib_root}/Source/MACLib/BitArray.cpp"
    "${maclib_root}/Source/MACLib/FloatTransform.cpp"
    "${maclib_root}/Source/MACLib/Interim.cpp"
    "${maclib_root}/Source/MACLib/MACLib.cpp"
    "${maclib_root}/Source/MACLib/MACProgressHelper.cpp"
    "${maclib_root}/Source/MACLib/MD5.cpp"
    "${maclib_root}/Source/MACLib/NewPredictor.cpp"
    "${maclib_root}/Source/MACLib/NNFilter.cpp"
    "${maclib_root}/Source/MACLib/NNFilterGeneric.cpp"
    "${maclib_root}/Source/MACLib/Prepare.cpp"
    "${maclib_root}/Source/MACLib/UnBitArray.cpp"
    "${maclib_root}/Source/MACLib/UnBitArrayBase.cpp"
    "${maclib_root}/Source/MACLib/WAVInputSource.cpp"
    "${maclib_root}/Source/MACLib/Old/APEDecompressCoreOld.cpp"
    "${maclib_root}/Source/MACLib/Old/APEDecompressOld.cpp"
    "${maclib_root}/Source/MACLib/Old/AntiPredictorExtraHighOld.cpp"
    "${maclib_root}/Source/MACLib/Old/AntiPredictorFastOld.cpp"
    "${maclib_root}/Source/MACLib/Old/AntiPredictorHighOld.cpp"
    "${maclib_root}/Source/MACLib/Old/AntiPredictorNormalOld.cpp"
    "${maclib_root}/Source/MACLib/Old/AntiPredictorOld.cpp"
    "${maclib_root}/Source/MACLib/Old/NewPredictorOld.cpp"
    "${maclib_root}/Source/MACLib/Old/UnBitArrayOld.cpp"
    "${maclib_root}/Source/MACLib/Old/UnMACOld.cpp"
    "${maclib_root}/Source/Shared/BufferIO.cpp"
    "${maclib_root}/Source/Shared/CharacterHelper.cpp"
    "${maclib_root}/Source/Shared/CircleBuffer.cpp"
    "${maclib_root}/Source/Shared/CPUFeatures.cpp"
    "${maclib_root}/Source/Shared/CRC.cpp"
    "${maclib_root}/Source/Shared/GlobalFunctions.cpp"
    "${maclib_root}/Source/Shared/MemoryIO.cpp"
    "${maclib_root}/Source/Shared/Semaphore.cpp"
    "${maclib_root}/Source/Shared/StdLibFileIO.cpp"
    "${maclib_root}/Source/Shared/Thread.cpp"
    "${maclib_root}/Source/Shared/WholeFileIO.cpp")

# SIMD kernels per ABI; MACLib selects implementations at runtime through
# CPUFeatures, so building the wider variants is safe.
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    list(APPEND maclib_sources "${maclib_root}/Source/MACLib/NNFilterNeon.cpp")
    if(ANDROID_ABI STREQUAL "armeabi-v7a")
        set_source_files_properties("${maclib_root}/Source/MACLib/NNFilterNeon.cpp"
            PROPERTIES COMPILE_FLAGS "-mfpu=neon")
    endif()
elseif(ANDROID_ABI STREQUAL "x86_64" OR ANDROID_ABI STREQUAL "x86")
    list(APPEND maclib_sources
        "${maclib_root}/Source/MACLib/NNFilterSSE2.cpp"
        "${maclib_root}/Source/MACLib/NNFilterSSE4.1.cpp"
        "${maclib_root}/Source/MACLib/NNFilterAVX2.cpp"
        "${maclib_root}/Source/MACLib/NNFilterAVX512.cpp")
    set_source_files_properties("${maclib_root}/Source/MACLib/NNFilterSSE2.cpp"
        PROPERTIES COMPILE_FLAGS "-msse2")
    set_source_files_properties("${maclib_root}/Source/MACLib/NNFilterSSE4.1.cpp"
        PROPERTIES COMPILE_FLAGS "-msse4.1")
    set_source_files_properties("${maclib_root}/Source/MACLib/NNFilterAVX2.cpp"
        PROPERTIES COMPILE_FLAGS "-mavx2")
    set_source_files_properties("${maclib_root}/Source/MACLib/NNFilterAVX512.cpp"
        PROPERTIES COMPILE_FLAGS "-mavx512dq -mavx512bw")
endif()

# MACLib declares overrides without the keyword, which clang warns about once
# per translation unit that includes the header -- around a thousand lines per
# build, drowning every other diagnostic. It is upstream code this project does
# not patch (see CONTRIBUTING.md), so the warning is noise here. Silenced for
# MACLib's own sources only; ape_jni.cc and ape_io_bridge.cc keep every warning.
set_source_files_properties(${maclib_sources} PROPERTIES
    COMPILE_OPTIONS "-Wno-inconsistent-missing-override")

add_library(apeJNI
            SHARED
            ape_jni.cc
            ape_io_bridge.cc
            ${maclib_sources})

# MACLib throws C++ exceptions and uses dynamic_cast.
target_compile_options(apeJNI PRIVATE -fexceptions -frtti)

# 16 KB page size support, required on Android 15+ devices that use 16 KB
# pages (and by Google Play for apps targeting SDK 35+). NDK r27 still links
# with 4 KB segment alignment by default; without this the library either
# fails to load or runs in a compatibility mode that raises a system warning.
# Harmless on armeabi-v7a, which has no 16 KB variant.
target_link_options(apeJNI PRIVATE "-Wl,-z,max-page-size=16384")

target_include_directories(apeJNI PRIVATE
    "${maclib_root}/Source/Shared"
    "${maclib_root}/Source/MACLib")

find_library(android_log_lib log)
target_link_libraries(apeJNI PRIVATE ${android_log_lib})
