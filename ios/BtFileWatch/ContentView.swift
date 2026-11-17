import SwiftUI
import UIKit

struct ContentView: View {
    @EnvironmentObject private var receiver: BLEReceiver

    var body: some View {
        NavigationStack {
            List {
                Section("상태") {
                    Label(receiver.statusText, systemImage: receiver.isReady ? "dot.radiowaves.left.and.right" : "exclamationmark.triangle")
                    if let active = receiver.active {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("받는 중: \(active.name)")
                            ProgressView(value: Double(active.received), total: Double(max(active.size, 1)))
                            Text("\(format(active.received)) / \(format(active.size))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    if let error = receiver.lastError {
                        Label(error, systemImage: "xmark.octagon")
                            .foregroundStyle(.red)
                    }
                }

                Section("받은 파일") {
                    if receiver.files.isEmpty {
                        Text("아직 받은 파일이 없습니다.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(receiver.files) { file in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(file.name)
                                Text("\(format(file.size)) · \(file.date.formatted(date: .abbreviated, time: .shortened))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            ShareLink(item: file.url) {
                                Label("Claude로 보내기", systemImage: "square.and.arrow.up")
                                    .labelStyle(.iconOnly)
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                    .onDelete { offsets in
                        offsets.map { receiver.files[$0] }.forEach(receiver.delete)
                    }
                }
            }
            .navigationTitle("블루투스 파일 감지")
            // 파일을 받으면 바로 공유 창을 띄운다. 여기서 Claude를 고르면 대화창에 첨부된다.
            .sheet(item: $receiver.justReceived) { file in
                ActivityView(url: file.url)
            }
        }
    }

    private func format(_ bytes: Int) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }
}

/// 시스템 공유 창. iOS는 특정 앱을 지정해 바로 넘길 수 없어 사용자가 Claude를 골라야 한다.
struct ActivityView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
