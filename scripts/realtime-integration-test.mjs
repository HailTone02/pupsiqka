import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { setTimeout as delay } from "node:timers/promises";
import { randomUUID } from "node:crypto";

const properties = new Map(
  readFileSync(new URL("../local.properties", import.meta.url), "utf8")
    .split(/\r?\n/)
    .filter((line) => line.trim() && !line.trim().startsWith("#") && !line.trim().startsWith("!"))
    .map((line) => {
      const separator = line.indexOf("=");
      return separator < 0
        ? [line.trim(), ""]
        : [line.slice(0, separator).trim(), line.slice(separator + 1).trim()];
    }),
);

const supabaseUrl = properties.get("supabase.url");
const publishableKey = properties.get("supabase.publishableKey");
if (!supabaseUrl || !publishableKey) {
  throw new Error("Supabase URL or publishable key is missing from local.properties");
}
if (typeof WebSocket === "undefined") {
  throw new Error("Node.js 22 or newer with global WebSocket is required");
}

const socketUrl = new URL(supabaseUrl);
socketUrl.protocol = socketUrl.protocol === "https:" ? "wss:" : "ws:";
socketUrl.pathname = `${socketUrl.pathname.replace(/\/+$/, "")}/realtime/v1/websocket`;
socketUrl.search = "";
socketUrl.searchParams.set("apikey", publishableKey);
socketUrl.searchParams.set("vsn", "1.0.0");
socketUrl.searchParams.set("client", "hailtone-codespaces-integration/1.0");

const runId = randomUUID();
const callId = `codespaces-${runId}`;
const wrongCallId = `wrong-${runId}`;
const globalChannel = "pupsikcall-signaling";
const presenceChannel = "pupsikcall-presence";
const callChannel = `pupsikcall-call-${callId}`;
const presenceTopic = `realtime:${presenceChannel}`;
const callTopic = `realtime:${callChannel}`;
const sleep = (milliseconds) => delay(milliseconds);

const signalingSource = readFileSync(
  new URL("../app/src/main/java/com/pupsikcall/app/SupabaseCallSignaling.kt", import.meta.url),
  "utf8",
);
const transportSource = readFileSync(
  new URL("../app/src/main/java/com/pupsikcall/app/RealtimeTransport.kt", import.meta.url),
  "utf8",
);
const presenceSetupStart = signalingSource.indexOf("private fun startPresence");
const presenceFlowStart = signalingSource.indexOf("channel.presenceChangeFlow()", presenceSetupStart);
const presenceSubscribeStart = signalingSource.indexOf("channel.subscribe(false)", presenceSetupStart);
assert.ok(presenceSetupStart >= 0 && presenceFlowStart > presenceSetupStart);
assert.ok(presenceSubscribeStart > presenceFlowStart);
assert.ok(signalingSource.slice(presenceSetupStart, presenceFlowStart).includes("CoroutineStart.UNDISPATCHED"));
assert.ok(signalingSource.includes("httpEngine = createSupabaseRealtimeHttpEngine()"));
assert.ok(transportSource.includes("OkHttp.create()"));

function topicName(channel) {
  return `realtime:${channel}`;
}

function hasDevice(value, deviceId) {
  if (!value || typeof value !== "object") return false;
  if (value.deviceId === deviceId) return true;
  return Object.values(value).some((nested) => hasDevice(nested, deviceId));
}

function hasPresenceSession(value, sessionKey) {
  if (value === null || typeof value !== "object") return false;
  return Object.entries(value).some(([key, nested]) =>
    key === sessionKey || hasPresenceSession(nested, sessionKey));
}

function signalPayload(message) {
  if (message?.event !== "broadcast" || message.payload?.event !== "signal") return null;
  return message.payload.payload;
}

function acceptsSignal(signal, receiverId, expectedCallId, expectedPeerId) {
  return signal?.toDeviceId === receiverId &&
    signal?.callId === expectedCallId &&
    signal?.fromDeviceId === expectedPeerId &&
    signal?.fromDeviceId !== receiverId;
}

class RealtimePeer {
  constructor(deviceId, presenceKey) {
    this.deviceId = deviceId;
    this.presenceKey = presenceKey;
    this.socket = null;
    this.messages = [];
    this.channels = new Map();
    this.ref = 0;
    this.heartbeat = null;
    this.closing = false;
    this.socketFailure = null;
  }

