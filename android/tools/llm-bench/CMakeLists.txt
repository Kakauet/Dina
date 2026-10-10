# Desktop benchmark for the on-device LLM path (same llama.cpp and PromptCache as the app).
# Built and run by `.\dev.ps1 llm-bench`.
cmake_minimum_required(VERSION 3.22)
project(dina_llm_bench LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

set(LLAMA_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../third_party/llama.cpp")
add_subdirectory("${LLAMA_ROOT}" "${CMAKE_BINARY_DIR}/llama" EXCLUDE_FROM_ALL)

add_executable(llm_bench llm_bench.cpp)
target_include_directories(llm_bench PRIVATE "${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp")
target_link_libraries(llm_bench PRIVATE llama)

# The app's JNI library (same source, same PromptCache) for the desktop JVM: `.\dev.ps1 eval`
# loads it from JVM tests through the real NativeLlmEngine. Built only on request.
set(JDK_ROOT "$ENV{JAVA_HOME}")
add_library(dina_native SHARED EXCLUDE_FROM_ALL "${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp/dina_llm_jni.cpp")
target_include_directories(dina_native PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/desktop"
    "${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/cpp"
    "${JDK_ROOT}/include" "${JDK_ROOT}/include/win32")
target_link_libraries(dina_native PRIVATE llama)
target_link_options(dina_native PRIVATE -static -static-libgcc -static-libstdc++)
set_target_properties(dina_native PROPERTIES PREFIX "")  # dina_native.dll, as System.loadLibrary expects
