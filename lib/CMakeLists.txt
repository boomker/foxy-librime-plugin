cmake_minimum_required(VERSION 3.18)

project(rime_plugin_dependencies LANGUAGES C CXX)

include(ExternalProject)

set(RIME_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/librime")
set(RIME_PATCH_DIR "${CMAKE_CURRENT_LIST_DIR}/patches")
execute_process(
    COMMAND git -C "${RIME_SOURCE_DIR}" rev-parse HEAD
    OUTPUT_VARIABLE RIME_SOURCE_REVISION
    OUTPUT_STRIP_TRAILING_WHITESPACE
    RESULT_VARIABLE RIME_SOURCE_REVISION_RESULT)
if(NOT RIME_SOURCE_REVISION_RESULT EQUAL 0 OR RIME_SOURCE_REVISION STREQUAL "")
    message(FATAL_ERROR "Failed to read the librime source revision")
endif()
execute_process(
    COMMAND git -C "${RIME_SOURCE_DIR}" rev-parse --git-path HEAD
    OUTPUT_VARIABLE RIME_GIT_HEAD_FILE
    OUTPUT_STRIP_TRAILING_WHITESPACE
    RESULT_VARIABLE RIME_GIT_HEAD_RESULT)
if(NOT RIME_GIT_HEAD_RESULT EQUAL 0)
    message(FATAL_ERROR "Failed to locate the librime git HEAD file")
endif()
get_filename_component(RIME_GIT_HEAD_FILE "${RIME_GIT_HEAD_FILE}" ABSOLUTE
    BASE_DIR "${RIME_SOURCE_DIR}")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${RIME_GIT_HEAD_FILE}")
set(RIME_BUILD_ROOT "${CMAKE_BINARY_DIR}/rime" CACHE PATH
    "Shared librime build root; one subdirectory is used per Android ABI")
set(RIME_COMMON_BUILD_ROOT "${RIME_BUILD_ROOT}/common")
if(ANDROID_ABI)
    set(RIME_BUILD_ROOT "${RIME_BUILD_ROOT}/${ANDROID_ABI}")
endif()
set(RIME_STAMP_ROOT "${RIME_BUILD_ROOT}/stamps")
set(RIME_TMP_ROOT "${RIME_BUILD_ROOT}/tmp")
set(RIME_LIBRIME_BUILD_SCRIPT "${RIME_BUILD_ROOT}/build-librime.cmake")
set(RIME_PREFIX "${RIME_BUILD_ROOT}/prefix")
set(RIME_SOURCE_COPY "${RIME_COMMON_BUILD_ROOT}/source")
set(RIME_BOOST_ROOT "${RIME_COMMON_BUILD_ROOT}/boost")
set(RIME_BOOST_BUILD_ROOT "${RIME_BUILD_ROOT}/boost")
set(RIME_BOOST_PREPARE_SCRIPT "${RIME_COMMON_BUILD_ROOT}/prepare-boost.cmake")
set(RIME_BOOST_BOOTSTRAP_SCRIPT "${RIME_COMMON_BUILD_ROOT}/bootstrap-boost.cmake")
set(RIME_BOOST_ARCHIVE "${CMAKE_CURRENT_LIST_DIR}/../boost_1_89_0.tar.gz"
    CACHE FILEPATH "Path to the Boost 1.89.0 source archive")
if(NOT EXISTS "${RIME_BOOST_ARCHIVE}")
    set(RIME_BOOST_ARCHIVE "${RIME_COMMON_BUILD_ROOT}/boost_1_89_0.tar.gz")
    if(NOT EXISTS "${RIME_BOOST_ARCHIVE}")
        message(STATUS "Boost archive not found locally, downloading...")
        file(DOWNLOAD
            "https://archives.boost.io/release/1.89.0/source/boost_1_89_0.tar.gz"
            "${RIME_BOOST_ARCHIVE}"
            EXPECTED_HASH SHA256=9de758db755e8330a01d995b0a24d09798048400ac25c03fc5ea9be364b13c93
            SHOW_PROGRESS)
    endif()
endif()
set(RIME_BOOST_B2 "${RIME_BOOST_ROOT}/tools/build/src/engine/b2")
set(RIME_BOOST_USER_CONFIG "${RIME_BOOST_BUILD_ROOT}/user-config.jam")
find_program(RIME_BASH bash REQUIRED)
file(MAKE_DIRECTORY "${RIME_COMMON_BUILD_ROOT}" "${RIME_BUILD_ROOT}"
    "${RIME_BOOST_BUILD_ROOT}")
file(WRITE "${RIME_BOOST_USER_CONFIG}"
    "using clang : android : ${CMAKE_CXX_COMPILER} ;\n")
configure_file("${CMAKE_CURRENT_LIST_DIR}/prepare-boost.cmake.in"
    "${RIME_BOOST_PREPARE_SCRIPT}" @ONLY)
configure_file("${CMAKE_CURRENT_LIST_DIR}/bootstrap-boost.cmake.in"
    "${RIME_BOOST_BOOTSTRAP_SCRIPT}" @ONLY)
set(RIME_BOOST_LIBS container iostreams random regex)

if(NOT DEFINED RIME_PLUGINS)
    set(RIME_PLUGINS "hchunhui/librime-lua lotem/librime-octagram rime/librime-predict")
endif()
set(RIME_PLUGIN_ENV "${RIME_PLUGINS}")
set(RIME_GIT_VERBOSE OFF CACHE BOOL "Show verbose output while fetching librime plugins")
set(RIME_GIT_ENV)
if(RIME_GIT_VERBOSE)
    list(APPEND RIME_GIT_ENV GIT_TRACE=1 GIT_CURL_VERBOSE=1)
endif()

set(RIME_ROOT_PATCHES
    "${RIME_PATCH_DIR}/librime.patch"
    "${RIME_PATCH_DIR}/librime-tools.patch"
    "${RIME_PATCH_DIR}/glog.patch"
    "${RIME_PATCH_DIR}/opencc-tools.patch")
set(RIME_PLUGIN_PATCHES
    "${RIME_PATCH_DIR}/librime-lua.patch"
    "${RIME_PATCH_DIR}/librime-octagram.patch"
    "${RIME_PATCH_DIR}/librime-predict.patch")
set(RIME_PATCH_FILES
    ${RIME_ROOT_PATCHES}
    ${RIME_PLUGIN_PATCHES}
    "${RIME_PATCH_DIR}/lua.patch")
file(SHA256 "${RIME_PATCH_DIR}/lua.patch" RIME_LUA_PATCH_HASH)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    ${RIME_PATCH_FILES})

