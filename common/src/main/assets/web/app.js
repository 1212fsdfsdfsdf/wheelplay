document.body.style.background = '#ff0000';
window.onerror = function(msg, url, line, col) {
  var d = document.createElement('div');
  d.style.cssText = 'position:fixed;bottom:0;left:0;right:0;background:#c00;color:#fff;padding:10px;z-index:99999;font-size:14px;font-family:monospace;white-space:pre-wrap;';
  d.textContent = 'JS错误: ' + msg + ' 行:' + line;
  document.body.appendChild(d);
};
window.addEventListener('unhandledrejection', function(e) {
  var d = document.createElement('div');
  d.style.cssText = 'position:fixed;bottom:0;left:0;right:0;background:#900;color:#fff;padding:10px;z-index:99999;font-size:14px;font-family:monospace;white-space:pre-wrap;';
  d.textContent = '请求错误: ' + (e.reason && e.reason.message ? e.reason.message : e.reason);
  document.body.appendChild(d);
});
function t(key, ...args) {
  const message = (window.wheelplayMessages && window.wheelplayMessages[key]) || key;
  return message.replace(/%(\d+)\$s/g, (_, index) => String(args[Number(index) - 1] || ''));
}

// Independent of video transport: the same paired channel works with JPEG and WebRTC.
function decodeAudioPacket(buffer) {
  if (!(buffer instanceof ArrayBuffer) || buffer.byteLength < 22) return null;
  const header = new DataView(buffer);
  if (header.getUint32(0, true) !== 0x31415057) return null;
  const stream = header.getInt32(4, true), rate = header.getUint32(8, true), channels = header.getUint16(12, true);
  if (rate < 8000 || rate > 96000 || channels < 1 || channels > 2 || (buffer.byteLength - 20) % (channels * 2)) return null;
  return { stream, rate, channels, media: !!(header.getUint16(14, true) & 1),
    sequence: header.getUint32(16, true), pcm: new Int16Array(buffer, 20) };
}

// Continuous box-filter resampling preserves phase across render quanta and averages before
// downsampling. CarPlay's negotiated mic rates are <= the browser's hardware capture rate.
function createMicResampler(inputRate, outputRate, channels, emit) {
  if (inputRate < outputRate || channels < 1 || channels > 2) throw new Error(t("web_unsupported_microphone_sample_format"));
  const step = inputRate / outputRate, frameSamples = Math.round(outputRate / 50) * channels;
  let remaining = step, sum = 0, packet = new Int16Array(frameSamples), filled = 0;
  return input => {
    for (const sample of input) {
      let weight = 1;
      while (weight > 1e-9) {
        const part = Math.min(weight, remaining); sum += sample * part; remaining -= part; weight -= part;
        if (remaining < 1e-9) {
          const value = Math.max(-32768, Math.min(32767, Math.round(sum / step * 32767)));
          for (let channel = 0; channel < channels; channel++) packet[filled++] = value;
          sum = 0; remaining = step;
          if (filled === packet.length) { emit(packet.buffer); packet = new Int16Array(frameSamples); filled = 0; }
        }
      }
    }
  };
}

