#pragma once

#include "Common.h"
#include "FrameBuffer.h"
#include "SignalingClient.h"
#include "SystemTray.h"
#include "VirtualCamera.h"
#include "WebRTCReceiver.h"


namespace WebCAMO {

/**
 * Main application class that coordinates all components.
 */
class Application {
public:
  static Application &Instance();

  Application();
  ~Application();

  // Initialize the application
  bool Initialize(HINSTANCE hInstance);

  // Run the message loop
  int Run();

  // Shutdown
  void Shutdown();

  // Connection management
  void Connect(const std::string &serverUrl, const std::string &room);
  void Disconnect();

  // Virtual camera
  void RegisterVirtualCamera();

  // State
  bool IsConnected() const {
    return m_signalingClient && m_signalingClient->IsConnected();
  }

  // Get window handle
  HWND GetHWnd() const { return m_hwnd; }

private:
  static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wParam,
                                  LPARAM lParam);
  void OnTrayMessage(WPARAM wParam, LPARAM lParam);
  void OnMenuCommand(int id);
  void OnSignalingMessage(const std::string &type, const std::string &payload);
  void UpdateTrayStatus();

  bool CreateMessageWindow(HINSTANCE hInstance);

  HWND m_hwnd = nullptr;
  std::shared_ptr<FrameBuffer> m_frameBuffer;
  std::unique_ptr<SignalingClient> m_signalingClient;
  std::unique_ptr<WebRTCReceiver> m_webrtcReceiver;
  std::unique_ptr<VirtualCamera> m_virtualCamera;
  std::unique_ptr<SystemTray> m_systemTray;

  std::string m_serverUrl = "ws://192.168.1.100:8080";
  std::string m_room = "webcamo";
};

} // namespace WebCAMO