file(MAKE_DIRECTORY "${RIME_BUILD_ROOT}")
set(RIME_SOURCE_REVISION_STAMP "${RIME_COMMON_BUILD_ROOT}/librime-source-revision.txt")
set(RIME_PLUGIN_STAMP "${RIME_COMMON_BUILD_ROOT}/librime-plugins.txt")
set(RIME_PATCH_STAMP "${RIME_COMMON_BUILD_ROOT}/librime-patches.txt")
set(RIME_APPLIED_PATCH_DIR "${RIME_COMMON_BUILD_ROOT}/applied-patches")

set(RIME_DESIRED_PATCH_STATE "")
foreach(patch_file IN LISTS RIME_ROOT_PATCHES RIME_PLUGIN_PATCHES)
    file(SHA256 "${patch_file}" patch_hash)
    get_filename_component(patch_name "${patch_file}" NAME)
    string(APPEND RIME_DESIRED_PATCH_STATE "${patch_name}|${patch_hash}\n")
endforeach()

file(LOCK "${RIME_COMMON_BUILD_ROOT}/.prepare.lock" GUARD PROCESS TIMEOUT 1800)
set(RIME_CURRENT_SOURCE_REVISION "")
if(EXISTS "${RIME_SOURCE_REVISION_STAMP}")
    file(READ "${RIME_SOURCE_REVISION_STAMP}" RIME_CURRENT_SOURCE_REVISION)
endif()
set(RIME_CURRENT_PLUGINS "")
if(EXISTS "${RIME_PLUGIN_STAMP}")
    file(READ "${RIME_PLUGIN_STAMP}" RIME_CURRENT_PLUGINS)
endif()
set(RIME_CURRENT_PATCH_STATE "")
if(EXISTS "${RIME_PATCH_STAMP}")
    file(READ "${RIME_PATCH_STAMP}" RIME_CURRENT_PATCH_STATE)
endif()
function(rime_apply_plugin_patch plugin_name patch_name)
    if(EXISTS "${RIME_SOURCE_COPY}/plugins/${plugin_name}")
        execute_process(
            COMMAND git apply "${RIME_PATCH_DIR}/${patch_name}"
            WORKING_DIRECTORY "${RIME_SOURCE_COPY}/plugins/${plugin_name}"
            RESULT_VARIABLE patch_result)
        if(NOT patch_result EQUAL 0)
            message(FATAL_ERROR "Failed to apply plugin patch: ${patch_name}")
        endif()
    endif()
endfunction()

