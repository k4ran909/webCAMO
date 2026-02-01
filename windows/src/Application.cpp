#include "Application.h"
#include <regex>

namespace WebCAMO {

Application &Application::Instance() {
  static Application instance;
  return instance;
}

Application::Application() { m_frameBuffer = std::make_shared<FrameBuffer>(3); }

Application::~Application() { Shutdown(); }

bool Application::Initialize(HINSTANCE hInstance) {
  // Create hidden message window
  if (!CreateMessageWindow(hInstance)) {
    return false;
  }

  // Initialize system tray
  m_systemTray = std::make_unique<SystemTray>();
  if (!m_systemTray->Initialize(m_hwnd)) {
    return false;
  }

  m_systemTray->SetMenuCallback([this](int id) { OnMenuCommand(id); });

  // Initialize components
  m_webrtcReceiver = std::make_unique<WebRTCReceiver>(m_frameBuffer);
  m_webrtcReceiver->Initialize();

  m_virtualCamera = std::make_unique<VirtualCamera>(m_frameBuffer);

  UpdateTrayStatus();

  return true;
}

int Application::Run() {
  MSG msg;
  while (GetMessage(&msg, nullptr, 0, 0)) {
    TranslateMessage(&msg);
    DispatchMessage(&msg);
  }
  return static_cast<int>(msg.wParam);
}

void Application::Shutdown() {
  Disconnect();

  if (m_virtualCamera) {
    m_virtualCamera->Stop();
  }

  if (m_systemTray) {
    m_systemTray->Remove();
  }

  if (m_hwnd) {
    DestroyWindow(m_hwnd);
    m_hwnd = nullptr;
  }
}

void Application::Connect(const std::string &serverUrl,
                          const std::string &room) {
  m_serverUrl = serverUrl;
  m_room = room;

  // Create signaling client
  m_signalingClient = std::make_unique<SignalingClient>();

  m_signalingClient->SetMessageCallback(
      [this](const std::string &type, const std::string &payload) {
        OnSignalingMessage(type, payload);
      });

  m_signalingClient->SetStateCallback([this](ConnectionState state) {
    UpdateTrayStatus();

    if (state == ConnectionState::Connected) {
      m_virtualCamera->Start();
    } else {
      m_virtualCamera->Stop();
    }
  });

  if (m_signalingClient->Connect(serverUrl, room)) {
    UpdateTrayStatus();
  }
}

void Application::Disconnect() {
  if (m_signalingClient) {
    m_signalingClient->Disconnect();
    m_signalingClient.reset();
  }

  if (m_virtualCamera) {
    m_virtualCamera->Stop();
  }

  UpdateTrayStatus();
}

void Application::RegisterVirtualCamera() {
  if (VirtualCamera::Register()) {
    MessageBoxW(m_hwnd,
                L"Virtual camera registered successfully!\n\nYou may need to "
                L"restart applications to see WebCAMO Camera.",
                L"WebCAMO", MB_OK | MB_ICONINFORMATION);
  } else {
    MessageBoxW(
        m_hwnd,
        L"Failed to register virtual camera.\n\nTry running as Administrator.",
        L"WebCAMO", MB_OK | MB_ICONERROR);
  }
}

void Application::OnSignalingMessage(const std::string &type,
                                     const std::string &payload) {
  if (type == "offer") {
    // Extract SDP from payload
    std::regex sdpRegex(R"("sdp"\s*:\s*"([^"]*)") ");
        std::smatch match;
    if (std::regex_search(payload, match, sdpRegex)) {
      std::string offerSdp = match[1].str();

      // Create answer
      std::string answerSdp = m_webrtcReceiver->CreateAnswer(offerSdp);
      m_signalingClient->SendAnswer(answerSdp);
    }
  } else if (type == "ice-candidate") {
    // Parse ICE candidate
    std::regex midRegex(R"("sdpMid"\s*:\s*"([^"]*)") ");
        std::regex indexRegex(R"("sdpMLineIndex"\s*:\s*(\d+))");
    std::regex candidateRegex(R"("candidate"\s*:\s*"([^"]*)") ");

        std::smatch match;
    std::string sdpMid, candidate;
    int sdpMLineIndex = 0;

    if (std::regex_search(payload, match, midRegex)) {
      sdpMid = match[1].str();
    }
    if (std::regex_search(payload, match, indexRegex)) {
      sdpMLineIndex = std::stoi(match[1].str());
    }
    if (std::regex_search(payload, match, candidateRegex)) {
      candidate = match[1].str();
    }

    m_webrtcReceiver->AddIceCandidate(sdpMid, sdpMLineIndex, candidate);
  } else if (type == "peer-joined") {
    UpdateTrayStatus();
  } else if (type == "peer-left") {
    UpdateTrayStatus();
  }
}

void Application::OnMenuCommand(int id) {
  switch (id) {
  case SystemTray::MENU_CONNECT:
    Connect(m_serverUrl, m_room);
    break;

  case SystemTray::MENU_DISCONNECT:
    Disconnect();
    break;

  case SystemTray::MENU_REGISTER:
    RegisterVirtualCamera();
    break;

  case SystemTray::MENU_SETTINGS:
    // TODO: Show settings dialog
    MessageBoxW(m_hwnd, L"Settings coming soon!", L"WebCAMO", MB_OK);
    break;

  case SystemTray::MENU_EXIT:
    PostQuitMessage(0);
    break;
  }
}

void Application::UpdateTrayStatus() {
  if (!m_systemTray)
    return;

  bool connected = IsConnected();
  m_systemTray->SetConnected(connected);

  if (connected) {
    m_systemTray->SetTooltip(L"WebCAMO - Connected");
  } else {
    m_systemTray->SetTooltip(L"WebCAMO - Disconnected");
  }
}

bool Application::CreateMessageWindow(HINSTANCE hInstance) {
  WNDCLASSEXW wc = {};
  wc.cbSize = sizeof(WNDCLASSEXW);
  wc.lpfnWndProc = WndProc;
  wc.hInstance = hInstance;
  wc.lpszClassName = L"WebCAMO_MessageWindow";

  if (!RegisterClassExW(&wc)) {
    return false;
  }

  m_hwnd = CreateWindowExW(0, L"WebCAMO_MessageWindow", L"WebCAMO", 0, 0, 0, 0,
                           0, HWND_MESSAGE, nullptr, hInstance, nullptr);

  return m_hwnd != nullptr;
}

LRESULT CALLBACK Application::WndProc(HWND hwnd, UINT msg, WPARAM wParam,
                                      LPARAM lParam) {
  switch (msg) {
  case WM_USER + 1: // Tray callback
    if (lParam == WM_RBUTTONUP || lParam == WM_LBUTTONUP) {
      Instance().m_systemTray->ShowMenu(hwnd);
    }
    return 0;

  case WM_CLOSE:
    Instance().Shutdown();
    PostQuitMessage(0);
    return 0;
  }

  return DefWindowProcW(hwnd, msg, wParam, lParam);
}

} // namespace WebCAMO
