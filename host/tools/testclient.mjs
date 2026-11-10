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

  // 5. second phone: busy + status
  const { c: c2, creds: creds2 } = await pair('Phone B');
  auth(c2, creds2, 'Phone B');
  const busy = await recvType(c2, 0x80);
  const bst = busy.r.u8();
  ok(bst === 3, `B: AUTH while A is active -> BUSY (${bst}: ${busy.r.str()})`);
  c2.close();
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