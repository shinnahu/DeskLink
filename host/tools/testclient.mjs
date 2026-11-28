// Independent DeskLink protocol test client (Node 18+), written from PROTOCOL.md.
// Usage: node testclient.mjs <host> <port> <workdir>
// Needs a host started with a TEST build: --auto-approve (pairing is approved without clicking).
import net from 'node:net';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const [, , HOST = '127.0.0.1', PORT = '47810', WORK = '.'] = process.argv;
let failures = 0;
const ok = (cond, what) => { console.log(`${cond ? 'PASS' : 'FAIL'}  ${what}`); if (!cond) failures++; };

// ---------------------------------------------------------------- framing
class Conn {
  constructor(sock) {
    this.sock = sock; this.buf = Buffer.alloc(0); this.waiters = []; this.closed = false;
    sock.on('data', (d) => { this.buf = Buffer.concat([this.buf, d]); this.pump(); });
    sock.on('close', () => { this.closed = true; this.pump(); });
    sock.on('error', () => {});
  }
  pump() {
    while (this.waiters.length) {
      const w = this.waiters[0];
      if (this.buf.length >= w.n) {
        this.waiters.shift();
        const b = Buffer.from(this.buf.subarray(0, w.n));
        this.buf = this.buf.subarray(w.n);
        w.resolve(b);
      } else if (this.closed) { this.waiters.shift(); w.reject(new Error('connection closed')); }
      else break;
    }
  }
  read(n) { return new Promise((resolve, reject) => { this.waiters.push({ n, resolve, reject }); this.pump(); }); }
  write(b) { this.sock.write(b); }
  close() { this.sock.destroy(); }
}
const u8 = (v) => Buffer.from([v & 255]);
const u16 = (v) => { const b = Buffer.alloc(2); b.writeUInt16BE(v); return b; };
const u32 = (v) => { const b = Buffer.alloc(4); b.writeUInt32BE(v >>> 0); return b; };
const u64 = (v) => { const b = Buffer.alloc(8); b.writeBigUInt64BE(BigInt(v)); return b; };
const i16 = (v) => { const b = Buffer.alloc(2); b.writeInt16BE(v); return b; };
const str = (s) => { const b = Buffer.from(s, 'utf8'); return Buffer.concat([u16(b.length), b]); };
const sha = (...parts) => { const h = crypto.createHash('sha256'); for (const p of parts) h.update(p); return h.digest(); };
const hmac = (k, d) => crypto.createHmac('sha256', k).update(d).digest();

class R {
  constructor(b) { this.b = b; this.o = 0; }
  u8() { return this.b.readUInt8(this.o++); }
  u16() { const v = this.b.readUInt16BE(this.o); this.o += 2; return v; }
  u32() { const v = this.b.readUInt32BE(this.o); this.o += 4; return v; }
  i32() { const v = this.b.readInt32BE(this.o); this.o += 4; return v; }
  u64() { const v = this.b.readBigUInt64BE(this.o); this.o += 8; return v; }
  raw(n) { const v = this.b.subarray(this.o, this.o + n); this.o += n; return Buffer.from(v); }
  str() { const n = this.u16(); return this.raw(n).toString('utf8'); }
  rest() { const v = this.b.subarray(this.o); this.o = this.b.length; return v; }
}

