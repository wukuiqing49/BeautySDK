cmake_minimum_required(VERSION 3.22.1)
project(beauty_rgb_input LANGUAGES CXX)
add_library(beauty_rgb_input SHARED rgba_input.cpp face_motion.cpp mask_output.cpp mask_motion.cpp)
target_compile_features(beauty_rgb_input PRIVATE cxx_std_17)
# Debug 设备测试也使用优化后的像素循环；保留符号，便于定位原生错误。
target_compile_options(beauty_rgb_input PRIVATE -O3 -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(beauty_rgb_input PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

