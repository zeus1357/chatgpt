const test = require('node:test');
const assert = require('node:assert/strict');
const P = require('./protocol.js');

const u32 = (packet, at) => new DataView(packet.buffer, packet.byteOffset).getUint32(at, true);

test('start packet carries size and UTF-8 name', () => {
  const packet = P.startPacket('사진.jpg', 0x01020304);
  assert.equal(packet[0], P.OP_START);
  assert.deepEqual([...packet.subarray(1, 5)], [4, 3, 2, 1]);
  assert.equal(new TextDecoder().decode(packet.subarray(5)), '사진.jpg');
});

test('long names are shortened to fit one packet, keeping the extension', () => {
  const packet = P.startPacket('가'.repeat(200) + '.pdf', 1);
  assert.ok(packet.length <= P.MAX_PACKET);
  const name = new TextDecoder('utf-8', { fatal: true }).decode(packet.subarray(5));
  assert.ok(name.endsWith('.pdf'));
  assert.ok(name.startsWith('가'));
});

test('files over 4GB are rejected', () => {
  assert.throws(() => P.startPacket('a', 2 ** 32), RangeError);
});

test('data packets cover the whole file in order and fit the packet limit', () => {
  const content = Uint8Array.from({ length: 1000 }, (_, i) => i % 251);
  const out = [];
  let expectedOffset = 0;
  for (const packet of P.dataPackets(content)) {
    assert.equal(packet[0], P.OP_DATA);
    assert.ok(packet.length <= P.MAX_PACKET);
    assert.equal(u32(packet, 1), expectedOffset);
    out.push(...packet.subarray(5));
    expectedOffset += packet.length - 5;
  }
  assert.deepEqual(out, [...content]);
});

test('empty file produces no data packets', () => {
  assert.equal([...P.dataPackets(new Uint8Array(0))].length, 0);
});

test('status notifications are parsed', () => {
  const view = (bytes) => new DataView(Uint8Array.from(bytes).buffer);
  assert.deepEqual(P.parseStatus(view([P.STATUS_DONE])), { type: 'done' });
  const msg = [...new TextEncoder().encode('실패')];
  assert.deepEqual(P.parseStatus(view([P.STATUS_ERROR, ...msg])), { type: 'error', message: '실패' });
  assert.deepEqual(P.parseStatus(view([])), { type: 'unknown' });
});
