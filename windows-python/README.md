# WebCAMO Python Receiver

Simple Python-based receiver that works immediately without C++ compilation.

## Quick Start

```bash
# Just run the start script!
start.bat
```

Or manually:
```bash
pip install opencv-python pyvirtualcam numpy
python tcp_receiver.py
```

## Options

### TCP Receiver (Recommended)
```bash
python tcp_receiver.py --port 9000
```
- Direct TCP connection from Android
- Simpler and more reliable
- Works with preview window or virtual camera

### WebSocket Receiver
```bash
python receiver.py --server ws://192.168.1.x:8080 --room webcamo
```
- Uses signaling server
- WebRTC-compatible

## Virtual Camera

For virtual camera output, you need OBS Virtual Camera or similar:

1. Download OBS Studio: https://obsproject.com/
2. Install and run once (this installs virtual camera)
3. Run the receiver - it will use OBS Virtual Camera

Or pyvirtualcam with other backends.

## Preview Mode

If virtual camera isn't available, a preview window opens instead.
Press 'Q' to quit.