function(rime_patch_dir out patch_name)
    if(patch_name STREQUAL "librime-lua.patch")
        set(patch_dir "${RIME_SOURCE_COPY}/plugins/lua")
    elseif(patch_name STREQUAL "librime-octagram.patch")
        set(patch_dir "${RIME_SOURCE_COPY}/plugins/octagram")
    elseif(patch_name STREQUAL "librime-predict.patch")
        set(patch_dir "${RIME_SOURCE_COPY}/plugins/predict")
    else()
        set(patch_dir "${RIME_SOURCE_COPY}")
    endif()
    set(${out} "${patch_dir}" PARENT_SCOPE)
endfunction()

macro(rime_full_prepare)
    file(REMOVE_RECURSE "${RIME_SOURCE_COPY}")
    file(COPY "${RIME_SOURCE_DIR}/" DESTINATION "${RIME_SOURCE_COPY}")
    # A submodule's .git file points into the parent checkout and is invalid here.
    file(REMOVE "${RIME_SOURCE_COPY}/.git")
    execute_process(
        COMMAND git init -q
        WORKING_DIRECTORY "${RIME_SOURCE_COPY}"
        RESULT_VARIABLE git_init_result)
    if(NOT git_init_result EQUAL 0)
        message(FATAL_ERROR "Failed to initialize copied librime source")
    endif()
    separate_arguments(RIME_PLUGIN_ARGS UNIX_COMMAND "${RIME_PLUGINS}")
    execute_process(
        COMMAND ${CMAKE_COMMAND} -E env ${RIME_GIT_ENV}
            "${RIME_BASH}" "${RIME_SOURCE_COPY}/install-plugins.sh" ${RIME_PLUGIN_ARGS}
        WORKING_DIRECTORY "${RIME_SOURCE_COPY}"
        RESULT_VARIABLE plugin_result)
    if(NOT plugin_result EQUAL 0)
        message(FATAL_ERROR "Failed to install librime plugins (exit ${plugin_result})")
    endif()

    foreach(plugin_slug IN LISTS RIME_PLUGIN_ARGS)
        string(REGEX REPLACE "@.*$" "" plugin_slug_base "${plugin_slug}")
        get_filename_component(plugin_project "${plugin_slug_base}" NAME)
        string(REGEX REPLACE "^librime-" "" plugin_name "${plugin_project}")
        set(plugin_dir "${RIME_SOURCE_COPY}/plugins/${plugin_name}")
        if(EXISTS "${plugin_dir}/.git")
            execute_process(
                COMMAND git submodule update --init --recursive
                WORKING_DIRECTORY "${plugin_dir}"
                RESULT_VARIABLE submodule_result
                OUTPUT_VARIABLE submodule_output
                ERROR_VARIABLE submodule_error)
            if(NOT submodule_result EQUAL 0)
                message(FATAL_ERROR "Failed to initialize submodules for plugin: ${plugin_slug}\n${submodule_output}\n${submodule_error}")
            endif()
        endif()
    endforeach()

    foreach(patch_file IN LISTS RIME_ROOT_PATCHES)
        execute_process(
            COMMAND git apply "${patch_file}"
            WORKING_DIRECTORY "${RIME_SOURCE_COPY}"
            RESULT_VARIABLE patch_result)
        if(NOT patch_result EQUAL 0)
            message(FATAL_ERROR "Failed to apply librime patch: ${patch_file}")
        endif()
    endforeach()

    rime_apply_plugin_patch(lua librime-lua.patch)
    rime_apply_plugin_patch(octagram librime-octagram.patch)
    rime_apply_plugin_patch(predict librime-predict.patch)

    file(MAKE_DIRECTORY "${RIME_APPLIED_PATCH_DIR}")
    foreach(patch_file IN LISTS RIME_ROOT_PATCHES RIME_PLUGIN_PATCHES)
        get_filename_component(patch_name "${patch_file}" NAME)
        file(COPY "${patch_file}" DESTINATION "${RIME_APPLIED_PATCH_DIR}")
    endforeach()
    file(WRITE "${RIME_SOURCE_REVISION_STAMP}" "${RIME_SOURCE_REVISION}")
    file(WRITE "${RIME_PLUGIN_STAMP}" "${RIME_PLUGINS}")
    file(WRITE "${RIME_PATCH_STAMP}" "${RIME_DESIRED_PATCH_STATE}")
endmacro()

set(RIME_NEEDS_FULL_PREPARE FALSE)
if(NOT EXISTS "${RIME_SOURCE_COPY}/CMakeLists.txt"
    OR NOT EXISTS "${RIME_SOURCE_REVISION_STAMP}"
    OR NOT EXISTS "${RIME_PLUGIN_STAMP}"
    OR NOT EXISTS "${RIME_PATCH_STAMP}"
    OR NOT EXISTS "${RIME_APPLIED_PATCH_DIR}")
    set(RIME_NEEDS_FULL_PREPARE TRUE)
