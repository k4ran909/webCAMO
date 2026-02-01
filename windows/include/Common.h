#pragma once

// Windows
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winsock2.h>
#include <ws2tcpip.h>

// STL
#include <string>
#include <memory>
#include <functional>
#include <mutex>
#include <queue>
#include <atomic>
#include <thread>
#include <vector>
#include <cstdint>

// DirectShow
#include <dshow.h>
#include <strmif.h>
#include <uuids.h>

namespace WebCAMO {

// Video frame dimensions
constexpr int VIDEO_WIDTH = 1280;
constexpr int VIDEO_HEIGHT = 720;
constexpr int VIDEO_FPS = 30;

// Connection states
enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Error
};

// Video frame data
struct VideoFrame {
    std::vector<uint8_t> data;
    int width;
    int height;
    int64_t timestamp;
    
    VideoFrame() : width(0), height(0), timestamp(0) {}
    VideoFrame(int w, int h) : width(w), height(h), timestamp(0) {
        data.resize(w * h * 4); // RGBA
    }
};

// Callbacks
using ConnectionCallback = std::function<void(ConnectionState)>;
using FrameCallback = std::function<void(const VideoFrame&)>;
using ErrorCallback = std::function<void(const std::string&)>;

// Utility functions
inline std::wstring ToWideString(const std::string& str) {
    if (str.empty()) return std::wstring();
    int size = MultiByteToWideChar(CP_UTF8, 0, str.c_str(), -1, nullptr, 0);
    std::wstring result(size - 1, 0);
    MultiByteToWideChar(CP_UTF8, 0, str.c_str(), -1, &result[0], size);
    return result;
}

inline std::string ToNarrowString(const std::wstring& wstr) {
    if (wstr.empty()) return std::string();
    int size = WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), -1, nullptr, 0, nullptr, nullptr);
    std::string result(size - 1, 0);
    WideCharToMultiByte(CP_UTF8, 0, wstr.c_str(), -1, &result[0], size, nullptr, nullptr);
    return result;
}

} // namespace WebCAMO
