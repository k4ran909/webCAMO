#include "VirtualCamera.h"
#include <shlwapi.h>

#pragma comment(lib, "shlwapi.lib")

namespace WebCAMO {

VirtualCamera::VirtualCamera(std::shared_ptr<FrameBuffer> frameBuffer)
    : m_frameBuffer(frameBuffer) {}

VirtualCamera::~VirtualCamera() {
  Stop();
  DestroySharedMemory();
}

bool VirtualCamera::Register() {
  // Get the path to our DLL
  wchar_t modulePath[MAX_PATH];
  GetModuleFileNameW(nullptr, modulePath, MAX_PATH);
  PathRemoveFileSpecW(modulePath);

  std::wstring dllPath = std::wstring(modulePath) + L"\\WebCAMOFilter.dll";

  // Use regsvr32 to register the DLL
  std::wstring cmd = L"regsvr32 /s \"" + dllPath + L"\"";

  STARTUPINFOW si = {sizeof(si)};
  PROCESS_INFORMATION pi;

  if (CreateProcessW(nullptr, const_cast<wchar_t *>(cmd.c_str()), nullptr,
                     nullptr, FALSE, 0, nullptr, nullptr, &si, &pi)) {
    WaitForSingleObject(pi.hProcess, INFINITE);

    DWORD exitCode;
    GetExitCodeProcess(pi.hProcess, &exitCode);

    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);

    return exitCode == 0;
  }

  return false;
}

bool VirtualCamera::Unregister() {
  wchar_t modulePath[MAX_PATH];
  GetModuleFileNameW(nullptr, modulePath, MAX_PATH);
  PathRemoveFileSpecW(modulePath);

  std::wstring dllPath = std::wstring(modulePath) + L"\\WebCAMOFilter.dll";
  std::wstring cmd = L"regsvr32 /u /s \"" + dllPath + L"\"";

  STARTUPINFOW si = {sizeof(si)};
  PROCESS_INFORMATION pi;

  if (CreateProcessW(nullptr, const_cast<wchar_t *>(cmd.c_str()), nullptr,
                     nullptr, FALSE, 0, nullptr, nullptr, &si, &pi)) {
    WaitForSingleObject(pi.hProcess, INFINITE);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    return true;
  }

  return false;
}

bool VirtualCamera::IsRegistered() {
  HKEY hKey;
  std::wstring keyPath = L"CLSID\\{E8F2A3B4-5C6D-7E8F-9A0B-C1D2E3F4A5B6}";

  if (RegOpenKeyExW(HKEY_CLASSES_ROOT, keyPath.c_str(), 0, KEY_READ, &hKey) ==
      ERROR_SUCCESS) {
    RegCloseKey(hKey);
    return true;
  }

  return false;
}

bool VirtualCamera::Start() {
  if (m_running)
    return true;

  if (!CreateSharedMemory()) {
    return false;
  }

  m_running = true;
  m_thread = std::thread(&VirtualCamera::FrameLoop, this);

  return true;
}

void VirtualCamera::Stop() {
  m_running = false;

  if (m_thread.joinable()) {
    m_thread.join();
  }
}

void VirtualCamera::PushFrame(const VideoFrame &frame) {
  if (!m_sharedMemoryPtr || !m_frameEvent)
    return;

  // Write frame to shared memory
  // Format: [width][height][timestamp_low][timestamp_high][pixel_data...]
  int *header = static_cast<int *>(m_sharedMemoryPtr);
  header[0] = frame.width;
  header[1] = frame.height;
  header[2] = static_cast<int>(frame.timestamp & 0xFFFFFFFF);
  header[3] = static_cast<int>((frame.timestamp >> 32) & 0xFFFFFFFF);

  uint8_t *pixels = reinterpret_cast<uint8_t *>(&header[4]);
  size_t pixelSize = std::min(
      frame.data.size(), static_cast<size_t>(VIDEO_WIDTH * VIDEO_HEIGHT * 4));
  memcpy(pixels, frame.data.data(), pixelSize);

  // Signal the filter that a new frame is available
  SetEvent(m_frameEvent);
}

void VirtualCamera::FrameLoop() {
  while (m_running) {
    VideoFrame frame;
    if (m_frameBuffer->Pop(frame, 33)) { // ~30fps timeout
      PushFrame(frame);
    }
  }
}

bool VirtualCamera::CreateSharedMemory() {
  // Create shared memory
  m_sharedMemoryHandle = CreateFileMappingW(
      INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0,
      static_cast<DWORD>(SHARED_MEMORY_SIZE), SHARED_MEMORY_NAME);

  if (!m_sharedMemoryHandle) {
    return false;
  }

  m_sharedMemoryPtr = MapViewOfFile(m_sharedMemoryHandle, FILE_MAP_ALL_ACCESS,
                                    0, 0, SHARED_MEMORY_SIZE);

  if (!m_sharedMemoryPtr) {
    CloseHandle(m_sharedMemoryHandle);
    m_sharedMemoryHandle = nullptr;
    return false;
  }

  // Create event for signaling new frames
  m_frameEvent = CreateEventW(nullptr, FALSE, FALSE, FRAME_EVENT_NAME);
  if (!m_frameEvent) {
    DestroySharedMemory();
    return false;
  }

  return true;
}

void VirtualCamera::DestroySharedMemory() {
  if (m_frameEvent) {
    CloseHandle(m_frameEvent);
    m_frameEvent = nullptr;
  }

  if (m_sharedMemoryPtr) {
    UnmapViewOfFile(m_sharedMemoryPtr);
    m_sharedMemoryPtr = nullptr;
  }

  if (m_sharedMemoryHandle) {
    CloseHandle(m_sharedMemoryHandle);
    m_sharedMemoryHandle = nullptr;
  }
}

} // namespace WebCAMO
