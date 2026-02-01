#include "SystemTray.h"
#include <shellapi.h>

namespace WebCAMO {

SystemTray::SystemTray() {}

SystemTray::~SystemTray() {
  Remove();

  if (m_iconConnected)
    DestroyIcon(m_iconConnected);
  if (m_iconDisconnected)
    DestroyIcon(m_iconDisconnected);
}

bool SystemTray::Initialize(HWND hwnd) {
  // Create icons (simple colored circles)
  // In production, load from resources
  m_iconConnected = LoadIcon(nullptr, IDI_APPLICATION);
  m_iconDisconnected = LoadIcon(nullptr, IDI_WARNING);

  // Set up notification icon data
  m_nid.cbSize = sizeof(NOTIFYICONDATAW);
  m_nid.hWnd = hwnd;
  m_nid.uID = 1;
  m_nid.uFlags = NIF_ICON | NIF_TIP | NIF_MESSAGE;
  m_nid.uCallbackMessage = WM_USER + 1;
  m_nid.hIcon = m_iconDisconnected;
  wcscpy_s(m_nid.szTip, L"WebCAMO - Disconnected");

  if (Shell_NotifyIconW(NIM_ADD, &m_nid)) {
    m_initialized = true;
    return true;
  }

  return false;
}

void SystemTray::SetConnected(bool connected) {
  if (!m_initialized)
    return;

  m_nid.hIcon = connected ? m_iconConnected : m_iconDisconnected;
  Shell_NotifyIconW(NIM_MODIFY, &m_nid);
}

void SystemTray::SetTooltip(const std::wstring &tooltip) {
  if (!m_initialized)
    return;

  wcsncpy_s(m_nid.szTip, tooltip.c_str(), _TRUNCATE);
  Shell_NotifyIconW(NIM_MODIFY, &m_nid);
}

void SystemTray::ShowMenu(HWND hwnd) {
  HMENU hMenu = CreatePopupMenu();
  if (!hMenu)
    return;

  AppendMenuW(hMenu, MF_STRING, MENU_CONNECT, L"Connect");
  AppendMenuW(hMenu, MF_STRING, MENU_DISCONNECT, L"Disconnect");
  AppendMenuW(hMenu, MF_SEPARATOR, 0, nullptr);
  AppendMenuW(hMenu, MF_STRING, MENU_REGISTER, L"Register Virtual Camera");
  AppendMenuW(hMenu, MF_STRING, MENU_SETTINGS, L"Settings...");
  AppendMenuW(hMenu, MF_SEPARATOR, 0, nullptr);
  AppendMenuW(hMenu, MF_STRING, MENU_EXIT, L"Exit");

  POINT pt;
  GetCursorPos(&pt);

  SetForegroundWindow(hwnd);
  int cmd = TrackPopupMenu(hMenu, TPM_RETURNCMD | TPM_NONOTIFY, pt.x, pt.y, 0,
                           hwnd, nullptr);

  DestroyMenu(hMenu);

  if (cmd && m_menuCallback) {
    m_menuCallback(cmd);
  }
}

void SystemTray::Remove() {
  if (m_initialized) {
    Shell_NotifyIconW(NIM_DELETE, &m_nid);
    m_initialized = false;
  }
}

} // namespace WebCAMO