async function connect() {
  const sock = net.connect({ host: HOST, port: +PORT });
  await new Promise((res, rej) => { sock.once('connect', res); sock.once('error', rej); });
  sock.setNoDelay(true);
  const c = new Conn(sock);
  const ecdh = crypto.createECDH('prime256v1');
  ecdh.generateKeys();
  const ch = Buffer.concat([Buffer.from('DLNK'), u8(1), ecdh.getPublicKey(), crypto.randomBytes(32)]);
  c.write(Buffer.concat([u32(ch.length), ch]));
  const len = (await c.read(4)).readUInt32BE(0);
  if (len !== 231) throw new Error('ServerHello length ' + len);
  const sh = await c.read(231);
  const hostEph = sh.subarray(5, 70), hostStatic = sh.subarray(102, 167), sig = sh.subarray(167, 231);
  const key = crypto.createPublicKey({ key: { kty: 'EC', crv: 'P-256', x: hostStatic.subarray(1, 33).toString('base64url'), y: hostStatic.subarray(33, 65).toString('base64url') }, format: 'jwk' });
  c.sigOk = crypto.verify('sha256', Buffer.concat([ch, sh.subarray(0, 167)]), { key, dsaEncoding: 'ieee-p1363' }, sig);
  const z = ecdh.computeSecret(hostEph);
  c.th = sha(ch, sh);
  const prk = hmac(c.th, z);
  const info = Buffer.from('desklink-v1-keys');
  c.kTx = hmac(prk, Buffer.concat([info, u8(1)]));
  c.kRx = hmac(prk, Buffer.concat([c.kTx, info, u8(2)]));
  c.txSeq = 0n; c.rxSeq = 0n;
  c.fp = sha(hostStatic).toString('hex');
  return c;
}
const nonce = (seq) => { const n = Buffer.alloc(12); n.writeBigUInt64BE(seq, 4); return n; };
function send(c, type, ...parts) {
  const pt = Buffer.concat([u8(type), ...parts]);
  const len = u32(pt.length + 16);
  const ci = crypto.createCipheriv('aes-256-gcm', c.kTx, nonce(c.txSeq++));
  ci.setAAD(len);
  c.write(Buffer.concat([len, ci.update(pt), ci.final(), ci.getAuthTag()]));
}
// One message read at a time. A wait that times out leaves its read running, and the next recv() gets that message:
// nothing is lost, and the reads of two messages never interleave (that would put the stream out of step).
function recv(c) {
  if (!c.pending) c.pending = readMessage(c).finally(() => { c.pending = null; });
  return c.pending;
}
async function readMessage(c) {
  const lenb = await c.read(4);
  const len = lenb.readUInt32BE(0);
  const data = await c.read(len);
  const de = crypto.createDecipheriv('aes-256-gcm', c.kRx, nonce(c.rxSeq++));
  de.setAAD(lenb);
  de.setAuthTag(data.subarray(len - 16));
  const pt = Buffer.concat([de.update(data.subarray(0, len - 16)), de.final()]);
  return { type: pt[0], r: new R(pt.subarray(1)), raw: pt.subarray(1) };
}
async function recvType(c, type, ms = 5000) {
  const deadline = Date.now() + ms;
  for (;;) {
    const m = await Promise.race([recv(c), new Promise((_, rej) => setTimeout(() => rej(new Error('timeout waiting 0x' + type.toString(16))), Math.max(1, deadline - Date.now())))]);
    if (m.type === type) return m;
    if (m.type === 0x50) send(c, 0x51, m.raw.subarray(0, 8));
  }
}

async function pair(name) {
  const c = await connect();
  ok(c.sigOk, `${name}: host signature verifies`);
  const nonceC = crypto.randomBytes(32);
  send(c, 0x02, sha(nonceC), str(name), str('TestModel'));
  const chal = await recvType(c, 0x81);
  const nonceH = chal.r.raw(32);
  send(c, 0x03, nonceC);
  const d = sha(Buffer.from('desklink-v1-sas'), c.th, nonceC, nonceH);
  const code = String(d.readUInt32BE(0) % 1000000).padStart(6, '0');
  const res = await recvType(c, 0x82, 10000);
  const st = res.r.u8();
  ok(st === 0, `${name}: paired (code ${code})`);
  const creds = { deviceId: res.r.raw(16), token: res.r.raw(32), hostId: res.r.raw(16), hostName: res.r.str(), fp: c.fp };
  return { c, creds };
}

function auth(c, creds, name) { send(c, 0x01, creds.deviceId, creds.token, str(name), u16(1)); }

// A side connection (phones from 2.17, for a backup): AUTH with the SIDE flag. Its AUTH_RESULT status.
async function side(creds, name) {
  const s = await connect();
  send(s, 0x01, creds.deviceId, creds.token, str(name), u16(1), u8(1));
  const r = await recvType(s, 0x80);
  const st = r.r.u8();
  if (st === 0) await recvType(s, 0x83);
  return { s, st };
}

// BACKUP_CHECK: what the PC has of each {folder, name, size}
async function backupCheck(s, req, entries) {
  send(s, 0x48, u32(req), u16(entries.length), ...entries.flatMap((e) => [str(e.folder), str(e.name), u64(e.size)]));
  const m = await recvType(s, 0x49, 5000);
  const rq = m.r.u32(), n = m.r.u16(), have = [];
  for (let i = 0; i < n; i++) have.push(Number(m.r.u64()));
  return { rq, have };
}

