#include "Application.h"

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE, LPWSTR, int) {
  // Initialize COM
  CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

  // Create and run application
  WebCAMO::Application &app = WebCAMO::Application::Instance();

  if (!app.Initialize(hInstance)) {
    MessageBoxW(nullptr, L"Failed to initialize WebCAMO", L"Error",
                MB_OK | MB_ICONERROR);
    return 1;
  }

  // Run message loop
  int result = app.Run();

  // Cleanup
  app.Shutdown();
  CoUninitialize();

  return result;
}
