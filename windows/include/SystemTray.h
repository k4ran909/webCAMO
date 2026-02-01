#pragma once

#include "Common.h"

namespace WebCAMO {

/**
 * System tray icon and menu
 */
class SystemTray {
public:
  using MenuCallback = std::function<void(int)>;

  enum MenuId {
    MENU_CONNECT = 1001,
    MENU_DISCONNECT,
    MENU_STATUS,
    MENU_SETTINGS,
    MENU_REGISTER,
    MENU_EXIT
  };

  SystemTray();
  ~SystemTray();

  // Initialize system tray
  bool Initialize(HWND hwnd);

  // Update tray icon and tooltip
  void SetConnected(bool connected);
  void SetTooltip(const std::wstring &tooltip);

  // Show context menu
  void ShowMenu(HWND hwnd);

  // Set menu callback
  void SetMenuCallback(MenuCallback callback) { m_menuCallback = callback; }

  // Remove from system tray
  void Remove();

private:
  NOTIFYICONDATAW m_nid = {};
  bool m_initialized = false;
  MenuCallback m_menuCallback;
  HICON m_iconConnected = nullptr;
  HICON m_iconDisconnected = nullptr;
};

} // namespace WebCAMO
