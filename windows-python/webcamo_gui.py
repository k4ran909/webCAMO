"""
WebCAMO Desktop Client - Optimized for Fast Connection
======================================================
- Faster UDP discovery (100ms intervals)
- Immediate connection on discovery
- Built-in virtual camera (no OBS required - uses DirectShow filter)
- Video preview window
"""

import socket
import struct
import threading
import cv2
import numpy as np
from queue import Queue, Empty
import time
import ctypes
import os

# GUI imports
import tkinter as tk
from tkinter import ttk
from PIL import Image, ImageTk

# Configuration
DISCOVERY_PORT = 9001
STREAM_PORT = 9000
BROADCAST_MESSAGE = b"WEBCAMO_DISCOVER"
VIDEO_WIDTH = 1280
VIDEO_HEIGHT = 720
FPS = 30

# Try to load virtual camera
HAS_VIRTUAL_CAM = False
try:
    import pyvirtualcam
    HAS_VIRTUAL_CAM = True
except ImportError:
    pass


class WebCAMOApp:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("WebCAMO")
        self.root.geometry("850x550")
        self.root.configure(bg="#0d0d0d")
        self.root.resizable(True, True)
        
        # Set window icon (if available)
        try:
            self.root.iconbitmap(default='')
        except:
            pass
        
        # State
        self.running = True
        self.connected = False
        self.streaming = False
        self.client_socket = None
        self.frame_queue = Queue(maxsize=2)  # Smaller queue for lower latency
        self.current_frame = None
        self.virtual_cam = None
        self.fps_counter = 0
        self.fps = 0
        self.last_fps_time = time.time()
        
        # Build UI
        self.setup_ui()
        
        # Start background threads
        self.start_discovery_server()
        self.start_stream_server()
        self.update_preview()
        self.update_fps()
        
        # Window close handler
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        
    def setup_ui(self):
        """Create the main UI"""
        # Header
        header = tk.Frame(self.root, bg="#1a1a1a", height=50)
        header.pack(fill=tk.X)
        header.pack_propagate(False)
        
        title = tk.Label(header, text="📷 WebCAMO", font=("Segoe UI", 18, "bold"),
                        bg="#1a1a1a", fg="#00ff88")
        title.pack(side=tk.LEFT, padx=15, pady=10)
        
        self.fps_label = tk.Label(header, text="", font=("Segoe UI", 10),
                                  bg="#1a1a1a", fg="#666666")
        self.fps_label.pack(side=tk.LEFT, padx=10, pady=10)
        
        self.status_label = tk.Label(header, text="🔍 Searching...",
                                     font=("Segoe UI", 11), bg="#1a1a1a", fg="#ffaa00")
        self.status_label.pack(side=tk.RIGHT, padx=15, pady=10)
        
        # Main content - video preview
        content = tk.Frame(self.root, bg="#0d0d0d")
        content.pack(fill=tk.BOTH, expand=True, padx=10, pady=5)
        
        self.canvas = tk.Canvas(content, bg="#000000", highlightthickness=1,
                               highlightbackground="#333333")
        self.canvas.pack(fill=tk.BOTH, expand=True)
        
        # Draw placeholder
        self.draw_placeholder()
        
        # Footer
        footer = tk.Frame(self.root, bg="#1a1a1a", height=45)
        footer.pack(fill=tk.X, side=tk.BOTTOM)
        footer.pack_propagate(False)
        
        # Virtual camera status
        if HAS_VIRTUAL_CAM:
            vcam_text = "✅ Virtual Camera Ready"
            vcam_color = "#00ff88"
        else:
            vcam_text = "⚠️ Install pyvirtualcam for virtual camera"
            vcam_color = "#ff8800"
            
        vcam_label = tk.Label(footer, text=vcam_text, font=("Segoe UI", 9),
                             bg="#1a1a1a", fg=vcam_color)
        vcam_label.pack(side=tk.LEFT, padx=15, pady=12)
        
        # IP info
        try:
            hostname = socket.gethostname()
            local_ip = socket.gethostbyname(hostname)
            ip_text = f"IP: {local_ip}"
        except:
            ip_text = "WiFi Required"
            
        ip_label = tk.Label(footer, text=ip_text, font=("Segoe UI", 9),
                           bg="#1a1a1a", fg="#555555")
        ip_label.pack(side=tk.RIGHT, padx=15, pady=12)
        
    def draw_placeholder(self):
        """Draw waiting message on canvas"""
        self.canvas.delete("all")
        w = max(self.canvas.winfo_width(), 400)
        h = max(self.canvas.winfo_height(), 300)
        
        self.canvas.create_text(w//2, h//2 - 30, text="📱",
                               font=("Segoe UI Emoji", 40), fill="#333333")
        self.canvas.create_text(w//2, h//2 + 20,
                               text="Open WebCAMO on your phone",
                               font=("Segoe UI", 13), fill="#555555")
        self.canvas.create_text(w//2, h//2 + 50,
                               text="Same WiFi • Auto-connects",
                               font=("Segoe UI", 10), fill="#444444")
                               
    def start_discovery_server(self):
        """Start UDP discovery responder - FAST"""
        def discovery_loop():
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.settimeout(0.1)  # 100ms timeout for fast response
            
            try:
                sock.bind(('0.0.0.0', DISCOVERY_PORT))
            except Exception as e:
                print(f"Discovery bind failed: {e}")
                return
            
            while self.running:
                try:
                    data, addr = sock.recvfrom(1024)
                    if data == BROADCAST_MESSAGE:
                        # Respond immediately
                        response = f"WEBCAMO_PC|{socket.gethostname()}|{STREAM_PORT}".encode()
                        sock.sendto(response, addr)
                except socket.timeout:
                    continue
                except Exception as e:
                    if self.running:
                        time.sleep(0.1)
            sock.close()
            
        thread = threading.Thread(target=discovery_loop, daemon=True)
        thread.start()
        
    def start_stream_server(self):
        """Start TCP stream server"""
        def server_loop():
            server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)  # Disable Nagle for speed
            server.settimeout(0.5)  # Fast accept timeout
            
            try:
                server.bind(('0.0.0.0', STREAM_PORT))
                server.listen(1)
            except Exception as e:
                print(f"Server bind error: {e}")
                return
            
            while self.running:
                try:
                    client, addr = server.accept()
                    client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                    
                    self.connected = True
                    self.streaming = True
                    self.client_socket = client
                    self.update_status(f"✅ {addr[0]}", "#00ff88")
                    
                    # Start virtual camera
                    if HAS_VIRTUAL_CAM and self.virtual_cam is None:
                        try:
                            self.virtual_cam = pyvirtualcam.Camera(
                                width=VIDEO_WIDTH, height=VIDEO_HEIGHT, fps=FPS,
                                fmt=pyvirtualcam.PixelFormat.BGR)
                            print(f"Virtual camera: {self.virtual_cam.device}")
                        except Exception as e:
                            print(f"Virtual camera error: {e}")
                    
                    self.receive_frames(client)
                    
                    self.connected = False
                    self.streaming = False
                    self.client_socket = None
                    self.update_status("🔍 Searching...", "#ffaa00")
                    
                except socket.timeout:
                    continue
                except Exception as e:
                    if self.running:
                        time.sleep(0.1)
                        
            server.close()
            
        thread = threading.Thread(target=server_loop, daemon=True)
        thread.start()
        
    def receive_frames(self, client):
        """Receive MJPEG frames from phone - optimized"""
        client.settimeout(5.0)  # 5s timeout for frames
        
        try:
            while self.running and self.streaming:
                # Read frame size (4 bytes)
                size_data = self.receive_exact(client, 4)
                if not size_data:
                    break
                    
                frame_size = struct.unpack('<I', size_data)[0]
                if frame_size == 0 or frame_size > 5 * 1024 * 1024:  # Max 5MB
                    break
                
                # Read frame data
                jpeg_data = self.receive_exact(client, frame_size)
                if not jpeg_data:
                    break
                
                # Decode frame
                nparr = np.frombuffer(jpeg_data, np.uint8)
                frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
                
                if frame is not None:
                    # Update FPS counter
                    self.fps_counter += 1
                    
                    # Queue for preview (drop old frames)
                    if self.frame_queue.full():
                        try:
                            self.frame_queue.get_nowait()
                        except Empty:
                            pass
                    self.frame_queue.put(frame)
                    
                    # Send to virtual camera
                    if self.virtual_cam:
                        try:
                            if frame.shape[1] != VIDEO_WIDTH or frame.shape[0] != VIDEO_HEIGHT:
                                frame_resized = cv2.resize(frame, (VIDEO_WIDTH, VIDEO_HEIGHT))
                            else:
                                frame_resized = frame
                            self.virtual_cam.send(frame_resized)
                        except:
                            pass
                            
        except Exception as e:
            print(f"Receive error: {e}")
        finally:
            try:
                client.close()
            except:
                pass
            
    def receive_exact(self, sock, size):
        """Receive exact number of bytes"""
        data = b''
        while len(data) < size:
            try:
                chunk = sock.recv(min(size - len(data), 65536))  # 64KB chunks
                if not chunk:
                    return None
                data += chunk
            except:
                return None
        return data
        
    def update_preview(self):
        """Update video preview on canvas - 30fps"""
        if not self.running:
            return
            
        try:
            frame = self.frame_queue.get_nowait()
            
            canvas_w = self.canvas.winfo_width()
            canvas_h = self.canvas.winfo_height()
            
            if canvas_w > 10 and canvas_h > 10:
                # Calculate size maintaining aspect ratio
                h, w = frame.shape[:2]
                ratio = min(canvas_w / w, canvas_h / h)
                new_w = int(w * ratio)
                new_h = int(h * ratio)
                
                frame_resized = cv2.resize(frame, (new_w, new_h))
                frame_rgb = cv2.cvtColor(frame_resized, cv2.COLOR_BGR2RGB)
                
                img = Image.fromarray(frame_rgb)
                self.current_frame = ImageTk.PhotoImage(image=img)
                
                self.canvas.delete("all")
                x = (canvas_w - new_w) // 2
                y = (canvas_h - new_h) // 2
                self.canvas.create_image(x, y, anchor=tk.NW, image=self.current_frame)
                
        except Empty:
            if not self.connected:
                self.draw_placeholder()
                
        self.root.after(33, self.update_preview)
        
    def update_fps(self):
        """Update FPS display"""
        if not self.running:
            return
            
        now = time.time()
        elapsed = now - self.last_fps_time
        if elapsed >= 1.0:
            self.fps = self.fps_counter / elapsed
            self.fps_counter = 0
            self.last_fps_time = now
            
            if self.streaming:
                self.fps_label.config(text=f"{self.fps:.0f} FPS")
            else:
                self.fps_label.config(text="")
                
        self.root.after(500, self.update_fps)
        
    def update_status(self, text, color):
        """Update status label (thread-safe)"""
        def update():
            self.status_label.config(text=text, fg=color)
        self.root.after(0, update)
        
    def on_close(self):
        """Handle window close"""
        self.running = False
        self.streaming = False
        
        if self.client_socket:
            try:
                self.client_socket.close()
            except:
                pass
                
        if self.virtual_cam:
            try:
                self.virtual_cam.close()
            except:
                pass
                
        self.root.destroy()
        
    def run(self):
        """Start the application"""
        self.root.mainloop()


def main():
    # Hide console window on Windows
    if os.name == 'nt':
        try:
            ctypes.windll.user32.ShowWindow(
                ctypes.windll.kernel32.GetConsoleWindow(), 0)
        except:
            pass
    
    app = WebCAMOApp()
    app.run()


if __name__ == "__main__":
    main()
