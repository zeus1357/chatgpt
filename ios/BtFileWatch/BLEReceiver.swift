import CoreBluetooth
import Foundation
import UserNotifications

struct ReceivedFile: Identifiable, Equatable {
    let url: URL
    let size: Int
    let date: Date
    var id: URL { url }
    var name: String { url.lastPathComponent }
}

struct ActiveTransfer: Equatable {
    var name: String
    var received: Int
    var size: Int
}

/// 아이폰을 BLE 주변기기(peripheral)로 광고하고, 다른 기기가 써 주는 파일을 받는다.
final class BLEReceiver: NSObject, ObservableObject, CBPeripheralManagerDelegate {
    @Published var statusText = "블루투스 준비 중…"
    @Published var isReady = false
    @Published var active: ActiveTransfer?
    @Published var lastError: String?
    @Published var files: [ReceivedFile] = []
    /// 방금 받은 파일. 화면이 이 값을 보고 공유 창을 띄운다.
    @Published var justReceived: ReceivedFile?

    static let directory: URL = FileManager.default
        .urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("Received", isDirectory: true)

    private var manager: CBPeripheralManager!
    private var statusCharacteristic: CBMutableCharacteristic?
    private var pendingStatus: Data?
    private let session = TransferSession(directory: BLEReceiver.directory)
    private var lastProgressUpdate = Date.distantPast

    override init() {
        super.init()
        reloadFiles()
        manager = CBPeripheralManager(delegate: self, queue: nil)
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    // MARK: - 파일 목록

    func reloadFiles() {
        let fm = FileManager.default
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        let urls = (try? fm.contentsOfDirectory(
            at: Self.directory, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles]
        )) ?? []
        files = urls.map { url in
            let values = try? url.resourceValues(forKeys: Set(keys))
            return ReceivedFile(
                url: url,
                size: values?.fileSize ?? 0,
                date: values?.contentModificationDate ?? .distantPast
            )
        }
        .sorted { $0.date > $1.date }
    }

    func delete(_ file: ReceivedFile) {
        try? FileManager.default.removeItem(at: file.url)
        reloadFiles()
    }

    // MARK: - CBPeripheralManagerDelegate

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        isReady = false
        switch peripheral.state {
        case .poweredOn:
            statusText = "수신 준비 중…"
            publishService()
        case .poweredOff:
            statusText = "블루투스가 꺼져 있습니다. 켜 주세요."
        case .unauthorized:
            statusText = "블루투스 권한이 없습니다. 설정에서 이 앱의 블루투스를 허용해 주세요."
        case .unsupported:
            statusText = "이 기기에서는 블루투스 LE를 쓸 수 없습니다."
        default:
            statusText = "블루투스 준비 중…"
        }
    }

    private func publishService() {
        let write = CBMutableCharacteristic(
            type: CBUUID(string: TransferProtocol.writeUUID),
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let status = CBMutableCharacteristic(
            type: CBUUID(string: TransferProtocol.statusUUID),
            properties: [.notify],
            value: nil,
            permissions: [.readable]
        )
        let service = CBMutableService(type: CBUUID(string: TransferProtocol.serviceUUID), primary: true)
        service.characteristics = [write, status]
        statusCharacteristic = status
        manager.removeAllServices()
        manager.add(service)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error {
            statusText = "서비스 등록 실패: \(error.localizedDescription)"
            return
        }
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [CBUUID(string: TransferProtocol.serviceUUID)],
            CBAdvertisementDataLocalNameKey: "BtFile",
        ])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error {
            statusText = "광고 시작 실패: \(error.localizedDescription)"
        } else {
            isReady = true
            statusText = "수신 대기 중입니다. 보내는 기기에서 이 아이폰(BtFile)을 선택하세요."
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        // 긴 쓰기는 offset이 이어지는 여러 요청으로 쪼개져 오므로 다시 붙인다.
        var packets: [Data] = []
        for request in requests {
            guard request.characteristic.uuid == CBUUID(string: TransferProtocol.writeUUID),
                  let value = request.value else { continue }
            if request.offset > 0, !packets.isEmpty {
                packets[packets.count - 1].append(value)
            } else {
                packets.append(value)
            }
        }
        for packet in packets {
            apply(session.handle(packet: packet))
        }
        if let first = requests.first {
            peripheral.respond(to: first, withResult: .success)
        }
    }

    func peripheralManager(
        _ peripheral: CBPeripheralManager, central: CBCentral,
        didUnsubscribeFrom characteristic: CBCharacteristic
    ) {
        apply(session.cancel())
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        if let data = pendingStatus {
            pendingStatus = nil
            sendStatus(data)
        }
    }

    // MARK: - 전송 이벤트 처리

    private func apply(_ event: TransferEvent) {
        switch event {
        case .none:
            break
        case let .started(name, size):
            lastError = nil
            active = ActiveTransfer(name: name, received: 0, size: size)
        case let .progress(received, size):
            // 화면 갱신은 초당 몇 번이면 충분하다.
            let now = Date()
            if now.timeIntervalSince(lastProgressUpdate) > 0.2 || received == size {
                lastProgressUpdate = now
                active?.received = received
            }
        case let .finished(url):
            active = nil
            reloadFiles()
            sendStatus(TransferProtocol.doneStatus())
            let file = files.first { $0.url == url }
            justReceived = file
            notify(title: "블루투스 파일 수신 감지", body: url.lastPathComponent)
        case let .failed(message):
            active = nil
            lastError = message
            sendStatus(TransferProtocol.errorStatus(message))
        }
    }

    private func sendStatus(_ data: Data) {
        guard let statusCharacteristic else { return }
        if !manager.updateValue(data, for: statusCharacteristic, onSubscribedCentrals: nil) {
            pendingStatus = data
        }
    }

    private func notify(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}