elseif(NOT RIME_CURRENT_PLUGINS STREQUAL RIME_PLUGINS)
    set(RIME_NEEDS_FULL_PREPARE TRUE)
elseif(NOT RIME_CURRENT_PATCH_STATE STREQUAL RIME_DESIRED_PATCH_STATE
    AND NOT RIME_CURRENT_SOURCE_REVISION STREQUAL RIME_SOURCE_REVISION)
    set(RIME_NEEDS_FULL_PREPARE TRUE)
endif()

if(RIME_NEEDS_FULL_PREPARE)
    rime_full_prepare()
elseif(NOT RIME_CURRENT_SOURCE_REVISION STREQUAL RIME_SOURCE_REVISION)
    execute_process(
        COMMAND git -C "${RIME_SOURCE_DIR}" diff --name-status --find-renames
            "${RIME_CURRENT_SOURCE_REVISION}" "${RIME_SOURCE_REVISION}"
        OUTPUT_VARIABLE RIME_REVISION_DIFF
        RESULT_VARIABLE RIME_REVISION_DIFF_RESULT)
    if(NOT RIME_REVISION_DIFF_RESULT EQUAL 0
        OR RIME_REVISION_DIFF MATCHES "(^|\n)[AMD]\\tdeps/"
        OR RIME_REVISION_DIFF MATCHES "(^|\n)[AMD]\\t\\.gitmodules")
        set(RIME_NEEDS_FULL_PREPARE TRUE)
    else()
        string(REPLACE "\r" "" RIME_REVISION_DIFF "${RIME_REVISION_DIFF}")
        string(REPLACE "\n" ";" RIME_REVISION_LINES "${RIME_REVISION_DIFF}")
        set(RIME_CHANGED_PATHS "")
        foreach(diff_line IN LISTS RIME_REVISION_LINES)
            if(diff_line STREQUAL "")
                continue()
            endif()
            string(REPLACE "\t" ";" diff_parts "${diff_line}")
            list(GET diff_parts 0 diff_status)
            list(GET diff_parts 1 diff_path)
            if(diff_status MATCHES "^R")
                list(GET diff_parts 2 diff_new_path)
                file(REMOVE "${RIME_SOURCE_COPY}/${diff_path}")
                set(diff_path "${diff_new_path}")
            elseif(diff_status MATCHES "^D")
                file(REMOVE "${RIME_SOURCE_COPY}/${diff_path}")
                continue()
            endif()
            get_filename_component(diff_dir "${diff_path}" DIRECTORY)
            if(diff_dir)
                file(MAKE_DIRECTORY "${RIME_SOURCE_COPY}/${diff_dir}")
                file(COPY "${RIME_SOURCE_DIR}/${diff_path}"
                    DESTINATION "${RIME_SOURCE_COPY}/${diff_dir}")
            else()
                file(COPY "${RIME_SOURCE_DIR}/${diff_path}"
                    DESTINATION "${RIME_SOURCE_COPY}")
            endif()
            list(APPEND RIME_CHANGED_PATHS "${diff_path}")
        endforeach()

        # A patch only needs to be re-applied when an upstream file it touches
        # changed.  This keeps HEAD updates incremental.
        foreach(patch_file IN LISTS RIME_ROOT_PATCHES)
            file(STRINGS "${patch_file}" patch_lines)
            set(patch_includes)
            foreach(patch_line IN LISTS patch_lines)
                if(patch_line MATCHES "^\\+\\+\\+ b/(.+)$")
                    set(patched_path "${CMAKE_MATCH_1}")
                    list(FIND RIME_CHANGED_PATHS "${patched_path}" changed_index)
                    if(NOT changed_index EQUAL -1)
                        list(APPEND patch_includes "--include=${patched_path}")
                    endif()
                endif()
            endforeach()
            if(patch_includes)
                execute_process(
                    COMMAND git apply ${patch_includes} "${patch_file}"
                    WORKING_DIRECTORY "${RIME_SOURCE_COPY}"
                    RESULT_VARIABLE reapply_result)
                if(NOT reapply_result EQUAL 0)
                    set(RIME_NEEDS_FULL_PREPARE TRUE)
                    break()
                endif()
            endif()
        endforeach()
        if(NOT RIME_NEEDS_FULL_PREPARE)
            file(WRITE "${RIME_SOURCE_REVISION_STAMP}" "${RIME_SOURCE_REVISION}")
        endif()
    endif()
    if(RIME_NEEDS_FULL_PREPARE)
        rime_full_prepare()
    endif()