function createBrowserAudio({
  send, sendBinary, onState = () => {},
  createContext = () => new (window.AudioContext || window.webkitAudioContext)({ latencyHint: 'interactive' }),
  getMicrophone = () => navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true }, video: false }),
  createWorklet = context => new AudioWorkletNode(context, 'wheelplay-microphone', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1] }),
  secure = () => window.isSecureContext && location.protocol === 'https:' && !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia),
} = {}) {
  let context = null, playback = false, microphone = false, connected = false;
  let playbackGeneration = 0, microphoneGeneration = 0;
  let desiredPlayback = false, desiredMicrophone = false, gestureRetry = false;
  let mediaBufferMillis = 300;
  let stream = null, source = null, worklet = null, micId = null, micSequence = 0;
  const streams = new Map(), nodes = new Set();
  function state(error = '') { onState({ playback, microphone, connected, error }); }
  function readyState() { if (connected) send({ type: 'audio-ready', playback, microphone }); }
  async function ready() {
    if (!context) context = createContext();
    await context.resume();
    if (context.state !== 'running') throw new Error(t("web_tap_the_display_to_allow_audio_playback"));
    return context;
  }
  function stopPlayback(id = null) {
    for (const node of [...nodes]) if (id === null || node.stream === id) {
      try { node.stop(); } catch (_) {} nodes.delete(node);
    }
    if (id === null) streams.clear(); else streams.delete(id);
  }
  function stopMicrophone() {
    micId = null;
    if (worklet) { worklet.port.postMessage(null); worklet.port.onmessage = null; worklet.disconnect(); }
    (source && source.disconnect)(); (stream && stream.getTracks)().forEach(track => track.stop());
    worklet = source = stream = null;
  }
  async function setPlayback(enabled) {
    const token = ++playbackGeneration;
    try {
      if (enabled) await ready();
      if (playbackGeneration !== token) return;
      playback = enabled;
      if (!enabled) stopPlayback();
      readyState(); state();
    } catch (error) { if (playbackGeneration === token) { playback = false; readyState(); state(error.message); } }
  }
  async function setMicrophone(enabled) {
    const token = ++microphoneGeneration;
    stopMicrophone(); microphone = false; readyState();
    if (!enabled) { state(); return; }
    try {
      if (!secure()) throw new Error(t("web_browser_microphones_require_trusted_https_open_the_https_address"));
      const audio = await ready();
      if (!audio.audioWorklet) throw new Error(t("web_this_browser_does_not_support_microphone_audio_processing_use"));
      await audio.audioWorklet.addModule('/audio-worklet.js');
      if (microphoneGeneration !== token) return;
      const captured = await getMicrophone();
      if (microphoneGeneration !== token) { captured.getTracks().forEach(track => track.stop()); return; }
      stream = captured; captured.getAudioTracks().forEach(track => { track.enabled = false; });
      worklet = createWorklet(audio); source = audio.createMediaStreamSource(captured);
      source.connect(worklet); worklet.connect(audio.destination);
      worklet.port.onmessage = event => {
        if (!connected || !microphone || micId === null || microphoneGeneration !== token) return;
        const packet = new Uint8Array(12 + event.data.byteLength), header = new DataView(packet.buffer);
        header.setUint32(0, 0x314d5057, true); header.setInt32(4, micId, true); header.setUint32(8, micSequence++, true);
        packet.set(new Uint8Array(event.data), 12); sendBinary(packet.buffer);
      };
      captured.getAudioTracks().forEach(track => { track.onended = () => {
        if (microphoneGeneration === token) { microphone = false; stopMicrophone(); readyState(); state(t("web_microphone_stopped_audio_input_has_returned_to_android")); }
      }; });
      microphone = true; readyState(); state();
    } catch (error) {
      if (microphoneGeneration !== token) return;
      stopMicrophone(); microphone = false; readyState();
      state(error.name === 'NotAllowedError' ? t("web_microphone_permission_denied_allow_this_site_to_use_the") : error.message);
    }
  }
  function receive(buffer) {
    const frame = decodeAudioPacket(buffer);
    if (!frame) return false;
    send({ type: 'audio-ack', sequence: frame.sequence });
    if (!playback || !connected || (context && context.state) !== 'running') return true;
    const now = context.currentTime;
    const delay = frame.media ? mediaBufferMillis / 1000 : .06;
    // A media burst after a radio gap must fit above the selected start buffer.
    const maxAhead = frame.media ? delay + 1 : .25;
    let next = streams.get(frame.stream) ?? now + delay;
    if (next < now) { stopPlayback(frame.stream); next = now + delay; }
    // Bound latency and allocation without cancelling sound that is already queued.
    if (next - now > maxAhead || nodes.size >= 512) return true;
    const count = frame.pcm.length / frame.channels;
    const audioBuffer = context.createBuffer(frame.channels, count, frame.rate);
    for (let channel = 0; channel < frame.channels; channel++) {
      const output = audioBuffer.getChannelData(channel);
      for (let i = 0; i < count; i++) output[i] = frame.pcm[i * frame.channels + channel] / 32768;
    }
    const node = context.createBufferSource(); node.buffer = audioBuffer; node.stream = frame.stream; node.media = frame.media;
    node.connect(context.destination); nodes.add(node);
    node.onended = () => { nodes.delete(node); node.disconnect(); };
    node.start(next); streams.set(frame.stream, next + count / frame.rate);
    return true;
  }
  function control(message) {
    if (message.type === 'audio-route') {
      const nextBufferMillis = [300, 500, 1000].includes(message.mediaBufferMillis) ? message.mediaBufferMillis : 300;
      if (nextBufferMillis !== mediaBufferMillis) {
        mediaBufferMillis = nextBufferMillis;
        for (const id of new Set([...nodes].filter(node => node.media).map(node => node.stream))) stopPlayback(id);
      }
      const nextPlayback = message.playback === true, nextMicrophone = message.microphone === true;
      if (nextPlayback && !playback || nextMicrophone && !microphone) state(t("web_if_the_browser_blocks_autoplay_tap_the_display_to"));
      if (desiredPlayback !== nextPlayback) { desiredPlayback = nextPlayback; if (connected) setPlayback(nextPlayback); }
      if (desiredMicrophone !== nextMicrophone) { desiredMicrophone = nextMicrophone; if (connected) setMicrophone(nextMicrophone); }
      gestureRetry = false;
      readyState();
      return true;
    }
    if (message.type === 'audio-stop') { stopPlayback(message.stream); return true; }
    if (message.type === 'audio-reset') { stopPlayback(); micId = null; (worklet && worklet.port).postMessage(null); (stream && stream.getAudioTracks)().forEach(t => { t.enabled = false; }); return true; }
    if (message.type === 'mic-stop') {
      if (message.id === micId) { micId = null; (worklet && worklet.port).postMessage(null); (stream && stream.getAudioTracks)().forEach(t => { t.enabled = false; }); }
      return true;
    }
    if (message.type !== 'mic-config') return false;
    if (!microphone || !worklet || !connected) return true;
    if (![1, 2].includes(message.channels) || message.rate < 8000 || message.rate > context.sampleRate) {
      setMicrophone(false); state(t("web_microphone_sample_format_unavailable_input_has_returned_to_android")); return true;
    }
    micId = message.id; micSequence = 0;
    worklet.port.postMessage({ rate: message.rate, channels: message.channels });
    stream.getAudioTracks().forEach(track => { track.enabled = true; });
    return true;
  }
  function close() {
    playbackGeneration++; microphoneGeneration++; connected = false; microphone = playback = false;
    desiredPlayback = desiredMicrophone = gestureRetry = false;
    mediaBufferMillis = 300;
    stopMicrophone(); stopPlayback();
    const old = context; context = null; (old && old.close)().catch(() => {}); state();
  }
  return { receive, control, close,
    attach() { connected = true; state(); },
    unlock() {
      if (!connected || gestureRetry) return;
      gestureRetry = true;
      if (desiredPlayback && !playback) setPlayback(true);
      if (desiredMicrophone && !microphone) setMicrophone(true);
    },
    get playback() { return playback; }, get microphone() { return microphone; },
  };
}

// Coordinates are normalized in the visible video rectangle, excluding letterboxing.
function mapPoint(clientX, clientY, rect, width, height, clamp = false) {
  if (width <= 0 || height <= 0 || rect.width <= 0 || rect.height <= 0) return null;
  const scale = Math.min(rect.width / width, rect.height / height);
  const w = width * scale, h = height * scale;
  const x = (clientX - rect.left - (rect.width - w) / 2) / w;
  const y = (clientY - rect.top - (rect.height - h) / 2) / h;
  if (!clamp && (x < 0 || x > 1 || y < 0 || y > 1)) return null;
  return { x: Math.max(0, Math.min(1, x)), y: Math.max(0, Math.min(1, y)) };
}

function pairingCodeFromUrl(search) {
  const match = String(search || '').match(/(?:^|[?&])code=([^&]*)/);
  let code = '';
  try { code = match ? decodeURIComponent(match[1].replace(/\+/g, ' ')) : ''; } catch (_) { return null; }
  return /^[0-9]{6}$/.test(code) ? code : null;
}

