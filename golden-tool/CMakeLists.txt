cmake_minimum_required(VERSION 3.25)
project(zvcr_golden CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# CMP0168 (CMake >= 3.30) makes a second FetchContent_Declare() of the same
# content name with different arguments an error. The submodule declares
# abseil/result-cpp itself (on floating branches); we declare them first
# with pinned revisions below, so keep the "first declaration wins" policy.
if(POLICY CMP0168)
    cmake_policy(SET CMP0168 OLD)
endif()

# The reference implementation lives in the `zvcr` submodule (see README.md
# for the pinned commit). Its own zvcr_lib/CMakeLists.txt pulls in abseil,
# zstd, fmt and result-cpp via FetchContent; two of those float on a branch
# (abseil: master, result-cpp: main), so they are declared here first with
# pinned revisions. FetchContent keeps the first declaration for a given
# name, so the submodule's own declarations are ignored.
include(FetchContent)

FetchContent_Declare(abseil
        GIT_REPOSITORY https://github.com/abseil/abseil-cpp.git
        GIT_TAG        20260817.0
        GIT_SHALLOW    TRUE
)
FetchContent_Declare(result
        GIT_REPOSITORY https://github.com/2b2tplace/result-cpp.git
        GIT_TAG        174865a9af0891e8c903b759a96f8480205c25ca
)
FetchContent_MakeAvailable(abseil result)

# Upstream's own library target: the exact sources and flags the reference
# implementation uses. It also provides the `include/` path for <zvcr/...>.
add_subdirectory(zvcr/zvcr_lib)

add_executable(zvcr_golden main.cpp)
target_link_libraries(zvcr_golden PRIVATE zvcr_lib)
