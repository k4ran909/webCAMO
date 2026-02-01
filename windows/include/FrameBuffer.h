#pragma once

#include "Common.h"

namespace WebCAMO {

/**
 * Thread-safe frame buffer for video frames.
 * Used to pass frames from WebRTC receiver to virtual camera.
 */
class FrameBuffer {
public:
  FrameBuffer(size_t maxFrames = 3);
  ~FrameBuffer();

  // Push a new frame (drops oldest if buffer is full)
  void Push(const VideoFrame &frame);
  void Push(VideoFrame &&frame);

  // Get the latest frame (blocking with timeout)
  bool Pop(VideoFrame &frame, int timeoutMs = 100);

  // Get the latest frame without removing it
  bool Peek(VideoFrame &frame);

  // Clear all frames
  void Clear();

  // Check if empty
  bool Empty() const;

  // Get current size
  size_t Size() const;

private:
  std::queue<VideoFrame> m_frames;
  mutable std::mutex m_mutex;
  std::condition_variable m_condition;
  size_t m_maxFrames;
};

} // namespace WebCAMO
