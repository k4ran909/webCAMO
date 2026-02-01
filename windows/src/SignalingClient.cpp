#include "SignalingClient.h"
#include <iomanip>
#include <random>
#include <sstream>


// JSON parsing (simple implementation)
#include <regex>

namespace WebCAMO {

SignalingClient::SignalingClient() {
  // Initialize Winsock
  WSADATA wsaData;
  WSAStartup(MAKEWORD(2, 2), &wsaData);
}

SignalingClient::~SignalingClient() {
  Disconnect();
  WSACleanup();
}

bool SignalingClient::Connect(const std::string &url, const std::string &room) {
  m_room = room;
  m_serverUrl = url;

  // Parse URL (ws://host:port)
  std::string host = "localhost";
  int port = 8080;
  std::string path = "/";

  // Simple URL parsing
  std::regex urlRegex(R"(ws://([^:/]+):?(\d+)?(/?.*)?)");
  std::smatch match;
  if (std::regex_match(url, match, urlRegex)) {
    host = match[1].str();
    if (match[2].matched) {
      port = std::stoi(match[2].str());
    }
    if (match[3].matched && !match[3].str().empty()) {
      path = match[3].str();
    }
  }

  // Add query parameters
  path += "?room=" + room + "&role=receiver";

  // Connect socket
  if (!ConnectSocket(host, port)) {
    SetState(ConnectionState::Error);
    return false;
  }

  // Perform WebSocket handshake
  if (!PerformHandshake(path)) {
    closesocket(m_socket);
    m_socket = INVALID_SOCKET;
    SetState(ConnectionState::Error);
    return false;
  }

  SetState(ConnectionState::Connected);

  // Start receive thread
  m_running = true;
  m_thread = std::thread(&SignalingClient::RunLoop, this);

  return true;
}

void SignalingClient::Disconnect() {
  m_running = false;

  if (m_socket != INVALID_SOCKET) {
    closesocket(m_socket);
    m_socket = INVALID_SOCKET;
  }

  if (m_thread.joinable()) {
    m_thread.join();
  }

  SetState(ConnectionState::Disconnected);
}

void SignalingClient::SendAnswer(const std::string &sdp) {
  std::ostringstream json;
  json << R"({"type":"answer","sdp":")" << sdp << R"("})";
  Send(json.str());
}

void SignalingClient::SendIceCandidate(const std::string &sdpMid,
                                       int sdpMLineIndex,
                                       const std::string &candidate) {
  std::ostringstream json;
  json << R"({"type":"ice-candidate","candidate":{"sdpMid":")" << sdpMid
       << R"(","sdpMLineIndex":)" << sdpMLineIndex << R"(,"candidate":")"
       << candidate << R"("}})";
  Send(json.str());
}

void SignalingClient::RunLoop() {
  while (m_running && m_socket != INVALID_SOCKET) {
    std::string message = ReadFrame();
    if (!message.empty()) {
      OnMessage(message);
    }
  }
}

