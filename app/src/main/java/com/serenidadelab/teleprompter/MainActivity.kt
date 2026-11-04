package com.serenidadelab.teleprompter

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var scriptEdit: EditText
    private lateinit var charCount: TextView
    private lateinit var fontLabel: TextView
    private lateinit var speedLabel: TextView
    private lateinit var opacityLabel: TextView
    private lateinit var fontSeek: SeekBar
    private lateinit var speedSeek: SeekBar
    private lateinit var opacitySeek: SeekBar
    private lateinit var durationGroup: RadioGroup
    private lateinit var customRow: View
    private lateinit var customMin: EditText
    private lateinit var customSec: EditText
    private lateinit var gestureSwitch: SwitchCompat
    private lateinit var countdownSwitch: SwitchCompat
    private lateinit var frontSwitch: SwitchCompat
    private lateinit var landscapeSwitch: SwitchCompat

    private val durationIds = listOf(R.id.durFree, R.id.dur30, R.id.dur60, R.id.dur180, R.id.durCustom)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPermission(Manifest.permission.CAMERA)) {
                if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    toast("Sem permissão de microfone: o vídeo será gravado sem som.")
                }
                startActivity(Intent(this, RecordActivity::class.java))
            } else {
                toast("O app precisa da permissão de câmera para gravar.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        scriptEdit = findViewById(R.id.scriptEdit)
        charCount = findViewById(R.id.charCount)
        fontLabel = findViewById(R.id.fontLabel)
        speedLabel = findViewById(R.id.speedLabel)
        opacityLabel = findViewById(R.id.opacityLabel)
        fontSeek = findViewById(R.id.fontSeek)
        speedSeek = findViewById(R.id.speedSeek)
        opacitySeek = findViewById(R.id.opacitySeek)
        durationGroup = findViewById(R.id.durationGroup)
        customRow = findViewById(R.id.customRow)
        customMin = findViewById(R.id.customMin)
        customSec = findViewById(R.id.customSec)
        gestureSwitch = findViewById(R.id.gestureSwitch)
        countdownSwitch = findViewById(R.id.countdownSwitch)
        frontSwitch = findViewById(R.id.frontSwitch)
        landscapeSwitch = findViewById(R.id.landscapeSwitch)

        // Valores salvos
        scriptEdit.setText(prefs.script)
        fontSeek.progress = prefs.fontSizeSp
        speedSeek.progress = prefs.speed
        opacitySeek.progress = prefs.opacity
        durationGroup.check(durationIds.getOrElse(prefs.durationMode) { R.id.durFree })
        customMin.setText((prefs.customSeconds / 60).toString())
        customSec.setText((prefs.customSeconds % 60).toString())
        gestureSwitch.isChecked = prefs.gestureEnabled
        countdownSwitch.isChecked = prefs.countdown
        frontSwitch.isChecked = prefs.frontCamera
        landscapeSwitch.isChecked = prefs.landscape

        updateLabels()
        updateCharCount()
        customRow.visibility = if (prefs.durationMode == 4) View.VISIBLE else View.GONE

        fontSeek.onChange { updateLabels() }
        speedSeek.onChange { updateLabels() }
        opacitySeek.onChange { updateLabels() }
        durationGroup.setOnCheckedChangeListener { _, id ->
            customRow.visibility = if (id == R.id.durCustom) View.VISIBLE else View.GONE
        }
        scriptEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateCharCount()
        })

        findViewById<Button>(R.id.pasteBtn).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.clearBtn).setOnClickListener { scriptEdit.setText("") }
        findViewById<Button>(R.id.startBtn).setOnClickListener { onStartClicked() }

        handleSharedText(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedText(intent)
    }

    override fun onRestart() {
        super.onRestart()
        // Tamanho/velocidade podem ter sido ajustados durante a gravação
        fontSeek.progress = prefs.fontSizeSp
        speedSeek.progress = prefs.speed
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    /** Recebe texto enviado por "Compartilhar" de outro app (Word, Notas, WhatsApp…). */
    private fun handleSharedText(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!shared.isNullOrBlank()) {
                scriptEdit.setText(shared)
                toast("Texto recebido.")
            }
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        if (clip == null || clip.itemCount == 0) {
            toast("A área de transferência está vazia.")
            return
        }
        val text = clip.getItemAt(0).coerceToText(this).toString()
        scriptEdit.setText(text)
        toast("Texto colado (${text.length} caracteres).")
    }

    private fun updateLabels() {
        fontLabel.text = "Tamanho da letra: ${fontSeek.progress}"
        speedLabel.text = "Velocidade do texto: ${speedSeek.progress}"
        opacityLabel.text = "Escurecimento do fundo: ${opacitySeek.progress}%  (0% = vídeo totalmente visível)"
    }

    private fun updateCharCount() {
        val text = scriptEdit.text?.toString().orEmpty()
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        // Estimativa de fala: ~140 palavras por minuto
        val secs = (words / 140.0 * 60).toInt()
        charCount.text = "$words palavras · cerca de ${secs / 60} min ${secs % 60} s de fala"
    }

    private fun readCustomSeconds(): Int {
        val m = customMin.text.toString().toIntOrNull() ?: 0
        val s = customSec.text.toString().toIntOrNull() ?: 0
        return m * 60 + s
    }

    private fun saveSettings() {
        prefs.script = scriptEdit.text?.toString().orEmpty()
        prefs.fontSizeSp = fontSeek.progress
        prefs.speed = speedSeek.progress
        prefs.opacity = opacitySeek.progress
        prefs.durationMode = durationIds.indexOf(durationGroup.checkedRadioButtonId).coerceAtLeast(0)
        val custom = readCustomSeconds()
        if (custom > 0) prefs.customSeconds = custom
        prefs.gestureEnabled = gestureSwitch.isChecked
        prefs.countdown = countdownSwitch.isChecked
        prefs.frontCamera = frontSwitch.isChecked
        prefs.landscape = landscapeSwitch.isChecked
    }

    private fun onStartClicked() {
        if (durationGroup.checkedRadioButtonId == R.id.durCustom && readCustomSeconds() <= 0) {
            toast("Informe o tempo personalizado (minutos e/ou segundos).")
            return
        }
        if (scriptEdit.text.isNullOrBlank()) {
            toast("Dica: cole o texto antes de abrir a câmera.")
        }
        saveSettings()
        permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

fun SeekBar.onChange(block: (Int) -> Unit) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = block(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    })
}
