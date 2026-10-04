package io.github.guellenmade.rootlessvm

import io.github.guellenmade.rootlessvm.vm.FailState
import org.junit.Assert.assertTrue
import org.junit.Test

class FailStateTest {
    @Test
    fun `unsupported abi carries reason`() {
        val f = FailState.UnsupportedAbi("riscv64")
        assertTrue(f.reason.startsWith("unsupported-abi:riscv64"))
        assertTrue(f.userMessage.contains("riscv64"))
    }

    @Test
    fun `checksum mismatch is clean abort`() {
        val f = FailState.ChecksumMismatch("aa", "bb")
        assertTrue(f.userMessage.contains("checksum"))
    }
}