  async connect() {
    this.socket = new WebSocket(socketUrl);
    this.socket.addEventListener("message", (event) => {
      let message;
      try {
        const data = typeof event.data === "string" ? event.data : String(event.data);
        message = JSON.parse(data);
      } catch {
        return;
      }
      this.messages.push(message);
    });
    this.socket.addEventListener("error", () => {
      this.socketFailure = new Error("Realtime websocket connection error");
    });
    this.socket.addEventListener("close", () => {
      if (!this.closing) this.socketFailure = new Error("Realtime websocket closed unexpectedly");
    });
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error("Realtime websocket open timed out")), 12000);
      this.socket.addEventListener("open", () => {
        clearTimeout(timeout);
        resolve();
      }, { once: true });
      this.socket.addEventListener("error", () => {
        clearTimeout(timeout);
        reject(new Error("Realtime websocket connection failed"));
      }, { once: true });
    });
    this.heartbeat = setInterval(() => {
      if (this.socket?.readyState === WebSocket.OPEN) {
        this.sendFrame("phoenix", "heartbeat", {}, null, null);
      }
    }, 12000);
  }

  mark() {
    return this.messages.length;
  }

  sendFrame(topic, event, payload, ref = String(++this.ref), joinRef = null) {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      throw new Error(`${this.deviceId} websocket is not open`);
    }
    this.socket.send(JSON.stringify({ topic, event, payload, ref, join_ref: joinRef }));
    return ref;
  }

  async waitFor(predicate, description, from = 0, timeoutMs = 12000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (this.socketFailure) throw this.socketFailure;
      const found = this.messages.slice(from).find(predicate);
      if (found) return found;
      await sleep(25);
    }
    throw new Error(`${this.deviceId} timed out waiting for ${description}`);
  }

  async join(channel, { presence = false } = {}) {
    const topic = topicName(channel);
    const ref = String(++this.ref);
    const from = this.mark();
    const response = this.waitFor(
      (message) => message.topic === topic && message.event === "phx_reply" && message.ref === ref,
      `${channel} subscription reply`,
      from,
    );
    this.sendFrame(topic, "phx_join", {
      config: {
        broadcast: { ack: false, self: false },
        presence: { key: presence ? this.presenceKey : "", enabled: presence },
        postgres_changes: [],
        private: false,
      },
    }, ref, ref);
    const reply = await response;
    if (reply.payload?.status !== "ok") {
      throw new Error(`${this.deviceId} failed to subscribe to ${channel} (status=${reply.payload?.status ?? "missing"})`);
    }
    this.channels.set(channel, { topic, joinRef: ref });
  }

  broadcast(channel, event, payload) {
    const joined = this.channels.get(channel);
    if (!joined) throw new Error(`${this.deviceId} is not subscribed to ${channel}`);
    this.sendFrame(joined.topic, "broadcast", {
      type: "broadcast",
      event,
      payload,
    }, String(++this.ref), joined.joinRef);
  }

  trackPresence() {
    const joined = this.channels.get(presenceChannel);
    if (!joined) throw new Error(`${this.deviceId} has no Presence subscription`);
    this.sendFrame(joined.topic, "presence", {
      type: "presence",
      event: "track",
      payload: { deviceId: this.deviceId },
    }, String(++this.ref), joined.joinRef);
  }

  untrackPresence() {
    const joined = this.channels.get(presenceChannel);
    if (!joined) return;
    this.sendFrame(joined.topic, "presence", {
      type: "presence",
      event: "untrack",
    }, String(++this.ref), joined.joinRef);
  }

  async leave(channel) {
    const joined = this.channels.get(channel);
    if (!joined) return;
    const ref = String(++this.ref);
    const from = this.mark();
    const response = this.waitFor(
      (message) => message.topic === joined.topic && message.event === "phx_reply" && message.ref === ref,
      `${channel} leave reply`,
      from,
    );
    this.sendFrame(joined.topic, "phx_leave", {}, ref, joined.joinRef);
    const reply = await response;
    if (reply.payload?.status !== "ok") {
      throw new Error(`${this.deviceId} failed to leave ${channel} (status=${reply.payload?.status ?? "missing"})`);
    }
    this.channels.delete(channel);
  }

  async disconnect() {
    if (!this.socket) return;
    this.closing = true;
    if (this.heartbeat) clearInterval(this.heartbeat);
    for (const channel of [...this.channels.keys()].reverse()) {
      try {
        await this.leave(channel);
      } catch {
        // The close below still releases the backend presence session.
      }
    }
    if (this.socket.readyState === WebSocket.OPEN) this.socket.close(1000, "integration test complete");
  }
}

