# Libreria JNI dell'app e whisper.cpp, scaricato a un tag fisso, verificato con SHA-256 e
# modificato da patch_whisper.cmake.
#
# ggml è compilato con un backend CPU per ogni famiglia di processori ARM (GGML_CPU_ALL_VARIANTS):
# all'avvio ggml_backend_load_all_from_path sceglie la variante migliore che il telefono supporta,
# così lo stesso APK gira sui core armv8.0 e sfrutta dotprod, fp16 e i8mm dove ci sono.
cmake_minimum_required(VERSION 3.22)
project(whisperwa C CXX)

include(FetchContent)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)

FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz
    URL_HASH SHA256=ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720
    PATCH_COMMAND ${CMAKE_COMMAND} -P ${CMAKE_CURRENT_SOURCE_DIR}/patch_whisper.cmake
)
FetchContent_MakeAvailable(whisper)

add_library(whisperwa SHARED whisper_jni.cpp)
target_link_libraries(whisperwa PRIVATE whisper ggml log)