// A backup file from `offset` on (FILE_BEGIN of kind 2). Without `upTo` it ends it and gives the PC's FILE_DONE.
async function backupSend(s, id, e, data, mtimeMs, offset, upTo) {
  send(s, 0x40, u32(id), u64(data.length), str(e.name), str('image/jpeg'), u8(2), str(e.folder), u64(mtimeMs), u64(offset));
  const end = upTo ?? data.length;
  for (let off = offset; off < end; off += 200 * 1024) send(s, 0x41, u32(id), data.subarray(off, Math.min(end, off + 200 * 1024)));
  if (upTo !== undefined) return null;
  send(s, 0x42, u32(id));
  const d = await recvType(s, 0x45, 15000);
  return { id: d.r.u32(), st: d.r.u8(), info: d.r.str() };
}

// Is the connection still there? (a PING gets its PONG)
async function answers(c) {
  send(c, 0x50, u64(Date.now()));
  try { await recvType(c, 0x51, 3000); return true; } catch { return false; }
}

// A quiet tone for the PC's speakers (48 kHz stereo, about -30 dB), played by Windows while the sound is checked
function toneWav(file, seconds, hz) {
  const rate = 48000, n = rate * seconds, data = Buffer.alloc(n * 4);
  for (let i = 0; i < n; i++) {
    const v = Math.round(Math.sin(2 * Math.PI * hz * i / rate) * 1000);
    data.writeInt16LE(v, i * 4); data.writeInt16LE(v, i * 4 + 2);
  }
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + data.length, 4); h.write('WAVE', 8); h.write('fmt ', 12);
  h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(2, 22); h.writeUInt32LE(rate, 24);
  h.writeUInt32LE(rate * 4, 28); h.writeUInt16LE(4, 32); h.writeUInt16LE(16, 34); h.write('data', 36);
  h.writeUInt32LE(data.length, 40);
  fs.writeFileSync(file, Buffer.concat([h, data]));
}

// AUDIO_DATA frames that come in `ms` (video frames are acked, pings answered)
async function countSound(c, ms) {
  let frames = 0;
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    let m;
    try { m = await Promise.race([recv(c), new Promise((_, rej) => setTimeout(() => rej(new Error('idle')), 200))]); } catch { continue; }
    if (m.type === 0x96) frames++;
    else if (m.type === 0x91) send(c, 0x13, u32(m.r.u32()));
    else if (m.type === 0x50) send(c, 0x51, m.raw.subarray(0, 8));
  }
  return frames;
}

