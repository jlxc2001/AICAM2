cmake_minimum_required(VERSION 3.10)
project(nanodetncnn)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-20260526-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(nanodetncnn SHARED nanodetncnn.cpp)

target_link_libraries(nanodetncnn
        ncnn
        android
        log)
