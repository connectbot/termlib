cmake_minimum_required(VERSION 3.18.1)

project("cb_term")

# libvterm static library
add_library(vterm STATIC
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/encoding.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/keyboard.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/mouse.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/parser.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/pen.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/screen.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/state.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/unicode.c
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/src/vterm.c
)

target_include_directories(vterm PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/include
)

target_compile_definitions(vterm PRIVATE
    VTERM_STATIC
)

# JNI wrapper library
add_library(jni_cb_term SHARED
    ${CMAKE_CURRENT_SOURCE_DIR}/Terminal.cpp
)

target_include_directories(jni_cb_term PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libvterm/include
)

target_compile_features(jni_cb_term PRIVATE cxx_std_17)

target_link_libraries(jni_cb_term
    vterm
    android
    log
)