void SignalingClient::OnMessage(const std::string &message) {
  // Simple JSON parsing
  std::regex typeRegex(R"("type"\s*:\s*"([^"]+)") ");
      std::smatch match;

  std::string type;
  if (std::regex_search(message, match, typeRegex)) {
    type = match[1].str();
  }

  if (m_messageCallback) {
    m_messageCallback(type, message);
  }
}

bool SignalingClient::Send(const std::string &message) {
  std::lock_guard<std::mutex> lock(m_sendMutex);
  return WriteFrame(message);
}

void SignalingClient::SetState(ConnectionState state) {
  m_state = state;
  if (m_stateCallback) {
    m_stateCallback(state);
  }
}

bool SignalingClient::ConnectSocket(const std::string &host, int port) {
  // Resolve host
  struct addrinfo hints = {}, *result = nullptr;
  hints.ai_family = AF_INET;
  hints.ai_socktype = SOCK_STREAM;
  hints.ai_protocol = IPPROTO_TCP;

  if (getaddrinfo(host.c_str(), std::to_string(port).c_str(), &hints,
                  &result) != 0) {
    return false;
  }

  m_socket =
      socket(result->ai_family, result->ai_socktype, result->ai_protocol);
  if (m_socket == INVALID_SOCKET) {
    freeaddrinfo(result);
    return false;
  }

  if (connect(m_socket, result->ai_addr, (int)result->ai_addrlen) ==
      SOCKET_ERROR) {
    closesocket(m_socket);
    m_socket = INVALID_SOCKET;
    freeaddrinfo(result);
    return false;
  }

  freeaddrinfo(result);
  return true;
}

bool SignalingClient::PerformHandshake(const std::string &path) {
  // Generate WebSocket key
  std::random_device rd;
  std::mt19937 gen(rd());
  std::uniform_int_distribution<> dis(0, 255);

  std::string key;
  for (int i = 0; i < 16; i++) {
    key += static_cast<char>(dis(gen));
  }

  // Base64 encode (simplified)
  static const char base64_chars[] =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  std::string encodedKey;
  for (size_t i = 0; i < key.size(); i += 3) {
    unsigned int n = (static_cast<unsigned char>(key[i]) << 16);
    if (i + 1 < key.size())
      n |= (static_cast<unsigned char>(key[i + 1]) << 8);
    if (i + 2 < key.size())
      n |= static_cast<unsigned char>(key[i + 2]);

    encodedKey += base64_chars[(n >> 18) & 0x3F];
    encodedKey += base64_chars[(n >> 12) & 0x3F];
    encodedKey += (i + 1 < key.size()) ? base64_chars[(n >> 6) & 0x3F] : '=';
    encodedKey += (i + 2 < key.size()) ? base64_chars[n & 0x3F] : '=';
  }

  // Send handshake request
  std::ostringstream request;
  request << "GET " << path << " HTTP/1.1\r\n"
          << "Host: localhost\r\n"
          << "Upgrade: websocket\r\n"
          << "Connection: Upgrade\r\n"
          << "Sec-WebSocket-Key: " << encodedKey << "\r\n"
          << "Sec-WebSocket-Version: 13\r\n"
          << "\r\n";

  std::string requestStr = request.str();
  if (send(m_socket, requestStr.c_str(), (int)requestStr.size(), 0) ==
      SOCKET_ERROR) {
    return false;
  }

  // Read response
  char buffer[1024];
  int received = recv(m_socket, buffer, sizeof(buffer) - 1, 0);
  if (received <= 0) {
    return false;
  }
  buffer[received] = '\0';

  // Check for 101 Switching Protocols
  return strstr(buffer, "101") != nullptr;
}

std::string SignalingClient::ReadFrame() {
  // Read WebSocket frame header
  unsigned char header[2];
  int received = recv(m_socket, (char *)header, 2, 0);
  if (received != 2) {
    return "";
  }

  // Get payload length
  uint64_t payloadLen = header[1] & 0x7F;
  if (payloadLen == 126) {
    unsigned char len16[2];
    if (recv(m_socket, (char *)len16, 2, 0) != 2)
      return "";
    payloadLen = (len16[0] << 8) | len16[1];
  } else if (payloadLen == 127) {
    unsigned char len64[8];
    if (recv(m_socket, (char *)len64, 8, 0) != 8)
      return "";
    payloadLen = 0;
    for (int i = 0; i < 8; i++) {
      payloadLen = (payloadLen << 8) | len64[i];
    }
  }

  // Read payload
  std::string payload;
  payload.resize(payloadLen);
  size_t totalReceived = 0;
  while (totalReceived < payloadLen) {
    received = recv(m_socket, &payload[totalReceived],
                    (int)(payloadLen - totalReceived), 0);
    if (received <= 0)
      break;
    totalReceived += received;
  }

  return payload;
}

bool SignalingClient::WriteFrame(const std::string &data) {
  std::vector<unsigned char> frame;

  // Opcode: text frame
  frame.push_back(0x81);

  // Payload length with mask bit
  if (data.size() <= 125) {
    frame.push_back(0x80 | static_cast<unsigned char>(data.size()));
  } else if (data.size() <= 65535) {
    frame.push_back(0x80 | 126);
    frame.push_back((data.size() >> 8) & 0xFF);
    frame.push_back(data.size() & 0xFF);
  } else {
    frame.push_back(0x80 | 127);
    for (int i = 7; i >= 0; i--) {
      frame.push_back((data.size() >> (i * 8)) & 0xFF);
    }
  }

  // Masking key (client must mask)
  unsigned char mask[4] = {0x12, 0x34, 0x56, 0x78};
  frame.insert(frame.end(), mask, mask + 4);

  // Masked payload
  for (size_t i = 0; i < data.size(); i++) {
    frame.push_back(data[i] ^ mask[i % 4]);
  }

  return send(m_socket, (const char *)frame.data(), (int)frame.size(), 0) !=
         SOCKET_ERROR;
}

} // namespace WebCAMO
