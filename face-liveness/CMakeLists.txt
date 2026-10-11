# Builds libface_liveness_android.so (invoked by build.gradle.kts).

cmake_minimum_required(VERSION 3.10)

project(face_liveness_android VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- ncnn (prebuilt Android release, downloaded once and shared by all ABIs) ---
set(NCNN_VERSION 20260526)
set(NCNN_SHA256 85b18b875488585c2d21360430e0e54abb6c04aa88094b471c20208ab55ff796)
set(NCNN_NAME ncnn-${NCNN_VERSION}-android)

if(DEFINED ENV{FACE_LIVENESS_NCNN_DIR})
  set(NCNN_CACHE_DIR "$ENV{FACE_LIVENESS_NCNN_DIR}")
elseif(DEFINED ENV{HOME})
  set(NCNN_CACHE_DIR "$ENV{HOME}/.face_liveness")
else()
  set(NCNN_CACHE_DIR "$ENV{USERPROFILE}/.face_liveness")
endif()
set(NCNN_ROOT "${NCNN_CACHE_DIR}/${NCNN_NAME}")

if(NOT EXISTS "${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn/ncnnConfig.cmake")
  file(MAKE_DIRECTORY "${NCNN_CACHE_DIR}")
  # Gradle configures every ABI in parallel: only one of them downloads.
  file(LOCK "${NCNN_CACHE_DIR}/${NCNN_NAME}.lock" GUARD FILE TIMEOUT 900)
  if(NOT EXISTS "${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn/ncnnConfig.cmake")
    set(NCNN_ZIP "${NCNN_CACHE_DIR}/${NCNN_NAME}.zip")
    message(STATUS "face_liveness: downloading ${NCNN_NAME}")
    file(DOWNLOAD
      "https://github.com/Tencent/ncnn/releases/download/${NCNN_VERSION}/${NCNN_NAME}.zip"
      "${NCNN_ZIP}"
      EXPECTED_HASH SHA256=${NCNN_SHA256}
      TLS_VERIFY ON
      SHOW_PROGRESS)
    execute_process(
      COMMAND ${CMAKE_COMMAND} -E tar xf "${NCNN_ZIP}"
      WORKING_DIRECTORY "${NCNN_CACHE_DIR}"
      RESULT_VARIABLE NCNN_EXTRACT_RESULT)
    file(REMOVE "${NCNN_ZIP}")
    if(NOT NCNN_EXTRACT_RESULT EQUAL 0)
      message(FATAL_ERROR "face_liveness: failed to extract ${NCNN_NAME}")
    endif()
  endif()
  file(LOCK "${NCNN_CACHE_DIR}/${NCNN_NAME}.lock" RELEASE)
endif()

set(ncnn_DIR "${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

# --- face_liveness_android ---
set(FL_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")

add_library(face_liveness_android SHARED
  ${FL_CPP_DIR}/face_liveness_jni.cpp
  ${FL_CPP_DIR}/face_liveness.cpp
  ${FL_CPP_DIR}/core/src/face_anti_spoofing.cpp
)

target_include_directories(face_liveness_android PRIVATE
  ${FL_CPP_DIR}
  ${FL_CPP_DIR}/core/include
  "${NCNN_ROOT}/${ANDROID_ABI}/include"   # for <ncnn/net.h>
)

target_link_libraries(face_liveness_android PRIVATE ncnn)

# 16 KB page size support (required for Android 15+ devices on Google Play).
target_link_options(face_liveness_android PRIVATE "-Wl,-z,max-page-size=16384")
