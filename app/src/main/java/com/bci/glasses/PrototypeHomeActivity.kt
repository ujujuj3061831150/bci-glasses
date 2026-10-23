package com.bci.glasses

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class PrototypeHomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 24, 32, 24)
            setBackgroundColor(Color.rgb(12, 20, 31))
        }
        fun text(value: String, size: Float, color: Int) = TextView(this).apply {
            text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }
        root.addView(text("BCI GLASSES / 开发原型", 14f, Color.rgb(99, 221, 203)))
        root.addView(text("看向目标，逐步完成选择", 28f, Color.WHITE))
        root.addView(text("八方向 → 三候选 → 单目标 → 虚拟移动\n原生眼动、脑电解码与真实外设尚未接入", 16f, Color.rgb(170, 189, 207)))
        root.addView(Button(this).apply {
            text = "进入眼动控制原型"
            setOnClickListener { startActivity(Intent(this@PrototypeHomeActivity, ControlPrototypeActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, 64).apply { topMargin = 20 })
        root.addView(Button(this).apply {
            text = "进入脑电采集模式"
            setOnClickListener { startActivity(Intent(this@PrototypeHomeActivity, MainActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, 64))
        root.addView(text("模拟模式不连接机械臂。采集模式保留原有Recorder数据接口。", 13f, Color.rgb(140, 160, 180)))
        setContentView(root)
    }
}