function isSignalFor(message, receiverId, expectedCallId, expectedPeerId, type) {
  const payload = signalPayload(message);
  return payload?.type === type && acceptsSignal(payload, receiverId, expectedCallId, expectedPeerId);
}

async function waitPresence(peer, eventName, direction, deviceId, from, sessionKey) {
  return peer.waitFor((message) => {
    if (message.topic !== presenceTopic || message.event !== "presence_diff") return false;
    const branch = message.payload?.[direction];
    return sessionKey ? hasPresenceSession(branch, sessionKey) : hasDevice(branch, deviceId);
  }, `${deviceId} Presence ${eventName}`, from);
}

const peerA = new RealtimePeer("pupsik-a", `pupsik-a-${runId}`);
const peerB = new RealtimePeer("pupsik-b", `pupsik-b-${runId}`);
let peerBReconnect = null;

try {
  console.log("PASS Presence regression guard: collector is installed before subscribe");
  console.log("PASS Android transport regression guard: Supabase uses the OkHttp WebSocket engine");
  await Promise.all([peerA.connect(), peerB.connect()]);
  console.log("PASS Supabase Realtime websocket: A and B connected");

  await Promise.all([
    peerA.join(presenceChannel, { presence: true }),
    peerB.join(presenceChannel, { presence: true }),
    peerA.join(globalChannel),
    peerB.join(globalChannel),
  ]);
  console.log("PASS A/B channel subscriptions: Presence and global signaling joined");

  let fromB = peerB.mark();
  peerA.trackPresence();
  const aPresenceKey = `pupsik-a-${runId}`;
  const bPresenceKey = `pupsik-b-${runId}`;
  const aOnlineAtB = await waitPresence(peerB, "join", "joins", "pupsik-a", fromB, aPresenceKey);
  assert.equal(hasPresenceSession(aOnlineAtB.payload.joins, aPresenceKey), true);

  let fromA = peerA.mark();
  peerB.trackPresence();
  const bOnlineAtA = await waitPresence(peerA, "join", "joins", "pupsik-b", fromA, bPresenceKey);
  assert.equal(hasPresenceSession(bOnlineAtA.payload.joins, bPresenceKey), true);
  console.log("PASS Presence: A and B observe one another online");

  await peerA.join(callChannel);
  fromB = peerB.mark();
  peerA.broadcast(globalChannel, "signal", {
    callId,
    fromDeviceId: "pupsik-a",
    toDeviceId: "pupsik-b",
    type: "ringing",
    sentAt: String(Date.now()),
  });
  const ring = await peerB.waitFor(
    (message) => isSignalFor(message, "pupsik-b", callId, "pupsik-a", "ringing"),
    "ring addressed to B",
    fromB,
  );
  assert.equal(signalPayload(ring).callId, callId);
  console.log("PASS ring: B receives the addressed callId");

  await peerB.join(callChannel);
  fromA = peerA.mark();
  peerB.broadcast(callChannel, "signal", {
    callId,
    fromDeviceId: "pupsik-b",
    toDeviceId: "pupsik-a",
    type: "accepted",
    sentAt: String(Date.now()),
  });
  await peerA.waitFor(
    (message) => isSignalFor(message, "pupsik-a", callId, "pupsik-b", "accepted"),
    "accepted from B",
    fromA,
  );

  let fromBCall = peerB.mark();
  peerA.broadcast(callChannel, "signal", {
    callId: wrongCallId,
    fromDeviceId: "pupsik-a",
    toDeviceId: "pupsik-b",
    type: "offer",
    sdp: "synthetic-safe-offer-wrong-call",
  });
  peerA.broadcast(callChannel, "signal", {
    callId,
    fromDeviceId: "unexpected-peer",
    toDeviceId: "pupsik-b",
    type: "offer",
    sdp: "synthetic-safe-offer-wrong-peer",
  });
  const wrongCall = await peerB.waitFor(
    (message) => signalPayload(message)?.callId === wrongCallId,
    "wrong-call test event",
    fromBCall,
  );
  const wrongPeer = await peerB.waitFor(
    (message) => signalPayload(message)?.fromDeviceId === "unexpected-peer",
    "wrong-peer test event",
    fromBCall,
  );
  assert.equal(acceptsSignal(signalPayload(wrongCall), "pupsik-b", callId, "pupsik-a"), false);
  assert.equal(acceptsSignal(signalPayload(wrongPeer), "pupsik-b", callId, "pupsik-a"), false);

  peerA.broadcast(callChannel, "signal", {
    callId,
    fromDeviceId: "pupsik-a",
    toDeviceId: "pupsik-b",
    type: "offer",
    sdp: "synthetic-safe-offer",
  });
  const offer = await peerB.waitFor(
    (message) => isSignalFor(message, "pupsik-b", callId, "pupsik-a", "offer"),
    "offer from A",
    fromBCall,
  );
  peerA.broadcast(callChannel, "signal", {
    callId,
    fromDeviceId: "pupsik-a",
    toDeviceId: "pupsik-b",
    type: "ice",
    sdpMid: "audio",
    sdpMLineIndex: "0",
    sdp: "candidate:a1 synthetic-safe",
  });
  peerA.broadcast(callChannel, "signal", {
    callId,
    fromDeviceId: "pupsik-a",
    toDeviceId: "pupsik-b",
    type: "ice",
    sdpMid: "audio",
    sdpMLineIndex: "0",
    sdp: "candidate:a2 synthetic-safe",
  });
  const aIce1 = await peerB.waitFor(
    (message) => isSignalFor(message, "pupsik-b", callId, "pupsik-a", "ice") && signalPayload(message).sdp === "candidate:a1 synthetic-safe",
    "first ICE candidate A to B",
    fromBCall,
  );
  const aIce2 = await peerB.waitFor(
    (message) => isSignalFor(message, "pupsik-b", callId, "pupsik-a", "ice") && signalPayload(message).sdp === "candidate:a2 synthetic-safe",
    "second ICE candidate A to B",
    fromBCall,
  );
  assert.deepEqual(
    [signalPayload(aIce1).sdpMid, signalPayload(aIce1).sdpMLineIndex, signalPayload(aIce1).sdp],
    ["audio", "0", "candidate:a1 synthetic-safe"],
  );
  assert.ok(peerB.messages.indexOf(offer) < peerB.messages.indexOf(aIce1));
  assert.ok(peerB.messages.indexOf(offer) < peerB.messages.indexOf(aIce2));
  assert.notEqual(aIce1, aIce2);

  fromA = peerA.mark();
  peerB.broadcast(callChannel, "signal", {
    callId,
    fromDeviceId: "pupsik-b",
    toDeviceId: "pupsik-a",
    type: "answer",
    sdp: "synthetic-safe-answer",
  });
  const answer = await peerA.waitFor(
    (message) => isSignalFor(message, "pupsik-a", callId, "pupsik-b", "answer"),
    "answer from B",
    fromA,
  );
  peerB.broadcast(callChannel, "signal", {
    callId,
    fromDeviceId: "pupsik-b",
    toDeviceId: "pupsik-a",
    type: "ice",
    sdpMid: "audio",
    sdpMLineIndex: "0",
    sdp: "candidate:b1 synthetic-safe",
  });
  peerB.broadcast(callChannel, "signal", {
    callId,
    fromDeviceId: "pupsik-b",
    toDeviceId: "pupsik-a",
    type: "ice",
    sdpMid: "audio",
    sdpMLineIndex: "0",
    sdp: "candidate:b2 synthetic-safe",
  });
  const bIce1 = await peerA.waitFor(
    (message) => isSignalFor(message, "pupsik-a", callId, "pupsik-b", "ice") && signalPayload(message).sdp === "candidate:b1 synthetic-safe",
    "first ICE candidate B to A",
    fromA,
  );
  const bIce2 = await peerA.waitFor(
    (message) => isSignalFor(message, "pupsik-a", callId, "pupsik-b", "ice") && signalPayload(message).sdp === "candidate:b2 synthetic-safe",
    "second ICE candidate B to A",
    fromA,
  );
  assert.deepEqual(
    [signalPayload(bIce1).sdpMid, signalPayload(bIce1).sdpMLineIndex, signalPayload(bIce1).sdp],
    ["audio", "0", "candidate:b1 synthetic-safe"],
  );
  assert.ok(peerA.messages.indexOf(answer) < peerA.messages.indexOf(bIce1));
  assert.ok(peerA.messages.indexOf(answer) < peerA.messages.indexOf(bIce2));
  assert.notEqual(bIce1, bIce2);
  console.log("PASS call signaling: accepted, offer, answer, ordered bidirectional ICE; wrong callId/peer rejected");

  fromA = peerA.mark();
  peerB.broadcast(callChannel, "signal", {
    callId,
    fromDeviceId: "pupsik-b",
    toDeviceId: "pupsik-a",
    type: "ended",
    sentAt: String(Date.now()),
  });
  await peerA.waitFor(
    (message) => isSignalFor(message, "pupsik-a", callId, "pupsik-b", "ended"),
    "ended from B",
    fromA,
  );
  await Promise.all([peerA.leave(callChannel), peerB.leave(callChannel)]);
  assert.equal(peerA.channels.has(callChannel), false);
  assert.equal(peerB.channels.has(callChannel), false);
  console.log("PASS call cleanup: ended delivered and both per-call channels left");

  fromA = peerA.mark();
  peerB.untrackPresence();
  const bOffline = await waitPresence(peerA, "leave", "leaves", "pupsik-b", fromA, bPresenceKey);
  assert.equal(hasPresenceSession(bOffline.payload.leaves, bPresenceKey), true);
  await peerB.leave(presenceChannel);
  console.log("PASS Presence leave: A observes B offline");

  peerBReconnect = new RealtimePeer("pupsik-b", `pupsik-b-${runId}-reconnect`);
  const reconnectPresenceKey = `pupsik-b-${runId}-reconnect`;
  await peerBReconnect.connect();
  fromA = peerA.mark();
  await peerBReconnect.join(presenceChannel, { presence: true });
  const initialState = await peerBReconnect.waitFor(
    (message) => message.topic === presenceTopic && message.event === "presence_state",
    "reconnect initial Presence state",
  );
  assert.equal(hasPresenceSession(initialState.payload, aPresenceKey), true);
  assert.equal(hasPresenceSession(initialState.payload, bPresenceKey), false);
  assert.equal(hasPresenceSession(initialState.payload, reconnectPresenceKey), false);
  peerBReconnect.trackPresence();
  const bOnlineAgain = await waitPresence(peerA, "reconnect join", "joins", "pupsik-b", fromA, reconnectPresenceKey);
  assert.equal(hasPresenceSession(bOnlineAgain.payload.joins, reconnectPresenceKey), true);
  await sleep(500);
  const reconnectJoins = peerA.messages.slice(fromA).filter((message) =>
    message.topic === presenceTopic && message.event === "presence_diff" &&
    hasPresenceSession(message.payload?.joins, reconnectPresenceKey));
  assert.equal(reconnectJoins.length, 1);
  console.log("PASS Presence reconnect: B online again once; no stale or duplicate B session");

  fromA = peerA.mark();
  peerBReconnect.untrackPresence();
  await waitPresence(peerA, "final leave", "leaves", "pupsik-b", fromA, reconnectPresenceKey);
  await peerBReconnect.leave(presenceChannel);
  await peerA.leave(presenceChannel);
  await peerA.leave(globalChannel);
  await peerB.leave(globalChannel);
  await peerBReconnect.disconnect();
  peerBReconnect = null;
  await Promise.all([peerA.disconnect(), peerB.disconnect()]);
  console.log("PASS cleanup: Presence and global channels released on A, B, and reconnect peer");
} catch (error) {
  console.error(`FAIL Realtime integration: ${error instanceof Error ? error.message : "unknown error"}`);
  process.exitCode = 1;
} finally {
  await Promise.allSettled([
    peerBReconnect?.disconnect(),
    peerA.disconnect(),
    peerB.disconnect(),
  ]);
}
