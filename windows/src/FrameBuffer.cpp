#include "FrameBuffer.h"

namespace WebCAMO {

FrameBuffer::FrameBuffer(size_t maxFrames) : m_maxFrames(maxFrames) {}

FrameBuffer::~FrameBuffer() { Clear(); }

void FrameBuffer::Push(const VideoFrame &frame) {
  std::lock_guard<std::mutex> lock(m_mutex);

  // Drop oldest frame if buffer is full
  while (m_frames.size() >= m_maxFrames) {
    m_frames.pop();
  }

  m_frames.push(frame);
  m_condition.notify_one();
}

void FrameBuffer::Push(VideoFrame &&frame) {
  std::lock_guard<std::mutex> lock(m_mutex);

  while (m_frames.size() >= m_maxFrames) {
    m_frames.pop();
  }

  m_frames.push(std::move(frame));
  m_condition.notify_one();
}

bool FrameBuffer::Pop(VideoFrame &frame, int timeoutMs) {
  std::unique_lock<std::mutex> lock(m_mutex);

  if (m_condition.wait_for(lock, std::chrono::milliseconds(timeoutMs),
                           [this] { return !m_frames.empty(); })) {
    frame = std::move(m_frames.front());
    m_frames.pop();
    return true;
  }

  return false;
}

bool FrameBuffer::Peek(VideoFrame &frame) {
  std::lock_guard<std::mutex> lock(m_mutex);

  if (m_frames.empty()) {
    return false;
  }

  frame = m_frames.back();
  return true;
}

void FrameBuffer::Clear() {
  std::lock_guard<std::mutex> lock(m_mutex);
  while (!m_frames.empty()) {
    m_frames.pop();
  }
}

bool FrameBuffer::Empty() const {
  std::lock_guard<std::mutex> lock(m_mutex);
  return m_frames.empty();
}

size_t FrameBuffer::Size() const {
  std::lock_guard<std::mutex> lock(m_mutex);
  return m_frames.size();
}

} // namespace WebCAMO
