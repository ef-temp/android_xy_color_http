package com.example.screencolortrigger

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private lateinit var rgbStatus: TextView
    private val postCompletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            showPostResult(
                intent.getIntExtra(ScreenColorMonitorService.EXTRA_POST_X, 0),
                intent.getIntExtra(ScreenColorMonitorService.EXTRA_POST_Y, 0),
                intent.getIntExtra(ScreenColorMonitorService.EXTRA_POST_RED, 0),
                intent.getIntExtra(ScreenColorMonitorService.EXTRA_POST_GREEN, 0),
                intent.getIntExtra(ScreenColorMonitorService.EXTRA_POST_BLUE, 0),
                intent.getBooleanExtra(ScreenColorMonitorService.EXTRA_POST_HAS_RGB, false),
                intent.getStringExtra(ScreenColorMonitorService.EXTRA_POST_STATUS)
            )
        }
    }
    private var isPostReceiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildContentView()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ScreenColorMonitorService.ACTION_POST_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(postCompletedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(postCompletedReceiver, filter)
        }
        isPostReceiverRegistered = true
        loadLastPostResult()
    }

    override fun onStop() {
        if (isPostReceiverRegistered) {
            unregisterReceiver(postCompletedReceiver)
            isPostReceiverRegistered = false
        }
        super.onStop()
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
            text = "监测屏幕指定坐标的颜色，并且每 12 秒（无论颜色条件是否成立）向\n" +
                ScreenColorMonitorService.POST_URL + "\n发送一次 HTTP POST。"
            textSize = 16f
        }, matchWidthWrapHeight())

        rgbStatus = TextView(this).apply {
            text = "发送 POST 后将在此显示坐标 RGB"
            textSize = 16f
        }
        content.addView(rgbStatus, matchWidthWrapHeight())

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

    private fun loadLastPostResult() {
        val preferences = getSharedPreferences(
            ScreenColorMonitorService.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
        val status = preferences.getString(ScreenColorMonitorService.KEY_STATUS, null)
            ?: return
        showPostResult(
            preferences.getInt(ScreenColorMonitorService.KEY_X, 0),
            preferences.getInt(ScreenColorMonitorService.KEY_Y, 0),
            preferences.getInt(ScreenColorMonitorService.KEY_RED, 0),
            preferences.getInt(ScreenColorMonitorService.KEY_GREEN, 0),
            preferences.getInt(ScreenColorMonitorService.KEY_BLUE, 0),
            preferences.getBoolean(ScreenColorMonitorService.KEY_HAS_RGB, false),
            status
        )
    }

    private fun showPostResult(
        x: Int,
        y: Int,
        red: Int,
        green: Int,
        blue: Int,
        hasRgb: Boolean,
        status: String?
    ) {
        val colorText = if (hasRgb) {
            "坐标 ($x, $y) RGB($red, $green, $blue)"
        } else {
            "坐标 ($x, $y) 暂无可用 RGB 采样"
        }
        rgbStatus.text = "最近一次 POST：$colorText\n结果：${status ?: "未知"}"
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
