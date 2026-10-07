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
        userMessage = "A downloaded component failed checksum verification " +
            "(expected $expected, got $actual). The download was corrupted or the published " +
            "artifact changed; it has been deleted. Please retry or report this message.",
    )

    class RootfsVersionIncompatible(rootfsApi: Int, vectorApi: Int) : FailState(
        reason = "rootfs-version-incompatible:$rootfsApi/$vectorApi",
        userMessage = "The rootfs Android version (API $rootfsApi) does not match the built Xposed/Vector artifacts (API $vectorApi). The VM was not started.",
    )

    class ProotBootFailure(exitCode: Int, logTail: String = "") : FailState(
        reason = "proot-boot-failure:$exitCode",
        userMessage = "The container failed to start (proot exited with code $exitCode). " +
            "The VM was stopped cleanly." +
            if (logTail.isNotBlank()) "\n\n--- proot log (last lines) ---\n$logTail" else "",
    )

    class RuntimeArtifactUnavailable(val detail: String, component: String) : FailState(
        reason = "runtime-artifact-unavailable:$component:$detail",
        userMessage = "The $component runtime component could not be downloaded (details: $detail). " +
            "The required artifacts have not been published yet for this build — see the project releases. " +
            "Nothing was half-installed; you can retry once artifacts are available.",
    )

    class UnexpectedError(detail: String) : FailState(
        reason = "unexpected-error",
        userMessage = "An unexpected error occurred: $detail\n\nPlease report this message so the cause can be fixed.",
    )

    class FirewallStartFailure(detail: String) : FailState(
        reason = "firewall-start-failure:$detail",
        userMessage = "The network firewall could not be started ($detail). The VM was not started because firewall mode is on.",
    )
}
