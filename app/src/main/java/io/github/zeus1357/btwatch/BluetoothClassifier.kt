package io.github.zeus1357.btwatch

/** 새로 생긴 파일이 블루투스로 수신된 것인지 판별한다. */
object BluetoothClassifier {

    /**
     * @param ownerPackage MediaStore에 파일을 등록한 앱의 패키지 이름 (모르면 null)
     * @param relativePath 저장소 기준 상대 경로, 예: "Download/Bluetooth/"
     * @param absolutePath 전체 경로, 예: "/storage/emulated/0/Bluetooth/a.jpg"
     */
    fun isBluetoothFile(ownerPackage: String?, relativePath: String?, absolutePath: String?): Boolean {
        // 1) 시스템 블루투스 앱이 저장한 파일 (삼성처럼 Download 폴더에 바로 저장하는 기기도 잡힌다)
        if (ownerPackage != null && ownerPackage.contains("bluetooth", ignoreCase = true)) return true
        // 2) 경로 중간에 "Bluetooth" 폴더가 있는 파일
        return hasBluetoothDir(relativePath, lastIsFile = false) ||
            hasBluetoothDir(absolutePath, lastIsFile = true)
    }

    private fun hasBluetoothDir(path: String?, lastIsFile: Boolean): Boolean {
        if (path.isNullOrEmpty()) return false
        val parts = path.split('/').filter { it.isNotEmpty() }
        val dirs = if (lastIsFile) parts.dropLast(1) else parts
        return dirs.any { it.equals("bluetooth", ignoreCase = true) }
    }
}
