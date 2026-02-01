/**
 * WebCAMO Signaling Server
 * 
 * A lightweight WebSocket server that facilitates WebRTC peer connection
 * by relaying SDP offers/answers and ICE candidates between peers.
 * 
 * Rooms:
 * - Each room has one "sender" (Android phone) and one "receiver" (Windows desktop)
 * - When both peers join, they can exchange signaling messages
 */

const WebSocket = require('ws');

const PORT = process.env.PORT || 8080;
const wss = new WebSocket.Server({ port: PORT });

// Room management
const rooms = new Map();

console.log(`🚀 WebCAMO Signaling Server running on ws://0.0.0.0:${PORT}`);

/**
 * Parse URL query parameters
 */
function parseQuery(url) {
    const query = {};
    const queryString = url.split('?')[1];
    if (queryString) {
        queryString.split('&').forEach(param => {
            const [key, value] = param.split('=');
            query[key] = decodeURIComponent(value || '');
        });
    }
    return query;
}

/**
 * Get or create a room
 */
function getRoom(roomId) {
    if (!rooms.has(roomId)) {
        rooms.set(roomId, {
            sender: null,
            receiver: null
        });
    }
    return rooms.get(roomId);
}

/**
 * Send JSON message to a WebSocket client
 */
function sendMessage(ws, message) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(message));
    }
}

/**
 * Broadcast to the other peer in the room
 */
function sendToPeer(room, senderRole, message) {
    const targetRole = senderRole === 'sender' ? 'receiver' : 'sender';
    const targetWs = room[targetRole];
    sendMessage(targetWs, message);
}

wss.on('connection', (ws, req) => {
    const query = parseQuery(req.url);
    const roomId = query.room || 'default';
    const role = query.role || 'receiver';
    
    console.log(`📱 New connection: room=${roomId}, role=${role}`);
    
    const room = getRoom(roomId);
    
    // Check if role is already taken
    if (room[role]) {
        console.log(`⚠️ Role '${role}' already taken in room '${roomId}'`);
        sendMessage(ws, { type: 'error', message: `Role '${role}' already taken` });
        ws.close();
        return;
    }
    
    // Assign this connection to the room
    room[role] = ws;
    ws.roomId = roomId;
    ws.role = role;
    
    // Notify peer that a new participant joined
    const otherRole = role === 'sender' ? 'receiver' : 'sender';
    if (room[otherRole]) {
        sendMessage(room[otherRole], { type: 'peer-joined', role: role });
        sendMessage(ws, { type: 'peer-joined', role: otherRole });
    }
    
    console.log(`✅ Room '${roomId}': sender=${!!room.sender}, receiver=${!!room.receiver}`);
    
    ws.on('message', (data) => {
        try {
            const message = JSON.parse(data.toString());
            console.log(`📨 ${role} -> ${message.type}`);
            
            switch (message.type) {
                case 'offer':
                case 'answer':
                case 'ice-candidate':
                    // Relay to the other peer
                    sendToPeer(room, role, message);
                    break;
                    
                default:
                    console.log(`⚠️ Unknown message type: ${message.type}`);
            }
        } catch (e) {
            console.error('❌ Failed to parse message:', e.message);
        }
    });
    
    ws.on('close', () => {
        console.log(`👋 Disconnected: room=${roomId}, role=${role}`);
        
        // Remove from room
        if (room[role] === ws) {
            room[role] = null;
        }
        
        // Notify the other peer
        const otherRole = role === 'sender' ? 'receiver' : 'sender';
        if (room[otherRole]) {
            sendMessage(room[otherRole], { type: 'peer-left', role: role });
        }
        
        // Clean up empty rooms
        if (!room.sender && !room.receiver) {
            rooms.delete(roomId);
            console.log(`🗑️ Room '${roomId}' deleted (empty)`);
        }
    });
    
    ws.on('error', (err) => {
        console.error(`❌ WebSocket error: ${err.message}`);
    });
});

// Graceful shutdown
process.on('SIGINT', () => {
    console.log('\n🛑 Shutting down signaling server...');
    wss.close(() => {
        process.exit(0);
    });
});

// Log room status periodically
setInterval(() => {
    if (rooms.size > 0) {
        console.log(`📊 Active rooms: ${rooms.size}`);
        rooms.forEach((room, id) => {
            console.log(`   - ${id}: sender=${!!room.sender}, receiver=${!!room.receiver}`);
        });
    }
}, 30000);
