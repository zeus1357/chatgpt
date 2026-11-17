import Foundation

/// 보내는 쪽(sender/index.html)과 맞춰야 하는 전송 규약.
///
/// 쓰기 특성으로 들어오는 패킷:
///   0x01 START  : [1][파일 크기 uint32 LE][파일 이름 UTF-8]
///   0x02 DATA   : [2][파일 내 위치 uint32 LE][내용]
///   0x03 END    : [3]
///   0x04 CANCEL : [4]
/// 상태 특성으로 나가는 알림:
///   0x11 완료, 0x12 실패 + [사유 UTF-8]
enum TransferProtocol {
    static let serviceUUID = "7B1D0001-5C3A-4F6E-9B2A-3E8F4C1D2A10"
    static let writeUUID = "7B1D0002-5C3A-4F6E-9B2A-3E8F4C1D2A10"
    static let statusUUID = "7B1D0003-5C3A-4F6E-9B2A-3E8F4C1D2A10"

    static let opStart: UInt8 = 0x01
    static let opData: UInt8 = 0x02
    static let opEnd: UInt8 = 0x03
    static let opCancel: UInt8 = 0x04

    static let statusDone: UInt8 = 0x11
    static let statusError: UInt8 = 0x12

    static func doneStatus() -> Data { Data([statusDone]) }

    static func errorStatus(_ message: String) -> Data {
        Data([statusError]) + Data(message.utf8.prefix(100))
    }
}

enum TransferEvent: Equatable {
    case none
    case started(name: String, size: Int)
    case progress(received: Int, size: Int)
    case finished(URL)
    case failed(String)
}

/// 패킷을 받아 파일 하나를 조립한다. 블루투스와 무관한 순수 로직이라 단위 테스트가 가능하다.
final class TransferSession {
    private let directory: URL
    private let fileManager = FileManager.default

    private var fileHandle: FileHandle?
    private var tempURL: URL?
    private var name = ""
    private var expected = 0
    private var received = 0

    var isActive: Bool { fileHandle != nil }

    init(directory: URL) {
        self.directory = directory
    }

    func handle(packet: Data) -> TransferEvent {
        let bytes = [UInt8](packet)
        guard let op = bytes.first else { return .none }
        switch op {
        case TransferProtocol.opStart: return start(bytes)
        case TransferProtocol.opData: return data(bytes)
        case TransferProtocol.opEnd: return end()
        case TransferProtocol.opCancel:
            guard isActive else { return .none }
            return fail("보내는 쪽에서 전송을 취소했습니다.")
        default: return .none
        }
    }

    /// 연결이 끊겼을 때 호출한다. 받던 파일이 있으면 버린다.
    func cancel() -> TransferEvent {
        guard isActive else { return .none }
        return fail("전송 도중 연결이 끊겼습니다.")
    }

    private func start(_ bytes: [UInt8]) -> TransferEvent {
        discard()
        guard bytes.count >= 5 else { return .failed("잘못된 시작 패킷입니다.") }
        expected = Int(Self.uint32(bytes, at: 1))
        name = Self.safeName(String(decoding: bytes[5...], as: UTF8.self))
        received = 0
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            let url = directory.appendingPathComponent(".incoming-\(UUID().uuidString)")
            guard fileManager.createFile(atPath: url.path, contents: nil) else {
                return .failed("파일을 만들 수 없습니다.")
            }
            fileHandle = try FileHandle(forWritingTo: url)
            tempURL = url
        } catch {
            discard()
            return .failed("파일을 만들 수 없습니다: \(error.localizedDescription)")
        }
        return .started(name: name, size: expected)
    }

    private func data(_ bytes: [UInt8]) -> TransferEvent {
        guard let fileHandle else { return .none }
        guard bytes.count > 5 else { return .none }
        let offset = Int(Self.uint32(bytes, at: 1))
        // 응답 없는 쓰기는 유실될 수 있으므로 위치가 어긋나면 바로 실패 처리한다.
        guard offset == received else {
            return fail("데이터가 유실되었습니다 (받은 위치 \(received), 온 위치 \(offset)).")
        }
        guard received + bytes.count - 5 <= expected else {
            return fail("예고된 크기보다 많은 데이터가 왔습니다.")
        }
        do {
            try fileHandle.write(contentsOf: Data(bytes[5...]))
        } catch {
            return fail("저장 실패: \(error.localizedDescription)")
        }
        received += bytes.count - 5
        return .progress(received: received, size: expected)
    }

    private func end() -> TransferEvent {
        guard let fileHandle, let tempURL else { return .none }
        guard received == expected else {
            return fail("파일이 덜 왔습니다 (\(received)/\(expected) 바이트).")
        }
        do {
            try fileHandle.close()
            self.fileHandle = nil
            let destination = uniqueDestination(for: name)
            try fileManager.moveItem(at: tempURL, to: destination)
            self.tempURL = nil
            return .finished(destination)
        } catch {
            return fail("저장 실패: \(error.localizedDescription)")
        }
    }

    private func fail(_ message: String) -> TransferEvent {
        discard()
        return .failed(message)
    }

    private func discard() {
        try? fileHandle?.close()
        fileHandle = nil
        if let tempURL { try? fileManager.removeItem(at: tempURL) }
        tempURL = nil
    }

    private func uniqueDestination(for name: String) -> URL {
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var candidate = directory.appendingPathComponent(name)
        var n = 2
        while fileManager.fileExists(atPath: candidate.path) {
            let next = ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)"
            candidate = directory.appendingPathComponent(next)
            n += 1
        }
        return candidate
    }

    /// 경로 구분자를 없애고 숨김 파일이 되지 않게 한다.
    static func safeName(_ raw: String) -> String {
        var name = raw.replacingOccurrences(of: "\\", with: "/")
        name = (name as NSString).lastPathComponent
        name = name.replacingOccurrences(of: "\0", with: "")
        while name.hasPrefix(".") { name.removeFirst() }
        if name.isEmpty || name == "/" { name = "받은 파일" }
        return name
    }

    private static func uint32(_ bytes: [UInt8], at index: Int) -> UInt32 {
        UInt32(bytes[index]) | UInt32(bytes[index + 1]) << 8 |
            UInt32(bytes[index + 2]) << 16 | UInt32(bytes[index + 3]) << 24
    }
}
