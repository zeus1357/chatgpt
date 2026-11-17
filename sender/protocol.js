// 아이폰 앱(ios/BtFileWatch/TransferProtocol.swift)과 맞춰야 하는 전송 규약.
(function (root) {
  const SERVICE_UUID = '7b1d0001-5c3a-4f6e-9b2a-3e8f4c1d2a10';
  const WRITE_UUID = '7b1d0002-5c3a-4f6e-9b2a-3e8f4c1d2a10';
  const STATUS_UUID = '7b1d0003-5c3a-4f6e-9b2a-3e8f4c1d2a10';

  const OP_START = 0x01, OP_DATA = 0x02, OP_END = 0x03, OP_CANCEL = 0x04;
  const STATUS_DONE = 0x11, STATUS_ERROR = 0x12;

  // 아이폰과의 BLE 연결은 보통 한 번에 182바이트까지 쓸 수 있다 (MTU 185).
  const MAX_PACKET = 180;
  const HEADER = 5;
  const CHUNK = MAX_PACKET - HEADER;
  const MAX_FILE_SIZE = 0xffffffff;

  const encoder = new TextEncoder();

  /** 이름이 패킷에 안 들어가면 확장자는 남기고 앞부분을 줄인다. */
  function fitName(name, maxBytes) {
    if (encoder.encode(name).length <= maxBytes) return name;
    const dot = name.lastIndexOf('.');
    const ext = dot > 0 && name.length - dot <= 10 ? name.slice(dot) : '';
    const stem = Array.from(ext ? name.slice(0, dot) : name);
    while (stem.length > 1 && encoder.encode(stem.join('') + ext).length > maxBytes) stem.pop();
    return stem.join('') + ext;
  }

  function startPacket(name, size) {
    if (!Number.isInteger(size) || size < 0 || size > MAX_FILE_SIZE) {
      throw new RangeError('파일이 너무 큽니다 (최대 4GB).');
    }
    const nameBytes = encoder.encode(fitName(name, MAX_PACKET - HEADER));
    const packet = new Uint8Array(HEADER + nameBytes.length);
    packet[0] = OP_START;
    new DataView(packet.buffer).setUint32(1, size, true);
    packet.set(nameBytes, HEADER);
    return packet;
  }

  function dataPacket(offset, bytes) {
    const packet = new Uint8Array(HEADER + bytes.length);
    packet[0] = OP_DATA;
    new DataView(packet.buffer).setUint32(1, offset, true);
    packet.set(bytes, HEADER);
    return packet;
  }

  /** 파일 내용을 DATA 패킷들로 나눈다. */
  function* dataPackets(bytes) {
    for (let offset = 0; offset < bytes.length; offset += CHUNK) {
      yield dataPacket(offset, bytes.subarray(offset, Math.min(offset + CHUNK, bytes.length)));
    }
  }

  const endPacket = () => new Uint8Array([OP_END]);
  const cancelPacket = () => new Uint8Array([OP_CANCEL]);

  /** 상태 알림을 해석한다. */
  function parseStatus(dataView) {
    if (dataView.byteLength === 0) return { type: 'unknown' };
    const code = dataView.getUint8(0);
    if (code === STATUS_DONE) return { type: 'done' };
    if (code === STATUS_ERROR) {
      const rest = new Uint8Array(dataView.buffer, dataView.byteOffset + 1, dataView.byteLength - 1);
      return { type: 'error', message: new TextDecoder().decode(rest) };
    }
    return { type: 'unknown' };
  }

  const api = {
    SERVICE_UUID, WRITE_UUID, STATUS_UUID, OP_START, OP_DATA, OP_END, OP_CANCEL,
    STATUS_DONE, STATUS_ERROR, MAX_PACKET, CHUNK,
    fitName, startPacket, dataPacket, dataPackets, endPacket, cancelPacket, parseStatus,
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.BtProto = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
