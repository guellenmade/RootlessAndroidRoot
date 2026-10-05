package io.github.guellenmade.rootlessvm.vm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Display bridge (ADR-006): polls the container's screencap, decodes the PNG,
 * and exposes the latest frame + measured fps for honest performance
 * reporting. Input goes back via the container's `input` command.
 */
class DisplaySession(
    private val session: ContainerSession,
    private val paths: ContainerPaths,
) {
    data class Frame(val bitmap: Bitmap?, val fps: Double, val lastError: String?)

    private val _frame = MutableStateFlow(Frame(null, 0.0, null))
    val frame: StateFlow<Frame> = _frame

    private var job: Job? = null

    fun start(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        stop()
        job = scope.launch {
            var last = System.nanoTime()
            var avgDelta = 0.0
            while (isActive) {
                val result = session.screencapRaw()
                result.onSuccess { bytes ->
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val now = System.nanoTime()
                    val delta = (now - last) / 1e9
                    last = now
                    avgDelta = if (avgDelta == 0.0) delta else (avgDelta * 0.8 + delta * 0.2)
                    _frame.value = Frame(bmp, if (avgDelta > 0) 1.0 / avgDelta else 0.0, null)
                }.onFailure {
                    _frame.value = Frame(null, 0.0, it.message)
                }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun tap(x: Int, y: Int) {
        session.injectInput("tap $x $y")
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int) {
        session.injectInput("swipe $x1 $y1 $x2 $y2 300")
    }

    suspend fun back() {
        session.injectInput("keyevent 4")
    }

    suspend fun home() {
        session.injectInput("keyevent 3")
    }

    companion object {
        const val POLL_MS = 250L
    }
}