// Keep essential controls until WebSocket accepts them. A successful send is never retried.
function createControlOutbox(socket, {
  schedule = setTimeout, cancel = clearTimeout, now = () => Date.now(), onFailure = () => socket.close(),
} = {}) {
  const touches = [];
  const signals = [];
  let ack = null, audioAck = null, audioReady = null, ping = null, feedback = null, viewport = null, timer = null, blockedSince = null, closed = false;
  const close = () => {
    closed = true; cancel(timer); timer = null;
    ack = null; audioAck = null; audioReady = null; ping = null; feedback = null; viewport = null; touches.length = 0; signals.length = 0;
  };
  const fail = () => { close(); onFailure(); };
  const flush = () => {
    if (closed) return;
    if (socket.readyState !== 1) { close(); return; }
    try {
      while ((ack || audioAck || audioReady || touches.length || signals.length || viewport || feedback || ping) && socket.bufferedAmount < 8192) {
        if (ack) { socket.send(ack); ack = null; }
        else if (audioAck) { socket.send(audioAck); audioAck = null; }
        else if (audioReady) { socket.send(audioReady); audioReady = null; }
        else if (touches.length) { socket.send(touches[0].json); touches.shift(); }
        else if (signals.length) { socket.send(signals[0]); signals.shift(); }
        else if (viewport) { socket.send(viewport); viewport = null; }
        else if (feedback) { socket.send(feedback); feedback = null; }
        else { socket.send(ping); ping = null; }
        blockedSince = null;
      }
    } catch (_) { fail(); return; }
    if (ack || audioAck || audioReady || touches.length || signals.length || viewport || feedback || ping) {
      if (blockedSince === null) blockedSince = now();
      if (now() - blockedSince >= 1500) { fail(); return; }
      if (timer === null) timer = schedule(() => { timer = null; flush(); }, 10);
    } else {
      cancel(timer); timer = null; blockedSince = null;
    }
  };
  return {
    send(data, kind = 'edge') {
      if (closed || socket.readyState !== 1) return false;
      const json = JSON.stringify(data);
      if (data.type === 'ack') ack = json;
      else if (data.type === 'audio-ack') audioAck = json;
      else if (data.type === 'audio-ready') audioReady = json;
      else if (data.type === 'ping') ping = json;
      else if (data.type === 'rtc-feedback') feedback = json;
      else if (data.type === 'viewport') viewport = json;
      else if (data.type === 'touch') {
        const tail = touches[touches.length - 1];
        // Only consecutive moves may be replaced; never cross a down/up/release boundary.
        if (kind === 'move' && tail && tail.kind === 'move') tail.json = json;
        else if (touches.length < 64) touches.push({ kind, json });
        else { fail(); return false; }
      } else if (['rtc-start', 'rtc-answer', 'rtc-ready', 'rtc-fallback', 'phone-connect'].includes(data.type)) {
        if (signals.length >= 8 || json.length > 32768) { fail(); return false; }
        signals.push(json);
      } else return false;
      flush();
      return !closed;
    },
    close,
  };
}

// Keep the decoder in the DOM, outside the viewport. display:none/hidden can
// suppress video frame callbacks on embedded browsers. drawImage copies decoded
// frames directly; it does not read pixels back through getImageData.
function createVideoCanvasRenderer(video, canvas, {
  onFrame = () => {}, onError = () => {},
  requestFrame = callback => requestAnimationFrame(callback), cancelFrame = id => cancelAnimationFrame(id),
  now = () => performance.now(),
} = {}) {
  let context = null, running = false, generation = 0, videoCallback = null, animationCallback = null;
  let lastVideoCallbackAt = 0, lastFrameKey = null;
  function prepare() {
    video.classList.toggle('offscreen-video', true);
    video.hidden = false;
  }
  function stop() {
    running = false; generation++;
    if (videoCallback !== null && video.cancelVideoFrameCallback) video.cancelVideoFrameCallback(videoCallback);
    if (animationCallback !== null) cancelFrame(animationCallback);
    videoCallback = animationCallback = null; context = null; lastFrameKey = null;
    video.hidden = true; video.classList.toggle('offscreen-video', false);
    canvas.hidden = true; canvas.width = canvas.height = 0;
  }
  function fail(error) { stop(); onError(error); }
  function draw(metadata = null, stalled = false) {
    if (!running || video.readyState < 2 || !video.videoWidth || !video.videoHeight) return;
    try {
      const width = video.videoWidth, height = video.videoHeight;
      const resized = canvas.width !== width || canvas.height !== height;
      // Prefer the frame counter over currentTime, which can advance between
      // decoded frames. Some WebRTC implementations leave this counter at zero.
      const frames = video.getVideoPlaybackQuality ? video.getVideoPlaybackQuality().totalVideoFrames : undefined;
      const time = (metadata && metadata.mediaTime) || video.currentTime;
      const key = !stalled && frames > 0 ? `frame:${frames}` : Number.isFinite(time) ? `time:${time}` : null;
      if (!resized && key !== null && key === lastFrameKey) return;
      if (resized) { canvas.width = width; canvas.height = height; }
      context.drawImage(video, 0, 0, width, height);
      lastFrameKey = key;
      const first = canvas.hidden;
      canvas.hidden = false;
      if (first || resized) onFrame(width, height);
    } catch (error) { fail(error); }
  }
  function start() {
    stop(); prepare();
    try {
      context = canvas.getContext('2d', { alpha: false });
      if (!context) throw new Error('Canvas 2D unavailable');
      running = true; lastVideoCallbackAt = now();
      const token = generation, current = () => running && token === generation;
      const observe = (_, metadata) => {
        if (!current()) return;
        videoCallback = null; lastVideoCallbackAt = now(); draw(metadata);
        if (current()) videoCallback = video.requestVideoFrameCallback(observe);
      };
      const refresh = () => {
        if (!current()) return;
        animationCallback = null;
        // Also recover if offscreen playback decodes but stops being composited.
        if (!video.requestVideoFrameCallback) draw();
        else if (now() - lastVideoCallbackAt >= 250) draw(null, true);
        if (current()) animationCallback = requestFrame(refresh);
      };
      draw();
      if (!current()) return;
      if (video.requestVideoFrameCallback) videoCallback = video.requestVideoFrameCallback(observe);
      animationCallback = requestFrame(refresh);
    } catch (error) { fail(error); }
  }
  return { prepare, start, stop };
}

