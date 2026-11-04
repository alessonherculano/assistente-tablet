package com.assistente.tablet

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    data class Ev(
        val id: Long,
        val date: String,      // yyyy-MM-dd
        val time: String,      // HH:mm ou vazio (dia todo)
        val title: String,
        val notified: Boolean
    )

    // =====================================================================
    //  AJUSTES FÁCEIS (mude aqui se quiser)
    // =====================================================================
    private val DIM_BRIGHTNESS = 0.02f     // brilho da tela em espera (0.0 a 1.0)
    private val DIM_ALPHA = 0.35f          // intensidade do conteúdo em espera (0.0 a 1.0)
    private val AWAKE_BRIGHTNESS = 0.8f    // brilho quando você chama o assistente
    private val AWAKE_MS = 20000L          // tempo acordado antes de voltar a dormir (20 s)
    private val DEFAULT_NAME = "Luna"      // nome inicial do assistente

    // ---------- cores (tema escuro) ----------
    private val cText = Color.parseColor("#E6E6E6")
    private val cGray = Color.parseColor("#8A8F98")
    private val cAccent = Color.parseColor("#1565C0")
    private val cCell = Color.parseColor("#1A1D22")
    private val cEvent = Color.parseColor("#1F3A5F")

    // ---------- formatos ----------
    private val ptBR = Locale("pt", "BR")
    private val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val hmFmt = SimpleDateFormat("HH:mm", Locale.US)
    private val clockFmt = SimpleDateFormat("HH:mm", ptBR)
    private val dateFmt = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", ptBR)
    private val monthFmt = SimpleDateFormat("MMMM 'de' yyyy", ptBR)

    // ---------- telas ----------
    private lateinit var main: LinearLayout
    private lateinit var tvClock: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvListen: TextView
    private lateinit var tvBanner: TextView
    private lateinit var tvMonth: TextView
    private lateinit var grid: GridLayout
    private lateinit var tasksBox: LinearLayout
    private lateinit var whiteOverlay: View

    // ---------- estado ----------
    private val handler = Handler(Looper.getMainLooper())
    private var shown: Calendar = firstOfThisMonth()
    private var lastDayKey = ""
    private var tickCount = 0
    private var awake = false
    private var screenLightOn = false
    private var wakeName = DEFAULT_NAME

    // ---------- microfone ----------
    private var recognizer: SpeechRecognizer? = null
    private var micWanted = false
    private var askedMic = false

    private val prefs by lazy { getSharedPreferences("assistente", Context.MODE_PRIVATE) }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000)
        }
    }

    private val sleepRunnable = Runnable {
        if (screenLightOn || tvBanner.visibility == View.VISIBLE) {
            scheduleSleep(AWAKE_MS)
        } else {
            goToSleep()
        }
    }

    private val restartRunnable = Runnable {
        if (micWanted) startRecognizer()
    }

    // =====================================================================
    //  Ciclo de vida
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wakeName = prefs.getString("wake_name", DEFAULT_NAME) ?: DEFAULT_NAME
        buildUi()
        updateListenLabel()
        applyMode()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        wakeUp()
        startMic()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(sleepRunnable)
        stopMic()
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    /** Se a tela está em espera, o primeiro toque só acorda (não aperta nada sem querer). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            if (!awake && !screenLightOn) {
                wakeUp()
                return true
            }
            if (awake) wakeUp()
        }
        return super.dispatchTouchEvent(ev)
    }

    // =====================================================================
    //  Modo espera (escuro) / modo ativo
    // =====================================================================

    private fun applyMode() {
        main.alpha = if (awake || screenLightOn) 1f else DIM_ALPHA
        val p = window.attributes
        p.screenBrightness = when {
            screenLightOn -> 1f
            awake -> AWAKE_BRIGHTNESS
            else -> DIM_BRIGHTNESS
        }
        window.attributes = p
    }

    private fun scheduleSleep(ms: Long) {
        handler.removeCallbacks(sleepRunnable)
        handler.postDelayed(sleepRunnable, ms)
    }

    private fun wakeUp(holdMs: Long = AWAKE_MS) {
        awake = true
        applyMode()
        scheduleSleep(holdMs)
    }

    private fun goToSleep() {
        awake = false
        handler.removeCallbacks(sleepRunnable)
        applyMode()
        status("Em espera. Diga \"$wakeName\" para me chamar.")
    }

    // =====================================================================
    //  Construção da tela
    // =====================================================================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun firstOfThisMonth(): Calendar {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1)
        return c
    }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.topMargin = dp(top)
        return p
    }

    /** Parâmetro "metade do espaço": usa largura em paisagem e altura em retrato. */
    private fun half(landscape: Boolean, weight: Float = 1f): LinearLayout.LayoutParams {
        return if (landscape) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight)
        }
    }

    private fun buildUi() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setPadding(dp(18), dp(12), dp(18), dp(8))

        // ----- aviso de lembrete (some quando não há lembrete) -----
        tvBanner = TextView(this)
        tvBanner.textSize = 26f
        tvBanner.setTextColor(Color.parseColor("#1E2430"))
        tvBanner.setBackgroundColor(Color.parseColor("#FFE9A8"))
        tvBanner.setPadding(dp(14), dp(12), dp(14), dp(12))
        tvBanner.visibility = View.GONE
        tvBanner.setOnClickListener {
            tvBanner.visibility = View.GONE
            wakeUp()
        }
        main.addView(tvBanner, lp())

        // =================== METADE DE CIMA: relógio + calendário ===================
        val top = LinearLayout(this)
        top.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL

        // ----- relógio -----
        val clockPanel = LinearLayout(this)
        clockPanel.orientation = LinearLayout.VERTICAL
        clockPanel.gravity = Gravity.CENTER

        tvClock = TextView(this)
        tvClock.textSize = 120f
        tvClock.setTypeface(null, Typeface.BOLD)
        tvClock.setTextColor(cText)
        tvClock.gravity = Gravity.CENTER
        tvClock.includeFontPadding = false
        clockPanel.addView(tvClock, LinearLayout.LayoutParams(wrap, wrap))

        tvDate = TextView(this)
        tvDate.textSize = 22f
        tvDate.setTextColor(cGray)
        tvDate.gravity = Gravity.CENTER
        clockPanel.addView(tvDate, LinearLayout.LayoutParams(wrap, wrap))

        // ----- calendário -----
        val calPanel = LinearLayout(this)
        calPanel.orientation = LinearLayout.VERTICAL
        calPanel.setPadding(dp(8), 0, 0, 0)

        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER_VERTICAL

        val prev = Button(this)
        prev.text = "◀"
        prev.setOnClickListener {
            shown.add(Calendar.MONTH, -1)
            buildCalendar()
        }
        val next = Button(this)
        next.text = "▶"
        next.setOnClickListener {
            shown.add(Calendar.MONTH, 1)
            buildCalendar()
        }
        val today = Button(this)
        today.text = "Hoje"
        today.setOnClickListener {
            shown = firstOfThisMonth()
            buildCalendar()
        }
        tvMonth = TextView(this)
        tvMonth.textSize = 24f
        tvMonth.setTypeface(null, Typeface.BOLD)
        tvMonth.setTextColor(cText)
        tvMonth.gravity = Gravity.CENTER

        nav.addView(prev, LinearLayout.LayoutParams(wrap, wrap))
        nav.addView(tvMonth, LinearLayout.LayoutParams(0, wrap, 1f))
        nav.addView(next, LinearLayout.LayoutParams(wrap, wrap))
        nav.addView(today, LinearLayout.LayoutParams(wrap, wrap))
        calPanel.addView(nav, lp())

        grid = GridLayout(this)
        grid.columnCount = 7
        calPanel.addView(grid, LinearLayout.LayoutParams(match, 0, 1f))

        top.addView(clockPanel, half(landscape))
        top.addView(calPanel, half(landscape))
        main.addView(top, LinearLayout.LayoutParams(match, 0, 1.5f))

        // =================== METADE DE BAIXO: tarefas + 3 botões ===================
        val bottom = LinearLayout(this)
        bottom.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL

        val tasksPanel = LinearLayout(this)
        tasksPanel.orientation = LinearLayout.VERTICAL
        tasksPanel.setPadding(0, dp(6), dp(8), 0)

        val tasksTitle = TextView(this)
        tasksTitle.text = "Próximas tarefas"
        tasksTitle.textSize = 20f
        tasksTitle.setTypeface(null, Typeface.BOLD)
        tasksTitle.setTextColor(cText)
        tasksPanel.addView(tasksTitle, lp())

        tasksBox = LinearLayout(this)
        tasksBox.orientation = LinearLayout.VERTICAL
        tasksPanel.addView(tasksBox, lp(4))

        val buttonsRow = LinearLayout(this)
        buttonsRow.orientation = LinearLayout.HORIZONTAL
        buttonsRow.setPadding(dp(8), dp(6), 0, 0)
        for (n in 1..3) {
            val b = Button(this)
            b.text = "Função $n\n(em breve)"
            b.setAllCaps(false)
            b.textSize = 18f
            b.setTextColor(cGray)
            b.setBackgroundColor(cCell)
            b.setOnClickListener { onFuncButton(n) }
            val p = LinearLayout.LayoutParams(0, match, 1f)
            p.setMargins(dp(5), dp(5), dp(5), dp(5))
            buttonsRow.addView(b, p)
        }

        bottom.addView(tasksPanel, half(landscape))
        bottom.addView(buttonsRow, half(landscape))
        main.addView(bottom, LinearLayout.LayoutParams(match, 0, 1f))

        // =================== BARRA DE STATUS ===================
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL

        tvStatus = TextView(this)
        tvStatus.textSize = 16f
        tvStatus.setTextColor(cGray)
        tvStatus.setPadding(dp(4), dp(8), dp(4), dp(8))
        tvStatus.text = "Toque aqui para digitar um comando."
        tvStatus.setOnClickListener { showTypeDialog() }

        tvListen = TextView(this)
        tvListen.textSize = 16f
        tvListen.setTextColor(cAccent)
        tvListen.setPadding(dp(10), dp(8), dp(4), dp(8))
        tvListen.setOnClickListener { showNameDialog() }

        bar.addView(tvStatus, LinearLayout.LayoutParams(0, wrap, 1f))
        bar.addView(tvListen, LinearLayout.LayoutParams(wrap, wrap))
        main.addView(bar, lp())

        root.addView(main, FrameLayout.LayoutParams(match, match))

        // Tela branca usada como "lanterna de tela" quando o tablet não tem flash
        whiteOverlay = View(this)
        whiteOverlay.setBackgroundColor(Color.WHITE)
        whiteOverlay.visibility = View.GONE
        whiteOverlay.setOnClickListener { setScreenLight(false) }
        root.addView(whiteOverlay, FrameLayout.LayoutParams(match, match))

        setContentView(root)
    }

    private fun updateListenLabel() {
        tvListen.text = if (micWanted) "🎤 Diga \"$wakeName\"" else "🎤 desligado"
    }

    private fun cap(s: String): String = s.replaceFirstChar { it.uppercase() }

    private fun onFuncButton(n: Int) {
        // PRÓXIMAS ETAPAS: cada botão poderá ligar uma função (luzes, música, etc.)
        wakeUp()
        status("O botão $n ainda não tem função. Em breve!")
    }

    private fun showTypeDialog() {
        wakeUp(60000L)
        val et = EditText(this)
        et.hint = "ex.: acender lanterna"
        et.setSingleLine(true)
        AlertDialog.Builder(this)
            .setTitle("Digitar comando")
            .setView(et)
            .setPositiveButton("Enviar") { _, _ ->
                val s = et.text.toString().trim()
                if (s.isNotEmpty()) handle(s)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showNameDialog() {
        wakeUp(60000L)
        val et = EditText(this)
        et.setText(wakeName)
        et.setSingleLine(true)
        AlertDialog.Builder(this)
            .setTitle("Nome do assistente")
            .setMessage("É por esse nome que você me chama.")
            .setView(et)
            .setPositiveButton("Salvar") { _, _ ->
                val s = et.text.toString().trim()
                if (s.isNotEmpty()) {
                    wakeName = s
                    prefs.edit().putString("wake_name", s).apply()
                    updateListenLabel()
                    status("Agora me chame de \"$s\".")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // =====================================================================
    //  Calendário (mês completo) e tarefas
    // =====================================================================

    private fun cell(row: Int, col: Int, text: String, bold: Boolean, fg: Int, bgc: Int?): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.gravity = Gravity.CENTER
        tv.textSize = 18f
        tv.setTextColor(fg)
        if (bold) tv.setTypeface(null, Typeface.BOLD)
        if (bgc != null) tv.setBackgroundColor(bgc)
        val p = GridLayout.LayoutParams(
            GridLayout.spec(row, 1, GridLayout.FILL, if (row == 0) 0f else 1f),
            GridLayout.spec(col, 1, GridLayout.FILL, 1f)
        )
        p.width = 0
        p.height = if (row == 0) ViewGroup.LayoutParams.WRAP_CONTENT else 0
        p.setMargins(dp(2), dp(2), dp(2), dp(2))
        tv.layoutParams = p
        return tv
    }

    private fun buildCalendar() {
        tvMonth.text = cap(monthFmt.format(shown.time))
        grid.removeAllViews()

        val first = shown.clone() as Calendar
        first.set(Calendar.DAY_OF_MONTH, 1)
        val offset = first.get(Calendar.DAY_OF_WEEK) - 1
        val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val weeks = (offset + daysInMonth + 6) / 7
        grid.rowCount = weeks + 1
        grid.columnCount = 7

        val heads = arrayOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb")
        for (i in heads.indices) grid.addView(cell(0, i, heads[i], true, cGray, null))

        val todayKey = keyFmt.format(Date())
        val withEvents = loadEvents().map { it.date }.toSet()

        for (d in 1..daysInMonth) {
            val idx = offset + d - 1
            val k = String.format(
                Locale.US, "%04d-%02d-%02d",
                first.get(Calendar.YEAR), first.get(Calendar.MONTH) + 1, d
            )
            val isToday = k == todayKey
            val hasEvent = withEvents.contains(k)
            val bg = when {
                isToday -> cAccent
                hasEvent -> cEvent
                else -> cCell
            }
            grid.addView(
                cell(1 + idx / 7, idx % 7, "$d", isToday, if (isToday) Color.WHITE else cText, bg)
            )
        }
    }

    private fun dayLabel(key: String): String {
        val c = Calendar.getInstance()
        if (key == keyFmt.format(c.time)) return "Hoje"
        c.add(Calendar.DAY_OF_YEAR, 1)
        if (key == keyFmt.format(c.time)) return "Amanhã"
        return key.substring(8, 10) + "/" + key.substring(5, 7)
    }

    private fun renderAgenda() {
        tasksBox.removeAllViews()
        val todayKey = keyFmt.format(Date())
        val list = loadEvents()
            .filter { it.date >= todayKey }
            .sortedWith(compareBy<Ev>({ it.date }, { it.time }))
            .take(3)

        for (slot in 0 until 3) {
            val tv = TextView(this)
            tv.textSize = 19f
            tv.setBackgroundColor(cCell)
            tv.setPadding(dp(10), dp(10), dp(10), dp(10))
            if (slot < list.size) {
                val e = list[slot]
                val hora = if (e.time.isEmpty()) "dia todo" else e.time
                tv.text = "${dayLabel(e.date)} • $hora — ${e.title}"
                tv.setTextColor(cText)
                tv.setOnLongClickListener {
                    deleteEvent(e.id)
                    true
                }
            } else {
                tv.text = if (slot == 0) "Nenhuma tarefa. Diga: \"$wakeName, criar agenda amanhã às 9 horas médico\"" else "—"
                tv.setTextColor(cGray)
            }
            tasksBox.addView(tv, lp(if (slot == 0) 0 else 6))
        }
    }

    private fun loadEvents(): MutableList<Ev> {
        val out = ArrayList<Ev>()
        try {
            val a = JSONArray(prefs.getString("agenda", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(
                    Ev(
                        o.getLong("id"),
                        o.getString("date"),
                        o.getString("time"),
                        o.getString("title"),
                        o.optBoolean("n", false)
                    )
                )
            }
        } catch (ex: Exception) {
            // agenda corrompida: começa vazia
        }
        return out
    }

    private fun saveEvents(list: List<Ev>) {
        val a = JSONArray()
        for (e in list) {
            val o = JSONObject()
            o.put("id", e.id)
            o.put("date", e.date)
            o.put("time", e.time)
            o.put("title", e.title)
            o.put("n", e.notified)
            a.put(o)
        }
        prefs.edit().putString("agenda", a.toString()).apply()
    }

    private fun deleteEvent(id: Long) {
        saveEvents(loadEvents().filter { it.id != id })
        buildCalendar()
        renderAgenda()
        status("Tarefa apagada.")
    }

    // =====================================================================
    //  Relógio e lembretes
    // =====================================================================

    private fun tick() {
        val now = Date()
        val clock = clockFmt.format(now)
        if (tvClock.text.toString() != clock) tvClock.text = clock
        val key = keyFmt.format(now)
        if (key != lastDayKey) {
            lastDayKey = key
            tvDate.text = cap(dateFmt.format(now))
            buildCalendar()
            renderAgenda()
        }
        tickCount++
        if (tickCount % 5 == 0) checkReminders(now)
    }

    private fun checkReminders(now: Date) {
        val key = keyFmt.format(now)
        val hm = hmFmt.format(now)
        val all = loadEvents()
        var changed = false
        val out = ArrayList<Ev>()
        for (e in all) {
            if (!e.notified && e.date == key && e.time.isNotEmpty() && e.time <= hm) {
                changed = true
                out.add(e.copy(notified = true))
                tvBanner.text = "🔔 ${e.time}  ${e.title}\n(toque para fechar)"
                tvBanner.visibility = View.VISIBLE
                wakeUp(60000L)
                try {
                    ToneGenerator(AudioManager.STREAM_ALARM, 100)
                        .startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 3000)
                } catch (ex: Exception) {
                    // sem som
                }
            } else {
                out.add(e)
            }
        }
        if (changed) saveEvents(out)
    }

    // =====================================================================
    //  Microfone sempre ouvindo (palavra de ativação = nome do assistente)
    // =====================================================================

    private fun status(msg: String) {
        tvStatus.text = msg
    }

    private fun startMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!askedMic) {
                askedMic = true
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            } else {
                status("Sem permissão do microfone. Libere em Configurações > Apps > Assistente > Permissões.")
            }
            updateListenLabel()
            return
        }
        micWanted = true
        muteBeeps(true)
        updateListenLabel()
        scheduleRestart(300)
    }

    private fun stopMic() {
        micWanted = false
        handler.removeCallbacks(restartRunnable)
        destroyRecognizer()
        muteBeeps(false)
        if (::tvListen.isInitialized) updateListenLabel()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMic()
            } else {
                status("Sem permissão do microfone: só funciona digitando (toque aqui).")
                updateListenLabel()
            }
        }
    }

    /** Silencia os "plins" do reconhecedor de voz enquanto o app escuta. */
    private fun muteBeeps(mute: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        val streams = intArrayOf(
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_MUSIC
        )
        for (s in streams) {
            try {
                am.adjustStreamVolume(s, dir, 0)
            } catch (ex: Exception) {
                // alguns aparelhos não permitem; sem problema
            }
        }
    }

    private fun scheduleRestart(ms: Long) {
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, ms)
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (ex: Exception) {
            // ignora
        }
        recognizer = null
    }

    private fun startRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status("Este tablet não tem reconhecimento de voz. Instale/atualize o app \"Google\" na Play Store.")
            return
        }
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                recognizer?.setRecognitionListener(recListener)
            }
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            recognizer?.startListening(i)
        } catch (ex: Exception) {
            destroyRecognizer()
            scheduleRestart(3000)
        }
    }

    private val recListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    micWanted = false
                    status("Sem permissão do microfone.")
                    updateListenLabel()
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(200)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    destroyRecognizer()
                    scheduleRestart(1500)
                }
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    status("Sem internet: o reconhecimento de voz precisa de conexão.")
                    destroyRecognizer()
                    scheduleRestart(5000)
                }
                else -> {
                    destroyRecognizer()
                    scheduleRestart(2500)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val l = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (l != null && l.isNotEmpty()) onHeard(l[0])
            scheduleRestart(150)
        }
    }

    /** Remove o nome do assistente da frase. Devolve null se o nome não foi dito. */
    private fun stripName(raw: String): String? {
        val name = deacc(wakeName).trim()
        if (name.isEmpty()) return null
        val m = Regex("\\b" + Regex.escape(name) + "\\b").find(deacc(raw)) ?: return null
        return (raw.substring(0, m.range.first) + " " + raw.substring(m.range.last + 1))
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim(',', '.', '!', '?', ' ')
    }

    private fun onHeard(raw: String) {
        val stripped = stripName(raw)
        // Em espera: só reage se ouvir o nome. Acordado: qualquer frase é comando.
        if (!awake && stripped == null) return
        wakeUp()
        val cmd = stripped ?: raw
        if (cmd.isEmpty()) {
            status("Pois não? Pode falar o comando.")
        } else {
            status("Ouvi: \"$cmd\"")
            handle(cmd)
        }
    }

    // =====================================================================
    //  Comandos
    // =====================================================================

    /** Troca letras acentuadas por simples, mantendo o MESMO tamanho do texto. */
    private fun deacc(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = Character.toLowerCase(ch)
            sb.append(
                when (c) {
                    'á', 'à', 'â', 'ã', 'ä' -> 'a'
                    'é', 'è', 'ê', 'ë' -> 'e'
                    'í', 'ì', 'î', 'ï' -> 'i'
                    'ó', 'ò', 'ô', 'õ', 'ö' -> 'o'
                    'ú', 'ù', 'û', 'ü' -> 'u'
                    'ç' -> 'c'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private fun handle(raw: String) {
        val t = deacc(raw)
        val isEvent = Regex("\\b(agend\\w*|marc\\w*|lembr\\w*|compromisso|evento|anota\\w*)").containsMatchIn(t)
        when {
            t.contains("lanterna") -> {
                val off = Regex("apag|deslig|fech").containsMatchIn(t)
                setTorch(!off)
            }
            isEvent -> createEvent(raw)
            t.contains("lampada") || Regex("\\bluz(es)?\\b").containsMatchIn(t) -> lamps(t)
            t.contains("dormir") || t.contains("boa noite") || t.contains("tela escura") -> goToSleep()
            else -> status("Não entendi: \"$raw\"")
        }
    }

    // ---------- lanterna ----------

    private fun setTorch(on: Boolean) {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            try {
                val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                for (id in cm.cameraIdList) {
                    val has = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                    if (has == true) {
                        cm.setTorchMode(id, on)
                        status(if (on) "Lanterna acesa." else "Lanterna apagada.")
                        return
                    }
                }
            } catch (ex: Exception) {
                // cai para a lanterna de tela
            }
        }
        setScreenLight(on)
    }

    private fun setScreenLight(on: Boolean) {
        screenLightOn = on
        whiteOverlay.visibility = if (on) View.VISIBLE else View.GONE
        applyMode()
        status(
            if (on) "Lanterna de tela acesa (este tablet não tem flash). Toque na tela para apagar."
            else "Lanterna de tela apagada."
        )
        if (!on) wakeUp()
    }

    // ---------- lâmpadas (PRÓXIMA ETAPA) ----------

    private fun lamps(t: String) {
        val off = Regex("apag|deslig").containsMatchIn(t)
        // AQUI entrará o controle das lâmpadas (depende da marca delas).
        status("Entendi: ${if (off) "apagar" else "acender"} a luz. Falta conectar com a marca das suas lâmpadas (próxima etapa).")
    }

    // ---------- criar tarefa por voz ----------

    private fun createEvent(raw: String) {
        val t = deacc(raw)
        val cut = BooleanArray(raw.length)

        fun mark(r: IntRange) {
            for (i in r) if (i >= 0 && i < cut.size) cut[i] = true
        }

        fun find(re: Regex): MatchResult? {
            val m = re.find(t)
            if (m != null) mark(m.range)
            return m
        }

        // ----- DATA -----
        val cal = Calendar.getInstance()
        if (find(Regex("depois de amanha")) != null) {
            cal.add(Calendar.DAY_OF_YEAR, 2)
        } else if (find(Regex("amanha")) != null) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        } else if (find(Regex("\\bhoje\\b")) != null) {
            // fica hoje
        } else {
            val wd = find(Regex("(?:proxim[ao]\\s+)?\\b(domingo|segunda|terca|quarta|quinta|sexta|sabado)(?:-feira)?"))
            if (wd != null) {
                val target = when (wd.groupValues[1]) {
                    "domingo" -> Calendar.SUNDAY
                    "segunda" -> Calendar.MONDAY
                    "terca" -> Calendar.TUESDAY
                    "quarta" -> Calendar.WEDNESDAY
                    "quinta" -> Calendar.THURSDAY
                    "sexta" -> Calendar.FRIDAY
                    else -> Calendar.SATURDAY
                }
                var delta = (target - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
                if (delta == 0) delta = 7
                cal.add(Calendar.DAY_OF_YEAR, delta)
            } else {
                val md = find(Regex("\\bdia\\s+(\\d{1,2})\\b"))
                if (md != null) {
                    val day = md.groupValues[1].toInt().coerceIn(1, 31)
                    if (day < cal.get(Calendar.DAY_OF_MONTH)) {
                        cal.set(Calendar.DAY_OF_MONTH, 1)
                        cal.add(Calendar.MONTH, 1)
                    }
                    val max = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, if (day > max) max else day)
                }
            }
        }

        // ----- HORA -----
        var hh = -1
        var mm = 0
        val m1 = find(Regex("(?:\\bas?\\s+)?\\b(\\d{1,2})\\s*[:h]\\s*(\\d{2})\\b"))
        if (m1 != null) {
            hh = m1.groupValues[1].toInt()
            mm = m1.groupValues[2].toInt()
        } else {
            val m2 = find(Regex("(?:\\bas?\\s+)?\\b(\\d{1,2})\\s*(?:horas?|hs|h)\\b(?:\\s*e\\s*(\\d{1,2})\\b)?"))
            if (m2 != null) {
                hh = m2.groupValues[1].toInt()
                mm = m2.groupValues[2].toIntOrNull() ?: 0
            } else {
                val m3 = find(Regex("\\bas\\s+(\\d{1,2})\\b(?:\\s*e\\s*(\\d{1,2})\\b)?"))
                if (m3 != null) {
                    hh = m3.groupValues[1].toInt()
                    mm = m3.groupValues[2].toIntOrNull() ?: 0
                } else {
                    val m4 = find(Regex("\\b(\\d{1,2})\\b(?=\\s+(?:da|de)\\s+(?:manha|tarde|noite))"))
                    if (m4 != null) {
                        hh = m4.groupValues[1].toInt()
                    } else if (find(Regex("meio[\\s-]?dia")) != null) {
                        hh = 12
                    }
                }
            }
        }
        val per = find(Regex("\\b(?:da|de|a)\\s+(manha|tarde|noite)\\b"))
        if (per != null && hh in 1..11 && per.groupValues[1] != "manha") hh += 12
        if (hh < 0 || hh > 23 || mm < 0 || mm > 59) {
            hh = -1
            mm = 0
        }

        // ----- TÍTULO (o que sobrar da frase) -----
        val lead = Regex(
            "^\\s*(?:(?:por favor|criar|crie|agendar|agende|marcar|marque|lembrar|lembre|lembra|anotar|anote|me|um|uma|novo|nova|na|a|agenda|compromisso|evento|lembrete|de|que|para|em|no)(?:-me)?\\s+)+"
        ).find(t)
        if (lead != null) mark(lead.range)

        val sb = StringBuilder()
        for (i in raw.indices) if (!cut[i]) sb.append(raw[i])
        var title = sb.toString().replace(Regex("\\s+"), " ").trim().trim(',', '.', '-', ' ')
        if (title.isEmpty()) title = "Compromisso"
        title = cap(title)

        // ----- SALVAR -----
        val key = keyFmt.format(cal.time)
        val time = if (hh >= 0) String.format(Locale.US, "%02d:%02d", hh, mm) else ""
        val now = Date()
        val jaPassou = time.isNotEmpty() &&
            (key + " " + time) <= (keyFmt.format(now) + " " + hmFmt.format(now))

        val list = loadEvents()
        list.add(Ev(System.currentTimeMillis(), key, time, title, jaPassou))
        saveEvents(list)

        shown = Calendar.getInstance()
        shown.time = cal.time
        shown.set(Calendar.DAY_OF_MONTH, 1)
        buildCalendar()
        renderAgenda()

        val quando = dayLabel(key) + if (time.isEmpty()) " (dia todo)" else " às $time"
        status("Agendado: $title — $quando")
    }
}
