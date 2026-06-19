cmake_minimum_required(VERSION 3.22.1)
project(vehicle_ncnn)

set(NCNN_VERSION "20260526" CACHE STRING "ncnn prebuilt version")
set(NCNN_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../jni/ncnn-${NCNN_VERSION}-android")

if(NOT EXISTS "${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn/ncnnConfig.cmake")
    message(FATAL_ERROR "ncnn not found for ${ANDROID_ABI}. Run scripts/setup_deps.sh first. Expected: ${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn/ncnnConfig.cmake")
endif()

set(ncnn_DIR "${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

add_library(vehicle_ncnn SHARED native-lib.cpp)

target_link_libraries(vehicle_ncnn
        ncnn
        android
        jnigraphics
        log)
