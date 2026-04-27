cmake_minimum_required(VERSION 3.18.1)

project("cb_term")

set(CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}")

add_library(vterm STATIC
    ${CPP_DIR}/libvterm/src/encoding.c
    ${CPP_DIR}/libvterm/src/keyboard.c
    ${CPP_DIR}/libvterm/src/mouse.c
    ${CPP_DIR}/libvterm/src/parser.c
    ${CPP_DIR}/libvterm/src/pen.c
    ${CPP_DIR}/libvterm/src/screen.c
    ${CPP_DIR}/libvterm/src/state.c
    ${CPP_DIR}/libvterm/src/unicode.c
    ${CPP_DIR}/libvterm/src/vterm.c
)

target_include_directories(vterm PUBLIC ${CPP_DIR}/libvterm/include)
target_compile_definitions(vterm PRIVATE VTERM_STATIC)
set_target_properties(vterm PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(jni_cb_term SHARED
    ${CPP_DIR}/Terminal.cpp
    ${CPP_DIR}/mutf8.cpp
)

target_include_directories(jni_cb_term PRIVATE
    ${CPP_DIR}
    ${CPP_DIR}/libvterm/include
)

target_compile_features(jni_cb_term PRIVATE cxx_std_17)

if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    target_link_options(jni_cb_term PRIVATE "-Wl,-z,max-page-size=16384")
endif()

if(ANDROID)
    target_link_libraries(jni_cb_term vterm android log)
else()
    find_package(JNI REQUIRED)
    target_include_directories(jni_cb_term PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(jni_cb_term vterm)
endif()
