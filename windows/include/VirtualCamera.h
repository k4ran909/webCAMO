#pragma once

#include "Common.h"
#include "FrameBuffer.h"

namespace WebCAMO {

// Virtual camera GUID - unique identifier for our camera
// {E8F2A3B4-5C6D-7E8F-9A0B-C1D2E3F4A5B6}
static const GUID CLSID_WebCAMOCamera = {
    0xe8f2a3b4,
    0x5c6d,
    0x7e8f,
    {0x9a, 0x0b, 0xc1, 0xd2, 0xe3, 0xf4, 0xa5, 0xb6}};

/**
 * DirectShow Virtual Camera
 *
 * This class wraps the virtual camera functionality.
 * The actual DirectShow filter is implemented separately as a DLL.
 *
 * For full implementation, you need:
 * 1. VirtualCameraFilter DLL (registers as DirectShow source)
 * 2. Shared memory for frame passing
 * 3. Registration script
 */
class VirtualCamera {
public:
  VirtualCamera(std::shared_ptr<FrameBuffer> frameBuffer);
  ~VirtualCamera();

  // Register/unregister the virtual camera
  static bool Register();
  static bool Unregister();

  // Check if registered
  static bool IsRegistered();

  // Start/stop providing frames
  bool Start();
  void Stop();

  // Push a frame to the virtual camera
  void PushFrame(const VideoFrame &frame);

  // State
  bool IsRunning() const { return m_running; }

private:
  void FrameLoop();
  bool CreateSharedMemory();
  void DestroySharedMemory();

  std::shared_ptr<FrameBuffer> m_frameBuffer;
  std::thread m_thread;
  std::atomic<bool> m_running{false};

  // Shared memory for frame passing
  HANDLE m_sharedMemoryHandle = nullptr;
  void *m_sharedMemoryPtr = nullptr;
  HANDLE m_frameEvent = nullptr;

  static constexpr size_t SHARED_MEMORY_SIZE =
      VIDEO_WIDTH * VIDEO_HEIGHT * 4 + sizeof(int) * 4;
  static constexpr wchar_t SHARED_MEMORY_NAME[] = L"WebCAMO_SharedFrame";
  static constexpr wchar_t FRAME_EVENT_NAME[] = L"WebCAMO_FrameEvent";
};

} // namespace WebCAMO
