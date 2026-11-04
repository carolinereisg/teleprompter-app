package com.serenidadelab.teleprompter

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.provider.Settings
import android.view.InputDevice
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var scriptEdit: EditText
    private lateinit var sourceText: TextView
    private lateinit var statsText: TextView
    private lateinit var fontLabel: TextView
    private lateinit var speedLabel: TextView
    private lateinit var opacityLabel: TextView
    private lateinit var fontSlider: Slider
    private lateinit var speedSlider: Slider
    private lateinit var opacitySlider: Slider
    private lateinit var boldSwitch: MaterialSwitch
    private lateinit var colorsSwitch: MaterialSwitch
    private lateinit var orientationGroup: MaterialButtonToggleGroup
    private lateinit var qualityGroup: MaterialButtonToggleGroup
    private lateinit var durationGroup: ChipGroup
    private lateinit var customRow: View
    private lateinit var customMin: EditText
    private lateinit var customSec: EditText
    private lateinit var frontSwitch: MaterialSwitch
    private lateinit var countdownSwitch: MaterialSwitch
    private lateinit var framingHint: TextView
    private lateinit var guidesSwitch: MaterialSwitch
    private lateinit var silhouetteSwitch: MaterialSwitch
    private lateinit var gestureSwitch: MaterialSwitch
    private lateinit var remoteGroup: MaterialButtonToggleGroup
    private lateinit var mouseStatus: TextView
    private lateinit var adaptiveSwitch: MaterialSwitch

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = updateMouseStatus()
        override fun onInputDeviceRemoved(deviceId: Int) = updateMouseStatus()
        override fun onInputDeviceChanged(deviceId: Int) = updateMouseStatus()
    }

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

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        applyInsets()

        scriptEdit = findViewById(R.id.scriptEdit)
        sourceText = findViewById(R.id.sourceText)
        statsText = findViewById(R.id.statsText)
        fontLabel = findViewById(R.id.fontLabel)
        speedLabel = findViewById(R.id.speedLabel)
        opacityLabel = findViewById(R.id.opacityLabel)
        fontSlider = findViewById(R.id.fontSlider)
        speedSlider = findViewById(R.id.speedSlider)
        opacitySlider = findViewById(R.id.opacitySlider)
        boldSwitch = findViewById(R.id.boldSwitch)
        colorsSwitch = findViewById(R.id.colorsSwitch)
        orientationGroup = findViewById(R.id.orientationGroup)
        qualityGroup = findViewById(R.id.qualityGroup)
        durationGroup = findViewById(R.id.durationGroup)
        customRow = findViewById(R.id.customRow)
        customMin = findViewById(R.id.customMin)
        customSec = findViewById(R.id.customSec)
        frontSwitch = findViewById(R.id.frontSwitch)
        countdownSwitch = findViewById(R.id.countdownSwitch)
        framingHint = findViewById(R.id.framingHint)
        guidesSwitch = findViewById(R.id.guidesSwitch)
        silhouetteSwitch = findViewById(R.id.silhouetteSwitch)
        gestureSwitch = findViewById(R.id.gestureSwitch)
        remoteGroup = findViewById(R.id.remoteGroup)
        mouseStatus = findViewById(R.id.mouseStatus)
        adaptiveSwitch = findViewById(R.id.adaptiveSwitch)
        findViewById<MaterialButton>(R.id.pairMouseBtn).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (_: Exception) {
                toast("Abra Configurações > Bluetooth para parear o mouse.")
            }
        }

        loadValues()

        fontSlider.addOnChangeListener { _, _, _ -> updateLabels() }
        speedSlider.addOnChangeListener { _, _, _ -> updateLabels(); updateStats() }
        opacitySlider.addOnChangeListener { _, _, _ -> updateLabels() }
        durationGroup.setOnCheckedStateChangeListener { _, ids ->
            customRow.visibility = if (ids.contains(R.id.durCustom)) View.VISIBLE else View.GONE
        }
        orientationGroup.addOnButtonCheckedListener { _, _, _ -> updateFramingHint() }
        qualityGroup.addOnButtonCheckedListener { _, _, _ -> updateFramingHint() }
        scriptEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateStats()
        })

        findViewById<MaterialButton>(R.id.importBtn).setOnClickListener {
            openDocument.launch(
                arrayOf(
                    "text/plain",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/msword",
                    "application/octet-stream",
                )
            )
        }
        findViewById<MaterialButton>(R.id.pasteBtn).setOnClickListener { pasteFromClipboard() }
        findViewById<MaterialButton>(R.id.clearBtn).setOnClickListener {
            scriptEdit.setText("")
            prefs.sourceName = ""
            sourceText.text = ""
        }
        findViewById<MaterialButton>(R.id.startBtn).setOnClickListener { onStartClicked() }

        handleSharedText(intent)
    }

    private fun applyInsets() {
        val header = findViewById<View>(R.id.header)
        val bottomBar = findViewById<View>(R.id.bottomBar)
        val headerTop = header.paddingTop
        val barBottom = bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            header.setPadding(header.paddingLeft, headerTop + bars.top, header.paddingRight, header.paddingBottom)
            bottomBar.setPadding(
                bottomBar.paddingLeft, bottomBar.paddingTop, bottomBar.paddingRight,
                barBottom + maxOf(bars.bottom, ime.bottom)
            )
            insets
        }
    }

    private fun loadValues() {
        setEditorText(prefs.loadScript())
        sourceText.text = if (prefs.sourceName.isNotEmpty()) "Arquivo: ${prefs.sourceName}" else ""
        fontSlider.value = prefs.fontSizeSp.toFloat()
        speedSlider.value = prefs.wpm.toFloat()
        opacitySlider.value = prefs.opacity.toFloat()
        boldSwitch.isChecked = prefs.boldAll
        colorsSwitch.isChecked = prefs.keepColors
        orientationGroup.check(if (prefs.landscape) R.id.btnLandscape else R.id.btnPortrait)
        qualityGroup.check(if (prefs.uhd) R.id.btnUhd else R.id.btnFhd)
        durationGroup.check(durationIds.getOrElse(prefs.durationMode) { R.id.durFree })
        customMin.setText((prefs.customSeconds / 60).toString())
        customSec.setText((prefs.customSeconds % 60).toString())
        customRow.visibility = if (prefs.durationMode == 4) View.VISIBLE else View.GONE
        frontSwitch.isChecked = prefs.frontCamera
        countdownSwitch.isChecked = prefs.countdown
        guidesSwitch.isChecked = prefs.showGuides
        silhouetteSwitch.isChecked = prefs.showSilhouette
        gestureSwitch.isChecked = prefs.gestureEnabled
        remoteGroup.check(if (prefs.remoteChangesSpeed) R.id.btnRemoteSpeed else R.id.btnRemotePos)
        adaptiveSwitch.isChecked = prefs.adaptivePace
        updateLabels()
        updateStats()
        updateFramingHint()
    }

    /** Mostra o texto no editor já com a aparência de fundo escuro. */
    private fun setEditorText(text: CharSequence) {
        scriptEdit.setText(ScriptFormat.forDarkBackground(text, keepColors = true, boldAll = false))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedText(intent)
    }

    override fun onRestart() {
        super.onRestart()
        // Tamanho/velocidade podem ter sido ajustados durante a gravação
        fontSlider.value = prefs.fontSizeSp.toFloat()
        speedSlider.value = prefs.wpm.toFloat()
    }

    override fun onResume() {
        super.onResume()
        getSystemService(InputManager::class.java)?.registerInputDeviceListener(inputDeviceListener, null)
        updateMouseStatus()
    }

    override fun onPause() {
        super.onPause()
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(inputDeviceListener)
        saveSettings()
    }

    /** Mostra se há um mouse conectado (pareado pelo Bluetooth do Android). */
    private fun updateMouseStatus() {
        val mouse = InputDevice.getDeviceIds()
            .mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull { !it.isVirtual && it.supportsSource(InputDevice.SOURCE_MOUSE) }
        mouseStatus.text = if (mouse != null) "✅ Conectado: ${mouse.name}"
        else "Nenhum mouse conectado. Ligue o mouse e pareie no Bluetooth do celular."
    }

    /** Recebe texto enviado por "Compartilhar" de outro app. */
    private fun handleSharedText(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            if (!shared.isNullOrBlank()) {
                setEditorText(shared)
                prefs.sourceName = ""
                sourceText.text = "Texto recebido por compartilhamento"
            }
        }
    }

    private fun importFile(uri: Uri) {
        toast("Importando…")
        Thread {
            try {
                val result = DocumentImporter.import(this, uri)
                runOnUiThread {
                    setEditorText(result.text)
                    prefs.sourceName = result.name
                    sourceText.text = "Arquivo: ${result.name}"
                    saveSettings()
                    toast("Importado: ${ScriptFormat.wordCount(result.text)} palavras.")
                }
            } catch (e: Exception) {
                runOnUiThread { toast(e.message ?: "Não foi possível ler o arquivo.") }
            }
        }.start()
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        if (clip == null || clip.itemCount == 0) {
            toast("A área de transferência está vazia.")
            return
        }
        val item = clip.getItemAt(0)
        val html = item.htmlText
        val text: CharSequence = if (!html.isNullOrBlank()) {
            ScriptFormat.fromHtml(html)
        } else {
            item.coerceToText(this)
        }
        setEditorText(SpannableStringBuilder(text))
        prefs.sourceName = ""
        sourceText.text = if (!html.isNullOrBlank()) "Colado com formatação" else "Colado"
    }

    private fun updateLabels() {
        fontLabel.text = "Tamanho da letra: ${fontSlider.value.toInt()}"
        speedLabel.text = "Velocidade: ${speedSlider.value.toInt()} palavras por minuto"
        opacityLabel.text = "Escurecimento do fundo: ${opacitySlider.value.toInt()}%  (0% = vídeo todo visível)"
    }

    private fun updateStats() {
        val words = ScriptFormat.wordCount(scriptEdit.text ?: "")
        val wpm = speedSlider.value.toInt().coerceAtLeast(1)
        val secs = (words * 60.0 / wpm).toInt()
        statsText.text = "$words palavras · cerca de ${secs / 60} min ${secs % 60} s a $wpm ppm"
    }

    private fun updateFramingHint() {
        val landscape = orientationGroup.checkedButtonId == R.id.btnLandscape
        val uhd = qualityGroup.checkedButtonId == R.id.btnUhd
        framingHint.text = if (landscape) {
            "Grave na horizontal: o quadro inteiro vira o vídeo do YouTube (16:9) e a faixa central " +
                "vira o Reels/TikTok (9:16). Fique dentro da silhueta para o mesmo take servir aos dois." +
                if (uhd) "" else "\nDica: escolha 4K. Em Full HD, o corte vertical fica com só ~600×1080."
        } else {
            "Grave na vertical: o quadro inteiro vira o Reels/TikTok (9:16) e a faixa do meio é o corte " +
                "horizontal (16:9), que fica pequeno. Para usar o mesmo take nos dois, prefira Horizontal + 4K."
        }
    }

    private fun readCustomSeconds(): Int {
        val m = customMin.text.toString().toIntOrNull() ?: 0
        val s = customSec.text.toString().toIntOrNull() ?: 0
        return m * 60 + s
    }

    private fun saveSettings() {
        prefs.saveScript(scriptEdit.text ?: "")
        prefs.fontSizeSp = fontSlider.value.toInt()
        prefs.wpm = speedSlider.value.toInt()
        prefs.opacity = opacitySlider.value.toInt()
        prefs.boldAll = boldSwitch.isChecked
        prefs.keepColors = colorsSwitch.isChecked
        prefs.landscape = orientationGroup.checkedButtonId == R.id.btnLandscape
        prefs.uhd = qualityGroup.checkedButtonId == R.id.btnUhd
        prefs.durationMode = durationIds.indexOf(durationGroup.checkedChipId).coerceAtLeast(0)
        val custom = readCustomSeconds()
        if (custom > 0) prefs.customSeconds = custom
        prefs.frontCamera = frontSwitch.isChecked
        prefs.countdown = countdownSwitch.isChecked
        prefs.showGuides = guidesSwitch.isChecked
        prefs.showSilhouette = silhouetteSwitch.isChecked
        prefs.gestureEnabled = gestureSwitch.isChecked
        prefs.remoteChangesSpeed = remoteGroup.checkedButtonId == R.id.btnRemoteSpeed
        prefs.adaptivePace = adaptiveSwitch.isChecked
    }

    private fun onStartClicked() {
        if (durationGroup.checkedChipId == R.id.durCustom && readCustomSeconds() <= 0) {
            toast("Informe o tempo personalizado (minutos e/ou segundos).")
            return
        }
        if (scriptEdit.text.isNullOrBlank()) toast("Dica: coloque o texto antes de abrir a câmera.")
        saveSettings()
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