elseif(NOT RIME_CURRENT_PATCH_STATE STREQUAL RIME_DESIRED_PATCH_STATE)
    string(REPLACE "\n" ";" RIME_DESIRED_PATCH_LINES "${RIME_DESIRED_PATCH_STATE}")
    string(REPLACE "\n" ";" RIME_CURRENT_PATCH_LINES "${RIME_CURRENT_PATCH_STATE}")
    foreach(old_line IN LISTS RIME_CURRENT_PATCH_LINES)
        if(old_line STREQUAL "")
            continue()
        endif()
        string(REPLACE "|" ";" old_parts "${old_line}")
        list(GET old_parts 0 old_name)
        list(GET old_parts 1 old_hash)
        set(new_hash "")
        foreach(new_line IN LISTS RIME_DESIRED_PATCH_LINES)
            if(new_line STREQUAL "")
                continue()
            endif()
            string(REPLACE "|" ";" new_parts "${new_line}")
            list(GET new_parts 0 new_name)
            if(new_name STREQUAL old_name)
                list(GET new_parts 1 new_hash)
            endif()
        endforeach()
        if(NOT new_hash STREQUAL old_hash)
            rime_patch_dir(patch_dir "${old_name}")
            execute_process(
                COMMAND git apply -R "${RIME_APPLIED_PATCH_DIR}/${old_name}"
                WORKING_DIRECTORY "${patch_dir}"
                RESULT_VARIABLE reverse_result)
            if(NOT reverse_result EQUAL 0)
                set(RIME_NEEDS_FULL_PREPARE TRUE)
                break()
            endif()
        endif()
    endforeach()
    if(NOT RIME_NEEDS_FULL_PREPARE)
        foreach(new_line IN LISTS RIME_DESIRED_PATCH_LINES)
            if(new_line STREQUAL "")
                continue()
            endif()
            string(REPLACE "|" ";" new_parts "${new_line}")
            list(GET new_parts 0 new_name)
            list(GET new_parts 1 new_hash)
            set(old_hash "")
            foreach(old_line IN LISTS RIME_CURRENT_PATCH_LINES)
                if(old_line STREQUAL "")
                    continue()
                endif()
                string(REPLACE "|" ";" old_parts "${old_line}")
                list(GET old_parts 0 old_name)
                if(old_name STREQUAL new_name)
                    list(GET old_parts 1 old_hash)
                endif()
            endforeach()
            if(NOT new_hash STREQUAL old_hash)
                rime_patch_dir(patch_dir "${new_name}")
                set(new_patch_path "")
                foreach(candidate IN LISTS RIME_ROOT_PATCHES RIME_PLUGIN_PATCHES)
                    get_filename_component(candidate_name "${candidate}" NAME)
                    if(candidate_name STREQUAL new_name)
                        set(new_patch_path "${candidate}")
                    endif()
                endforeach()
                execute_process(
                    COMMAND git apply "${new_patch_path}"
                    WORKING_DIRECTORY "${patch_dir}"
                    RESULT_VARIABLE apply_result)
                if(NOT apply_result EQUAL 0)
                    set(RIME_NEEDS_FULL_PREPARE TRUE)
                    break()
                endif()
            endif()
        endforeach()
    endif()
    if(RIME_NEEDS_FULL_PREPARE)
        rime_full_prepare()
    else()
        file(MAKE_DIRECTORY "${RIME_APPLIED_PATCH_DIR}")
        foreach(patch_file IN LISTS RIME_ROOT_PATCHES RIME_PLUGIN_PATCHES)
            get_filename_component(patch_name "${patch_file}" NAME)
            file(COPY "${patch_file}" DESTINATION "${RIME_APPLIED_PATCH_DIR}")
        endforeach()
        file(WRITE "${RIME_PATCH_STAMP}" "${RIME_DESIRED_PATCH_STATE}")
    endif()
endif()
file(LOCK "${RIME_COMMON_BUILD_ROOT}/.prepare.lock" RELEASE)

# The glog patch changes how glog is built, so its per-ABI build tree must be
# invalidated when the patch changes.  The dependency markers only trigger an
# ExternalProject build when the installed library is missing, so remove both
# the build stamps and the installed artifact here.
file(SHA256 "${RIME_PATCH_DIR}/glog.patch" RIME_GLOG_PATCH_HASH)
set(RIME_GLOG_PATCH_STAMP "${RIME_BUILD_ROOT}/glog-patch-hash.txt")
set(RIME_CURRENT_GLOG_PATCH_HASH "")
if(EXISTS "${RIME_GLOG_PATCH_STAMP}")
    file(READ "${RIME_GLOG_PATCH_STAMP}" RIME_CURRENT_GLOG_PATCH_HASH)
