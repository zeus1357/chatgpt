package io.github.zeus1357.btwatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothClassifierTest {
    private fun check(owner: String?, rel: String?, abs: String?) =
        BluetoothClassifier.isBluetoothFile(owner, rel, abs)

    @Test
    fun ownerPackageOfBluetoothAppMatches() {
        assertTrue(check("com.android.bluetooth", "Download/", "/storage/emulated/0/Download/a.pdf"))
        assertTrue(check("com.google.android.bluetooth", null, null))
    }

    @Test
    fun bluetoothFolderMatches() {
        assertTrue(check(null, "Download/Bluetooth/", "/storage/emulated/0/Download/Bluetooth/a.jpg"))
        assertTrue(check(null, "bluetooth/", null))
        assertTrue(check(null, null, "/storage/emulated/0/Bluetooth/a.jpg"))
    }

    @Test
    fun otherFilesDoNotMatch() {
        assertFalse(check("com.android.chrome", "Download/", "/storage/emulated/0/Download/a.pdf"))
        assertFalse(check(null, "DCIM/Camera/", "/storage/emulated/0/DCIM/Camera/a.jpg"))
        assertFalse(check(null, null, null))
    }

    @Test
    fun fileMerelyNamedBluetoothDoesNotMatch() {
        assertFalse(check(null, "Download/", "/storage/emulated/0/Download/bluetooth"))
        assertFalse(check(null, "Download/", "/storage/emulated/0/Download/Bluetooth-manual.pdf"))
    }
}
