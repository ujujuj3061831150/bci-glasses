package com.bci.glasses

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

class ControlPrototypeActivity : Activity() {
    private lateinit var session: ControlSession
    private lateinit var scene: ControlSceneView
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var eventText: TextView
    private lateinit var confirm: Button
    private lateinit var start: Button
    private lateinit var flashSwitch: Switch
    private lateinit var attentionSwitch: Switch
    private lateinit var logFile: File
    private val logWriter = Executors.newSingleThreadExecutor()
    private val recentEvents = ArrayDeque<String>()
    private var lastUiRefresh = 0L
    private var lastSnapshotAt = 0L
    private var logFailure: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val directory = File(filesDir, "control_sessions").apply { mkdirs() }
        logFile = File(directory, "control_${System.currentTimeMillis()}.jsonl")
        session = ControlSession(onEvent = ::recordEvent)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 17, 27))
        }
        root.addView(label("BCI / 眼动驱动控制原型", 20f, Color.WHITE).apply {
            setPadding(dp(16), dp(10), dp(16), dp(2)); typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(label("输入：模拟注视 + 人工判决事件  ·  原生眼动 / 脑电解码 / 真实外设：未连接", 11f, Color.rgb(244, 195, 111)).apply {
            setPadding(dp(16), 0, dp(16), dp(6))
        })
        val wide = resources.configuration.screenWidthDp >= 600
        val body = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        scene = ControlSceneView(this, session) { refreshUi(it) }
        body.addView(scene, if (wide) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setBackgroundColor(Color.rgb(18, 29, 43))
        }
        status = label("尚未启动", 14f, Color.WHITE).apply { minHeight = dp(90); maxLines = 4 }
        panel.addView(status)
        metrics = label("", 11f, Color.rgb(151, 177, 198)).apply { minHeight = dp(56) }
        panel.addView(metrics)
        start = button("开始模拟") {
            scene.resetInput()
            attentionSwitch.isChecked = false
            session.start(now())
            recordEvent(ControlEvent(now(), "STIMULUS_PLAN", session.phase, session.candidateVersion,
                scene.encoding.joinToString(";") { "${it.index}:${it.frequencyHz}Hz:${it.phaseRadians}rad" } + ";phase_epoch=choreographer_monotonic; simulated_only"))
            refreshUi(now(), true)
        }
        panel.addView(start)
        confirm = button("注入选择与确认") {
            val direction = session.hoveredDirection(now())
            if (direction != null) session.injectSelectionAndConfirmation(direction, session.candidateVersion, now())
            refreshUi(now(), true)
        }
        panel.addView(confirm)
        panel.addView(button("停止模拟") {
            session.stop(now(), "手动停止虚拟动作")
            flashSwitch.isChecked = false
            refreshUi(now(), true)
        })
        flashSwitch = toggle("启用闪烁（默认关闭）", false) { scene.flashingEnabled = it }
        panel.addView(flashSwitch)
        attentionSwitch = toggle("模拟注意力下降", false) { session.setAttentionLow(it, now()); refreshUi(now(), true) }
        panel.addView(attentionSwitch)
        panel.addView(toggle("模拟眼动丢失", false) { scene.gazeAvailable = !it })
        panel.addView(toggle("模拟对象跟踪丢失", false) { session.setObjectTracked(!it, now()); refreshUi(now(), true) })
        panel.addView(toggle("模拟超出显示范围", false) { scene.forceOutsideViewport = it })
        val actions = LinearLayout(this)
        actions.addView(button("重置位置") { session.resetPosition(now()); refreshUi(now(), true) }, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(button("导出日志") { exportLog() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        panel.addView(actions)
        panel.addView(button("刺激参数 / 编码配置") { showStimulusSettings() })
        panel.addView(button("眼动接入诊断") { showGazeDiagnostics() })
        panel.addView(label("触控 / 鼠标落点保持为模拟视线。\n稳定看方向后形成三候选，再注入判决。\n正常退出：注意力下降 + 视线移开。", 11f, Color.rgb(163, 184, 201)))
        eventText = label("事件记录", 10f, Color.rgb(113, 152, 174)).apply { setPadding(0, dp(6), 0, 0) }
        panel.addView(eventText)
        val scroll = ScrollView(this).apply { addView(panel) }
        body.addView(scroll, if (wide) LinearLayout.LayoutParams(dp(254), -1) else LinearLayout.LayoutParams(-1, dp(260)))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refreshUi(now(), true)
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(3), 0, dp(3))
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 12f; isAllCaps = false; minimumHeight = dp(42)
        setOnClickListener { action() }
    }
    @Suppress("DEPRECATION")
    private fun toggle(value: String, checked: Boolean, action: (Boolean) -> Unit) = Switch(this).apply {
        text = value; textSize = 11f; setTextColor(Color.rgb(196, 210, 221))
        minHeight = dp(38); isChecked = checked
        setOnCheckedChangeListener { _, enabled -> action(enabled) }
    }

    private fun refreshUi(time: Long, force: Boolean = false) {
        if (!force && time - lastUiRefresh < 120) return
        lastUiRefresh = time
        val phaseLabel = when (session.phase) {
            ControlPhase.STOPPED -> "未启动"
            ControlPhase.NAVIGATION -> "八方向导航"
            ControlPhase.CANDIDATES -> "三候选观察"
            ControlPhase.EXECUTING -> "单目标 · 虚拟移动"
            ControlPhase.INTERRUPTED -> "已中断 · 需重新开始"
        }
        status.text = "$phaseLabel\n${session.status}"
        val gazeIndex = session.hoveredDirection(time)
        val gazeLabel = if (!session.gazeUsable(time)) "无效" else gazeIndex?.let { ControlDirections.all[it].label } ?: "离开方向区"
        metrics.text = "注视：$gazeLabel  /  版本：${session.candidateVersion}\n虚拟末端：${"%.2f, %.2f".format(session.positionX, session.positionY)}\n眼动接入：模拟  /  判决：事件注入"
        confirm.isEnabled = session.phase == ControlPhase.CANDIDATES && gazeIndex in session.candidates && !session.attentionLow
        start.isEnabled = session.phase in listOf(ControlPhase.STOPPED, ControlPhase.INTERRUPTED)
        eventText.text = logFailure ?: recentEvents.joinToString("\n")
        if (time - lastSnapshotAt >= 250 && session.phase in listOf(ControlPhase.NAVIGATION, ControlPhase.CANDIDATES, ControlPhase.EXECUTING)) {
            lastSnapshotAt = time
            recordEvent(ControlEvent(time, "OBSERVATION_SNAPSHOT", session.phase, session.candidateVersion, scene.frameDiagnostics()))
        }
    }

    private fun recordEvent(event: ControlEvent) {
        val line = JSONObject().apply {
            put("schema_version", 1); put("monotonic_ms", event.atMillis); put("wall_time_ms", System.currentTimeMillis())
            put("type", event.type); put("phase", event.phase.name); put("candidate_version", event.candidateVersion)
            put("object_id", "virtual-arm-01"); put("input_mode", "SIMULATED"); put("detail", event.detail)
            put("candidates", JSONArray(session.candidates)); put("selected_direction", session.selectedDirection ?: JSONObject.NULL)
            put("position_x", session.positionX); put("position_y", session.positionY); put("attention_low_simulated", session.attentionLow)
            put("object_tracked_simulated", session.objectTracked); put("layout_valid", session.layoutValid)
            session.gaze?.let { sample ->
                put("gaze", JSONObject().apply {
                    put("x", sample.x); put("y", sample.y); put("valid", sample.valid)
                    put("observed_at_ms", sample.observedAtMillis); put("source", sample.source)
                    put("coordinate_space", "NORMALIZED_TARGET_PLANE_Y_UP")
                })
            }
        }.toString()
        if (event.type != "OBSERVATION_SNAPSHOT") {
            recentEvents.addFirst("${event.type} · v${event.candidateVersion}")
            while (recentEvents.size > 5) recentEvents.removeLast()
        }
        logWriter.execute {
            runCatching { logFile.appendText(line + "\n") }.onFailure {
                runOnUiThread { logFailure = "日志写入失败：${it.message}"; refreshUi(now(), true) }
            }
        }
    }

    private fun exportLog() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, logFile.name)
        }, EXPORT_LOG)
    }

    private fun showStimulusSettings() {
        session.stop(now(), "编辑刺激参数，已停止虚拟动作")
        flashSwitch.isChecked = false
        refreshUi(now(), true)
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), 0) }
        fields.addView(label("四频率复用到八方向，同频对置两相。参数仅供软件验证，真实输出需实测。", 12f, Color.rgb(215, 225, 234)))
        fun field(title: String, initial: String): EditText {
            fields.addView(label(title, 12f, Color.rgb(215, 225, 234)))
            return EditText(this).apply { setText(initial); isSingleLine = true; fields.addView(this) }
        }
        val frequencies = field("四个频率 Hz（英文逗号分隔）", scene.encoding.take(4).joinToString(",") { "%.5f".format(java.util.Locale.US, it.frequencyHz) })
        val scale = field("闪块尺寸 / 环半径（0.25～0.55）", scene.targetScale.toString())
        val brightness = field("渲染亮度上限（0.2～1.0）", scene.brightnessLimit.toString())
        val dialog = AlertDialog.Builder(this).setTitle("刺激配置").setView(fields)
            .setPositiveButton("应用配置", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    val values = frequencies.text.toString().replace('，', ',').split(',').map { it.trim().toDouble() }
                    scene.configureStimulus(values, scale.text.toString().toFloat(), brightness.text.toString().toFloat())
                    flashSwitch.isChecked = false
                    recordEvent(ControlEvent(now(), "STIMULUS_CONFIG_CHANGED", session.phase, session.candidateVersion,
                        "frequencies=${values.joinToString(",")}; phases=0,PI; size=${scene.targetScale}; brightness=${scene.brightnessLimit}; epoch=choreographer_monotonic"))
                    refreshUi(now(), true)
                }.onSuccess { dialog.dismiss() }.onFailure { frequencies.error = "请输入四个不同的0～30Hz频率，尺寸与亮度须在范围内" }
            }
        }
        dialog.show()
    }

    private fun showGazeDiagnostics() {
        val sample = session.gaze
        val report = "当前来源：${sample?.source ?: "尚无输入"}\n" +
            "原生眼动SDK：未连接\n设备校准：未验证\n" +
            "坐标：目标平面归一化，X向右，Y向上\n" +
            "位置：${sample?.let { "%.3f, %.3f".format(it.x, it.y) } ?: "未知"}\n" +
            "输入有效：${sample?.valid ?: false}\n" +
            "数据年龄：${sample?.let { now() - it.observedAtMillis } ?: -1}ms\n" +
            "软件置信度：模拟固定值，不能作为眼动精度\n\n" +
            "后续SDK适配需提供真实注视、坐标转换、有效状态与时钟映射。\n${scene.frameDiagnostics()}"
        AlertDialog.Builder(this).setTitle("输入与显示诊断").setMessage(report).setPositiveButton("关闭", null).show()
    }

    @Deprecated("Platform document export without an AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != EXPORT_LOG || resultCode != RESULT_OK) return
        logWriter.execute {
            val result = runCatching {
                contentResolver.openOutputStream(uri, "w")?.use { output ->
                    if (logFile.exists()) logFile.inputStream().use { it.copyTo(output) }
                } ?: error("无法打开日志目标")
            }
            runOnUiThread {
                android.widget.Toast.makeText(this, result.fold({ "日志已导出" }, { "导出失败：${it.message}" }), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onPause() {
        scene.suspendFrames()
        session.stop(now(), "应用离开前台，虚拟动作与刺激已停止")
        flashSwitch.isChecked = false
        super.onPause()
    }
    override fun onResume() { super.onResume(); if (::scene.isInitialized) scene.resumeFrames() }
    override fun onDestroy() { logWriter.shutdown(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun now() = SystemClock.elapsedRealtime()
    private companion object { const val EXPORT_LOG = 2201 }
}