endif()
if(NOT RIME_CURRENT_GLOG_PATCH_HASH STREQUAL RIME_GLOG_PATCH_HASH)
    file(REMOVE_RECURSE "${RIME_BUILD_ROOT}/build-glog" "${RIME_STAMP_ROOT}/glog")
    file(REMOVE "${RIME_PREFIX}/lib/libglog.a")
    file(REMOVE_RECURSE "${RIME_PREFIX}/include/glog")
    file(WRITE "${RIME_GLOG_PATCH_STAMP}" "${RIME_GLOG_PATCH_HASH}")
endif()

# Remove artifacts from the old static-librime and CLI-tools builds.  Android
# only consumes librime.so; the tools are not packaged.
file(GLOB RIME_STALE_ARTIFACTS
    "${RIME_PREFIX}/lib/librime.a"
    "${RIME_BUILD_ROOT}/build-librime/lib/librime.a"
    "${RIME_PREFIX}/bin/rime_*"
    "${RIME_PREFIX}/bin/opencc*")
if(RIME_STALE_ARTIFACTS)
    file(REMOVE ${RIME_STALE_ARTIFACTS})
endif()

include(ProcessorCount)
ProcessorCount(RIME_CORE_COUNT)
if(RIME_CORE_COUNT EQUAL 0)
    set(RIME_CORE_COUNT 4)
endif()
math(EXPR RIME_PARALLEL_JOBS "${RIME_CORE_COUNT} - 2")
if(RIME_PARALLEL_JOBS LESS 1)
    set(RIME_PARALLEL_JOBS 1)
endif()

set(RIME_DEP_ARGS
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_VERBOSE_MAKEFILE=OFF
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON
    -DCMAKE_C_FLAGS_RELEASE=-Oz
    -DCMAKE_CXX_FLAGS_RELEASE=-Oz
    -DCMAKE_SHARED_LINKER_FLAGS_RELEASE=-Wl,--gc-sections
    -DCMAKE_MODULE_LINKER_FLAGS_RELEASE=-Wl,--gc-sections
    -DCMAKE_EXE_LINKER_FLAGS_RELEASE=-Wl,--gc-sections
    -DCMAKE_INSTALL_PREFIX=${RIME_PREFIX}
    -DBUILD_SHARED_LIBS=OFF
    -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
    -DANDROID_ABI=${ANDROID_ABI}
    -DANDROID_PLATFORM=${ANDROID_PLATFORM})

ExternalProject_Add(rime_boost
    DOWNLOAD_COMMAND ${CMAKE_COMMAND} -P "${RIME_BOOST_PREPARE_SCRIPT}"
    BINARY_DIR "${RIME_BOOST_BUILD_ROOT}"
    STAMP_DIR "${RIME_STAMP_ROOT}/boost"
    TMP_DIR "${RIME_TMP_ROOT}/boost"
    UPDATE_COMMAND ""
    CONFIGURE_HANDLED_BY_BUILD TRUE
    CONFIGURE_COMMAND ${CMAKE_COMMAND} -P "${RIME_BOOST_BOOTSTRAP_SCRIPT}"
    BUILD_COMMAND ${RIME_BASH} -c
        "cd '${RIME_BOOST_ROOT}' && '${RIME_BOOST_B2}' '-j${RIME_PARALLEL_JOBS}' '--build-dir=${RIME_BOOST_BUILD_ROOT}/bin.v2' '--stagedir=${RIME_BOOST_BUILD_ROOT}/stage' variant=release link=static threading=multi target-os=android toolset=clang-android '--user-config=${RIME_BOOST_USER_CONFIG}' 'cflags=--target=${CMAKE_C_COMPILER_TARGET}' 'cxxflags=--target=${CMAKE_CXX_COMPILER_TARGET}' --with-container --with-iostreams --with-random --with-regex stage 1>&2"
    INSTALL_COMMAND ${CMAKE_COMMAND} -E make_directory
        "${RIME_PREFIX}/include" "${RIME_PREFIX}/lib"
        COMMAND ${CMAKE_COMMAND} -E copy
        "${RIME_BOOST_BUILD_ROOT}/stage/lib/libboost_container.a"
        "${RIME_BOOST_BUILD_ROOT}/stage/lib/libboost_iostreams.a"
        "${RIME_BOOST_BUILD_ROOT}/stage/lib/libboost_random.a"
        "${RIME_BOOST_BUILD_ROOT}/stage/lib/libboost_regex.a"
        "${RIME_PREFIX}/lib"
    USES_TERMINAL_CONFIGURE TRUE
    USES_TERMINAL_BUILD TRUE
    USES_TERMINAL_INSTALL TRUE)

