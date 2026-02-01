#pragma once

#include "Common.h"
#include "FrameBuffer.h"

namespace WebCAMO {

/**
 * WebRTC receiver that handles incoming video stream.
 *
 * Note: This is a stub implementation. Full WebRTC requires
 * the native WebRTC library. For an MVP, consider:
 * 1. Using libdatachannel (easier WebRTC in C++)
 * 2. Using prebuilt WebRTC binaries
 * 3. Simple raw frame streaming without full WebRTC
 */
class WebRTCReceiver {
public:
  WebRTCReceiver(std::shared_ptr<FrameBuffer> frameBuffer);
  ~WebRTCReceiver();

  // Initialize WebRTC
  bool Initialize();

  // Handle incoming SDP offer
  std::string CreateAnswer(const std::string &offer);

  // Add ICE candidate
  void AddIceCandidate(const std::string &sdpMid, int sdpMLineIndex,
                       const std::string &candidate);

  // Set callbacks
  void SetIceCandidateCallback(
      std::function<void(const std::string &, int, const std::string &)>
          callback) {
    m_iceCandidateCallback = callback;
  }

  // State
  bool IsConnected() const { return m_connected; }

  // Shutdown
  void Shutdown();

private:
  void OnVideoFrame(const uint8_t *data, int width, int height);

  std::shared_ptr<FrameBuffer> m_frameBuffer;
  std::function<void(const std::string &, int, const std::string &)>
      m_iceCandidateCallback;
  std::atomic<bool> m_connected{false};

  // WebRTC native objects would go here
  // For now, this is a stub that simulates receiving frames
};

} // namespace WebCAMO
