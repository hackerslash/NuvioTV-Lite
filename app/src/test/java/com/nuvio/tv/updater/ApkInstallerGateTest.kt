package com.nuvio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Test

// TG-START: updater observability, pre-install gate (re-apply on upstream merge)
class ApkInstallerGateTest {

    @Test
    fun `matching archive digests launch`() {
        assertEquals(
            ApkInstaller.LaunchGate.LAUNCH,
            ApkInstaller.gate(setOf("aa"), setOf("aa"))
        )
    }

    @Test
    fun `subset of installed digests launches`() {
        assertEquals(
            ApkInstaller.LaunchGate.LAUNCH,
            ApkInstaller.gate(setOf("aa", "bb"), setOf("bb"))
        )
    }

    @Test
    fun `disjoint archive digests are a genuine mismatch`() {
        assertEquals(
            ApkInstaller.LaunchGate.SIGNATURE_MISMATCH,
            ApkInstaller.gate(setOf("aa"), setOf("zz"))
        )
    }

    @Test
    fun `empty archive digests are unverifiable, not a mismatch`() {
        // Platforms where getPackageArchiveInfo yields no certificates even
        // for a well-formed APK (seen on some Android TV builds): the system
        // installer gets the final verdict instead of crying wolf.
        assertEquals(
            ApkInstaller.LaunchGate.UNVERIFIABLE,
            ApkInstaller.gate(setOf("aa"), emptySet())
        )
    }

    @Test
    fun `empty on both sides is unverifiable`() {
        assertEquals(
            ApkInstaller.LaunchGate.UNVERIFIABLE,
            ApkInstaller.gate(emptySet(), emptySet())
        )
    }
}
// TG-END
