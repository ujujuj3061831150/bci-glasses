package com.bci.glasses

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var dashboard: StimulusDashboardView
    private lateinit var settings: DeviceSettings
    private lateinit var liveSource: NeuracleTcpEegSource
    private lateinit var qualityEvaluator: SignalQualityEvaluator
    private lateinit var miQualityEvaluator: SignalQualityEvaluator
    private lateinit var captureButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val monitorExecutor = Executors.newSingleThreadScheduledExecutor { work ->
        Thread(work, "CaptureMonitor").apply { isDaemon = true }
    }
    private var monitorFuture: ScheduledFuture<*>? = null
    @Volatile private var runtime: CaptureRuntime? = null
    @Volatile private var miRuntime: MiCaptureRuntime? = null
    private val isCapturing: Boolean get() = runtime != null || miRuntime != null
    private var finalizing = false
    private var latestSession: File? = null
    private var pendingExport: File? = null
    private var lastQualityProbeNanos = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.decorView.post {
                window.decorView.windowInsetsController?.apply {
                    hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
        settings = DeviceSettings.load(this)
        val profile = settings.profile
        dashboard = StimulusDashboardView(this, profile.displayName)
        dashboard.setCalibrationFrameListener(::onFrameEvent)
        dashboard.setMiFrameListener(::onMiFrameEvent)
        dashboard.setOnClickListener { showMenu() }
        dashboard.setOnLongClickListener { showMenu(); true }
        val root = FrameLayout(this)
        root.addView(dashboard, FrameLayout.LayoutParams(-1, -1))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(4))
        }
        captureButton = Button(this).apply {
            text = "开始采集"
            setOnClickListener { if (!isCapturing) showCaptureSetup() else confirmStop() }
        }
        actions.addView(captureButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(Button(this).apply {
            setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_manage, 0, 0, 0)
            contentDescription = "设置与导出"
            tooltipText = contentDescription
            setOnClickListener { showMenu() }
        }, LinearLayout.LayoutParams(dp(56), dp(48)))
        root.addView(
            actions,
            FrameLayout.LayoutParams(-1, dp(56), android.view.Gravity.BOTTOM),
        )
        setContentView(root)
        qualityEvaluator = SignalQualityEvaluator(
            expectedSamples = profile.amplifier.rawSampleRateHz *
                PersonalCaptureSpec.READINESS_WINDOW_MILLIS.toInt() / 1000,
            liveChannelIndices = PersonalCaptureSpec.OCCIPITAL_MODEL_SLOTS.toSet(),
        )
        val motorSlotsInProfile = MiCalibrationSpec.MOTOR_MODEL_SLOTS.filter { it in profile.amplifier.channelMap.values }.toSet()
        miQualityEvaluator = SignalQualityEvaluator(
            expectedSamples = profile.amplifier.rawSampleRateHz *
                MiCalibrationSpec.READINESS_WINDOW_MILLIS.toInt() / 1000,
            liveChannelIndices = if (motorSlotsInProfile.isNotEmpty()) motorSlotsInProfile else PersonalCaptureSpec.OCCIPITAL_MODEL_SLOTS.toSet(),
        )
        liveSource = createSource(profile)
        window.decorView.post { selectDisplayMode() }
    }

    override fun onResume() {
        super.onResume()
        if (monitorFuture == null) {
            monitorFuture = monitorExecutor.scheduleWithFixedDelay(
                { runCatching { monitorSource() }.onFailure { Log.e(TAG, "Monitor failed", it) } },
                0L, 500L, TimeUnit.MILLISECONDS,
            )
        }
    }

    override fun onPause() {
        stopCapture("应用已进入后台", showDialog = false)
        stopMiCapture("应用已进入后台", showDialog = false)
        monitorFuture?.cancel(false)
        monitorFuture = null
        super.onPause()
    }

    override fun onDestroy() {
        liveSource.close()
        monitorExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_MENU,
            )
        ) {
            showMenu()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("Uses the platform activity result API to avoid an AndroidX dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            if (requestCode == REQUEST_EXPORT_SESSION) pendingExport = null
            return
        }
        when (requestCode) {
            REQUEST_EXPORT_SESSION -> {
                val directory = pendingExport
                pendingExport = null
                if (directory != null) {
                    Thread({
                        val result = runCatching {
                            contentResolver.openOutputStream(uri, "w")?.use { output ->
                                CaptureArchive.write(directory, output)
                            } ?: error("无法写入所选位置")
                        }
                        runOnUiThread {
                            result.onSuccess { message("导出完成", "${directory.name}.zip 已导出。") }
                                .onFailure { message("导出失败", it.message ?: "无法写入 ZIP") }
                        }
                    }, "CaptureExport").start()
                }
            }
            REQUEST_IMPORT_PROFILE -> {
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("无法读取档案")
                    require(text.length < 512 * 1024) { "档案过大" }
                    DeviceProfileJson.decode(text)
                }.onSuccess(::confirmProfile).onFailure { message("导入失败", it.message ?: "无效档案") }
            }
            REQUEST_EXPORT_PROFILE -> {
                runCatching {
                    contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use {
                        it.write(DeviceProfileJson.encode(settings.profile))
                    } ?: error("无法写入档案")
                }.onSuccess { message("档案已导出", "设备档案已写入所选位置。") }
                    .onFailure { message("导出失败", it.message ?: "无法写入档案") }
            }
        }
    }

    private fun createSource(profile: DeviceProfile): NeuracleTcpEegSource =
        NeuracleTcpEegSource(
            hosts = profile.amplifier.hosts,
            port = profile.amplifier.port,
            nChannels = profile.amplifier.channelCount,
            eegChannelCount = profile.amplifier.eegChannelCount,
            eventChannelCount = profile.amplifier.eventChannelCount,
            packetSamples = profile.amplifier.packetSamples,
            rawSampleRateHz = profile.amplifier.rawSampleRateHz,
            deviceTo32Map = profile.amplifier.channelMap,
            streamFormat = profile.amplifier.streamFormat,
        )

    private fun monitorSource() {
        val status = liveSource.statusSnapshot(PersonalCaptureSpec.READINESS_WINDOW_MILLIS / 1000.0)
        runOnUiThread { dashboard.updateDataSource(status.sourceStatus) }
        val capture = runtime
        val miCapture = miRuntime
        capture?.recorder?.failureReason()?.let { reason ->
            runOnUiThread { if (runtime === capture) stopCapture(reason) }
            return
        }
        miCapture?.recorder?.failureReason()?.let { reason ->
            runOnUiThread { if (miRuntime === miCapture) stopMiCapture(reason) }
            return
        }
        if (!status.dataAvailable || System.nanoTime() - lastQualityProbeNanos < 2_000_000_000L) return
        lastQualityProbeNanos = System.nanoTime()
        val sample = liveSource.nextWindow(PersonalCaptureSpec.READINESS_WINDOW_MILLIS / 1000.0)
        if (!sample.dataAvailable) return
        val currentEvaluator = if (miCapture != null) miQualityEvaluator else qualityEvaluator
        val quality = currentEvaluator.evaluate(sample.channels, isLive = true)
        runOnUiThread {
            if (capture != null && runtime === capture) {
                val trial = capture.currentTrial
                guardRecording(capture) {
                    capture.recorder.appendQuality(trial?.blockId ?: 0, trial?.trialId ?: 0, quality)
                }
            }
            if (miCapture != null && miRuntime === miCapture) {
                val trial = miCapture.currentTrial
                guardMiRecording(miCapture) {
                    miCapture.recorder.appendQuality(trial?.blockId ?: 0, trial?.trialId ?: 0, quality)
                }
            }
            dashboard.updateDiagnostics(quality.diagnostics)
        }
    }

    private fun isHardFailure(status: String): Boolean =
        listOf("stale", "disconnected", "rate-mismatch", "closed").any(status.lowercase()::contains)

    private fun showMenu() {
        val items = arrayOf(
            if (!isCapturing) "开始个人采集" else "停止个人采集",
            "导出最近一次采集",
            "选择采集设备档案",
            "编辑当前设备档案",
            "导入设备档案",
            "导出设备档案",
            "连接与显示状态",
        )
        AlertDialog.Builder(this).setTitle("BCI 个人采集")
            .setItems(items) { _, index ->
                when (index) {
                    0 -> if (!isCapturing) showCaptureSetup() else confirmStop()
                    1 -> exportLatestSession()
                    2 -> showPresets()
                    3 -> showProfileEditor()
                    4 -> if (!isCapturing && !finalizing) {
                        pickFile(REQUEST_IMPORT_PROFILE, "application/json", false, "")
                    } else message("采集进行中", "先停止采集再修改档案。")
                    5 -> if (!isCapturing && !finalizing) {
                        pickFile(
                            REQUEST_EXPORT_PROFILE, "application/json", true,
                            "${settings.profile.profileId}.json",
                        )
                    } else message("采集进行中", "采集完成后再导出档案。")
                    6 -> message(
                        "采集状态",
                        "${settings.profile.displayName}\n" +
                            "${liveSource.statusSnapshot(4.0).sourceStatus}\n" +
                            "显示：${dashboard.stimulusTimingStatus().reason}\n" +
                            "原始数据保存在 App 私有目录。",
                    )
                }
            }.setNegativeButton("关闭", null).show()
    }

    @SuppressLint("UsableSpace")
    private fun showCaptureSetup() {
        if (finalizing) {
            message("正在保存", "请等待上一次采集完成写盘。")
            return
        }
        // Require physical free space; reclaimable app caches are not safe headroom for raw EEG.
        if (filesDir.usableSpace < MIN_CAPTURE_FREE_BYTES) {
            message("存储空间不足", "至少需要 500 MB 可用空间。")
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }

        container.addView(TextView(this).apply { text = "采集范式" })
        val paradigmSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    "MI 左右手运动想象 (40次)",
                    "SSVEP 11 Hz 视觉诱发 (10次)",
                ),
            )
        }
        container.addView(paradigmSpinner)

        val descView = TextView(this).apply {
            setPadding(0, dp(4), 0, dp(8))
            setTextColor(android.graphics.Color.DKGRAY)
        }
        container.addView(descView)

        fun field(label: String, initial: String) = EditText(this).apply {
            setText(initial)
            isSingleLine = true
            container.addView(TextView(this@MainActivity).apply { text = label })
            container.addView(this)
        }
        val user = field("用户 ID", settings.profile.user.userId)
        val session = field("会话编号", "mi_${System.currentTimeMillis()}")

        val miDesc = "MI 范式：注视中心十字准备 2s -> 左右箭头提示 1.25s -> 运动想象 3.75s -> 休息 2s。分 2 组共 40 次（每组左右各 10 次）。"
        val ssvepDesc = "SSVEP 范式：共 10 次，每次准备 4s、11 Hz 闪烁 60s、休息 6s。"
        descView.text = miDesc

        paradigmSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    descView.text = miDesc
                    if (session.text.toString().startsWith("session_")) {
                        session.setText("mi_${System.currentTimeMillis()}")
                    }
                } else {
                    descView.text = ssvepDesc
                    if (session.text.toString().startsWith("mi_")) {
                        session.setText("session_${System.currentTimeMillis()}")
                    }
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        AlertDialog.Builder(this).setTitle("个人信号采集")
            .setView(container)
            .setPositiveButton("开始") { _, _ ->
                val selectedMi = paradigmSpinner.selectedItemPosition == 0
                val userId = user.text.toString().trim()
                val sessionId = session.text.toString().trim()
                if (selectedMi) {
                    runCatching { startMiCapture(userId, sessionId) }
                        .onFailure { message("无法开始", it.message ?: "初始化失败") }
                } else {
                    runCatching { startCapture(userId, sessionId) }
                        .onFailure { message("无法开始", it.message ?: "初始化失败") }
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun startCapture(userId: String, sessionId: String) {
        check(!isCapturing && !finalizing) { "已有采集正在运行或保存" }
        val plan = CalibrationPlanFactory.create(System.currentTimeMillis())
        val recorder = CalibrationSessionRecorder(this, userId, sessionId, plan.seed, settings.profile)
        val capture = CaptureRuntime(plan, recorder)
        val runner = CalibrationExperimentRunner(handler, plan, object : CalibrationExperimentListener {
            override fun onBlockStarted(block: CalibrationBlock) {
                guardRecording(capture) {
                    recordMarker(capture, CalibrationEventCodes.BLOCK_START, "BLOCK_START", 0, null)
                }
            }

            override fun onVisualState(state: CalibrationVisualState, eventType: String?, eventCode: Int?) {
                val trial = capture.plan.formalBlocks[0].trials[state.trialId - 1]
                if (capture.currentTrial != trial) {
                    capture.currentTrial = trial
                    capture.onsetSequence = null
                    capture.onsetFrameNanos = null
                    capture.invalidReasons.clear()
                }
                dashboard.updateCalibrationVisual(state, eventType, eventCode)
            }

            override fun onTrialEnded(trial: CalibrationTrial) {
                if (trial.trialId !in capture.recordedTrials) {
                    guardRecording(capture) {
                        capture.recorder.appendTrial(trial, 0L, 0L, 0L, true, "正常完成")
                        capture.recordedTrials += trial.trialId
                        capture.completedTrials++
                    }
                }
            }

            override fun onBlockFinished(block: CalibrationBlock, hasNextBlock: Boolean) {
                guardRecording(capture) {
                    recordMarker(capture, CalibrationEventCodes.BLOCK_END, "BLOCK_END", 0, null)
                    finishCapture(capture)
                }
            }
        })
        capture.runner = runner
        runtime = capture
        updateCaptureButton()
        try {
            liveSource.setRawPacketObserver(recorder)
            recordMarker(capture, CalibrationEventCodes.EXPERIMENT_START, "EXPERIMENT_START", 0, null)
            runner.start()
        } catch (exception: Exception) {
            recorder.fail("启动失败：${exception.message}")
            stopCapture("启动失败：${exception.message}", showDialog = false)
            throw exception
        }
    }

    private fun startMiCapture(userId: String, sessionId: String) {
        check(!isCapturing && !finalizing) { "已有采集正在运行或保存" }
        val plan = MiPlanFactory.create(System.currentTimeMillis())
        val recorder = MiSessionRecorder(this, userId, sessionId, plan.seed, settings.profile)
        val capture = MiCaptureRuntime(plan, recorder)
        val runner = MiExperimentRunner(handler, plan, object : MiExperimentListener {
            override fun onBlockStarted(block: MiBlock) {
                guardMiRecording(capture) {
                    recordMiMarker(capture, MiCalibrationSpec.EventCodes.BLOCK_START, "BLOCK_START", block.blockId, 0, null)
                }
            }

            override fun onVisualState(state: MiVisualState, eventType: String?, eventCode: Int?) {
                val allBlocks = listOfNotNull(capture.plan.practiceBlock) + capture.plan.formalBlocks
                val block = allBlocks.firstOrNull { it.blockId == state.blockId }
                val trial = block?.trials?.getOrNull(state.trialId - 1)
                if (trial != null && capture.currentTrial != trial) {
                    capture.currentTrial = trial
                    capture.onsetSequence = null
                    capture.onsetFrameNanos = null
                    capture.invalidReasons.clear()
                }
                dashboard.updateMiVisual(state, eventType, eventCode)
            }

            override fun onTrialEnded(trial: MiTrial) {
                val key = trial.blockId to trial.trialId
                if (key !in capture.recordedTrials) {
                    guardMiRecording(capture) {
                        capture.recorder.appendTrial(trial, 0L, 0L, 0L, true, "正常完成")
                        capture.recordedTrials += key
                        if (!trial.practice) capture.completedTrials++
                    }
                }
            }

            override fun onBlockFinished(block: MiBlock, hasNextBlock: Boolean) {
                guardMiRecording(capture) {
                    recordMiMarker(capture, MiCalibrationSpec.EventCodes.BLOCK_END, "BLOCK_END", block.blockId, 0, null)
                    if (hasNextBlock) {
                        runOnUiThread {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Block ${block.blockId} 完成")
                                .setMessage(
                                    if (block.practice) "练习完成！接下来进入正式采集。\n请放松休息，准备好后点击“开始正式采集”。"
                                    else "第 ${block.blockId} 组已完成！\n请闭目或远眺放松休息 1-2 分钟，准备好后点击“继续下一组”。"
                                )
                                .setCancelable(false)
                                .setPositiveButton(if (block.practice) "开始正式采集" else "继续下一组") { _, _ ->
                                    capture.runner.continueNextBlock()
                                }
                                .setNegativeButton("提前结束") { _, _ ->
                                    stopMiCapture("用户在Block间结束采集")
                                }
                                .show()
                        }
                    } else {
                        finishMiCapture(capture)
                    }
                }
            }
        })
        capture.runner = runner
        miRuntime = capture
        updateCaptureButton()
        try {
            liveSource.setRawPacketObserver(recorder)
            recordMiMarker(capture, MiCalibrationSpec.EventCodes.EXPERIMENT_START, "EXPERIMENT_START", 0, 0, null)
            runner.start()
        } catch (exception: Exception) {
            recorder.abort("启动失败：${exception.message}")
            stopMiCapture("启动失败：${exception.message}", showDialog = false)
            throw exception
        }
    }

    private fun onFrameEvent(event: CalibrationFrameEvent) {
        val capture = runtime ?: return
        val trial = capture.currentTrial ?: return
        if (trial.trialId != event.visualState.trialId) return
        guardRecording(capture) { recordFrameEvent(capture, trial, event) }
    }

    private fun recordFrameEvent(
        capture: CaptureRuntime, trial: CalibrationTrial, event: CalibrationFrameEvent,
    ) {
        val estimate = liveSource.estimateSequenceAt(event.frameTimeNanos)
        capture.recorder.appendMarker(
            CalibrationMarker(
                trial.blockId, trial.trialId, event.eventCode, event.eventType, trial.condition,
                event.frameTimeNanos, System.currentTimeMillis(), estimate,
            )
        )
        val timing = dashboard.stimulusTimingStatus()
        capture.recorder.appendFrameTiming(
            trial.blockId, trial.trialId, event.eventType, event.frameTimeNanos, timing,
        )
        when (event.eventType) {
            "ACTIVE_ONSET" -> {
                capture.onsetSequence = estimate.estimatedSequence
                capture.onsetFrameNanos = event.frameTimeNanos
                if (estimate.uncertaintySamples > MAX_CLOCK_UNCERTAINTY) {
                    capture.invalidReasons += "起始事件时间不确定度 ${estimate.uncertaintySamples} 点"
                }
            }
            "ACTIVE_OFFSET" -> {
                if (estimate.uncertaintySamples > MAX_CLOCK_UNCERTAINTY) {
                    capture.invalidReasons += "结束事件时间不确定度 ${estimate.uncertaintySamples} 点"
                }
                val onsetTime = capture.onsetFrameNanos
                val elapsed = if (onsetTime == null) 0L
                    else (event.frameTimeNanos - onsetTime).coerceAtLeast(0L) / 1_000_000L
                if (onsetTime == null || capture.onsetSequence == null) capture.invalidReasons += "缺少刺激起始帧"
                if (elapsed !in 59_800L..60_200L) capture.invalidReasons += "刺激时长 $elapsed ms"
                if (!timing.healthy) capture.invalidReasons += "显示时序：${timing.reason}"
                capture.recorder.failureReason()?.let(capture.invalidReasons::add)
                capture.recorder.appendTrial(
                    trial, capture.onsetSequence ?: 0L, estimate.estimatedSequence,
                    elapsed, capture.invalidReasons.isEmpty(), capture.invalidReasons.joinToString("；"),
                )
                capture.recordedTrials += trial.trialId
                capture.completedTrials++
            }
        }
    }

    private fun recordMarker(
        capture: CaptureRuntime, code: Int, type: String, trialId: Int,
        condition: CalibrationCondition?,
    ) {
        val now = System.nanoTime()
        capture.recorder.appendMarker(
            CalibrationMarker(
                1, trialId, code, type, condition, now, System.currentTimeMillis(),
                liveSource.estimateSequenceAt(now),
            )
        )
    }

    private fun onMiFrameEvent(event: MiFrameEvent) {
        val capture = miRuntime ?: return
        val trial = capture.currentTrial ?: return
        if (trial.blockId != event.visualState.blockId || trial.trialId != event.visualState.trialId) return
        guardMiRecording(capture) { recordMiFrameEvent(capture, trial, event) }
    }

    private fun recordMiFrameEvent(
        capture: MiCaptureRuntime, trial: MiTrial, event: MiFrameEvent,
    ) {
        val estimate = liveSource.estimateSequenceAt(event.frameTimeNanos)
        capture.recorder.appendMarker(
            MiMarker(
                trial.blockId, trial.trialId, event.eventCode, event.eventType, trial.condition,
                event.frameTimeNanos, System.currentTimeMillis(), estimate,
            )
        )
        val timing = dashboard.stimulusTimingStatus()
        capture.recorder.appendFrameTiming(
            trial.blockId, trial.trialId, event.eventType, event.frameTimeNanos, timing,
        )
        when (event.eventType) {
            "ACTIVE_ONSET" -> {
                capture.onsetSequence = estimate.estimatedSequence
                capture.onsetFrameNanos = event.frameTimeNanos
            }
            "ACTIVE_OFFSET" -> {
                val onsetTime = capture.onsetFrameNanos
                val elapsed = if (onsetTime == null) 0L
                    else (event.frameTimeNanos - onsetTime).coerceAtLeast(0L) / 1_000_000L
                capture.recorder.appendTrial(
                    trial, capture.onsetSequence ?: 0L, estimate.estimatedSequence,
                    elapsed, true, "正常",
                )
                capture.recordedTrials += (trial.blockId to trial.trialId)
                if (!trial.practice) capture.completedTrials++
            }
        }
    }

    private fun recordMiMarker(
        capture: MiCaptureRuntime, code: Int, type: String, blockId: Int, trialId: Int,
        condition: MiCondition?,
    ) {
        val now = System.nanoTime()
        capture.recorder.appendMarker(
            MiMarker(
                blockId, trialId, code, type, condition, now, System.currentTimeMillis(),
                liveSource.estimateSequenceAt(now),
            )
        )
    }

    private fun confirmStop() {
        AlertDialog.Builder(this).setTitle("停止采集")
            .setMessage("立即停止采集；已采集数据保留，并标记为未完成。")
            .setPositiveButton("停止") { _, _ ->
                if (runtime != null) stopCapture("用户主动停止")
                if (miRuntime != null) stopMiCapture("用户主动停止")
            }
            .setNegativeButton("继续采集", null).show()
    }

    private fun stopCapture(reason: String, showDialog: Boolean = true) {
        val capture = runtime ?: return
        runCatching {
            capture.currentTrial?.takeIf { it.trialId !in capture.recordedTrials }?.let { trial ->
                val now = System.nanoTime()
                val estimate = liveSource.estimateSequenceAt(now)
                val elapsed = capture.onsetFrameNanos?.let { (now - it).coerceAtLeast(0L) / 1_000_000L } ?: 0L
                capture.recorder.appendTrial(
                    trial, capture.onsetSequence ?: 0L, estimate.estimatedSequence,
                    elapsed, false, "未完成：$reason",
                )
                capture.recordedTrials += trial.trialId
            }
            recordMarker(capture, CalibrationEventCodes.CANCELLED, "ABORTED", capture.currentTrial?.trialId ?: 0, capture.currentTrial?.condition)
        }.onFailure {
            capture.recorder.fail("终止事件写入失败：${it.message}")
            Log.e(TAG, "Could not append abort markers", it)
        }
        closeCapture(capture, "aborted", reason, showDialog)
    }

    private fun finishCapture(capture: CaptureRuntime) {
        if (runtime !== capture) return
        recordMarker(capture, CalibrationEventCodes.EXPERIMENT_END, "EXPERIMENT_END", 0, null)
        closeCapture(capture, "complete", null, showDialog = true)
    }

    private inline fun guardRecording(capture: CaptureRuntime, action: () -> Unit) {
        if (runtime !== capture) return
        try {
            action()
        } catch (exception: Exception) {
            Log.e(TAG, "Capture write failed", exception)
            val reason = "采集写盘异常：${exception.message ?: exception.javaClass.simpleName}"
            capture.recorder.fail(reason)
            stopCapture(reason)
        }
    }

    private fun closeCapture(capture: CaptureRuntime, status: String, reason: String?, showDialog: Boolean) {
        if (runtime !== capture) return
        capture.runner.cancel()
        runtime = null
        finalizing = true
        updateCaptureButton()
        liveSource.setRawPacketObserver(null)
        dashboard.clearCalibrationVisual()
        Thread({
            val result = runCatching {
                capture.recorder.finish(status, capture.completedTrials, reason) to
                    capture.recorder.directory
            }
            runOnUiThread {
                finalizing = false
                updateCaptureButton()
                result.onSuccess { (finalStatus, directory) ->
                    latestSession = directory
                    if (showDialog) {
                        message(
                            if (finalStatus == "complete") "采集结束" else "采集已停止",
                            "${capture.completedTrials}/${PersonalCaptureSpec.TRIAL_COUNT} 次已记录。\n" +
                                "数据：${directory.absolutePath}" +
                                ((reason ?: capture.recorder.failureReason())?.let { "\n原因：$it" } ?: "") +
                                "\n可在菜单中导出 ZIP。",
                        )
                    }
                }.onFailure { message("写盘失败", it.message ?: "请检查存储空间") }
            }
        }, "CaptureFinalize").start()
    }

    private fun stopMiCapture(reason: String, showDialog: Boolean = true) {
        val capture = miRuntime ?: return
        runCatching {
            capture.currentTrial?.takeIf { (it.blockId to it.trialId) !in capture.recordedTrials }?.let { trial ->
                val now = System.nanoTime()
                val estimate = liveSource.estimateSequenceAt(now)
                val elapsed = capture.onsetFrameNanos?.let { (now - it).coerceAtLeast(0L) / 1_000_000L } ?: 0L
                capture.recorder.appendTrial(
                    trial, capture.onsetSequence ?: 0L, estimate.estimatedSequence,
                    elapsed, false, "未完成：$reason",
                )
                capture.recordedTrials += (trial.blockId to trial.trialId)
            }
            recordMiMarker(
                capture, MiCalibrationSpec.EventCodes.CANCELLED, "ABORTED",
                capture.currentTrial?.blockId ?: 0, capture.currentTrial?.trialId ?: 0, capture.currentTrial?.condition,
            )
        }.onFailure {
            capture.recorder.fail("终止事件写入失败：${it.message}")
            Log.e(TAG, "Could not append MI abort markers", it)
        }
        closeMiCapture(capture, "aborted", reason, showDialog)
    }

    private fun finishMiCapture(capture: MiCaptureRuntime) {
        if (miRuntime !== capture) return
        recordMiMarker(capture, MiCalibrationSpec.EventCodes.EXPERIMENT_END, "EXPERIMENT_END", 0, 0, null)
        closeMiCapture(capture, "complete", null, showDialog = true)
    }

    private inline fun guardMiRecording(capture: MiCaptureRuntime, action: () -> Unit) {
        if (miRuntime !== capture) return
        try {
            action()
        } catch (exception: Exception) {
            Log.e(TAG, "MI capture write failed", exception)
            val reason = "MI 采集写盘异常：${exception.message ?: exception.javaClass.simpleName}"
            capture.recorder.fail(reason)
            stopMiCapture(reason)
        }
    }

    private fun closeMiCapture(capture: MiCaptureRuntime, status: String, reason: String?, showDialog: Boolean) {
        if (miRuntime !== capture) return
        capture.runner.cancel()
        miRuntime = null
        finalizing = true
        updateCaptureButton()
        liveSource.setRawPacketObserver(null)
        dashboard.clearMiVisual()
        Thread({
            val result = runCatching {
                if (status == "complete") {
                    capture.recorder.finish(capture.completedTrials)
                } else {
                    capture.recorder.abort(reason ?: "主动停止")
                }
                capture.recorder.directory
            }
            runOnUiThread {
                finalizing = false
                updateCaptureButton()
                result.onSuccess { directory ->
                    latestSession = directory
                    if (showDialog) {
                        message(
                            if (status == "complete") "MI 采集结束" else "MI 采集已停止",
                            "${capture.completedTrials}/${capture.plan.totalFormalTrials} 次已记录。\n" +
                                "数据：${directory.absolutePath}" +
                                ((reason ?: capture.recorder.failureReason())?.let { "\n原因：$it" } ?: "") +
                                "\n可在菜单中导出 ZIP。",
                        )
                    }
                }.onFailure { message("写盘失败", it.message ?: "请检查存储空间") }
            }
        }, "MiCaptureFinalize").start()
    }

    private fun exportLatestSession() {
        if (isCapturing || finalizing) {
            message("采集尚未结束", "停止或完成采集后才能导出。")
            return
        }
        val directory = latestSession ?: filesDir.listFiles()
            ?.filter {
                it.isDirectory &&
                    (it.name.startsWith("capture_") || it.name.startsWith("calibration_mi_")) &&
                    File(it, "checksums.sha256").exists()
            }
            ?.maxByOrNull(File::lastModified)
        if (directory == null) {
            message("没有采集数据", "完成一次采集后再导出。")
            return
        }
        pendingExport = directory
        pickFile(REQUEST_EXPORT_SESSION, "application/zip", true, "${directory.name}.zip")
    }

    private fun pickFile(code: Int, mime: String, create: Boolean, name: String) {
        val intent = Intent(if (create) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            if (create) putExtra(Intent.EXTRA_TITLE, name)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, code)
    }

    private fun showPresets() {
        if (isCapturing || finalizing) {
            message("采集进行中", "先停止采集再切换设备档案。")
            return
        }
        val presets = DeviceSettings.presets()
        AlertDialog.Builder(this).setTitle("采集设备档案")
            .setItems(presets.map { it.displayName }.toTypedArray()) { _, index ->
                confirmProfile(presets[index])
            }.show()
    }

    private fun showProfileEditor() {
        if (isCapturing || finalizing) {
            message("采集进行中", "先停止采集再编辑设备档案。")
            return
        }
        val old = settings.profile
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(24))
        }
        fun field(label: String, initial: String, numeric: Boolean = false): EditText {
            layout.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                setText(initial)
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
                layout.addView(this)
            }
        }
        val name = field("档案名称", old.displayName)
        val user = field("用户 ID", old.user.userId)
        val hosts = field("Recorder PC 地址（逗号分隔）", old.amplifier.hosts.joinToString(","))
        val port = field("TCP 端口", old.amplifier.port.toString(), true)
        val eeg = field("EEG 通道数", old.amplifier.eegChannelCount.toString(), true)
        val event = field("event 通道数", old.amplifier.eventChannelCount.toString(), true)
        val packet = field("每包采样点数", old.amplifier.packetSamples.toString(), true)
        val mapping = field("设备索引:通道槽（逗号分隔）", NeuracleChannelMapping.format(old.amplifier.channelMap))
        val refresh = field("首选刷新率 Hz（留空自动）", old.display.preferredRefreshRateHz?.toString().orEmpty())
        val scroll = ScrollView(this).apply { addView(layout) }
        val dialog = AlertDialog.Builder(this).setTitle("编辑采集档案")
            .setView(scroll).setPositiveButton("校验并保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    val eegCount = eeg.text.toString().trim().toInt()
                    val eventCount = event.text.toString().trim().toInt()
                    old.copy(
                        displayName = name.text.toString().trim(),
                        user = old.user.copy(userId = user.text.toString().trim()),
                        amplifier = old.amplifier.copy(
                            hosts = hosts.text.toString().split(',', ';', '\n').map(String::trim).filter(String::isNotEmpty),
                            port = port.text.toString().trim().toInt(),
                            eegChannelCount = eegCount, eventChannelCount = eventCount,
                            channelCount = eegCount + eventCount,
                            packetSamples = packet.text.toString().trim().toInt(),
                            channelMap = NeuracleChannelMapping.parse(mapping.text.toString(), eegCount),
                        ),
                        display = old.display.copy(
                            preferredRefreshRateHz = refresh.text.toString().trim().takeIf(String::isNotEmpty)?.toDouble(),
                        ),
                    )
                }.onSuccess { updated ->
                    val validation = DeviceProfileValidator.validate(updated)
                    val incompatible = CalibrationRecordingProfile.incompatibilityReason(updated)
                    if (!validation.isValid || incompatible != null) {
                        message("档案不兼容", (validation.errors + listOfNotNull(incompatible)).joinToString("\n"))
                    } else {
                        dialog.dismiss()
                        confirmProfile(updated)
                    }
                }.onFailure { message("输入错误", it.message ?: "请检查输入") }
            }
        }
        dialog.show()
    }

    private fun confirmProfile(profile: DeviceProfile) {
        val incompatibility = CalibrationRecordingProfile.incompatibilityReason(profile)
        if (incompatibility != null) {
            message("档案不兼容", incompatibility)
            return
        }
        AlertDialog.Builder(this).setTitle("应用采集档案")
            .setMessage("应用“${profile.displayName}”并重启界面？")
            .setPositiveButton("应用") { _, _ ->
                runCatching { DeviceSettings(profile).save(this) }
                    .onSuccess { liveSource.close(); recreate() }
                    .onFailure { message("保存失败", it.message ?: "无效档案") }
            }.setNegativeButton("取消", null).show()
    }

    private fun selectDisplayMode() {
        val display = window.decorView.display ?: return
        val current = display.mode
        val spec = DisplayModeSpec(current.modeId, current.physicalWidth, current.physicalHeight, current.refreshRate.toDouble())
        val supported = display.supportedModes.map {
            DisplayModeSpec(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate.toDouble())
        }
        val selected = SsvepDisplayModeSelector(
            doubleArrayOf(PersonalCaptureSpec.TARGET_FREQUENCY_HZ),
            settings.profile.display.preferredRefreshRateHz,
        ).select(spec, supported)
        if (selected.modeId != current.modeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = selected.modeId }
        }
    }

    private fun message(title: String, text: String) {
        if (!isFinishing && !isDestroyed) {
            AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("知道了", null).show()
        }
    }

    private fun updateCaptureButton() {
        captureButton.text = if (!isCapturing) "开始采集" else "停止采集"
        captureButton.isEnabled = !finalizing
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class CaptureRuntime(val plan: CalibrationPlan, val recorder: CalibrationSessionRecorder) {
        lateinit var runner: CalibrationExperimentRunner
        var currentTrial: CalibrationTrial? = null
        var onsetSequence: Long? = null
        var onsetFrameNanos: Long? = null
        var completedTrials = 0
        val recordedTrials = mutableSetOf<Int>()
        val invalidReasons = linkedSetOf<String>()
    }

    private class MiCaptureRuntime(val plan: MiPlan, val recorder: MiSessionRecorder) {
        lateinit var runner: MiExperimentRunner
        var currentTrial: MiTrial? = null
        var onsetSequence: Long? = null
        var onsetFrameNanos: Long? = null
        var completedTrials = 0
        val recordedTrials = mutableSetOf<Pair<Int, Int>>()
        val invalidReasons = linkedSetOf<String>()
    }

    companion object {
        private const val TAG = "PersonalCapture"
        private const val REQUEST_EXPORT_SESSION = 503
        private const val REQUEST_IMPORT_PROFILE = 501
        private const val REQUEST_EXPORT_PROFILE = 502
        private const val MAX_CLOCK_UNCERTAINTY = 60L
        private const val MIN_CAPTURE_FREE_BYTES = 500L * 1024L * 1024L
    }
}