// Video WebRTC remains receive-only. Browser audio uses the paired WebSocket.
function createRtcReceiver(video, {
  send, onReady = () => {}, onStop = () => {}, onStats = () => {},
  onTrack = () => {}, onReset = () => {},
  createPeer = () => new RTCPeerConnection({ iceServers: [] }),
  supportsCodec = codec => {
    if (typeof RTCRtpReceiver === 'undefined' || !RTCRtpReceiver.getCapabilities) return true;
    const caps = RTCRtpReceiver.getCapabilities('video');
    return !caps || caps.codecs.some(c => c.mimeType.toLowerCase() === `video/${codec.toLowerCase()}`);
  },
  schedule = setTimeout, cancel = clearTimeout, now = () => Date.now(),
} = {}) {
  let frameCallback = null, presented = 0, previousStats = null;
  let codec = 'H264';
  let peer = null, id = null, generation = 0, timeout = null, statsTimer = null, ready = false;
  let lastDecoded = 0, sent = 0, lastSentAt = 0, sentAtLastProgress = 0;
  let stalledSince = null, statsFailedSince = null, disconnectTimer = null, recoveryGraceMs = 10000;
  function close() {
    generation++; cancel(timeout); cancel(statsTimer); cancel(disconnectTimer);
    timeout = null; statsTimer = null; disconnectTimer = null;
    stalledSince = null; statsFailedSince = null;
    const old = peer; peer = null; ready = false; id = null;
    if (frameCallback !== null && video.cancelVideoFrameCallback) video.cancelVideoFrameCallback(frameCallback);
    frameCallback = null; presented = 0; previousStats = null;
    video.onloadeddata = null; video.onerror = null;
    if (old) { old.ontrack = null; old.onconnectionstatechange = null; old.close(); }
    video.srcObject = null; video.hidden = true;
    onReset();
  }
  function fallback() {
    const current = id;
    close(); onStop();
    if (current !== null) send({ type: 'rtc-fallback', id: current });
  }
  async function receive(message) {
    if (message.type === 'rtc-stop') {
      if (message.id === id) { close(); onStop(); }
      return;
    }
    if (message.type !== 'rtc-offer') return;
    close(); id = message.id; codec = message.codec === 'H265' ? 'H265' : 'H264';
    recoveryGraceMs = [4000, 10000, 30000].includes(message.recoveryGraceMs) ? message.recoveryGraceMs : 10000;
    const token = generation, current = () => generation === token && peer !== null;
    timeout = schedule(() => { if (generation === token) fallback(); }, Math.max(10000, recoveryGraceMs));
    try {
      if (!supportsCodec(codec)) { fallback(); return; }
      const pc = createPeer(); peer = pc;
      const displayed = () => {
        if (!current() || ready || !video.videoWidth || video.readyState < 2) return;
        ready = true; cancel(timeout); timeout = null;
        lastDecoded = 0; sent = 0; sentAtLastProgress = 0; lastSentAt = now();
        send({ type: 'rtc-ready', id }); onReady();
        if (current()) statsTimer = schedule(sample, 1000);
      };
      const sample = async () => {
        if (!current()) return;
        try {
          const report = await pc.getStats();
          if (!current()) return;
          statsFailedSince = null;
          report.forEach(stat => {
            if (stat.type !== 'inbound-rtp' || (stat.kind || stat.mediaType) !== 'video') return;
            const decoded = stat.framesDecoded || 0, at = now();
            if (decoded !== lastDecoded || sent <= sentAtLastProgress || at - lastSentAt >= 2000) {
              stalledSince = null; sentAtLastProgress = sent;
            } else if (stalledSince === null) stalledSince = at;
            const previous = previousStats;
            const elapsed = previous ? Math.max(1, at - previous.at) : 0;
            const delta = key => previous && Number.isFinite(stat[key]) && Number.isFinite(previous.stat[key])
              ? Math.max(0, stat[key] - previous.stat[key]) : null;
            const rate = key => elapsed && delta(key) !== null ? delta(key) * 1000 / elapsed : null;
            const averageMs = (total, count) => delta(count) > 0 && delta(total) !== null ? delta(total) * 1000 / delta(count) : null;
            let rttMs = null;
            report.forEach(entry => {
              if (entry.type === 'candidate-pair' && entry.state === 'succeeded' && entry.nominated && Number.isFinite(entry.currentRoundTripTime)) rttMs = entry.currentRoundTripTime * 1000;
            });
            const metrics = {
              receivedFps: rate('framesReceived'), decodedFps: rate('framesDecoded'),
              presentedFps: video.requestVideoFrameCallback && elapsed ? Math.max(0, presented - previous.presented) * 1000 / elapsed : null,
              decodeMs: averageMs('totalDecodeTime', 'framesDecoded'),
              jitterBufferMs: averageMs('jitterBufferDelay', 'jitterBufferEmittedCount'),
              processingMs: averageMs('totalProcessingDelay', 'framesDecoded'),
              dropped: delta('framesDropped'), lost: delta('packetsLost'), nack: delta('nackCount'),
              bitrateMbps: rate('bytesReceived') === null ? null : rate('bytesReceived') * 8 / 1e6,
              rttMs,
            };
            onStats(metrics.presentedFps || metrics.decodedFps, metrics);
            send({ type: 'rtc-feedback', id, packets: Math.max(0, stat.packetsReceived || 0),
              lost: Math.max(0, stat.packetsLost || 0), decoded, nack: Math.max(0, stat.nackCount || 0), rttMs });
            previousStats = { at, stat, presented };
            lastDecoded = decoded;
            // Static CarPlay screens legitimately stop producing frames.
            if (stalledSince !== null && at - stalledSince >= recoveryGraceMs) fallback();
          });
        } catch (_) {
          if (current()) {
            if (statsFailedSince === null) statsFailedSince = now();
            if (now() - statsFailedSince >= recoveryGraceMs) fallback();
          }
        }
        if (current()) statsTimer = schedule(sample, 1000);
      };
      pc.onconnectionstatechange = () => {
        if (!current()) return;
        if (['failed', 'closed'].includes(pc.connectionState)) { fallback(); return; }
        if (pc.connectionState === 'disconnected') {
          if (disconnectTimer === null) {
            const timer = schedule(() => {
              if (current() && disconnectTimer === timer && pc.connectionState !== 'connected') fallback();
            }, recoveryGraceMs);
            disconnectTimer = timer;
          }
        } else if (pc.connectionState === 'connected') {
          cancel(disconnectTimer); disconnectTimer = null;
        }
      };
      pc.ontrack = event => {
        if (!current()) return;
        video.onloadeddata = displayed;
        video.onerror = () => { if (current()) fallback(); };
        if (video.requestVideoFrameCallback && frameCallback === null) {
          let firstPresented = null;
          const observe = (_, metadata) => {
            if (!current()) return;
            if (firstPresented === null) firstPresented = metadata.presentedFrames;
            presented = metadata.presentedFrames - firstPresented;
            frameCallback = video.requestVideoFrameCallback(observe);
          };
          frameCallback = video.requestVideoFrameCallback(observe);
        }
        video.srcObject = event.streams[0] || new MediaStream([event.track]);
        onTrack();
        if (!current()) return;
        video.play().then(displayed).catch(() => { if (current()) fallback(); });
      };
      await pc.setRemoteDescription({ type: 'offer', sdp: message.sdp });
      if (!current()) return;
      const answer = await pc.createAnswer();
      if (!current()) return;
      if (/^m=video 0(?: |\r?$)/m.test(answer.sdp)) { fallback(); return; }
      // Use the browser as DTLS server: Mbed TLS 3.6 cannot reassemble a fragmented
      // ClientHello from newer Chrome versions. Passive is a standard answer role.
      await pc.setLocalDescription({ type: answer.type, sdp: answer.sdp.replace(/a=setup:active/g, 'a=setup:passive') });
      if (!current()) return;
      const answerReady = () => {
        if (!current() || pc.iceGatheringState !== 'complete') return;
        pc.onicegatheringstatechange = null;
        send({ type: 'rtc-answer', id, sdp: pc.localDescription.sdp });
      };
      if (pc.iceGatheringState === 'complete') answerReady();
      else pc.onicegatheringstatechange = answerReady;
    } catch (_) { if (generation === token) fallback(); }
  }
  return { receive, close, fallback, get active() { return ready; }, get codec() { return codec; },
    get recovering() { return ready && (disconnectTimer !== null || stalledSince !== null || statsFailedSince !== null); },
    progress(count) {
      if (count > sent) {
        if (now() - lastSentAt >= 2000) stalledSince = null;
        lastSentAt = now();
      }
      sent = count;
    },
  };
}

