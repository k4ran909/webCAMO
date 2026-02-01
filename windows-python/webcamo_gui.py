"""
WebCAMO Desktop Client - GUI Version with Auto-Discovery
========================================================
A polished Windows application like Iriun Webcam.
- No IP entry required - automatic discovery
- Video preview window
- System tray integration
- No command prompt

Requirements:
    pip install opencv-python pyvirtualcam numpy pillow
"""

import socket
import struct
import threading
import cv2
import numpy as np
from queue import Queue, Empty
import sys
import time

# GUI imports
import tkinter as tk
from tkinter import ttk
from PIL import Image, ImageTk

try:
    import pyvirtualcam
    HAS_VIRTUAL_CAM = True
except ImportError:
    HAS_VIRTUAL_CAM = False

# Configuration
DISCOVERY_PORT = 9001
STREAM_PORT = 9000
BROADCAST_MESSAGE = b"WEBCAMO_DISCOVER"
VIDEO_WIDTH = 1280
VIDEO_HEIGHT = 720
FPS = 30


class WebCAMOApp:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("WebCAMO")
        self.root.geometry("800x520")
        self.root.configure(bg="#1a1a1a")
        self.root.resizable(True, True)
        
        # State
        self.running = True
        self.connected = False
        self.streaming = False
        self.client_socket = None
        self.frame_queue = Queue(maxsize=3)
        self.discovered_devices = {}
        self.current_frame = None
        self.virtual_cam = None
        
        # Build UI
        self.setup_ui()
        
        # Start background threads
        self.start_discovery_server()
        self.start_stream_server()
        self.update_preview()
        
        # Window close handler
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        
    def setup_ui(self):
        """Create the main UI"""
        # Header
        header = tk.Frame(self.root, bg="#2a2a2a", height=60)
        header.pack(fill=tk.X, padx=0, pady=0)
        header.pack_propagate(False)
        
        title = tk.Label(header, text="📷 WebCAMO", font=("Segoe UI", 20, "bold"),
                        bg="#2a2a2a", fg="#4CAF50")
        title.pack(side=tk.LEFT, padx=20, pady=15)
        
        self.status_label = tk.Label(header, text="⏳ Waiting for phone...",
                                     font=("Segoe UI", 12), bg="#2a2a2a", fg="#888888")
        self.status_label.pack(side=tk.RIGHT, padx=20, pady=15)
        
        # Main content
        content = tk.Frame(self.root, bg="#1a1a1a")
        content.pack(fill=tk.BOTH, expand=True, padx=20, pady=20)
        
        # Video preview canvas
        self.canvas = tk.Canvas(content, bg="#000000", highlightthickness=0)
        self.canvas.pack(fill=tk.BOTH, expand=True)
        
        # Draw placeholder
        self.draw_placeholder()
        
        # Footer
        footer = tk.Frame(self.root, bg="#2a2a2a", height=50)
        footer.pack(fill=tk.X, side=tk.BOTTOM)
        footer.pack_propagate(False)
        
        # Virtual camera status
        if HAS_VIRTUAL_CAM:
            vcam_text = "✅ Virtual camera ready"
            vcam_color = "#4CAF50"
        else:
            vcam_text = "⚠️ pyvirtualcam not installed"
            vcam_color = "#FF9800"
            
        vcam_label = tk.Label(footer, text=vcam_text, font=("Segoe UI", 10),
                             bg="#2a2a2a", fg=vcam_color)
        vcam_label.pack(side=tk.LEFT, padx=20, pady=15)
        
        # Instructions
        instr = tk.Label(footer, 
                        text="Install WebCAMO app on your phone and connect to same WiFi",
                        font=("Segoe UI", 10), bg="#2a2a2a", fg="#666666")
        instr.pack(side=tk.RIGHT, padx=20, pady=15)
        
    def draw_placeholder(self):
        """Draw waiting message on canvas"""
        self.canvas.delete("all")
        w = self.canvas.winfo_width() or 760
        h = self.canvas.winfo_height() or 380
        
        # Draw centered text
        self.canvas.create_text(w//2, h//2 - 20, text="📱",
                               font=("Segoe UI Emoji", 48), fill="#444444")
        self.canvas.create_text(w//2, h//2 + 40,
                               text="Open WebCAMO on your Android phone",
                               font=("Segoe UI", 14), fill="#666666")
        self.canvas.create_text(w//2, h//2 + 70,
                               text="Connection will happen automatically",
                               font=("Segoe UI", 11), fill="#555555")
                               
    def start_discovery_server(self):
        """Start UDP discovery responder"""
        def discovery_loop():
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.settimeout(1.0)
            
            try:
                sock.bind(('0.0.0.0', DISCOVERY_PORT))
            except:
                return
            
            while self.running:
                try:
                    data, addr = sock.recvfrom(1024)
                    if data == BROADCAST_MESSAGE:
                        # Respond with our details
                        response = f"WEBCAMO_PC|{socket.gethostname()}|{STREAM_PORT}".encode()
                        sock.sendto(response, addr)
                        
                        # Track discovered device
                        self.discovered_devices[addr[0]] = time.time()
                except socket.timeout:
                    continue
                except Exception as e:
                    if self.running:
                        print(f"Discovery error: {e}")
            sock.close()
            
        thread = threading.Thread(target=discovery_loop, daemon=True)
        thread.start()
        
    def start_stream_server(self):
        """Start TCP stream server"""
        def server_loop():
            server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.settimeout(1.0)
            
            try:
                server.bind(('0.0.0.0', STREAM_PORT))
                server.listen(1)
            except Exception as e:
                print(f"Server bind error: {e}")
                return
            
            while self.running:
                try:
                    client, addr = server.accept()
                    self.connected = True
                    self.client_socket = client
                    self.update_status(f"✅ Connected: {addr[0]}", "#4CAF50")
                    
                    # Start virtual camera if available
                    if HAS_VIRTUAL_CAM and self.virtual_cam is None:
                        try:
                            self.virtual_cam = pyvirtualcam.Camera(
                                width=VIDEO_WIDTH, height=VIDEO_HEIGHT, fps=FPS)
                        except:
                            pass
                    
                    self.receive_frames(client)
                    
                    self.connected = False
                    self.client_socket = None
                    self.update_status("⏳ Waiting for phone...", "#888888")
                    
                except socket.timeout:
                    continue
                except Exception as e:
                    if self.running:
                        print(f"Server error: {e}")
                        
            server.close()
            
        thread = threading.Thread(target=server_loop, daemon=True)
        thread.start()
        
    def receive_frames(self, client):
        """Receive MJPEG frames from phone"""
        try:
            while self.running and self.connected:
                # Read frame size (4 bytes)
                size_data = self.receive_exact(client, 4)
                if not size_data:
                    break
                    
                frame_size = struct.unpack('<I', size_data)[0]
                if frame_size == 0 or frame_size > 10 * 1024 * 1024:
                    break
                
                # Read frame data
                jpeg_data = self.receive_exact(client, frame_size)
                if not jpeg_data:
                    break
                
                # Decode frame
                nparr = np.frombuffer(jpeg_data, np.uint8)
                frame = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
                
                if frame is not None:
                    # Queue for preview
                    while self.frame_queue.full():
                        try:
                            self.frame_queue.get_nowait()
                        except Empty:
                            break
                    self.frame_queue.put(frame)
                    
                    # Send to virtual camera
                    if self.virtual_cam:
                        try:
                            if frame.shape[1] != VIDEO_WIDTH or frame.shape[0] != VIDEO_HEIGHT:
                                frame = cv2.resize(frame, (VIDEO_WIDTH, VIDEO_HEIGHT))
                            frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                            self.virtual_cam.send(frame_rgb)
                        except:
                            pass
                            
        except Exception as e:
            print(f"Receive error: {e}")
        finally:
            client.close()
            
    def receive_exact(self, sock, size):
        """Receive exact number of bytes"""
        data = b''
        while len(data) < size:
            try:
                chunk = sock.recv(size - len(data))
                if not chunk:
                    return None
                data += chunk
            except:
                return None
        return data
        
    def update_preview(self):
        """Update video preview on canvas"""
        if not self.running:
            return
            
        try:
            frame = self.frame_queue.get_nowait()
            
            # Resize to fit canvas
            canvas_w = self.canvas.winfo_width()
            canvas_h = self.canvas.winfo_height()
            
            if canvas_w > 1 and canvas_h > 1:
                # Maintain aspect ratio
                h, w = frame.shape[:2]
                ratio = min(canvas_w / w, canvas_h / h)
                new_w = int(w * ratio)
                new_h = int(h * ratio)
                
                frame_resized = cv2.resize(frame, (new_w, new_h))
                frame_rgb = cv2.cvtColor(frame_resized, cv2.COLOR_BGR2RGB)
                
                # Convert to PhotoImage
                img = Image.fromarray(frame_rgb)
                self.current_frame = ImageTk.PhotoImage(image=img)
                
                # Draw on canvas
                self.canvas.delete("all")
                x = (canvas_w - new_w) // 2
                y = (canvas_h - new_h) // 2
                self.canvas.create_image(x, y, anchor=tk.NW, image=self.current_frame)
                
        except Empty:
            # No frame available
            if not self.connected:
                self.draw_placeholder()
                
        # Schedule next update
        self.root.after(33, self.update_preview)  # ~30 FPS
        
    def update_status(self, text, color):
        """Update status label (thread-safe)"""
        def update():
            self.status_label.config(text=text, fg=color)
        self.root.after(0, update)
        
    def on_close(self):
        """Handle window close"""
        self.running = False
        self.connected = False
        
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
    app = WebCAMOApp()
    app.run()


if __name__ == "__main__":
    main()
