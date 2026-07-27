package org.connectbot.terminal.testapp

import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.os.Trace
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulatorFactory
import java.io.ByteArrayOutputStream
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/** Same public-API-only workload is built against both library revisions. */
class FrameBenchmarkActivity : ComponentActivity() {
    @Volatile private var running = true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val currentMode = display?.mode
        val mode = display?.supportedModes?.firstOrNull {
            kotlin.math.abs(it.refreshRate - 60f) < 0.1f &&
                it.physicalWidth == currentMode?.physicalWidth && it.physicalHeight == currentMode.physicalHeight
        }
        window.attributes = window.attributes.apply {
            preferredRefreshRate = 60f
            if (mode != null) preferredDisplayModeId = mode.modeId
        }
        val workload = intent.getStringExtra("workload") ?: "text"
        var outputBytes = 0L
        val terminal = TerminalEmulatorFactory.create(initialRows = 24, initialCols = 80, onKeyboardInput = {
            outputBytes += it.size
            Trace.setCounter("terminal.benchmark.outputBytes", outputBytes)
        })
        val dimensions = mutableStateOf(24 to 80)
        val raw = ByteArray(512 * 512 * 4) { i ->
            when (i % 4) {
                3 -> -1
                else -> (i / 4 xor (i / 2048)).toByte()
            }
        }
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        for (y in 0 until 512) for (x in 0 until 512) bitmap.setPixel(x, y, -0x1000000 or (x shl 16) or (y shl 8) or (x xor y))
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val payload = Base64.encodeToString(if (workload == "rgba") raw else png, Base64.NO_WRAP)
        val format = if (workload == "rgba") "f=32,s=512,v=512" else "f=100"
        val image = "\u001b[5;1H\u001b_Ga=T,$format,i=1,c=40,r=15,C=1;$payload\u001b\\".toByteArray()
        val frames = if (workload == "cacafire") {
            captureFrames()
        } else {
            List(1200) { tick ->
                ("\u001b[2;1H" + if (workload == "text") "\u001b[3${tick % 8}m${"abc日é ".repeat(150)}\u001b[0m" else "").toByteArray()
            }
        }
        setContent {
            val pulse = remember { mutableLongStateOf(0) }
            LaunchedEffect(Unit) { while (true) withFrameNanos { pulse.longValue = it } }
            Box(Modifier.fillMaxSize()) {
                Terminal(terminal, typeface = Typeface.MONOSPACE, initialFontSize = 10.sp, forcedSize = dimensions.value, keyboardEnabled = true, showSoftKeyboard = false)
                Canvas(Modifier.fillMaxSize()) {
                    val n = pulse.longValue
                    drawRect(if (n % 2L == 0L) Color.White else Color.Gray, Offset.Zero, Size(2f, 2f))
                }
            }
        }
        thread(name = "terminal-benchmark-reader") {
            val start = System.nanoTime()
            var tick = 0
            while (running) {
                val deadline = start + tick * 1_000_000_000L / 60
                val wait = deadline - System.nanoTime()
                if (wait > 0) LockSupport.parkNanos(wait)
                if (!running) break
                Trace.beginSection("terminal.benchmark.input")
                Trace.setCounter("terminal.benchmark.tick", tick.toLong())
                if (tick % 60 == 0) Trace.setCounter("terminal.benchmark.refreshMilliHz", ((display?.refreshRate ?: 0f) * 1000).toLong())
                if (workload != "idle") {
                    // Label the same input batch atomically. A separate marker write
                    // could be captured one snapshot later than the text it labels.
                    val marker = "\u001b7\u001b[H%08d\u001b8".format(tick).toByteArray()
                    terminal.writeInput(frames[tick % frames.size] + marker)
                }
                if (workload in listOf("png", "rgba", "images", "animation") && tick % 60 == 0) {
                    var offset = 0
                    while (offset < image.size) {
                        val size = minOf(4096, image.size - offset)
                        terminal.writeInput(image, offset, size)
                        offset += size
                    }
                }
                if (workload == "animation" && tick % 60 == 0) {
                    terminal.writeInput("\u001b_Ga=f,f=100,i=1,c=1,z=40;$payload\u001b\\\u001b_Ga=a,i=1,s=3,v=1\u001b\\".toByteArray())
                }
                if (workload == "input" && tick % 120 == 0) {
                    val landscape = tick % 240 == 0
                    runOnUiThread {
                        dimensions.value = if (landscape) 24 to 80 else 40 to 48
                        val editor = findEditor(window.decorView) ?: return@runOnUiThread
                        editor.requestFocus()
                        editor.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_A))
                        editor.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!.commitText("paste日".repeat(2000), 1)
                        editor.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_B))
                    }
                }
                Trace.endSection()
                tick++
                if (tick % 1200 == 0) Log.i("TerminalFrameBenchmark", "workload=$workload ticks=$tick refresh=${display?.refreshRate}")
            }
        }
    }

    private fun findEditor(view: android.view.View): android.view.View? {
        if (view.onCheckIsTextEditor()) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) findEditor(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun captureFrames(): List<ByteArray> {
        val data = assets.open("cacafire.bin").use { it.readBytes() }
        val reads = org.json.JSONObject(assets.open("cacafire.json").bufferedReader().use { it.readText() }).getJSONArray("reads")
        val frames = mutableListOf<ByteArray>()
        var start = 0
        var end = 0
        var tick = -1
        for (i in 0 until reads.length()) {
            val read = reads.getJSONArray(i)
            val next = (read.getDouble(0) * 60).toInt()
            if (next != tick && end > start) {
                frames.add(data.copyOfRange(start, end))
                start = end
            }
            tick = next
            end += read.getInt(1)
        }
        if (end > start) frames.add(data.copyOfRange(start, end))
        return frames
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
