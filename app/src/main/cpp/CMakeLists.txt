cmake_minimum_required(VERSION 3.18)

project(plugin_template VERSION 1.0.0 LANGUAGES C CXX)

# For reproducible build
add_link_options("LINKER:--hash-style=gnu,--build-id=none")

set(RIME_PLUGINS "${RIME_PLUGINS}" CACHE STRING "librime plugins")
message(STATUS "Rime plugin: librime build root = ${RIME_BUILD_ROOT}")
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../../../lib" "${CMAKE_BINARY_DIR}/rime-lib")

add_library(plugin_template SHARED plugin_template.c)
target_compile_features(plugin_template PRIVATE cxx_std_17)
add_dependencies(plugin_template rime_librime)
# Make the runtime payload an explicit link input so Ninja builds the
# ExternalProject target when librime.so is missing.
set_property(TARGET plugin_template APPEND PROPERTY LINK_DEPENDS
        "${RIME_SOURCE_PREFIX}/lib/librime.so")

set(RIME_JNILIBS_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/generated/rimeJniLibs"
    CACHE PATH "Generated jniLibs directory for the bundled librime.so")
add_custom_command(TARGET plugin_template POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E make_directory
        "${RIME_JNILIBS_DIR}/${ANDROID_ABI}"
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${RIME_SOURCE_PREFIX}/lib/librime.so"
        "${RIME_JNILIBS_DIR}/${ANDROID_ABI}/librime.so")
