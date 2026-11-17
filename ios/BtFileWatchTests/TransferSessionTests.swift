import XCTest
@testable import BtFileWatch

final class TransferSessionTests: XCTestCase {
    private var directory: URL!
    private var session: TransferSession!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        session = TransferSession(directory: directory)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func le(_ value: Int) -> [UInt8] {
        [UInt8(value & 0xff), UInt8((value >> 8) & 0xff), UInt8((value >> 16) & 0xff), UInt8((value >> 24) & 0xff)]
    }

    private func start(_ name: String, size: Int) -> Data {
        Data([TransferProtocol.opStart] + le(size) + Array(name.utf8))
    }

    private func chunk(_ offset: Int, _ bytes: [UInt8]) -> Data {
        Data([TransferProtocol.opData] + le(offset) + bytes)
    }

    private var end: Data { Data([TransferProtocol.opEnd]) }

    private func visibleFiles() -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []).filter { !$0.hasPrefix(".") }
    }

    func testReceivesFileInChunks() throws {
        let content = (0..<1000).map { UInt8($0 % 251) }
        XCTAssertEqual(session.handle(packet: start("사진.jpg", size: 1000)), .started(name: "사진.jpg", size: 1000))
        var offset = 0
        while offset < content.count {
            let part = Array(content[offset..<min(offset + 175, content.count)])
            XCTAssertEqual(
                session.handle(packet: chunk(offset, part)),
                .progress(received: offset + part.count, size: 1000)
            )
            offset += part.count
        }
        guard case let .finished(url) = session.handle(packet: end) else {
            return XCTFail("expected finished")
        }
        XCTAssertEqual(url.lastPathComponent, "사진.jpg")
        XCTAssertEqual([UInt8](try Data(contentsOf: url)), content)
        XCTAssertFalse(session.isActive)
    }

    func testLostChunkFailsAndLeavesNoFile() {
        _ = session.handle(packet: start("a.bin", size: 20))
        _ = session.handle(packet: chunk(0, Array(repeating: 1, count: 10)))
        guard case .failed = session.handle(packet: chunk(15, Array(repeating: 1, count: 5))) else {
            return XCTFail("expected failure")
        }
        XCTAssertFalse(session.isActive)
        XCTAssertEqual(session.handle(packet: end), .none)
        XCTAssertEqual(visibleFiles(), [])
    }

    func testShortFileFailsAtEnd() {
        _ = session.handle(packet: start("a.bin", size: 20))
        _ = session.handle(packet: chunk(0, Array(repeating: 1, count: 10)))
        guard case .failed = session.handle(packet: end) else { return XCTFail("expected failure") }
        XCTAssertEqual(visibleFiles(), [])
    }

    func testTooMuchDataFails() {
        _ = session.handle(packet: start("a.bin", size: 4))
        guard case .failed = session.handle(packet: chunk(0, Array(repeating: 1, count: 5))) else {
            return XCTFail("expected failure")
        }
    }

    func testDuplicateNamesGetNumbered() {
        for expected in ["a.txt", "a (2).txt", "a (3).txt"] {
            _ = session.handle(packet: start("a.txt", size: 1))
            _ = session.handle(packet: chunk(0, [7]))
            guard case let .finished(url) = session.handle(packet: end) else { return XCTFail("expected finished") }
            XCTAssertEqual(url.lastPathComponent, expected)
        }
    }

    func testEmptyFile() {
        _ = session.handle(packet: start("empty.txt", size: 0))
        guard case let .finished(url) = session.handle(packet: end) else { return XCTFail("expected finished") }
        XCTAssertEqual(try? Data(contentsOf: url).count, 0)
    }

    func testCancelWhenDisconnected() {
        XCTAssertEqual(session.cancel(), .none)
        _ = session.handle(packet: start("a.bin", size: 20))
        guard case .failed = session.cancel() else { return XCTFail("expected failure") }
        XCTAssertEqual(visibleFiles(), [])
    }

    func testUnsafeNamesAreSanitized() {
        XCTAssertEqual(TransferSession.safeName("../../etc/passwd"), "passwd")
        XCTAssertEqual(TransferSession.safeName("C:\\Users\\me\\a.txt"), "a.txt")
        XCTAssertEqual(TransferSession.safeName(".hidden"), "hidden")
        XCTAssertEqual(TransferSession.safeName(""), "받은 파일")
        XCTAssertEqual(TransferSession.safeName(".."), "받은 파일")
    }
}