async function main() {
  fs.mkdirSync(WORK, { recursive: true });
  // 1. pair device A and continue on the same connection with AUTH
  const { c, creds } = await pair('Phone A');
  auth(c, creds, 'Phone A');
  const ar = await recvType(c, 0x80);
  ok(ar.r.u8() === 0, 'A: AUTH ok');
  const hi = await recvType(c, 0x83);
  const hostId = hi.r.raw(16); const hostName = hi.r.str(); const caps = hi.r.u32(); const nMon = hi.r.u8();
  const mons = [];
  for (let i = 0; i < nMon; i++) mons.push({ idx: hi.r.u8(), l: hi.r.i32(), t: hi.r.i32(), w: hi.r.i32(), h: hi.r.i32(), primary: hi.r.u8(), name: hi.r.str() });
  const nAddr = hi.r.u8(); const addrs = [];
  for (let i = 0; i < nAddr; i++) addrs.push(hi.r.str());
  ok(hostId.equals(creds.hostId), `HOST_INFO: host ${hostName}, caps=${caps}, monitors=${JSON.stringify(mons.map((m) => m.name))}, addresses=${addrs.join(' ')}`);

  // 2. stream 3 seconds at 1280x720/30
  send(c, 0x10, u8(0xff), u16(1280), u16(720), u8(30), u32(0), u8(1));
  const out = fs.openSync(path.join(WORK, 'net.h264'), 'w');
  let frames = 0, keys = 0, bytes = 0, fmt = null, shapes = 0, states = 0, pongs = 0, rtt = [];
  const t0 = Date.now();
  let lastPing = 0;
  while (Date.now() - t0 < 3000) {
    if (Date.now() - lastPing > 1000) { send(c, 0x50, u64(Date.now())); lastPing = Date.now(); }
    let m;
    try { m = await Promise.race([recv(c), new Promise((_, rej) => setTimeout(() => rej(new Error('idle')), 300))]); } catch { continue; }
    switch (m.type) {
      case 0x90: fmt = { codec: m.r.u8(), w: m.r.u16(), h: m.r.u16(), mon: m.r.u8(), dl: m.r.i32(), dt: m.r.i32(), dw: m.r.i32(), dh: m.r.i32() }; break;
      case 0x91: { const id = m.r.u32(); const fl = m.r.u8(); m.r.u64(); const d = m.r.rest(); fs.writeSync(out, d); frames++; bytes += d.length; if (fl & 1) keys++; send(c, 0x13, u32(id)); break; }
      case 0x92: shapes++; break;
      case 0x93: states++; break;
      case 0x51: pongs++; rtt.push(Date.now() - Number(m.r.u64())); break;
      case 0x50: send(c, 0x51, m.raw.subarray(0, 8)); break;
      case 0x94: console.log('      HOST_STATUS', m.r.u8(), m.r.str()); break;
    }
  }
  fs.closeSync(out);
  ok(fmt && fmt.codec === 1 && fmt.w === 1280 && fmt.h === 720, `VIDEO_FORMAT ${JSON.stringify(fmt)}`);
  ok(frames > 0 && keys >= 1, `frames=${frames} keyframes=${keys} bytes=${bytes}`);
  ok(shapes >= 1 && states >= 1, `cursor shapes=${shapes} states=${states}`);
  ok(pongs >= 2, `ping/pong x${pongs}, rtt ${rtt.join('/')} ms`);

  // 3. pause / resume / keyframe request
  send(c, 0x11);
  await new Promise((r) => setTimeout(r, 400));
  send(c, 0x12);
  const kf = await recvType(c, 0x91, 3000);
  kf.r.u32();
  ok((kf.r.u8() & 1) === 1, 'RESUME starts with a keyframe');

  // 3b. the PC's sound (capability AUDIO): SET_OPTIONS with the sound flag brings AUDIO_FORMAT
  ok((caps & 128) !== 0, `capabilities include AUDIO (${caps})`);
  send(c, 0x16, u8(1 | 2));
  const af = await recvType(c, 0x95, 4000);
  const acodec = af.r.u8(), arate = af.r.u32(), ach = af.r.u8(), acfg = af.r.rest();
  ok((acodec === 1 && (arate === 48000 || arate === 44100) && ach === 2 && acfg.length === 2) || acodec === 0,
    `AUDIO_FORMAT codec=${acodec} ${arate} Hz ${ach} ch config=${acfg.toString('hex')}` +
    (acodec === 0 ? ' (this PC has no sound output)' : ''));
  send(c, 0x16, u8(1)); // sound off again

  // 3c. a screenshot (capability SCREENSHOT): the shown screen at full size, sent as a PNG file of kind 1
  ok((caps & 256) !== 0, `capabilities include SCREENSHOT (${caps})`);
  send(c, 0x18);
  {
    let begin = null, shot = null, failed = null, got = 0;
    const parts = [], deadline = Date.now() + 15000;
    while (!shot && !failed && Date.now() < deadline) {
      let m;
      try { m = await Promise.race([recv(c), new Promise((_, rej) => setTimeout(() => rej(new Error('idle')), 300))]); } catch { continue; }
      if (m.type === 0x50) send(c, 0x51, m.raw.subarray(0, 8));
      else if (m.type === 0x91) send(c, 0x13, u32(m.r.u32()));
      else if (m.type === 0x97) failed = `${m.r.u8()}: ${m.r.str()}`;
      else if (m.type === 0x40) begin = { id: m.r.u32(), size: Number(m.r.u64()), name: m.r.str(), mime: m.r.str(), kind: m.r.u8() };
      else if (m.type === 0x41 && begin && m.r.u32() === begin.id) {
        const d = Buffer.from(m.r.rest());
        parts.push(d);
        got += d.length;
        send(c, 0x44, u32(begin.id), u64(got));
      } else if (m.type === 0x42 && begin && m.r.u32() === begin.id) {
        shot = Buffer.concat(parts);
        send(c, 0x45, u32(begin.id), u8(0), str(''));
      }
    }
    const png = shot && shot.subarray(0, 8).equals(Buffer.from('89504e470d0a1a0a', 'hex'));
    const pw = png ? shot.readUInt32BE(16) : 0, ph = png ? shot.readUInt32BE(20) : 0;
    if (shot) fs.writeFileSync(path.join(WORK, 'shot.png'), shot);
    ok(!failed && begin && begin.kind === 1 && begin.mime === 'image/png' && begin.name.endsWith('.png') &&
      shot.length === begin.size && png && fmt && pw === fmt.dw && ph === fmt.dh,
      failed ? `SCREENSHOT failed (${failed})` : `SCREENSHOT: "${begin && begin.name}" kind ${begin && begin.kind}, ` +
      `${pw}x${ph} PNG (screen ${fmt && fmt.dw}x${fmt && fmt.dh}), ${Math.round(got / 1024)} KB`);
  }

  // 3d. CLIENT_LOG (host 2.9): a line for the PC's log; the session goes on as before. With HOST_LOG set to the test
  // host's log file (its --data folder's host.log), the line must be in it.
  {
    const marker = `decoder check ${crypto.randomBytes(4).toString('hex')} ñ\ttab`;
    send(c, 0x53, Buffer.from(marker, 'utf8'));
    send(c, 0x53, Buffer.alloc(0)); // an empty one is left out
    send(c, 0x50, u64(Date.now()));
    let pong = false;
    try { await recvType(c, 0x51, 3000); pong = true; } catch { /* no answer */ }
    const logPath = process.env.HOST_LOG;
    let logged = null;
    for (let i = 0; logPath && i < 30 && !logged; i++) {
      await new Promise((r) => setTimeout(r, 100));
      const text = fs.existsSync(logPath) ? fs.readFileSync(logPath, 'utf8') : '';
      logged = text.split(/\r?\n/).find((l) => l.includes('phone: ' + marker.replace('\t', ' '))) || null;
    }
    ok(pong && (!logPath || logged), 'CLIENT_LOG: the session goes on' +
      (!logPath ? ' (HOST_LOG not set: the log line is not checked)' : logged ? `; logged: ${logged.slice(13)}` : '; NOT in the log'));
  }

  // 3e. PASTE (host 2.10): text the phone's keyboard pasted. A test host without its UI (--no-ui) has no clipboard
  // to paste from, so it types the text, the line break as Shift+Enter. Sent only when HOST_NO_INJECT=1 says the test
  // host was started with --no-inject (it then logs the keys instead of typing them on this PC), and checked in its log.
  ok((caps & 512) !== 0, `capabilities include PASTE (${caps})`);
  if (process.env.HOST_NO_INJECT === '1' && process.env.HOST_LOG) {
    const logPath = process.env.HOST_LOG;
    const before = fs.existsSync(logPath) ? fs.readFileSync(logPath, 'utf8').length : 0;
    send(c, 0x28, Buffer.from('ab\ncd', 'utf8'));
    let keys = null;
    for (let i = 0; i < 30 && !keys; i++) {
      await new Promise((r) => setTimeout(r, 100));
      const lines = fs.readFileSync(logPath, 'utf8').slice(before).split(/\r?\n/);
      const at = lines.findIndex((l) => l.includes('input: a paste of 5 characters, typed'));
      if (at < 0) continue;
      const k = lines.slice(at + 1).filter((l) => l.includes('input(test): key')).slice(0, 12);
      if (k.length < 12) continue;
      keys = k.map((l) => {
        const m = /vk=0x([0-9a-f]+) scan=0x([0-9a-f]+) flags=0x([0-9a-f]+)/.exec(l);
        const vk = parseInt(m[1], 16), scan = parseInt(m[2], 16), fl = parseInt(m[3], 16);
        const name = fl & 4 ? String.fromCharCode(scan) : vk === 0x10 ? 'SHIFT' : vk === 0x0d ? 'RETURN' : `vk${vk}`;
        return `${name} ${fl & 2 ? 'up' : 'down'}`;
      });
    }
    const want = ['a down', 'a up', 'b down', 'b up', 'SHIFT down', 'RETURN down', 'RETURN up', 'SHIFT up', 'c down', 'c up', 'd down', 'd up'];
    ok(keys && keys.join() === want.join(), `PASTE typed without a clipboard: ${keys ? keys.join(', ') : 'NOT in the log'}`);

    // 3f. TEXT with a line break inside (a phone before 2.13 pasting): taken as a paste, so no plain Enter (host 2.11)
    const mark = fs.readFileSync(logPath, 'utf8').length;
    send(c, 0x24, Buffer.from('ef\ngh', 'utf8'));
    let added = [];
    for (let i = 0; i < 30 && added.filter((l) => l.includes('input(test): key')).length < 12; i++) {
      await new Promise((r) => setTimeout(r, 100));
      added = fs.readFileSync(logPath, 'utf8').slice(mark).split(/\r?\n/);
    }
    const at = (vk, up) => added.findIndex((l) => l.includes(`key vk=0x${vk}`) && /flags=0x[0-9a-f]*([0-9a-f])/.test(l) &&
      ((parseInt(/flags=0x([0-9a-f]+)/.exec(l)[1], 16) & 2) !== 0) === up);
    ok(added.some((l) => l.includes('taken as a paste')) && at('10', false) >= 0 && at('10', false) < at('0d', false) &&
      at('0d', true) < at('10', true), 'TEXT with a line break: taken as a paste, Shift held around its Enter');
  } else {
    console.log('      PASTE not sent (set HOST_NO_INJECT=1 and HOST_LOG for a host started with --no-inject)');
  }

  // 4. upload a 1.5 MB file (phone -> PC)
  const blob = crypto.randomBytes(1536 * 1024 + 123);
  send(c, 0x40, u32(1), u64(blob.length), str('report ñ.bin'), str('application/octet-stream'));
  for (let off = 0; off < blob.length; off += 200 * 1024) send(c, 0x41, u32(1), blob.subarray(off, Math.min(blob.length, off + 200 * 1024)));
  send(c, 0x42, u32(1));
  const done = await recvType(c, 0x45, 10000);
  const did = done.r.u32(), dst = done.r.u8(), info = done.r.str();
  const saved = fs.existsSync(info) ? fs.readFileSync(info) : null;
  ok(did === 1 && dst === 0 && saved && saved.equals(blob), `upload saved as "${info}"`);
  if (saved) fs.unlinkSync(info);

  // 4b. a backup (host 2.12, capability BACKUP) on a side connection, while A's remote session goes on
  ok((caps & 2048) !== 0 && (caps & 1024) !== 0, `capabilities include BACKUP and SOUND_PAUSED (${caps})`);
  {
    const run = crypto.randomBytes(3).toString('hex'); // folders of their own: a test run before left its files
    const cam = { folder: `DCIM/Camera ${run}`, name: 'IMG_0001.jpg' };
    const wa = { folder: `WhatsApp ${run}/Media/WhatsApp Images`, name: 'IMG-ñandú.jpg' };
    const vid = { folder: `DCIM/Camera ${run}`, name: 'VID_0002.mp4' };
    const camData = crypto.randomBytes(700 * 1024 + 5), waData = crypto.randomBytes(90 * 1024), vidData = crypto.randomBytes(3 * 1024 * 1024 + 77);
    cam.size = camData.length; wa.size = waData.length; vid.size = vidData.length;
    const mtime = Date.UTC(2021, 4, 6, 12, 34, 56, 789);
    let { s, st } = await side(creds, 'Phone A');
    ok(st === 0 && await answers(c), 'side connection: AUTH ok, and A\'s remote session is still there');
    let ch = await backupCheck(s, 7, [cam, wa, vid]);
    ok(ch.rq === 7 && ch.have.join() === '0,0,0', `BACKUP_CHECK of 3 new files: ${ch.have.join(', ')}`);
    const d1 = await backupSend(s, 11, cam, camData, mtime, 0);
    const p1 = d1 && d1.info, f1 = p1 && fs.existsSync(p1) ? fs.readFileSync(p1) : null;
    const want1 = path.join('backup', 'Phone A', 'DCIM', `Camera ${run}`, 'IMG_0001.jpg');
    ok(d1 && d1.st === 0 && f1 && f1.equals(camData) && p1.endsWith(want1),
      `backup file saved in its folder: ${p1}`);
    ok(f1 && Math.abs(fs.statSync(p1).mtimeMs - mtime) < 2, `it has the phone's date (${f1 && fs.statSync(p1).mtime.toISOString()})`);
    // the video, cut off a bit after the PC said it had 1 MB: its part stays for the next backup
    await backupSend(s, 13, vid, vidData, mtime, 0, 1536 * 1024);
    const pr = await recvType(s, 0x44, 5000);
    pr.r.u32();
    const acked = Number(pr.r.u64());
    await new Promise((r) => setTimeout(r, 400));
    s.close();
    await new Promise((r) => setTimeout(r, 600));
    ({ s, st } = await side(creds, 'Phone A'));
    ch = await backupCheck(s, 8, [cam, wa, vid]);
    const part = ch.have[2];
    ok(st === 0 && ch.have[0] === cam.size && ch.have[1] === 0 && part >= acked && part < vid.size,
      `after the cut: the photo is there, the video goes on from ${part} of ${vid.size} bytes (${acked} acknowledged)`);
    const d3 = await backupSend(s, 15, vid, vidData, mtime, part);
    const f3 = d3 && fs.existsSync(d3.info) ? fs.readFileSync(d3.info) : null;
    ok(d3 && d3.st === 0 && f3 && f3.equals(vidData), `the video, gone on from its part, is whole: ${d3 && d3.info}`);
    const dir = p1 ? path.dirname(p1) : null;
    ok(dir && fs.readdirSync(dir).filter((n) => n.endsWith('.part')).length === 0, 'no part is left behind');
    const d2 = await backupSend(s, 17, wa, waData, 0, 0);
    const want2 = path.join('backup', 'Phone A', `WhatsApp ${run}`, 'Media', 'WhatsApp Images', 'IMG-ñandú.jpg');
    ok(d2 && d2.st === 0 && d2.info.endsWith(want2) && fs.readFileSync(d2.info).equals(waData),
      `folders inside folders, a name with ñ, no date given: ${d2 && d2.info}`);
    // another file of the same name and folder: it's kept beside the first, and found there the next time
    const other = { folder: cam.folder, name: cam.name }, otherData = crypto.randomBytes(1234);
    other.size = otherData.length;
    const d4 = await backupSend(s, 19, other, otherData, mtime, 0);
    ch = await backupCheck(s, 9, [cam, wa, vid, other]);
    ok(d4 && d4.st === 0 && d4.info.endsWith('IMG_0001 (1).jpg') && ch.have.join() === [cam.size, wa.size, vid.size, other.size].join(),
      `everything is there for the next backup: ${ch.have.join(', ')} (the other one as ${d4 && path.basename(d4.info)})`);
    // the backup's end; and a side connection takes no input: with --no-inject the PC would log the key
    const logPath = process.env.HOST_LOG;
    const mark = logPath && fs.existsSync(logPath) ? fs.readFileSync(logPath, 'utf8').length : 0;
    send(s, 0x4a, u32(4), u64(cam.size + vid.size + wa.size + other.size), u32(0));
    send(s, 0x23, u16(0x41), u8(1));
    send(s, 0x23, u16(0x41), u8(0));
    const stillThere = await answers(s);
    if (logPath) {
      await new Promise((r) => setTimeout(r, 500));
      const added = fs.readFileSync(logPath, 'utf8').slice(mark);
      ok(stillThere && !added.includes('vk=0x41') && added.includes('backup from Phone A over: 4 files'),
        'the side connection\'s key is left alone; the backup\'s end is in the log');
    } else {
      ok(stillThere, 'the side connection takes a key without ending (HOST_LOG not set: the log is not checked)');
    }
    // a newer side connection of the same phone replaces the older one, and A's session stays
    const { s: s2, st: st2 } = await side(creds, 'Phone A');
    const bye = await recvType(s, 0x5f, 3000).catch(() => null);
    ok(st2 === 0 && bye && bye.r.u8() === 2 && await answers(c), 'a newer side connection replaces the older one; A\'s session stays');
    send(s2, 0x5f, u8(0), str('bye'));
    s2.close();
    s.close();
    if (dir) {
      fs.rmSync(dir, { recursive: true, force: true });
      fs.rmSync(path.join(dir, '..', '..', `WhatsApp ${run}`), { recursive: true, force: true });
    }
  }

  // 4c. the sound goes on while the picture is paused (host 2.12, SOUND_PAUSED): a quiet tone plays on the PC
  {
    send(c, 0x16, u8(1 | 2));
    const af2 = await recvType(c, 0x95, 4000);
    if (af2.r.u8() === 0) {
      console.log('      sound while paused not checked: this PC has no sound output');
    } else {
      const wav = path.join(WORK, 'tone.wav');
      toneWav(wav, 4, 523);
      const { spawn } = await import('node:child_process');
      const player = spawn('powershell', ['-NoProfile', '-Command', `(New-Object Media.SoundPlayer '${wav}').PlaySync()`], { stdio: 'ignore' });
      await new Promise((r) => setTimeout(r, 700));
      send(c, 0x11, u8(1)); // pause, keeping the sound
      const kept = await countSound(c, 1500);
      send(c, 0x11); // a plain pause stops it
      await countSound(c, 400);
      const after = await countSound(c, 1000);
      player.kill();
      send(c, 0x12);
      await recvType(c, 0x91, 3000).catch(() => null);
      ok(kept > 20 && after === 0, `paused with KEEP_SOUND: ${kept} sound frames in 1.5 s; a plain pause: ${after} in 1 s`);
    }
    send(c, 0x16, u8(1)); // sound off again
  }

  // 5. second phone: busy + status
  const { c: c2, creds: creds2 } = await pair('Phone B');
  auth(c2, creds2, 'Phone B');
  const busy = await recvType(c2, 0x80);
  const bst = busy.r.u8();
  ok(bst === 3, `B: AUTH while A is active -> BUSY (${bst}: ${busy.r.str()})`);
  c2.close();
  // ...but B's backup isn't turned away: it takes nothing from A
  const { s: sb, st: sbst } = await side(creds2, 'Phone B');
  ok(sbst === 0 && await answers(c), `B: a side connection while A is active -> OK (${sbst}); A stays`);
  send(sb, 0x5f, u8(0), str('bye'));
  sb.close();
  const c3 = await connect();
  send(c3, 0x04, creds2.deviceId, creds2.token);
  const sr = await recvType(c3, 0x84);
  const sst = sr.r.u8(), sfl = sr.r.u8(), sname = sr.r.str(), sactive = sr.r.str(), up = sr.r.u32();
  ok(sst === 0 && (sfl & 1) && sactive === 'Phone A', `STATUS_QUERY: flags=${sfl} host=${sname} activeDevice=${sactive} uptime=${up}s`);
  c3.close();

  // 6. bad token is rejected
  const c4 = await connect();
  send(c4, 0x01, creds2.deviceId, crypto.randomBytes(32), str('Phone B'), u16(1));
  const bad = await recvType(c4, 0x80);
  ok(bad.r.u8() === 2, 'bad token -> BAD_TOKEN');
  c4.close();

  // 7. BYE ends the session; then B can connect
  send(c, 0x5f, u8(0), str('bye'));
  await new Promise((r) => setTimeout(r, 600));
  c.close();
  const c5 = await connect();
  auth(c5, creds2, 'Phone B');
  const ok5 = await recvType(c5, 0x80);
  ok(ok5.r.u8() === 0, 'B can connect after A said BYE');
  send(c5, 0x5f, u8(0), str('bye'));
  await new Promise((r) => setTimeout(r, 300));
  c5.close();

  fs.writeFileSync(path.join(WORK, 'creds.json'), JSON.stringify({ deviceId: creds.deviceId.toString('hex'), token: creds.token.toString('hex'), fp: creds.fp }));
  console.log(failures === 0 ? '\nALL PASSED' : `\n${failures} FAILURE(S)`);
  process.exit(failures === 0 ? 0 : 1);
}

