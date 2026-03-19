package com.allicex.magnifier

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        var opacity = 1.0f
        var pendingPreset: JSONArray? = null // для загрузки пресета после projection
    }

    private lateinit var presetListLayout: LinearLayout
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvOpacity = findViewById<TextView>(R.id.tvOpacity)
        tvStatus = findViewById(R.id.tvStatus)
        presetListLayout = findViewById(R.id.presetList)
        val seekOpacity = findViewById<SeekBar>(R.id.seekOpacity)

        seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                opacity = p / 100f
                tvOpacity.text = "Прозрачность: ${p}%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Разреши overlay!", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
            pendingPreset = null
            requestProjection()
        }

        findViewById<MaterialButton>(R.id.btnStopAll).setOnClickListener {
            MagnifierService.stopAll()
            updateStatus()
        }

        findViewById<MaterialButton>(R.id.btnSavePreset).setOnClickListener {
            saveCurrentAsPreset()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        loadPresets()
    }

    private fun requestProjection() {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(pm.createScreenCaptureIntent(), 200)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == Activity.RESULT_OK && data != null) {
            val preset = pendingPreset
            if (preset != null && preset.length() > 0) {
                // Загрузка пресета — создаём несколько луп
                for (i in 0 until preset.length()) {
                    val obj = preset.getJSONObject(i)
                    startForegroundService(Intent(this, MagnifierService::class.java).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("projIntent", data)
                        putExtra("opacity", opacity)
                        putExtra("presetX", obj.optInt("x", 100))
                        putExtra("presetY", obj.optInt("y", 100))
                        putExtra("presetW", obj.optInt("w", 260))
                        putExtra("presetH", obj.optInt("h", 150))
                        putExtra("areaL", obj.optInt("aL", 0))
                        putExtra("areaT", obj.optInt("aT", 0))
                        putExtra("areaR", obj.optInt("aR", 0))
                        putExtra("areaB", obj.optInt("aB", 0))
                    })
                }
                pendingPreset = null
            } else {
                // Одна новая лупа
                startForegroundService(Intent(this, MagnifierService::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("projIntent", data)
                    putExtra("opacity", opacity)
                })
            }
            updateStatus()
        }
    }

    private fun saveCurrentAsPreset() {
        val states = MagnifierService.getAllStates()
        if (states.isEmpty()) {
            Toast.makeText(this, "Нет активных луп!", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("magnifier", MODE_PRIVATE)
        val presetsStr = prefs.getString("presets", "[]") ?: "[]"
        val presets = JSONArray(presetsStr)

        val newPreset = JSONObject().apply {
            put("name", "Пресет ${presets.length() + 1}")
            put("data", JSONArray(states.map {
                JSONObject().apply {
                    put("x", it.x); put("y", it.y)
                    put("w", it.w); put("h", it.h)
                    put("aL", it.areaL); put("aT", it.areaT)
                    put("aR", it.areaR); put("aB", it.areaB)
                }
            }))
        }
        presets.put(newPreset)

        prefs.edit().putString("presets", presets.toString()).apply()
        loadPresets()
        Toast.makeText(this, "Пресет сохранён!", Toast.LENGTH_SHORT).show()
    }

    private fun loadPresets() {
        presetListLayout.removeAllViews()
        val prefs = getSharedPreferences("magnifier", MODE_PRIVATE)
        val presetsStr = prefs.getString("presets", "[]") ?: "[]"
        val presets = JSONArray(presetsStr)

        for (i in 0 until presets.length()) {
            val preset = presets.getJSONObject(i)
            val name = preset.optString("name", "Пресет")
            val data = preset.getJSONArray("data")

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }

            val btnLoad = MaterialButton(this).apply {
                text = "▶ $name (${data.length()})"
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, 40, 1f)
                setOnClickListener {
                    pendingPreset = data
                    requestProjection()
                }
            }

            val btnDel = MaterialButton(this).apply {
                text = "🗑"
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(50, 40)
                setBackgroundColor(0xFF883333.toInt())
                setOnClickListener {
                    deletePreset(i)
                }
            }

            row.addView(btnLoad)
            row.addView(btnDel)
            presetListLayout.addView(row)
        }
    }

    private fun deletePreset(index: Int) {
        val prefs = getSharedPreferences("magnifier", MODE_PRIVATE)
        val presetsStr = prefs.getString("presets", "[]") ?: "[]"
        val presets = JSONArray(presetsStr)
        val newPresets = JSONArray()
        for (i in 0 until presets.length()) {
            if (i != index) newPresets.put(presets.get(i))
        }
        prefs.edit().putString("presets", newPresets.toString()).apply()
        loadPresets()
    }

    private fun updateStatus() {
        val count = MagnifierService.getCount()
        tvStatus.text = if (count > 0) "✅ Активно луп: $count" else ""
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        loadPresets()
    }
}
