package io.github.guellenmade.rootlessvm

import io.github.guellenmade.rootlessvm.data.RootfsManifest
import io.github.guellenmade.rootlessvm.data.BuiltArtifacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RootfsManifestTest {
    @Test
    fun `version cross check detects mismatch`() {
        val manifest = RootfsManifest("a13", "13", 33, "https://example.invalid/r.tar.xz", "x", 1)
        val artifacts = BuiltArtifacts("ddeed8c", 34)
        assertNotEquals(manifest.apiLevel, artifacts.targetApi)
    }

    @Test
    fun `version cross check accepts match`() {
        val manifest = RootfsManifest("a13", "13", 33, "https://example.invalid/r.tar.xz", "x", 1)
        val artifacts = BuiltArtifacts("ddeed8c", 33)
        assertEquals(manifest.apiLevel, artifacts.targetApi)
    }
}