ExternalProject_Add(rime_lua
    GIT_REPOSITORY https://github.com/walterschell/Lua.git
    GIT_TAG v5.4.7
    GIT_SHALLOW TRUE
    SOURCE_DIR "${RIME_BUILD_ROOT}/src-lua"
    BINARY_DIR "${RIME_BUILD_ROOT}/build-lua"
    STAMP_DIR "${RIME_STAMP_ROOT}/lua"
    TMP_DIR "${RIME_TMP_ROOT}/lua"
    UPDATE_COMMAND ""
    CONFIGURE_HANDLED_BY_BUILD TRUE
    INSTALL_DIR "${RIME_PREFIX}"
    PATCH_COMMAND ${RIME_BASH} -c
        "echo 'lua.patch ${RIME_LUA_PATCH_HASH}' 1>&2 && git reset --hard HEAD && git clean -fdx && git apply '${RIME_PATCH_DIR}/lua.patch'"
    CMAKE_ARGS
        ${RIME_DEP_ARGS}
        -DLUA_BUILD_BINARY=OFF
        -DLUA_BUILD_COMPILER=OFF
        -DLUA_ENABLE_SHARED=OFF
        -DLUA_ENABLE_TESTING=OFF
        -DLUA_SUPPORT_DL=ON
    BUILD_COMMAND ${RIME_BASH} -c "CMAKE_BUILD_PARALLEL_LEVEL=${RIME_PARALLEL_JOBS} '${CMAKE_COMMAND}' --build '<BINARY_DIR>' 1>&2"
    INSTALL_COMMAND ${RIME_BASH} -c "'${CMAKE_COMMAND}' --build '<BINARY_DIR>' --target install 1>&2"
    USES_TERMINAL_CONFIGURE TRUE
    USES_TERMINAL_BUILD TRUE
    USES_TERMINAL_INSTALL TRUE)

function(add_rime_dependency name source)
    cmake_parse_arguments(DEP "" "" "ARGS;DEPENDS;PATCH_COMMAND" ${ARGN})
    ExternalProject_Add(rime_${name}
        SOURCE_DIR "${source}"
        BINARY_DIR "${RIME_BUILD_ROOT}/build-${name}"
        STAMP_DIR "${RIME_STAMP_ROOT}/${name}"
        TMP_DIR "${RIME_TMP_ROOT}/${name}"
        UPDATE_COMMAND ""
        CONFIGURE_HANDLED_BY_BUILD TRUE
        INSTALL_DIR "${RIME_PREFIX}"
        PATCH_COMMAND ${DEP_PATCH_COMMAND}
        CMAKE_ARGS ${RIME_DEP_ARGS} ${DEP_ARGS}
        DEPENDS ${DEP_DEPENDS}
        BUILD_COMMAND ${RIME_BASH} -c "CMAKE_BUILD_PARALLEL_LEVEL=${RIME_PARALLEL_JOBS} '${CMAKE_COMMAND}' --build '<BINARY_DIR>' 1>&2"
        INSTALL_COMMAND ${RIME_BASH} -c "'${CMAKE_COMMAND}' --build '<BINARY_DIR>' --target install 1>&2"
        USES_TERMINAL_CONFIGURE TRUE
        USES_TERMINAL_BUILD TRUE
        USES_TERMINAL_INSTALL TRUE
        EXCLUDE_FROM_ALL FALSE)
endfunction()

add_rime_dependency(glog "${RIME_SOURCE_COPY}/deps/glog"
    ARGS -DBUILD_TESTING=OFF -DWITH_GFLAGS=OFF)
add_rime_dependency(leveldb "${RIME_SOURCE_COPY}/deps/leveldb"
    ARGS -DLEVELDB_BUILD_BENCHMARKS=OFF -DLEVELDB_BUILD_TESTS=OFF
    DEPENDS rime_glog)
add_rime_dependency(marisa "${RIME_SOURCE_COPY}/deps/marisa-trie"
    ARGS -DBUILD_TESTING=OFF -DENABLE_TOOLS=OFF
    DEPENDS rime_leveldb)
add_rime_dependency(opencc "${RIME_SOURCE_COPY}/deps/opencc"
    ARGS -DENABLE_GTEST=OFF -DENABLE_BENCHMARK=OFF
    DEPENDS rime_marisa
    PATCH_COMMAND ${RIME_BASH} -c
        "sed -i '/^add_subdirectory(data)/d' '${RIME_SOURCE_COPY}/deps/opencc/CMakeLists.txt'")
add_rime_dependency(yaml-cpp "${RIME_SOURCE_COPY}/deps/yaml-cpp"
    ARGS -DYAML_CPP_BUILD_CONTRIB=OFF -DYAML_CPP_BUILD_TESTS=OFF -DYAML_CPP_BUILD_TOOLS=OFF
        -DCMAKE_CXX_STANDARD=17 -DCMAKE_CXX_STANDARD_REQUIRED=ON
    DEPENDS rime_glog)

