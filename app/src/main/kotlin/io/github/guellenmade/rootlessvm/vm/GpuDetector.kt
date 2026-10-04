package io.github.guellenmade.rootlessvm.vm

data class GpuInfo(
    val vendor: Vendor,
    val rendererString: String,
    val hostGpuUsedByContainer: Boolean = false,
) {
    enum class Vendor { ADRENO, MALI, POWERVR, OTHER, NONE }
}

class GpuDetector {
    fun detect(): GpuInfo {
        val glEs = readSystemProperty("ro.hardware.egl") ?: ""
        val chip = readSystemProperty("ro.board.platform") ?: ""
        val renderer = readSystemProperty("ro.hardware.chipname")
            ?: readSystemProperty("ro.hardware") ?: ""
        val vendor = when {
            glEs.contains("adreno", true) || rendererStringHints(renderer, "adreno", "qcom", "sm8", "msm") -> GpuInfo.Vendor.ADRENO
            glEs.contains("mali", true) || rendererStringHints(renderer, "mali", "exynos", "kirin", "mt6", "tensor") -> GpuInfo.Vendor.MALI
            glEs.contains("powervr", true) || rendererStringHints(renderer, "powervr", "rogue", "imgt") -> GpuInfo.Vendor.POWERVR
            else -> GpuInfo.Vendor.OTHER
        }
        return GpuInfo(
            vendor = vendor,
            rendererString = listOf(glEs, chip, renderer).filter { it.isNotBlank() }.joinToString(" / "),
        )
    }

    private fun rendererStringHints(s: String, vararg hints: String): Boolean =
        hints.any { s.contains(it, true) }

    private fun readSystemProperty(key: String): String? =
        try {
            val method = Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
            method.invoke(null, key) as? String
        } catch (_: Exception) {
            null
        }
}
