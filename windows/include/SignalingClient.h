#pragma once

#include "Common.h"
#include <atomic>
#include <functional>
#include <string>
#include <thread>


namespace WebCAMO {

/**
 * WebSocket signaling client for WebRTC connection.
 * Handles SDP offer/answer and ICE candidate exchange.
 *
 * Note: This is a simplified implementation. For production,
 * consider using a full WebSocket library like libwebsockets.
 */
class SignalingClient {
public:
  // Callbacks
  using MessageCallback =
      std::function<void(const std::string &type, const std::string &payload)>;
  using StateCallback = std::function<void(ConnectionState state)>;

  SignalingClient();
  ~SignalingClient();

  // Connect to signaling server
  bool Connect(const std::string &url, const std::string &room);

  // Disconnect
  void Disconnect();

  // Send SDP answer
  void SendAnswer(const std::string &sdp);

  // Send ICE candidate
  void SendIceCandidate(const std::string &sdpMid, int sdpMLineIndex,
                        const std::string &candidate);

  // Callbacks
  void SetMessageCallback(MessageCallback callback) {
    m_messageCallback = callback;
  }
  void SetStateCallback(StateCallback callback) { m_stateCallback = callback; }

  // State
  ConnectionState GetState() const { return m_state; }
  bool IsConnected() const { return m_state == ConnectionState::Connected; }

private:
  void RunLoop();
  bool Send(const std::string &message);
  void OnMessage(const std::string &message);
  void SetState(ConnectionState state);

  // Simple HTTP/WebSocket implementation
  bool ConnectSocket(const std::string &host, int port);
  bool PerformHandshake(const std::string &path);
  std::string ReadFrame();
  bool WriteFrame(const std::string &data);

  SOCKET m_socket = INVALID_SOCKET;
  std::thread m_thread;
  std::atomic<bool> m_running{false};
  std::atomic<ConnectionState> m_state{ConnectionState::Disconnected};

  MessageCallback m_messageCallback;
  StateCallback m_stateCallback;

  std::string m_serverUrl;
  std::string m_room;
  std::mutex m_sendMutex;
};

} // namespace WebCAMO
