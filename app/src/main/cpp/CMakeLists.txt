cmake_minimum_required(VERSION 3.18.1)
project(espflasher C)

set(PORT USER_DEFINED CACHE STRING "port type")
set(SERIAL_FLASHER_LOG_LEVEL INFO CACHE STRING "log level")

# 两个来源，任选其一：
#   1) 官方仓库（git submodule / clone 到 extern/esp-serial-flasher）
#   2) 工程内自带副本（app/src/main/cpp/esp-serial-flasher）
# 用 -DESP_FLASHER_SRC=vendored 可以强制走自带副本。
set(ESP_FLASHER_SRC "auto" CACHE STRING "upstream / vendored / auto")
set(UPSTREAM_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../extern/esp-serial-flasher")
if(ESP_FLASHER_SRC STREQUAL "upstream" OR (ESP_FLASHER_SRC STREQUAL "auto" AND
        EXISTS "${UPSTREAM_DIR}/CMakeLists.txt"))
    message(STATUS "esp-serial-flasher: 使用官方仓库 ${UPSTREAM_DIR}")
    add_subdirectory(${UPSTREAM_DIR} ${CMAKE_CURRENT_BINARY_DIR}/esp-serial-flasher-upstream)
else()
    message(STATUS "esp-serial-flasher: 使用工程内副本")
    add_subdirectory(esp-serial-flasher)
endif()

add_library(espflasher SHARED
        android_port.c
        jni_bridge.c)

target_link_libraries(espflasher
        flasher
        log
        android)

target_compile_options(espflasher PRIVATE -Wall -Wextra -Wno-unused-parameter)

# Android 15 部分设备是 16KB 内存页，native 库按 16KB 对齐，避免装上去加载失败
target_link_options(espflasher PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")