// "hold" mode: pair + auth + stream full HD and stay connected (for UI / memory checks)
async function hold(seconds) {
  const { c, creds } = await pair('Galaxy S23');
  auth(c, creds, 'Galaxy S23');
  await recvType(c, 0x80); await recvType(c, 0x83);
  send(c, 0x10, u8(0xff), u16(1920), u16(1080), u8(60), u32(0), u8(1));
  const t0 = Date.now(); let lastPing = 0, frames = 0;
  while (Date.now() - t0 < seconds * 1000) {
    if (Date.now() - lastPing > 1000) { send(c, 0x50, u64(Date.now())); lastPing = Date.now(); }
    let m;
    try { m = await Promise.race([recv(c), new Promise((_, rej) => setTimeout(() => rej(new Error('idle')), 300))]); } catch { continue; }
    if (m.type === 0x91) { send(c, 0x13, u32(m.r.u32())); frames++; }
    if (m.type === 0x50) send(c, 0x51, m.raw.subarray(0, 8));
  }
  console.log(`hold: ${frames} frames in ${seconds}s`);
  send(c, 0x5f, u8(0), str('bye'));
  await new Promise((r) => setTimeout(r, 300));
  process.exit(0);
}
if (process.argv[5] === 'hold') { hold(+(process.argv[6] || 10)).catch((e) => { console.error(e); process.exit(2); }); }
else main().catch((e) => { console.error('ERROR', e); process.exit(2); });