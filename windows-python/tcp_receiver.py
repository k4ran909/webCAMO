"""
WebCAMO Simple TCP Receiver
===========================
Alternative receiver that uses direct TCP for video frames.
Even simpler than WebRTC - just receives MJPEG frames over TCP.

Requirements:
    pip install opencv-python pyvirtualcam numpy

Usage:
    python tcp_receiver.py [--port 9000]
"""

import socket
import struct
import threading
import argparse
import cv2
import numpy as np
from queue import Queue, Empty
import sys

try:
    import pyvirtualcam
    HAS_VIRTUAL_CAM = True
except ImportError:
    print("pyvirtualcam not installed. Will show preview window instead.")
    print("To get virtual camera: pip install pyvirtualcam")
    HAS_VIRTUAL_CAM = False

# Configuration
DEFAULT_PORT = 9000
VIDEO_WIDTH = 1280
VIDEO_HEIGHT = 720
FPS = 30


class TCPReceiver:
    def __init__(self, port: int):
        self.port = port
        self.frame_queue = Queue(maxsize=3)
        self.running = False
        self.connected = False
        
    def receive_exact(self, sock: socket.socket, size: int) -> bytes:
        """Receive exact number of bytes"""
        data = b''
        while len(data) < size:
            chunk = sock.recv(size - len(data))
            if not chunk:
                raise ConnectionError("Connection closed")
            data += chunk
        return data
    
    def server_loop(self):
        """Accept connections and receive frames"""
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(('0.0.0.0', self.port))
        server.listen(1)
        server.settimeout(1.0)
        
        print(f"Listening on port {self.port}...")
        print(f"Your IP addresses:")
        
        # Show local IPs
        hostname = socket.gethostname()
        try:
            for ip in socket.gethostbyname_ex(hostname)[2]:
                print(f"  - {ip}:{self.port}")
        except:
            print(f"  - localhost:{self.port}")
        
        print("\nEnter this in Android app to connect!")
        
        while self.running:
            try:
                client, addr = server.accept()
                print(f"\n✓ Android connected from {addr[0]}")
                self.connected = True
                
                try:
                    while self.running:
                        # Read frame size (4 bytes, little-endian)
                        size_data = self.receive_exact(client, 4)
                        frame_size = struct.unpack('<I', size_data)[0]
                        
                        if frame_size == 0 or frame_size > 10 * 1024 * 1024:
                            print(f"Invalid frame size: {frame_size}")
                            break
                        
                        # Read frame data
                        jpeg_data = self.receive_exact(client, frame_size)
                        
                        # Decode and queue
                        nparr = np.frombuffer(jpeg_data, np.uint8)
                        frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
                        
                        if frame is not None:
                            while self.frame_queue.full():
                                try:
                                    self.frame_queue.get_nowait()
                                except Empty:
                                    break
                            self.frame_queue.put(frame)
                            
                except Exception as e:
                    print(f"Receive error: {e}")
                finally:
                    client.close()
                    self.connected = False
                    print("Android disconnected")
                    
            except socket.timeout:
                continue
            except Exception as e:
                if self.running:
                    print(f"Server error: {e}")
                    
        server.close()
    
    def run_virtual_camera(self):
        """Output frames to virtual camera or preview window"""
        print("Starting virtual camera...")
        
        # Create placeholder
        placeholder = np.zeros((VIDEO_HEIGHT, VIDEO_WIDTH, 3), dtype=np.uint8)
        cv2.putText(placeholder, "WebCAMO", (VIDEO_WIDTH//2 - 150, VIDEO_HEIGHT//2),
                   cv2.FONT_HERSHEY_SIMPLEX, 2, (0, 255, 0), 3)
        cv2.putText(placeholder, "Waiting for Android...", (VIDEO_WIDTH//2 - 200, VIDEO_HEIGHT//2 + 60),
                   cv2.FONT_HERSHEY_SIMPLEX, 1, (255, 255, 255), 2)
        
        if HAS_VIRTUAL_CAM:
            try:
                with pyvirtualcam.Camera(width=VIDEO_WIDTH, height=VIDEO_HEIGHT, fps=FPS) as cam:
                    print(f"✓ Virtual camera: {cam.device}")
                    print("Select this camera in Zoom/Teams!")
                    
                    while self.running:
                        try:
                            frame = self.frame_queue.get(timeout=0.033)
                            if frame.shape[1] != VIDEO_WIDTH or frame.shape[0] != VIDEO_HEIGHT:
                                frame = cv2.resize(frame, (VIDEO_WIDTH, VIDEO_HEIGHT))
                            frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                            cam.send(frame_rgb)
                        except Empty:
                            cam.send(cv2.cvtColor(placeholder, cv2.COLOR_BGR2RGB))
                        cam.sleep_until_next_frame()
                        
            except Exception as e:
                print(f"Virtual camera error: {e}")
                self.run_preview_window()
        else:
            self.run_preview_window()
    
    def run_preview_window(self):
        """Fallback: show preview window"""
        print("Using preview window (no virtual camera)")
        
        cv2.namedWindow("WebCAMO", cv2.WINDOW_NORMAL)
        cv2.resizeWindow("WebCAMO", VIDEO_WIDTH // 2, VIDEO_HEIGHT // 2)
        
        placeholder = np.zeros((VIDEO_HEIGHT, VIDEO_WIDTH, 3), dtype=np.uint8)
        cv2.putText(placeholder, "Waiting for Android...", (VIDEO_WIDTH//2 - 200, VIDEO_HEIGHT//2),
                   cv2.FONT_HERSHEY_SIMPLEX, 1, (255, 255, 255), 2)
        
        while self.running:
            try:
                frame = self.frame_queue.get(timeout=0.033)
            except Empty:
                frame = placeholder
                
            cv2.imshow("WebCAMO", frame)
            if cv2.waitKey(1) & 0xFF == ord('q'):
                self.running = False
                break
                
        cv2.destroyAllWindows()
        
    def start(self):
        """Start receiver"""
        self.running = True
        
        # Start server in thread
        server_thread = threading.Thread(target=self.server_loop, daemon=True)
        server_thread.start()
        
        # Run camera in main thread
        try:
            self.run_virtual_camera()
        except KeyboardInterrupt:
            print("\nShutting down...")
        finally:
            self.running = False


def main():
    parser = argparse.ArgumentParser(description="WebCAMO TCP Receiver")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="TCP port to listen on")
    args = parser.parse_args()
    
    print("=" * 50)
    print("  WebCAMO TCP Receiver")
    print("=" * 50)
    print()
    
    receiver = TCPReceiver(args.port)
    receiver.start()


if __name__ == "__main__":
    main()
