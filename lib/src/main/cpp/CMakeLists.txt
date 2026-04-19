cmake_minimum_required(VERSION 3.18.1)

project("cb_term")

set(LIB_NATIVE_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../../../lib-native/src/main/cpp")

add_library(vterm STATIC
    ${LIB_NATIVE_CPP}/libvterm/src/encoding.c
    ${LIB_NATIVE_CPP}/libvterm/src/keyboard.c
    ${LIB_NATIVE_CPP}/libvterm/src/mouse.c
    ${LIB_NATIVE_CPP}/libvterm/src/parser.c
    ${LIB_NATIVE_CPP}/libvterm/src/pen.c
    ${LIB_NATIVE_CPP}/libvterm/src/screen.c
    ${LIB_NATIVE_CPP}/libvterm/src/state.c
    ${LIB_NATIVE_CPP}/libvterm/src/unicode.c
    ${LIB_NATIVE_CPP}/libvterm/src/vterm.c
)

target_include_directories(vterm PUBLIC ${LIB_NATIVE_CPP}/libvterm/include)
target_compile_definitions(vterm PRIVATE VTERM_STATIC)
set_target_properties(vterm PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(jni_cb_term SHARED
    ${LIB_NATIVE_CPP}/Terminal.cpp
    ${LIB_NATIVE_CPP}/mutf8.cpp
)

target_include_directories(jni_cb_term PRIVATE
    ${LIB_NATIVE_CPP}
    ${LIB_NATIVE_CPP}/libvterm/include
)

target_compile_features(jni_cb_term PRIVATE cxx_std_17)

if(ANDROID)
    target_link_options(jni_cb_term PRIVATE "-Wl,-z,max-page-size=16384")

    target_link_libraries(jni_cb_term
        vterm
        android
        log
    )
else()
    find_package(JNI REQUIRED)
    target_include_directories(jni_cb_term PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(jni_cb_term vterm)
endif()
