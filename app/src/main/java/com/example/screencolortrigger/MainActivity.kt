package com.example.screencolortrigger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var xInput: EditText
    private lateinit var yInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildContentView()
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE_PERMISSION) return

        if (resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenColorMonitorService::class.java).apply {
                putExtra(ScreenColorMonitorService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenColorMonitorService.EXTRA_RESULT_DATA, data)
                putExtra(ScreenColorMonitorService.EXTRA_X, pendingX)
                putExtra(ScreenColorMonitorService.EXTRA_Y, pendingY)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "开始监测屏幕颜色", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未获得屏幕录制授权", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildContentView() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            gravity = Gravity.CENTER_VERTICAL
        }

        content.addView(TextView(this).apply {
            text = "监测屏幕指定坐标的颜色。检测到 RGB(255, 0, 0) 时，向\n" +
                ScreenColorMonitorService.POST_URL + "\n发送一次 HTTP POST。"
            textSize = 16f
        }, matchWidthWrapHeight())

        xInput = coordinateInput("X 坐标")
        yInput = coordinateInput("Y 坐标")
        content.addView(xInput, matchWidthWrapHeight())
        content.addView(yInput, matchWidthWrapHeight())

        content.addView(Button(this).apply {
            text = "开始监测"
            setOnClickListener { requestScreenCapture() }
        }, matchWidthWrapHeight())

        content.addView(Button(this).apply {
            text = "停止监测"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScreenColorMonitorService::class.java))
                Toast.makeText(this@MainActivity, "已停止监测", Toast.LENGTH_SHORT).show()
            }
        }, matchWidthWrapHeight())

        setContentView(content)
    }

    private fun coordinateInput(label: String) = EditText(this).apply {
        hint = label
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        setText("0")
    }

    private fun requestScreenCapture() {
        val x = xInput.text.toString().toIntOrNull()
        val y = yInput.text.toString().toIntOrNull()
        if (x == null || y == null) {
            Toast.makeText(this, "请输入有效的 X、Y 坐标", Toast.LENGTH_SHORT).show()
            return
        }

        pendingX = x
        pendingY = y
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE_PERMISSION)
    }

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private var pendingX = 0
    private var pendingY = 0

    companion object {
        private const val REQUEST_CAPTURE_PERMISSION = 100
    }
}
