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

# ---------------------------------------------------------------------------
# libnpffmpeg（E204 白名单批）：白名单解码器专用 FFmpeg 解码模块。
#
# 链接 prebuilt/<abi>/ 的**静态** avcodec/avutil/swresample——同一套库经
# build-ffmpeg.sh 重编后既服务 libnplegacy（wmv2/mpeg4）也服务本模块的白名单
# 解码器（wmv1/wmv3/vc1/msmpeg4/rv/vp5-6/prores/ffv1/…/wma 族），单份静态链入、
# --exclude-libs + gc-sections 只保留被引用的对象。早期曾试过动态链 NextLib 的
# 共享 libavcodec，但其构建清单只含 22 个标准解码器、白名单一个都没有，故重编。
# ---------------------------------------------------------------------------
if(NOT EXISTS ${PREBUILT}/lib/libswresample.a)
    message(FATAL_ERROR "missing ${PREBUILT}/lib/libswresample.a — run build-ffmpeg.sh（白名单批重编）")
endif()

add_library(npavformat STATIC IMPORTED)
set_target_properties(npavformat PROPERTIES IMPORTED_LOCATION ${PREBUILT}/lib/libavformat.a)
add_library(npavcodec STATIC IMPORTED)
set_target_properties(npavcodec PROPERTIES IMPORTED_LOCATION ${PREBUILT}/lib/libavcodec.a)
add_library(npavutil STATIC IMPORTED)
set_target_properties(npavutil PROPERTIES IMPORTED_LOCATION ${PREBUILT}/lib/libavutil.a)
add_library(npswr STATIC IMPORTED)
set_target_properties(npswr PROPERTIES IMPORTED_LOCATION ${PREBUILT}/lib/libswresample.a)

add_library(npffmpeg SHARED np_ffmpeg_decoder.c np_ffmpeg_extractor.c)
set_property(TARGET npffmpeg APPEND PROPERTY LINK_DEPENDS
        ${CMAKE_CURRENT_SOURCE_DIR}/npffmpeg.map)
target_include_directories(npffmpeg PRIVATE ${PREBUILT}/include)
target_compile_options(npffmpeg PRIVATE -fvisibility=hidden -Os -ffunction-sections -fdata-sections)
target_link_libraries(npffmpeg npavformat npavcodec npavutil npswr m z atomic android log)
target_link_options(npffmpeg PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,--gc-sections"
        "-Wl,--exclude-libs,ALL"
        "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/npffmpeg.map")
