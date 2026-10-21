cmake_minimum_required(VERSION 3.22.1)
project(nplegacy C)

set(PREBUILT ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI})
if(NOT EXISTS ${PREBUILT}/lib/libavcodec.a OR NOT EXISTS ${PREBUILT}/lib/libavutil.a)
    message(FATAL_ERROR "missing ${PREBUILT}/lib/libavcodec.a — run src/main/cpp/build-ffmpeg.sh")
endif()

add_library(avcodec STATIC IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${PREBUILT}/lib/libavcodec.a)
add_library(avutil STATIC IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${PREBUILT}/lib/libavutil.a)

add_library(nplegacy SHARED legacy_decoder.c)
set_property(TARGET nplegacy APPEND PROPERTY LINK_DEPENDS
        ${CMAKE_CURRENT_SOURCE_DIR}/nplegacy.map)
target_include_directories(nplegacy PRIVATE ${PREBUILT}/include)
target_compile_options(nplegacy PRIVATE -fvisibility=hidden -Os -ffunction-sections -fdata-sections)
target_link_libraries(nplegacy avcodec avutil m z atomic android log)
target_link_options(nplegacy PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,--gc-sections"
        "-Wl,--exclude-libs,ALL"
        "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/nplegacy.map")
