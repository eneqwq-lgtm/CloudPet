package com.example.cloudpet

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.cloudpet.service.OverlayService

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            if (!checkOverlayPermission()) {
                requestOverlayPermission()
                return@setOnClickListener
            }
            if (!checkUsageStatsPermission()) {
                Toast.makeText(this, "需要先授予\"使用情况访问\"权限", Toast.LENGTH_LONG).show()
                requestUsageStatsPermission()
                return@setOnClickListener
            }
            startPetService()
        }

        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "云宝休息了~", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_usage_access).setOnClickListener {
            requestUsageStatsPermission()
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
    }

    private fun checkUsageStatsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        return try {
            val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }

    private fun requestUsageStatsPermission() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "请找到 ☁️ 云宝 并开启\"使用情况访问\"权限", Toast.LENGTH_LONG).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (checkOverlayPermission()) {
                if (checkUsageStatsPermission()) {
                    startPetService()
                } else {
                    Toast.makeText(this, "还需要\"使用情况访问\"权限才能检测前台 App", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "需要悬浮窗权限才能显示云宝", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startPetService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "☁️ 云宝出现啦！", Toast.LENGTH_SHORT).show()
    }
}
