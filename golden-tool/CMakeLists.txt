cmake_minimum_required(VERSION 3.25)
project(zvcr_golden)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(result
        GIT_REPOSITORY https://github.com/2b2tplace/result-cpp.git
        GIT_TAG        main
        GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(result)

find_package(absl REQUIRED)
find_package(fmt REQUIRED)

file(GLOB_RECURSE ZVCR_LIB_SOURCES
        "${CMAKE_CURRENT_SOURCE_DIR}/../../zvcr/zvcr_lib/src/*.cpp")
add_library(zvcr_lib_golden STATIC ${ZVCR_LIB_SOURCES})
target_include_directories(zvcr_lib_golden PUBLIC
        "${CMAKE_CURRENT_SOURCE_DIR}/../../zvcr/zvcr_lib/src"
        "${CMAKE_CURRENT_SOURCE_DIR}/../../zvcr/zvcr_lib"
)
target_link_libraries(zvcr_lib_golden PUBLIC
        absl::flat_hash_map
        absl::hash
        fmt::fmt
        zstd
        result_cpp
)
# fmt >= 11 requires an explicit opt-in for fmt::format via fmt/core.h
# (the reference library predates that split).
target_compile_definitions(zvcr_lib_golden PUBLIC FMT_DEPRECATED_HEAVY_CORE)

add_executable(zvcr_golden main.cpp)
target_link_libraries(zvcr_golden PRIVATE zvcr_lib_golden)
