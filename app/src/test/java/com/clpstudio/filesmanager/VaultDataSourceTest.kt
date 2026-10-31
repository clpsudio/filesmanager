package com.clpstudio.filesmanager

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.clpstudio.filesmanager.vault.BytesSource
import com.clpstudio.filesmanager.vault.VaultDataSource
import com.clpstudio.filesmanager.vault.VaultEncryptor
import com.clpstudio.filesmanager.vault.VaultReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

/** The player's data source decrypts any range of a vault file (video seeking), never touching disk. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VaultDataSourceTest {
    private val key = ByteArray(32).also { Random(1).nextBytes(it) }
    private val plain = ByteArray(300_000).also { Random(2).nextBytes(it) }
    private val enc = VaultEncryptor.encryptBytes(plain, key, 8192)

    private fun readRange(pos: Long, len: Long): ByteArray {
        val ds = VaultDataSource { VaultReader(BytesSource(enc), key) }
        val n = ds.open(DataSpec.Builder().setUri(Uri.parse("vault://item/x")).setPosition(pos).setLength(len).build())
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(7000)
        while (true) {
            val r = ds.read(buf, 0, buf.size)
            if (r == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, r)
        }
        ds.close()
        assertEquals(n, out.size().toLong())
        return out.toByteArray()
    }

    @Test fun wholeFile() = assertArrayEquals(plain, readRange(0, C.LENGTH_UNSET.toLong()))

    @Test fun seekRanges() {
        assertArrayEquals(plain.copyOfRange(123_456, plain.size), readRange(123_456, C.LENGTH_UNSET.toLong()))
        assertArrayEquals(plain.copyOfRange(8190, 8190 + 20_000), readRange(8190, 20_000))
        assertEquals(0, readRange(plain.size.toLong(), C.LENGTH_UNSET.toLong()).size)
    }
}
