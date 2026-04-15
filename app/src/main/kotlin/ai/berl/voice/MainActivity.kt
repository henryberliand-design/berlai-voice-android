package ai.berl.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val PERM_REQ = 1
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btn = findViewById<Button>(R.id.btnToggle)
        val status = findViewById<TextView>(R.id.tvStatus)

        btn.setOnClickListener {
            if (!running) {
                if (hasPermissions()) {
                    startVoice()
                    running = true
                    btn.text = "Stop"
                    status.text = "Running — speak freely"
                } else {
                    requestPermissions()
                }
            } else {
                stopVoice()
                running = false
                btn.text = "Start"
                status.text = "Stopped"
            }
        }
    }

    private fun hasPermissions(): Boolean {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        return perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERM_REQ)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQ && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startVoice()
            running = true
            findViewById<Button>(R.id.btnToggle).text = "Stop"
            findViewById<TextView>(R.id.tvStatus).text = "Running — speak freely"
        }
    }

    private fun startVoice() {
        val intent = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
        else startService(intent)
    }

    private fun stopVoice() {
        val intent = Intent(this, VoiceService::class.java).apply {
            action = VoiceService.ACTION_STOP
        }
        startService(intent)
    }
}
