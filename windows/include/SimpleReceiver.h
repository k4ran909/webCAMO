#pragma once

#include "Common.h"
#include "FrameBuffer.h"
#include <vector>

namespace WebCAMO {

/**
 * Simple TCP video receiver - alternative to full WebRTC
 * Receives MJPEG frames over raw TCP connection
 */
class SimpleReceiver {
public:
  using StateCallback = std::function<void(ConnectionState)>;

  SimpleReceiver(std::shared_ptr<FrameBuffer> frameBuffer);
  ~SimpleReceiver();

  // Start listening for connections
  bool Start(int port = 9000);

  // Stop receiver
  void Stop();

  // State
  bool IsRunning() const { return m_running; }
  bool IsConnected() const { return m_clientSocket != INVALID_SOCKET; }

  // Callbacks
  void SetStateCallback(StateCallback callback) { m_stateCallback = callback; }

private:
  void AcceptLoop();
  void ReceiveLoop();
  bool ReceiveExact(uint8_t *buffer, size_t size);
  bool DecodeJPEG(const std::vector<uint8_t> &jpeg, VideoFrame &frame);

  std::shared_ptr<FrameBuffer> m_frameBuffer;
  StateCallback m_stateCallback;

  SOCKET m_listenSocket = INVALID_SOCKET;
  SOCKET m_clientSocket = INVALID_SOCKET;

  std::thread m_acceptThread;
  std::thread m_receiveThread;
  std::atomic<bool> m_running{false};
  int m_port = 9000;
};

} // namespace WebCAMO
