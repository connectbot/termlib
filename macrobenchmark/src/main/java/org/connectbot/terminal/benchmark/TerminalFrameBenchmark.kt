package org.connectbot.terminal.benchmark

import android.content.Intent
import androidx.benchmark.ExperimentalBenchmarkConfigApi
import androidx.benchmark.ExperimentalConfig
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.perfetto.ExperimentalPerfettoCaptureApi
import androidx.benchmark.perfetto.PerfettoConfig
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalBenchmarkConfigApi::class, ExperimentalPerfettoCaptureApi::class)
class TerminalFrameBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun terminalFrames() {
        val args = InstrumentationRegistry.getArguments()
        val workload = args.getString("workload") ?: "text"
        val duration = args.getString("durationMs")?.toLong() ?: 20_000L
        benchmark.measureRepeated(
            packageName = "org.connectbot.terminal.testapp",
            metrics = listOf(FrameTimingMetric()),
            experimentalConfig = ExperimentalConfig(perfettoConfig = frameTraceConfig()),
            compilationMode = CompilationMode.Full(),
            iterations = args.getString("iterations")?.toInt() ?: 5,
            setupBlock = {
                killProcess()
                pressHome()
                startActivityAndWait(
                    Intent().apply {
                        setClassName("org.connectbot.terminal.testapp", "org.connectbot.terminal.testapp.FrameBenchmarkActivity")
                        putExtra("workload", workload)
                        putExtra("durationMs", duration)
                    },
                )
                Thread.sleep(3_000)
            },
            measureBlock = { Thread.sleep(duration) },
        )
    }

    // Keep both revisions on exactly the same bounded frame-focused trace.
    // Broad system data sources can stall trace startup on some OS builds.
    private fun frameTraceConfig() = PerfettoConfig.Text(
        """
        buffers { size_kb: 32768 fill_policy: RING_BUFFER }
        buffers { size_kb: 4096 fill_policy: RING_BUFFER }
        data_sources { config {
          name: "linux.ftrace"
          target_buffer: 0
          ftrace_config {
            ftrace_events: "sched/sched_switch"
            ftrace_events: "sched/sched_waking"
            ftrace_events: "task/task_newtask"
            ftrace_events: "task/task_rename"
            atrace_categories: "gfx"
            atrace_categories: "view"
            atrace_categories: "input"
            atrace_categories: "dalvik"
            atrace_apps: "org.connectbot.terminal.testapp"
          }
        } }
        data_sources { config {
          name: "linux.process_stats"
          target_buffer: 1
          process_stats_config { scan_all_processes_on_start: true }
        } }
        data_sources { config {
          name: "android.surfaceflinger.frametimeline"
          target_buffer: 0
        } }
        write_into_file: true
        file_write_period_ms: 1000
        flush_period_ms: 2500
        data_source_stop_timeout_ms: 2500
        """.trimIndent(),
    )
}