configure_file("${CMAKE_CURRENT_LIST_DIR}/build-librime.cmake.in"
    "${RIME_LIBRIME_BUILD_SCRIPT}" @ONLY)
set(RIME_LIBRIME_MARKER "${RIME_PREFIX}/lib/librime.so")
set(RIME_DEPENDENCY_MARKERS
    "${RIME_PREFIX}/lib/libboost_container.a"
    "${RIME_PREFIX}/lib/libboost_iostreams.a"
    "${RIME_PREFIX}/lib/libboost_random.a"
    "${RIME_PREFIX}/lib/libboost_regex.a"
    "${RIME_PREFIX}/lib/liblua_static.a"
    "${RIME_PREFIX}/lib/libglog.a"
    "${RIME_PREFIX}/lib/libleveldb.a"
    "${RIME_PREFIX}/lib/libmarisa.a"
    "${RIME_PREFIX}/lib/libopencc.a"
    "${RIME_PREFIX}/lib/libyaml-cpp.a")
set(RIME_LIBRIME_DEPENDS)
set(RIME_DEPENDENCY_PAIRS
    "${RIME_PREFIX}/lib/libboost_container.a|rime_boost"
    "${RIME_PREFIX}/lib/libboost_iostreams.a|rime_boost"
    "${RIME_PREFIX}/lib/libboost_random.a|rime_boost"
    "${RIME_PREFIX}/lib/libboost_regex.a|rime_boost"
    "${RIME_PREFIX}/lib/liblua_static.a|rime_lua"
    "${RIME_PREFIX}/lib/libglog.a|rime_glog"
    "${RIME_PREFIX}/lib/libleveldb.a|rime_leveldb"
    "${RIME_PREFIX}/lib/libmarisa.a|rime_marisa"
    "${RIME_PREFIX}/lib/libopencc.a|rime_opencc"
    "${RIME_PREFIX}/lib/libyaml-cpp.a|rime_yaml-cpp")
foreach(dependency_pair IN LISTS RIME_DEPENDENCY_PAIRS)
    string(REPLACE "|" ";" dependency_parts "${dependency_pair}")
    list(GET dependency_parts 0 dependency_marker)
    list(GET dependency_parts 1 dependency_target)
    list(APPEND RIME_LIBRIME_DEPENDS "${dependency_marker}")
    if(NOT EXISTS "${dependency_marker}")
        add_custom_command(
            OUTPUT "${dependency_marker}"
            COMMAND ${CMAKE_COMMAND} -E touch "${dependency_marker}"
            DEPENDS "${dependency_target}"
            USES_TERMINAL)
    endif()
endforeach()
add_custom_command(
    OUTPUT "${RIME_LIBRIME_MARKER}"
    COMMAND ${CMAKE_COMMAND} -P "${RIME_LIBRIME_BUILD_SCRIPT}"
    DEPENDS ${RIME_LIBRIME_DEPENDS} "${RIME_LIBRIME_BUILD_SCRIPT}"
        ${RIME_PATCH_FILES} "${RIME_SOURCE_REVISION_STAMP}"
    USES_TERMINAL)
add_custom_target(rime_librime DEPENDS "${RIME_LIBRIME_MARKER}")

add_library(Rime_source SHARED IMPORTED GLOBAL)
file(MAKE_DIRECTORY "${RIME_PREFIX}/include")
set_target_properties(Rime_source PROPERTIES
    IMPORTED_LOCATION "${RIME_PREFIX}/lib/librime.so"
    INTERFACE_INCLUDE_DIRECTORIES "${RIME_PREFIX}/include")
add_dependencies(Rime_source rime_librime)

set(RIME_SOURCE_TARGET Rime_source PARENT_SCOPE)
set(RIME_SOURCE_PREFIX "${RIME_PREFIX}" PARENT_SCOPE)
set(RIME_DEP_ARGS "${RIME_DEP_ARGS}" PARENT_SCOPE)
set(RIME_BUILD_ROOT "${RIME_BUILD_ROOT}" PARENT_SCOPE)
set(RIME_COMMON_BUILD_ROOT "${RIME_COMMON_BUILD_ROOT}" PARENT_SCOPE)
set(RIME_STAMP_ROOT "${RIME_STAMP_ROOT}" PARENT_SCOPE)
set(RIME_TMP_ROOT "${RIME_TMP_ROOT}" PARENT_SCOPE)
set(RIME_PARALLEL_JOBS "${RIME_PARALLEL_JOBS}" PARENT_SCOPE)
set(RIME_BASH "${RIME_BASH}" PARENT_SCOPE)
