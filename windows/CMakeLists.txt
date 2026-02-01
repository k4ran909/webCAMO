cmake_minimum_required(VERSION 3.20)
project(WebCAMO VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Windows SDK for DirectShow
if(WIN32)
    find_package(WindowsSDK QUIET)
endif()

# Output directory
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)

# Sources
set(SOURCES
    src/main.cpp
    src/Application.cpp
    src/SignalingClient.cpp
    src/WebRTCReceiver.cpp
    src/VirtualCamera.cpp
    src/FrameBuffer.cpp
    src/SystemTray.cpp
    src/SimpleReceiver.cpp
)

set(HEADERS
    include/Application.h
    include/SignalingClient.h
    include/WebRTCReceiver.h
    include/VirtualCamera.h
    include/FrameBuffer.h
    include/SystemTray.h
    include/SimpleReceiver.h
    include/Common.h
)

# Main executable
add_executable(WebCAMO WIN32 ${SOURCES} ${HEADERS})

target_include_directories(WebCAMO PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/webrtc/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/websocketpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/asio/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/json/include
)

# Link libraries
target_link_libraries(WebCAMO PRIVATE
    # Windows libraries
    ws2_32
    mswsock
    strmiids
    ole32
    oleaut32
    uuid
    winmm
    shell32
    user32
    gdi32
)

# Compiler definitions
target_compile_definitions(WebCAMO PRIVATE
    _WIN32_WINNT=0x0A00
    NOMINMAX
    WIN32_LEAN_AND_MEAN
    UNICODE
    _UNICODE
    ASIO_STANDALONE
)

# Warning level
if(MSVC)
    target_compile_options(WebCAMO PRIVATE /W3 /WX-)
endif()

# DirectShow Virtual Camera Filter (separate DLL)
add_library(WebCAMOFilter SHARED
    src/filter/VirtualCameraFilter.cpp
    src/filter/VirtualCameraFilter.def
)

target_include_directories(WebCAMOFilter PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_link_libraries(WebCAMOFilter PRIVATE
    strmiids
    ole32
    oleaut32
    uuid
    winmm
)

target_compile_definitions(WebCAMOFilter PRIVATE
    UNICODE
    _UNICODE
)

# Install
install(TARGETS WebCAMO DESTINATION bin)
install(TARGETS WebCAMOFilter DESTINATION bin)