if (typeof document !== 'undefined') {
  const $ = id => document.getElementById(id);
  const screen = $('screen');
  const video = $('video');
  const canvas = $('video-canvas');
  const display = $('display');
  const touchSurface = $('touch-surface');
  // Read-only diagnostic snapshots; counters never include pairing credentials or video payloads.
  const performanceStats = { source: null, transport: null, receiver: null };
  Object.defineProperty(window, 'wheelplayStats', { configurable: true, get: () => JSON.parse(JSON.stringify(performanceStats)) });
  let rtc = null, rtcRequested = false, rtcFps = null;
  let socket = null, stopped = true, paired = false, retry = null, attempts = 0;
  let width = 1280, height = 720, live = false, objectUrl = null, lastFrame = 0, lastStatus = 0;
  let heartbeat = null, movePending = false, moveGeneration = 0, outbox = null;
  let authGeneration = 0, deviceCredential = null, storageAvailable = false;
  let launchPresentation = null;
  const devicePreference = 'wheelplay.browserDevice';
  let pairingToken = null, qrGeneration = 0, qrTimer = null, qrRequest = null;
  let viewportReportTimer = null, lastViewportSignature = '';
  const canvasOption = $('canvas-video');
  const canvasPreference = 'wheelplay.canvasVideo';
  try { canvasOption.checked = window.localStorage.getItem(canvasPreference) === 'true'; } catch (_) {}
  canvasOption.addEventListener('change', () => {
    try { window.localStorage.setItem(canvasPreference, String(canvasOption.checked)); } catch (_) {}
  });
  const canvasRenderer = createVideoCanvasRenderer(video, canvas, {
    onFrame: (frameWidth, frameHeight) => {
      width = frameWidth; height = frameHeight; screen.hidden = true; setLive(true);
    },
    onError: () => { if (rtc) rtc.fallback(); },
  });
  const pairApi = async (path, request = null) => {
    const headers = (request && request.token) ? { 'X-Pair-Token': request.token } :
      (request && request.code) ? { 'X-Pair-Code': request.code } :
      (request && request.device) ? { 'X-Device-Id': request.device.id, 'X-Device-Secret': request.device.secret } :
      request ? { 'X-Pair-Id': request.id, 'X-Pair-Secret': request.secret } : {};
    const controller = typeof AbortController === 'undefined' ? null : new AbortController();
    const timeout = setTimeout(() => controller && controller.abort(), 8000);
    try {
      const response = await fetch(`/pair/${path}`, { method: 'POST', headers, cache: 'no-store',
        keepalive: path === 'cancel' || path === 'revoke', ...(controller ? { signal: controller.signal } : {}) });
      if (!response.ok) {
        const error = new Error(response.status === 404 ? 'expired' : 'unavailable');
        error.status = response.status; throw error;
      }
      return await response.json();
    } finally { clearTimeout(timeout); }
  };
  try {
    const stored = window.localStorage.getItem(devicePreference);
    storageAvailable = true;
    if (stored) {
      const device = JSON.parse(stored);
      if (typeof device.id === 'string' && typeof device.secret === 'string' && device.id.length === 32 && device.secret.length === 32) deviceCredential = device;
    }
  } catch (_) {}
  function showPairingReady(name = t("web_browser_paired")) {
    $('remembered-device').hidden = false;
    $('device-name').textContent = name;
    $('qr-pairing').hidden = true; $('pair-instructions').hidden = true;
    $('code-entry').hidden = true; $('code').required = false; $('code').disabled = true;
    $('connect-label').textContent = t("web_start_display");
    $('connect').disabled = false;
    $('status').textContent = t("web_paired_ready_to_start"); $('message').textContent = '';
  }
  function showPairingEntry() {
    $('remembered-device').hidden = true;
    $('qr-pairing').hidden = false; $('pair-instructions').hidden = false;
    $('code-entry').hidden = false; $('code').required = true; $('code').disabled = false;
    $('connect-label').textContent = t("web_pair_start_display");
    $('connect').disabled = false;
  }
  async function preparePairing(token, generation) {
    pairingToken = token;
    let name = t("web_browser_paired");
    let message = t("web_paired_choose_the_options_above_then_tap_start_display");
    if (storageAvailable) {
      try {
        const device = await pairApi('remember', { token });
        if (generation !== authGeneration) return;
        deviceCredential = device;
        window.localStorage.setItem(devicePreference, JSON.stringify(device));
        name = device.name;
      } catch (_) {
        message = t("web_paired_for_this_session_but_the_device_could_not");
      }
    } else message = t("web_paired_for_this_session_this_browser_cannot_remember_the");
    if (generation !== authGeneration) return;
    showPairingReady(name); $('device-status').textContent = message;
  }
  async function initializePairing(linkedCode = null) {
    const generation = ++authGeneration;
    stopQr(); pairingToken = null;
    showPairingEntry(); $('connect').disabled = true;
    if (deviceCredential) {
      $('status').textContent = t("web_restoring_pairing");
      try {
        const result = await pairApi('resume', { device: deviceCredential });
        if (generation !== authGeneration) return;
        pairingToken = result.token;
        showPairingReady(result.name);
        $('device-status').textContent = t("web_pairing_restored_automatically_choose_the_options_above_then_tap");
        return;
      } catch (error) {
        if (generation !== authGeneration) return;
        if (error.status === 401) {
          deviceCredential = null;
          try { window.localStorage.removeItem(devicePreference); } catch (_) {}
          $('message').textContent = t("web_remembered_device_expired_or_removed_pair_again");
        } else {
          showPairingReady(deviceCredential.name || t("web_remembered_browser"));
          $('status').textContent = t("web_waiting_to_restore_pairing");
          $('device-status').textContent = t("web_could_not_restore_pairing_yet_check_the_service_and");
          return;
        }
      }
    }
    if (linkedCode) {
      $('code').value = linkedCode;
      try {
        const result = await pairApi('code', { code: linkedCode });
        if (generation !== authGeneration) return;
        await preparePairing(result.token, generation); return;
      } catch (_) {
        if (generation !== authGeneration) return;
        $('message').textContent = t("web_the_pairing_code_in_this_link_has_expired_scan");
      }
    }
    if (generation !== authGeneration) return;
    showPairingEntry(); $('status').textContent = t("web_waiting_to_pair"); startQr();
  }
  function stopQr(cancel = true) {
    qrGeneration++; clearTimeout(qrTimer);
    if (cancel && qrRequest) pairApi('cancel', qrRequest).catch(() => {});
    qrRequest = null;
    $('pair-qr').onload = null; $('pair-qr').onerror = null;
    $('pair-qr').hidden = true; $('pair-qr').removeAttribute('src');
  }
  async function startQr() {
    stopQr(); const generation = qrGeneration;
    $('qr-status').textContent = t("web_generating_qr_code");
    $('refresh-qr').disabled = true;
    try {
      const request = await pairApi('request');
      if (generation !== qrGeneration) { pairApi('cancel', request).catch(() => {}); return; }
      qrRequest = request;
      $('pair-qr').onload = () => {
        if (generation === qrGeneration) $('pair-qr').hidden = false;
      };
      $('pair-qr').onerror = () => {
        if (generation !== qrGeneration) return;
        $('pair-qr').hidden = true;
        $('qr-status').textContent = t("web_could_not_load_qr_code_refresh_and_try_again");
      };
      $('pair-qr').src = `/pair/qr?id=${encodeURIComponent(request.id)}&secret=${encodeURIComponent(request.secret)}`;
      $('qr-status').textContent = t("web_scan_with_the_server_app_and_allow_the_connection");
      const expiresAt = Date.now() + request.expiresIn * 1000;
      const poll = async () => {
        try {
          if (generation !== qrGeneration) return;
          if (Date.now() >= expiresAt) throw new Error('expired');
          const result = await pairApi('status', request);
          if (generation !== qrGeneration) return;
          if (result.state === 'approved') {
            stopQr(false); $('connect').disabled = true;
            await preparePairing(result.token, ++authGeneration); return;
          }
          qrTimer = setTimeout(poll, 1000);
        } catch (error) {
          if (generation !== qrGeneration) return;
          stopQr();
          $('qr-status').textContent = error.message === 'expired' ? t("web_qr_code_expired_refresh_and_scan_again") : t("web_could_not_check_pairing_status_refresh_the_qr_code");
        }
      };
      qrTimer = setTimeout(poll, 1000);
    } catch (_) {
      if (generation === qrGeneration) $('qr-status').textContent = t("web_qr_code_unavailable_refresh_and_try_again_or_enter");
    } finally {
      if (generation === qrGeneration) $('refresh-qr').disabled = false;
    }
  }
  $('refresh-qr').addEventListener('click', startQr);
  const pointers = new Map();
  const send = (data, kind) => outbox ? outbox.send(data, kind) : false;
  const browserAudio = createBrowserAudio({
    send,
    sendBinary: packet => {
      if ((!socket || socket.readyState !== WebSocket.OPEN) || socket.bufferedAmount > 8192) return false;
      try { socket.send(packet); return true; } catch (_) { return false; }
    },
    onState: ({ error }) => {
      $('audio-message').textContent = error;
      $('audio-message').hidden = !error || !paired;
    },
  });
  document.addEventListener('pointerdown', () => browserAudio.unlock(), { capture: true });
  $('secure-setup').addEventListener('toggle', async () => {
    if (!$('secure-setup').open) return;
    $('https-entry').removeAttribute('href');
    try {
      const response = await fetch('/tls.json', { cache: 'no-store' });
      const info = await response.json();
      if (info.available && Number.isInteger(info.port) && info.port >= 1 && info.port <= 65535) {
        const endpoint = new URL(location.href);
        endpoint.protocol = 'https:'; endpoint.port = String(info.port); endpoint.pathname = '/';
        endpoint.search = ''; endpoint.hash = '';
        $('https-entry').href = endpoint.href;
      }
      $('tls-fingerprint').textContent = info.enabled === false ? t("web_https_is_disabled_enable_it_in_the_app_s")
        : info.fingerprint || t("web_preparing_https_close_and_reopen_this_section_shortly");
      $('tls-error').textContent = info.error || '';
    } catch (_) { $('tls-error').textContent = t("web_certificate_information_is_temporarily_unavailable"); }
  });
  function scheduleViewportReport() {
    clearTimeout(viewportReportTimer);
    viewportReportTimer = setTimeout(() => {
      if (!socket || socket.readyState !== WebSocket.OPEN || display.hidden) return;
      const rect = display.getBoundingClientRect(), scale = window.devicePixelRatio || 1;
      const width = Math.round(rect.width * scale / 2) * 2;
      const height = Math.round(rect.height * scale / 2) * 2;
      if (width < 320 || height < 240) return;
      const signature = `${width}x${height}`;
      if (signature === lastViewportSignature) return;
      lastViewportSignature = signature;
      send({ type: 'viewport', width, height });
    }, 180);
  }
  if (typeof ResizeObserver !== 'undefined') new ResizeObserver(scheduleViewportReport).observe(display);
  window.addEventListener('resize', scheduleViewportReport);
  function cancelMove() { moveGeneration++; movePending = false; }
  function contacts() { return [...pointers.values()].map(p => ({ id: p.id, x: p.x, y: p.y, down: true })); }
  function release() { cancelMove(); pointers.clear(); send({ type: 'touch', contacts: [] }); }
  function setLive(value) {
    live = value;
    screen.classList.toggle('stale', !value);
    video.classList.toggle('stale', !value);
    canvas.classList.toggle('stale', !value);
    touchSurface.classList.toggle('stale', !value);
    $('waiting').hidden = value;
    if (!value && pointers.size) release();
  }
  function cleanupImage() {
    screen.onload = null; screen.onerror = null; screen.removeAttribute('src');
    screen.hidden = true;
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    objectUrl = null;
  }
  function cleanupRtc() {
    if (rtc) rtc.close(); rtc = null; rtcRequested = false; rtcFps = null;
    canvasRenderer.stop();
    performanceStats.source = performanceStats.transport = performanceStats.receiver = null;
    screen.hidden = !(screen.complete && screen.naturalWidth > 0); video.hidden = true;
  }
  function exitLaunchFullscreen() {
    try { Promise.resolve(document.exitFullscreen()).catch(() => {}); } catch (_) {}
  }
  function prepareLaunchPresentation() {
    const presentation = { immersive: $('fullscreen-on-start').checked, ownsFullscreen: false, cancelled: false };
    launchPresentation = presentation;
    if (!presentation.immersive || document.fullscreenElement || !(document.documentElement && document.documentElement.requestFullscreen)) return;
    presentation.ownsFullscreen = true;
    try {
      // Keep the request in the submit gesture, before asynchronous pairing or WebSocket setup.
      Promise.resolve(document.documentElement.requestFullscreen()).then(() => {
        if (presentation.cancelled && !(launchPresentation && launchPresentation.immersive) && document.fullscreenElement) exitLaunchFullscreen();
      }).catch(() => { presentation.ownsFullscreen = false; });
    } catch (_) { presentation.ownsFullscreen = false; }
  }
  function restoreLaunchPresentation() {
    if (!launchPresentation) return;
    launchPresentation.cancelled = true;
    launchPresentation.immersive = false;
    if (launchPresentation.ownsFullscreen && document.fullscreenElement) exitLaunchFullscreen();
  }
  function disconnect(refreshQr = true) {
    stopQr();
    authGeneration++;
    restoreLaunchPresentation();
    stopped = true; paired = false; release();
    if (outbox) { outbox.close(); outbox = null; }
    clearTimeout(retry); clearInterval(heartbeat);
    if (socket) { const old = socket; socket = null; old.close(); }
    cleanupRtc(); cleanupImage(); setLive(false);
    browserAudio.close(); $('audio-message').hidden = true;
    $('topbar').hidden = false; $('pairing').hidden = false; $('display').hidden = true;
    $('disconnect').hidden = true; $('connect').disabled = false;
    $('status').textContent = t("web_disconnected");
    if (refreshQr) initializePairing();
  }
  function connect() {
    clearTimeout(retry);
    $('connect').disabled = true;
    $('status').textContent = paired ? t("web_reconnecting") : t("web_connecting");
    const auth = pairingToken ? `token=${encodeURIComponent(pairingToken)}` : `code=${encodeURIComponent($('code').value)}`;
    const ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/stream?${auth}`);
    ws.binaryType = 'arraybuffer';
    const useCanvas = canvasOption.checked;
    socket = ws;
    const connectTimer = setTimeout(() => ws.close(), 8000);
    ws.onopen = () => {
      clearTimeout(connectTimer);
      if (socket !== ws) return;
      if (outbox) outbox.close();
      outbox = createControlOutbox(ws, { onFailure: () => {
        if (socket !== ws) return;
        cancelMove(); pointers.clear(); setLive(false); ws.close();
      } });
      paired = true; attempts = 0; lastStatus = Date.now();
      browserAudio.attach();
      cleanupRtc();
      rtc = createRtcReceiver(video, { send,
        onTrack: () => { if (useCanvas) canvasRenderer.prepare(); },
        onReset: () => canvasRenderer.stop(),
        onReady: () => {
          if (socket !== ws) return;
          if (useCanvas) { canvasRenderer.start(); return; }
          screen.hidden = true; video.hidden = false;
          width = video.videoWidth; height = video.videoHeight; setLive(true);
        },
        onStop: () => {
          if (socket !== ws) return;
          screen.hidden = !(screen.complete && screen.naturalWidth > 0); video.hidden = true; rtcFps = null;
          setLive(Boolean(lastFrame && screen.naturalWidth));
        },
        onStats: (fps, metrics) => { rtcFps = fps === null ? null : Math.round(fps); performanceStats.receiver = metrics; },
      });
      $('message').textContent = '';
      $('phone-connect').disabled = false;
      $('phone-connect-message').textContent = t("web_keep_wheelplay_in_the_foreground_on_android_tap_connect");
      $('pairing').hidden = true; $('display').hidden = false; $('disconnect').hidden = false;
      $('topbar').hidden = $('hide-toolbar').checked || Boolean(launchPresentation && launchPresentation.immersive);
      lastViewportSignature = '';
      scheduleViewportReport();
      $('status').textContent = t("web_connected_waiting_for_video");
      send({ type: 'ping' });
      clearInterval(heartbeat);
      heartbeat = setInterval(() => {
        if (Date.now() - lastStatus > 6000) { ws.close(); return; }
        send({ type: 'ping' });
        if (pointers.size) send({ type: 'touch', contacts: contacts() }, 'move');
      }, 400);
    };
    ws.onmessage = event => {
      if (socket !== ws || ws.readyState !== WebSocket.OPEN) return;
      if (typeof event.data === 'string') {
        const data = JSON.parse(event.data);
        if (browserAudio.control(data)) return;
        if (data.type === 'rtc-offer' || data.type === 'rtc-stop') {
          if (data.type === 'rtc-stop') rtcRequested = false;
          rtc.receive(data); return;
        }
        if (data.type === 'phone-connect-result') {
          $('phone-connect').disabled = false;
          $('phone-connect-message').textContent = data.message;
          return;
        }
        if (data.type === 'busy') {
          const message = data.message; disconnect(); $('message').textContent = message; return;
        }
        if (data.type === 'status') {
          performanceStats.source = (data.performance && data.performance.source) || null;
          performanceStats.transport = (data.performance && data.performance.transport) || null;
          lastStatus = Date.now(); width = data.width; height = data.height;
          $('stage').textContent = data.stage || t("web_waiting_for_iphone_video");
          rtc.progress(data.rtcFrames || 0);
          if (data.rtcAvailable && !rtcRequested && typeof RTCPeerConnection !== 'undefined') {
            rtcRequested = true; send({ type: 'rtc-start' });
          }
          // The server resets availability when the iPhone starts a new video configuration.
          if (!data.rtcAvailable) rtcRequested = false;
          setLive(Boolean(data.streaming && (rtc.active || lastFrame && screen.naturalWidth)));
          const transport = rtc.active ? `${rtc.codec === 'H265' ? 'HEVC' : 'H.264'} / WebRTC${useCanvas ? ' / Canvas' : ''}${rtcFps === null ? '' : ` 鐠?${rtcFps} fps`}` : `JPEG${data.fallback ? t("web_fallback") : ''}`;
          $('status').textContent = live ? t("web_transport_touch_connected", transport, rtc.recovering ? t("web_waiting_for_recovery") : '') : t("web_connected_waiting_for_iphone_video");
        }
        if (data.type === 'input-unavailable') $('status').textContent = t("web_waiting_for_iphone_touch_input");
        return;
      }
      if (browserAudio.receive(event.data)) return;
      if (rtc && rtc.active) { send({ type: 'ack' }); return; }
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      objectUrl = URL.createObjectURL(event.data instanceof ArrayBuffer ? new Blob([event.data], { type: 'image/jpeg' }) : event.data);
      screen.onload = () => {
        if (socket !== ws || ws.readyState !== WebSocket.OPEN) return;
        if (rtc && rtc.active) { send({ type: 'ack' }); return; }
        screen.hidden = false;
        lastFrame = Date.now(); width = screen.naturalWidth; height = screen.naturalHeight;
        if (!rtc || !rtc.active) { setLive(true); $('status').textContent = t("web_jpeg_touch_connected"); }
        send({ type: 'ack' });
      };
      screen.onerror = () => { screen.hidden = true; ws.close(); };
      screen.src = objectUrl;
    };
    ws.onclose = () => {
      clearTimeout(connectTimer);
      if (socket !== ws) return;
      if (outbox) { outbox.close(); outbox = null; }
      cancelMove();
      socket = null; pointers.clear(); clearInterval(heartbeat); cleanupRtc(); cleanupImage(); setLive(false);
      browserAudio.close(); $('audio-message').hidden = true;
      if (stopped) return;
      if (!paired || ++attempts > 8) {
        disconnect(); $('status').textContent = t("web_could_not_connect");
        $('message').textContent = t("web_check_that_the_server_is_running_and_the_pairing");
        return;
      }
      $('status').textContent = t("web_connection_interrupted_retrying");
      $('stage').textContent = t("web_check_that_the_server_and_head_unit_are_still");
      const generation = authGeneration;
      retry = setTimeout(async () => {
        if (deviceCredential) {
          try {
            const result = await pairApi('resume', { device: deviceCredential });
            if (stopped || generation !== authGeneration) return;
            pairingToken = result.token;
          } catch (error) {
            if (stopped || generation !== authGeneration) return;
            if (error.status === 401) { disconnect(); return; }
          }
        }
        if (!stopped && generation === authGeneration) connect();
      }, Math.min(8000, 500 * 2 ** attempts));
    };
    ws.onerror = () => {}; // onclose owns retry and visible error state.
  }
  $('connect-form').addEventListener('submit', event => {
    event.preventDefault();
    const generation = ++authGeneration;
    prepareLaunchPresentation();
    stopQr(); $('connect').disabled = true; $('message').textContent = '';
    const start = () => { if (generation === authGeneration) { stopped = false; attempts = 0; connect(); } };
    if (pairingToken && !deviceCredential) { start(); return; }
    (async () => {
      try {
        if (deviceCredential) {
          const result = await pairApi('resume', { device: deviceCredential });
          if (generation !== authGeneration) return;
          pairingToken = result.token;
        } else {
          const result = await pairApi('code', { code: $('code').value });
          if (generation !== authGeneration) return;
          await preparePairing(result.token, generation);
        }
        start();
      } catch (error) {
        if (generation !== authGeneration) return;
        restoreLaunchPresentation();
        $('connect').disabled = false;
        if (error.status === 401 && deviceCredential) {
          pairingToken = null; deviceCredential = null;
          try { window.localStorage.removeItem(devicePreference); } catch (_) {}
          showPairingEntry(); startQr();
        }
        $('status').textContent = t("web_could_not_pair");
        $('message').textContent = t("web_check_the_service_and_network_or_scan_again_enter");
      }
    })();
  });
  $('phone-connect').addEventListener('click', () => {
    if (!paired || !send({ type: 'phone-connect' })) {
      $('phone-connect-message').textContent = t("web_browser_connection_interrupted_pair_again_and_retry");
      return;
    }
    $('phone-connect').disabled = true;
    $('phone-connect-message').textContent = t("web_requesting_iphone_connection");
  });
  $('disconnect').addEventListener('click', () => disconnect());
  $('fullscreen').addEventListener('click', async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else if (document.documentElement.requestFullscreen) await document.documentElement.requestFullscreen();
      else $('status').textContent = t("web_this_browser_does_not_support_full_screen");
    } catch (_) { $('status').textContent = t("web_use_the_browser_menu_to_toggle_full_screen"); }
  });
  document.addEventListener('fullscreenchange', () => {
    $('fullscreen').textContent = document.fullscreenElement ? t("web_exit_full_screen") : t("web_full_screen");
    if (!document.fullscreenElement && launchPresentation && launchPresentation.immersive) {
      launchPresentation.immersive = false;
      launchPresentation.ownsFullscreen = false;
      $('topbar').hidden = false;
    }
    scheduleViewportReport();
  });
  // A separate layer prevents Android Chrome's native video gestures cancelling drags.
  for (const surface of [screen, video, canvas, touchSurface]) {
  surface.addEventListener('contextmenu', event => event.preventDefault());
  surface.addEventListener('pointerdown', event => {
    if (!live || pointers.size >= 2 || (event.pointerType === 'mouse' && event.button !== 0)) return;
    const point = mapPoint(event.clientX, event.clientY, surface.getBoundingClientRect(), width, height);
    if (!point) return;
    event.preventDefault();
    const id = [...pointers.values()].some(p => p.id === 0) ? 1 : 0;
    pointers.set(event.pointerId, { id, ...point });
    surface.setPointerCapture(event.pointerId);
    cancelMove();
    send({ type: 'touch', contacts: contacts() });
  });
  surface.addEventListener('pointermove', event => {
    const p = pointers.get(event.pointerId); if (!p) return;
    const point = mapPoint(event.clientX, event.clientY, surface.getBoundingClientRect(), width, height, true);
    if (!point) return;
    Object.assign(p, point);
    if (!movePending) {
      movePending = true;
      const generation = moveGeneration;
      requestAnimationFrame(() => {
        if (generation !== moveGeneration) return;
        movePending = false;
        if (pointers.size) send({ type: 'touch', contacts: contacts() }, 'move');
      });
    }
  });
  function up(event) {
    const p = pointers.get(event.pointerId); if (!p) return;
    cancelMove();
    pointers.delete(event.pointerId);
    send({ type: 'touch', contacts: [...contacts(), { ...p, down: false }] });
  }
  surface.addEventListener('pointerup', up);
  surface.addEventListener('pointercancel', up);
  surface.addEventListener('lostpointercapture', up);
  }
  window.addEventListener('blur', release);
  document.addEventListener('visibilitychange', () => { if (document.hidden) release(); });
  window.addEventListener('pagehide', () => disconnect(false));
  window.addEventListener('pageshow', event => { if (event.persisted) initializePairing(); });
  const linkedCode = pairingCodeFromUrl(window.location.search || '');
  if (linkedCode) {
    $('code').value = linkedCode;
    // Keep the short-lived code out of copied URLs and browser history entries.
    window.history.replaceState(null, '', `${window.location.pathname}${window.location.hash}`);
  }
  initializePairing(linkedCode);
}

