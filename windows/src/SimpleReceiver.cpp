/**
 * Simple Video Streaming Receiver
 *
 * This is a simplified alternative to full WebRTC that uses raw TCP sockets
 * to receive MJPEG frames. Much easier to implement and debug.
 *
 * Protocol:
 * - Frame header: 4 bytes (frame size as uint32 little-endian)
 * - Frame data: JPEG bytes
 */

#include "SimpleReceiver.h"
#include <iostream>

namespace WebCAMO {

SimpleReceiver::SimpleReceiver(std::shared_ptr<FrameBuffer> frameBuffer)
    : m_frameBuffer(frameBuffer) {
  // Initialize Winsock
  WSADATA wsaData;
  WSAStartup(MAKEWORD(2, 2), &wsaData);
}

SimpleReceiver::~SimpleReceiver() {
  Stop();
  WSACleanup();
}

bool SimpleReceiver::Start(int port) {
  if (m_running)
    return true;

  m_port = port;

  // Create listening socket
  m_listenSocket = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (m_listenSocket == INVALID_SOCKET) {
    return false;
  }

  // Allow address reuse
  int opt = 1;
  setsockopt(m_listenSocket, SOL_SOCKET, SO_REUSEADDR, (const char *)&opt,
             sizeof(opt));

  // Bind
  sockaddr_in serverAddr = {};
  serverAddr.sin_family = AF_INET;
  serverAddr.sin_addr.s_addr = INADDR_ANY;
  serverAddr.sin_port = htons(static_cast<u_short>(port));

  if (bind(m_listenSocket, (sockaddr *)&serverAddr, sizeof(serverAddr)) ==
      SOCKET_ERROR) {
    closesocket(m_listenSocket);
    m_listenSocket = INVALID_SOCKET;
    return false;
  }

  // Listen
  if (listen(m_listenSocket, 1) == SOCKET_ERROR) {
    closesocket(m_listenSocket);
    m_listenSocket = INVALID_SOCKET;
    return false;
  }

  m_running = true;
  m_acceptThread = std::thread(&SimpleReceiver::AcceptLoop, this);

  if (m_stateCallback) {
    m_stateCallback(ConnectionState::Connecting);
  }

  return true;
}

void SimpleReceiver::Stop() {
  m_running = false;

  if (m_clientSocket != INVALID_SOCKET) {
    closesocket(m_clientSocket);
    m_clientSocket = INVALID_SOCKET;
  }

  if (m_listenSocket != INVALID_SOCKET) {
    closesocket(m_listenSocket);
    m_listenSocket = INVALID_SOCKET;
  }

  if (m_acceptThread.joinable()) {
    m_acceptThread.join();
  }

  if (m_receiveThread.joinable()) {
    m_receiveThread.join();
  }

  if (m_stateCallback) {
    m_stateCallback(ConnectionState::Disconnected);
  }
}

void SimpleReceiver::AcceptLoop() {
  while (m_running && m_listenSocket != INVALID_SOCKET) {
    fd_set readSet;
    FD_ZERO(&readSet);
    FD_SET(m_listenSocket, &readSet);

    timeval timeout = {1, 0};

    if (select(0, &readSet, nullptr, nullptr, &timeout) > 0) {
      sockaddr_in clientAddr;
      int addrLen = sizeof(clientAddr);

      SOCKET client = accept(m_listenSocket, (sockaddr *)&clientAddr, &addrLen);
      if (client != INVALID_SOCKET) {
        // Close previous client if any
        if (m_clientSocket != INVALID_SOCKET) {
          closesocket(m_clientSocket);
          if (m_receiveThread.joinable()) {
            m_receiveThread.join();
          }
        }

        m_clientSocket = client;

        if (m_stateCallback) {
          m_stateCallback(ConnectionState::Connected);
        }

        // Start receiving frames
        m_receiveThread = std::thread(&SimpleReceiver::ReceiveLoop, this);
      }
    }
  }
}

void SimpleReceiver::ReceiveLoop() {
  while (m_running && m_clientSocket != INVALID_SOCKET) {
    // Read frame header (4 bytes = frame size)
    uint32_t frameSize = 0;
    if (!ReceiveExact((uint8_t *)&frameSize, 4)) {
      break;
    }

    // Sanity check
    if (frameSize == 0 || frameSize > 10 * 1024 * 1024) {
      break;
    }

    // Read frame data
    std::vector<uint8_t> jpegData(frameSize);
    if (!ReceiveExact(jpegData.data(), frameSize)) {
      break;
    }

    // Decode JPEG and push to buffer
    VideoFrame frame;
    if (DecodeJPEG(jpegData, frame)) {
      m_frameBuffer->Push(std::move(frame));
    }
  }

  if (m_stateCallback) {
    m_stateCallback(ConnectionState::Disconnected);
  }
}

bool SimpleReceiver::ReceiveExact(uint8_t *buffer, size_t size) {
  size_t received = 0;
  while (received < size && m_running) {
    int result = recv(m_clientSocket, (char *)(buffer + received),
                      static_cast<int>(size - received), 0);
    if (result <= 0) {
      return false;
    }
    received += result;
  }
  return received == size;
}

bool SimpleReceiver::DecodeJPEG(const std::vector<uint8_t> &jpeg,
                                VideoFrame &frame) {
  // Simple JPEG decoding - for production use libjpeg-turbo
  // For now, create a placeholder frame with the raw data size info

  // A real implementation would:
  // 1. Use libjpeg-turbo to decode the JPEG
  // 2. Convert to RGBA/RGB32 format
  // 3. Store in VideoFrame

  // Placeholder: create blank frame with pattern
  frame = VideoFrame(VIDEO_WIDTH, VIDEO_HEIGHT);
  frame.timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::system_clock::now().time_since_epoch())
                        .count();

  // Fill with gradient (placeholder until proper JPEG decode)
  for (int y = 0; y < VIDEO_HEIGHT; y++) {
    for (int x = 0; x < VIDEO_WIDTH; x++) {
      int offset = (y * VIDEO_WIDTH + x) * 4;
      frame.data[offset + 0] = static_cast<uint8_t>((jpeg.size() >> 8) & 0xFF);
      frame.data[offset + 1] = static_cast<uint8_t>(y % 256);
      frame.data[offset + 2] = static_cast<uint8_t>(x % 256);
      frame.data[offset + 3] = 255;
    }
  }

  return true;
}

} // namespace WebCAMO
