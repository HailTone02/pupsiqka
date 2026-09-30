package com.pupsikcall.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.crypto.OlmMachine
import java.nio.file.Files

class MatrixOlmCompatibilityTest {
    @Test
    fun officialOlmMachineInitializesThroughKotlinNativeBinding() {
        val nativeLibrary = System.getenv("MATRIX_OLM_HOST_LIBRARY")
        assumeTrue("Set MATRIX_OLM_HOST_LIBRARY to run the native Olm smoke test", !nativeLibrary.isNullOrBlank())

        val overrideProperties = listOf(
            "uniffi.component.matrix_sdk_common.libraryOverride",
            "uniffi.component.matrix_sdk_crypto.libraryOverride",
            "uniffi.component.matrix_sdk_crypto_ffi.libraryOverride",
        )
        val previousOverrides = overrideProperties.associateWith(System::getProperty)
        val storeDirectory = Files.createTempDirectory("hailtone-matrix-olm-test-")
        overrideProperties.forEach { System.setProperty(it, nativeLibrary!!) }
        try {
            OlmMachine("@olm-spike:example.test", "HAILTONE_SPIKE", storeDirectory.resolve("crypto.db").toString(), "test-only-passphrase").use { machine ->
                assertEquals("@olm-spike:example.test", machine.userId())
                assertEquals("HAILTONE_SPIKE", machine.deviceId())
                val identityKeys = machine.identityKeys()
                assertFalse(identityKeys["curve25519"].isNullOrBlank())
                assertFalse(identityKeys["ed25519"].isNullOrBlank())
            }
        } finally {
            previousOverrides.forEach { (property, previousValue) ->
                if (previousValue == null) System.clearProperty(property)
                else System.setProperty(property, previousValue)
            }
            storeDirectory.toFile().deleteRecursively()
        }
    }
}