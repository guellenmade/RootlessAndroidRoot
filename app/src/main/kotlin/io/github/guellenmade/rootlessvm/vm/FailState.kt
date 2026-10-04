package io.github.guellenmade.rootlessvm.vm

sealed class FailState(val reason: String, val userMessage: String) {
    class UnsupportedAbi(abi: String) : FailState(
        reason = "unsupported-abi:$abi",
        userMessage = "This device architecture ($abi) is not supported. RootlessVM needs arm64, arm, or x86_64.",
    )

    class InsufficientStorage(requiredBytes: Long, availableBytes: Long) : FailState(
        reason = "insufficient-storage:${requiredBytes}/${availableBytes}",
        userMessage = "Not enough free storage: about ${requiredBytes / (1024 * 1024)} MB required, ${availableBytes / (1024 * 1024)} MB available.",
    )

    class ChecksumMismatch(expected: String, actual: String) : FailState(
        reason = "checksum-mismatch:$expected/$actual",
        userMessage = "Rootfs image failed checksum verification. The download was corrupted or tampered with; it has been deleted. Please retry.",
    )

    class RootfsVersionIncompatible(rootfsApi: Int, vectorApi: Int) : FailState(
        reason = "rootfs-version-incompatible:$rootfsApi/$vectorApi",
        userMessage = "The rootfs Android version (API $rootfsApi) does not match the built Xposed/Vector artifacts (API $vectorApi). The VM was not started.",
    )

    class ProotBootFailure(exitCode: Int) : FailState(
        reason = "proot-boot-failure:$exitCode",
        userMessage = "The container failed to start (proot exited with code $exitCode). The VM was stopped cleanly.",
    )

    class FirewallStartFailure(detail: String) : FailState(
        reason = "firewall-start-failure:$detail",
        userMessage = "The network firewall could not be started ($detail). The VM was not started because firewall mode is on.",
    )
}
