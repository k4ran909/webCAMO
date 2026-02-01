"""
WebCAMO Windows Receiver - Python Version
=========================================
A ready-to-use virtual webcam receiver that works immediately.
Uses pyvirtualcam for virtual camera output.

Requirements:
    pip install websockets opencv-python pyvirtualcam numpy

Usage:
    python receiver.py [--server ws://192.168.1.x:8080] [--room webcamo]
"""

import asyncio
import argparse
import json
import cv2
import numpy as np
import sys
import threading
from queue import Queue, Empty

try:
    import websockets
except ImportError:
    print("Installing websockets...")
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "websockets"])
    import websockets

try:
    import pyvirtualcam
except ImportError:
    print("Installing pyvirtualcam...")
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "pyvirtualcam"])
    import pyvirtualcam

# Configuration
DEFAULT_SERVER = "ws://localhost:8080"
DEFAULT_ROOM = "webcamo"
VIDEO_WIDTH = 1280
VIDEO_HEIGHT = 720
FPS = 30


class WebCAMOReceiver:
    def __init__(self, server_url: str, room: str):
        self.server_url = server_url
        self.room = room
        self.frame_queue = Queue(maxsize=3)
        self.running = False
        self.connected = False
        
    async def connect(self):
        """Connect to signaling server and wait for video stream"""
        url = f"{self.server_url}?room={self.room}&role=receiver"
        print(f"Connecting to {url}...")
        
        try:
            async with websockets.connect(url) as ws:
                self.connected = True
                print("✓ Connected to signaling server")
                print("Waiting for Android sender to connect...")
                
                while self.running:
                    try:
                        message = await asyncio.wait_for(ws.recv(), timeout=1.0)
                        await self.handle_message(json.loads(message), ws)
                    except asyncio.TimeoutError:
                        continue
                    except websockets.ConnectionClosed:
                        print("Connection closed")
                        break
                        
        except Exception as e:
            print(f"Connection error: {e}")
            self.connected = False
    
    async def handle_message(self, msg: dict, ws):
        """Handle signaling messages"""
        msg_type = msg.get("type")
        
        if msg_type == "peer-joined":
            print("✓ Android sender connected!")
            
        elif msg_type == "offer":
            print("Received offer, sending answer...")
            # For WebRTC, we'd process SDP here
            # For now, acknowledge we're ready for direct streaming
            await ws.send(json.dumps({
                "type": "ready",
                "target": "sender"
            }))
            
        elif msg_type == "video-frame":
            # Base64 encoded JPEG frame from simple streaming mode
            import base64
            frame_data = base64.b64decode(msg.get("data", ""))
            self.process_frame(frame_data)
            
        elif msg_type == "peer-left":
            print("Android sender disconnected")
            
    def process_frame(self, jpeg_data: bytes):
        """Decode JPEG and add to queue"""
        try:
            # Decode JPEG
            nparr = np.frombuffer(jpeg_data, np.uint8)
            frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
            
            if frame is not None:
                # Drop old frames if queue is full
                while self.frame_queue.full():
                    try:
                        self.frame_queue.get_nowait()
                    except Empty:
                        break
                self.frame_queue.put(frame)
        except Exception as e:
            print(f"Frame decode error: {e}")
    
    def run_virtual_camera(self):
        """Output frames to virtual camera"""
        print("Starting virtual camera...")
        
        try:
            with pyvirtualcam.Camera(width=VIDEO_WIDTH, height=VIDEO_HEIGHT, fps=FPS) as cam:
                print(f"✓ Virtual camera started: {cam.device}")
                print("\nOpen Zoom/Teams/etc and select this camera!")
                
                # Create placeholder frame
                placeholder = np.zeros((VIDEO_HEIGHT, VIDEO_WIDTH, 3), dtype=np.uint8)
                cv2.putText(placeholder, "WebCAMO", (VIDEO_WIDTH//2 - 150, VIDEO_HEIGHT//2),
                           cv2.FONT_HERSHEY_SIMPLEX, 2, (0, 255, 0), 3)
                cv2.putText(placeholder, "Waiting for Android...", (VIDEO_WIDTH//2 - 200, VIDEO_HEIGHT//2 + 60),
                           cv2.FONT_HERSHEY_SIMPLEX, 1, (255, 255, 255), 2)
                
                while self.running:
                    try:
                        frame = self.frame_queue.get(timeout=0.033)
                        # Resize if needed
                        if frame.shape[1] != VIDEO_WIDTH or frame.shape[0] != VIDEO_HEIGHT:
                            frame = cv2.resize(frame, (VIDEO_WIDTH, VIDEO_HEIGHT))
                        # Convert BGR to RGB for pyvirtualcam
                        frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                        cam.send(frame_rgb)
                    except Empty:
                        # No frame available, send placeholder
                        cam.send(cv2.cvtColor(placeholder, cv2.COLOR_BGR2RGB))
                    cam.sleep_until_next_frame()
                    
        except Exception as e:
            print(f"Virtual camera error: {e}")
            print("\nNote: You may need to install OBS Virtual Camera or similar.")
            print("Download OBS: https://obsproject.com/")
            
    def start(self):
        """Start receiver"""
        self.running = True
        
        # Start virtual camera in separate thread
        cam_thread = threading.Thread(target=self.run_virtual_camera, daemon=True)
        cam_thread.start()
        
        # Run signaling in main thread
        try:
            asyncio.run(self.connect())
        except KeyboardInterrupt:
            print("\nShutting down...")
        finally:
            self.running = False


def main():
    parser = argparse.ArgumentParser(description="WebCAMO Windows Receiver")
    parser.add_argument("--server", default=DEFAULT_SERVER, help="Signaling server URL")
    parser.add_argument("--room", default=DEFAULT_ROOM, help="Room name")
    args = parser.parse_args()
    
    print("=" * 50)
    print("  WebCAMO Windows Receiver")
    print("=" * 50)
    print()
    
    receiver = WebCAMOReceiver(args.server, args.room)
    receiver.start()


if __name__ == "__main__":
    main()
