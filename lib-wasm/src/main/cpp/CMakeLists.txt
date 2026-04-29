cmake_minimum_required(VERSION 3.18)
project(vterm_wasm C)

# This CMake file is only used with the WASI SDK toolchain to produce
# libvterm.wasm.  Invoke with:
#
#   cmake -S . -B build-wasm \
#     -DCMAKE_TOOLCHAIN_FILE=/opt/wasi-sdk/share/cmake/wasi-sdk.cmake \
#     -DWASI_SDK_PREFIX=/opt/wasi-sdk
#   cmake --build build-wasm --target vterm_wasm

set(LIBVTERM_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../lib-native/src/main/cpp/libvterm")

add_library(vterm STATIC
    ${LIBVTERM_DIR}/src/encoding.c
    ${LIBVTERM_DIR}/src/keyboard.c
    ${LIBVTERM_DIR}/src/mouse.c
    ${LIBVTERM_DIR}/src/parser.c
    ${LIBVTERM_DIR}/src/pen.c
    ${LIBVTERM_DIR}/src/screen.c
    ${LIBVTERM_DIR}/src/state.c
    ${LIBVTERM_DIR}/src/unicode.c
    ${LIBVTERM_DIR}/src/vterm.c
)
target_include_directories(vterm PUBLIC ${LIBVTERM_DIR}/include)
target_compile_definitions(vterm PRIVATE VTERM_STATIC)

add_executable(vterm_wasm
    ${CMAKE_CURRENT_SOURCE_DIR}/vterm_wasm.c
)
target_include_directories(vterm_wasm PRIVATE ${LIBVTERM_DIR}/include)
target_link_libraries(vterm_wasm vterm)

# Strip to minimum size; keep only the exported symbols
target_link_options(vterm_wasm PRIVATE
    -Wl,--no-entry
    -Wl,--export-dynamic
    -nostartfiles
)

# Output a .wasm file (not an executable)
set_target_properties(vterm_wasm PROPERTIES SUFFIX ".wasm")
