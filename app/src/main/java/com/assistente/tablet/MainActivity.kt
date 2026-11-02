package com.assistente.tablet

import android.app.Activity
import android.content.ActivityNotFoundException
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
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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

    // ---------- cores ----------
    private val cBg = Color.parseColor("#FBFBF6")
    private val cText = Color.parseColor("#1E2430")
    private val cGray = Color.parseColor("#6B7280")
    private val cAccent = Color.parseColor("#1565C0")
    private val cCell = Color.parseColor("#EFEFE6")

    // ---------- formatos ----------
    private val ptBR = Locale("pt", "BR")
    private val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val hmFmt = SimpleDateFormat("HH:mm", Locale.US)
    private val clockFmt = SimpleDateFormat("HH:mm:ss", ptBR)
    private val dateFmt = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", ptBR)
    private val monthFmt = SimpleDateFormat("MMMM 'de' yyyy", ptBR)

    // ---------- telas ----------
    private lateinit var tvClock: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvBanner: TextView
    private lateinit var tvMonth: TextView
    private lateinit var grid: GridLayout
    private lateinit var agendaBox: LinearLayout
    private lateinit var input: EditText
    private lateinit var whiteOverlay: View

    // ---------- estado ----------
    private val handler = Handler(Looper.getMainLooper())
    private var shown: Calendar = firstOfThisMonth()
    private var lastDayKey = ""
    private var tickCount = 0

    private val prefs by lazy { getSharedPreferences("assistente", Context.MODE_PRIVATE) }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000)
        }
    }

    // =====================================================================
    //  Ciclo de vida
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
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

    private fun buildUi() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = FrameLayout(this)
        root.setBackgroundColor(cBg)

        val scroll = ScrollView(this)
        scroll.isFillViewport = true

        val main = LinearLayout(this)
        main.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        main.setPadding(dp(20), dp(16), dp(20), dp(16))

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL

        if (landscape) {
            left.setPadding(0, 0, dp(14), 0)
            main.addView(left, LinearLayout.LayoutParams(0, wrap, 1f))
            main.addView(right, LinearLayout.LayoutParams(0, wrap, 1.25f))
        } else {
            main.addView(left, LinearLayout.LayoutParams(match, wrap))
            main.addView(right, LinearLayout.LayoutParams(match, wrap))
        }

        // ----- coluna da esquerda: relógio, microfone, status, agenda -----
        tvClock = TextView(this)
        tvClock.textSize = 76f
        tvClock.setTypeface(null, Typeface.BOLD)
        tvClock.setTextColor(cText)
        left.addView(tvClock, lp())

        tvDate = TextView(this)
        tvDate.textSize = 22f
        tvDate.setTextColor(cGray)
        left.addView(tvDate, lp())

        val mic = Button(this)
        mic.text = "🎤  Toque e fale"
        mic.textSize = 28f
        mic.setTextColor(Color.WHITE)
        mic.setBackgroundColor(cAccent)
        mic.setOnClickListener { listen() }
        left.addView(mic, LinearLayout.LayoutParams(match, dp(96)).apply { topMargin = dp(18) })

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        input = EditText(this)
        input.hint = "ou digite o comando aqui"
        input.setSingleLine(true)
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setOnEditorActionListener { _, _, _ ->
            submitText()
            true
        }
        val ok = Button(this)
        ok.text = "OK"
        ok.setOnClickListener { submitText() }
        row.addView(input, LinearLayout.LayoutParams(0, wrap, 1f))
        row.addView(ok, LinearLayout.LayoutParams(wrap, wrap))
        left.addView(row, lp(8))

        tvStatus = TextView(this)
        tvStatus.textSize = 20f
        tvStatus.setTextColor(cAccent)
        tvStatus.text = "Pronto. Toque no microfone e diga um comando."
        tvStatus.setPadding(dp(4), dp(10), dp(4), dp(10))
        left.addView(tvStatus, lp(4))

        tvBanner = TextView(this)
        tvBanner.textSize = 26f
        tvBanner.setTextColor(cText)
        tvBanner.setBackgroundColor(Color.parseColor("#FFE9A8"))
        tvBanner.setPadding(dp(14), dp(14), dp(14), dp(14))
        tvBanner.visibility = View.GONE
        tvBanner.setOnClickListener { tvBanner.visibility = View.GONE }
        left.addView(tvBanner, lp(4))

        val hints = TextView(this)
        hints.textSize = 16f
        hints.setTextColor(cGray)
        hints.text = "Exemplos:\n" +
            "• \"acender lanterna\" / \"apagar lanterna\"\n" +
            "• \"criar agenda amanhã às 10 horas dentista\"\n" +
            "• \"marcar dia 15 às 14:30 reunião\"\n" +
            "• \"lembrar sexta às 8 da manhã pagar conta\"\n" +
            "• \"acender a luz da sala\" (em breve)"
        left.addView(hints, lp(8))

        val agendaTitle = TextView(this)
        agendaTitle.text = "Próximos compromissos"
        agendaTitle.textSize = 22f
        agendaTitle.setTypeface(null, Typeface.BOLD)
        agendaTitle.setTextColor(cText)
        left.addView(agendaTitle, lp(18))

        agendaBox = LinearLayout(this)
        agendaBox.orientation = LinearLayout.VERTICAL
        left.addView(agendaBox, lp(6))

        // ----- coluna da direita: calendário do mês -----
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
        tvMonth.textSize = 28f
        tvMonth.setTypeface(null, Typeface.BOLD)
        tvMonth.setTextColor(cText)
        tvMonth.gravity = Gravity.CENTER

        nav.addView(prev, LinearLayout.LayoutParams(wrap, wrap))
        nav.addView(tvMonth, LinearLayout.LayoutParams(0, wrap, 1f))
        nav.addView(next, LinearLayout.LayoutParams(wrap, wrap))
        nav.addView(today, LinearLayout.LayoutParams(wrap, wrap))
        right.addView(nav, lp())

        grid = GridLayout(this)
        grid.columnCount = 7
        right.addView(grid, lp(10))

        scroll.addView(main, FrameLayout.LayoutParams(match, wrap))
        root.addView(scroll, FrameLayout.LayoutParams(match, match))

        // Tela branca usada como "lanterna de tela" quando o tablet não tem flash
        whiteOverlay = View(this)
        whiteOverlay.setBackgroundColor(Color.WHITE)
        whiteOverlay.visibility = View.GONE
        whiteOverlay.setOnClickListener { setScreenLight(false) }
        root.addView(whiteOverlay, FrameLayout.LayoutParams(match, match))

        setContentView(root)
    }

    private fun cell(text: String, bold: Boolean, fg: Int, bgc: Int?): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.gravity = Gravity.CENTER
        tv.textSize = 20f
        tv.setTextColor(fg)
        if (bold) tv.setTypeface(null, Typeface.BOLD)
        if (bgc != null) tv.setBackgroundColor(bgc)
        val p = GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED),
            GridLayout.spec(GridLayout.UNDEFINED, 1f)
        )
        p.width = 0
        p.height = dp(60)
        p.setMargins(dp(2), dp(2), dp(2), dp(2))
        tv.layoutParams = p
        return tv
    }

    private fun cap(s: String): String = s.replaceFirstChar { it.uppercase() }

    // =====================================================================
    //  Calendário e agenda
    // =====================================================================

    private fun buildCalendar() {
        tvMonth.text = cap(monthFmt.format(shown.time))
        grid.removeAllViews()

        val heads = arrayOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb")
        for (h in heads) grid.addView(cell(h, true, cGray, null))

        val first = shown.clone() as Calendar
        first.set(Calendar.DAY_OF_MONTH, 1)
        val offset = first.get(Calendar.DAY_OF_WEEK) - 1
        val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val todayKey = keyFmt.format(Date())
        val withEvents = loadEvents().map { it.date }.toSet()

        for (i in 0 until offset) grid.addView(cell("", false, cText, null))

        for (d in 1..daysInMonth) {
            val k = String.format(
                Locale.US, "%04d-%02d-%02d",
                first.get(Calendar.YEAR), first.get(Calendar.MONTH) + 1, d
            )
            val isToday = k == todayKey
            val dot = if (withEvents.contains(k)) "\n•" else ""
            grid.addView(
                cell(
                    "$d$dot",
                    isToday,
                    if (isToday) Color.WHITE else cText,
                    if (isToday) cAccent else cCell
                )
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
        agendaBox.removeAllViews()
        val todayKey = keyFmt.format(Date())
        val list = loadEvents()
            .filter { it.date >= todayKey }
            .sortedWith(compareBy<Ev>({ it.date }, { it.time }))
            .take(30)

        if (list.isEmpty()) {
            val tv = TextView(this)
            tv.text = "Nenhum compromisso. Diga, por exemplo: \"criar agenda amanhã às 9 horas médico\"."
            tv.textSize = 17f
            tv.setTextColor(cGray)
            agendaBox.addView(tv, lp())
            return
        }

        for (e in list) {
            val tv = TextView(this)
            val hora = if (e.time.isEmpty()) "dia todo" else e.time
            tv.text = "${dayLabel(e.date)} • $hora — ${e.title}"
            tv.textSize = 20f
            tv.setTextColor(cText)
            tv.setBackgroundColor(cCell)
            tv.setPadding(dp(10), dp(12), dp(10), dp(12))
            tv.setOnLongClickListener {
                deleteEvent(e.id)
                true
            }
            agendaBox.addView(tv, lp(6))
        }
        val dica = TextView(this)
        dica.text = "Segure o dedo sobre um compromisso para apagar."
        dica.textSize = 14f
        dica.setTextColor(cGray)
        agendaBox.addView(dica, lp(8))
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
        status("Compromisso apagado.")
    }

    // =====================================================================
    //  Relógio e lembretes
    // =====================================================================

    private fun tick() {
        val now = Date()
        tvClock.text = clockFmt.format(now)
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
    //  Voz e comandos
    // =====================================================================

    private fun status(msg: String) {
        tvStatus.text = msg
    }

    private fun submitText() {
        val s = input.text.toString().trim()
        if (s.isNotEmpty()) {
            input.setText("")
            handle(s)
        }
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Diga o comando")
        try {
            startActivityForResult(i, 100)
        } catch (ex: ActivityNotFoundException) {
            status("Este tablet não tem reconhecimento de voz. Instale ou atualize o app \"Google\" na Play Store. Enquanto isso, digite o comando.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            val r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (r != null && r.isNotEmpty()) handle(r[0])
        }
    }

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
        whiteOverlay.visibility = if (on) View.VISIBLE else View.GONE
        val p = window.attributes
        p.screenBrightness = if (on) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = p
        status(
            if (on) "Lanterna de tela acesa (este tablet não tem flash). Toque na tela para apagar."
            else "Lanterna de tela apagada."
        )
    }

    // ---------- lâmpadas (PRÓXIMA ETAPA) ----------

    private fun lamps(t: String) {
        val off = Regex("apag|deslig").containsMatchIn(t)
        // AQUI entrará o controle das lâmpadas (depende da marca delas).
        status("Entendi: ${if (off) "apagar" else "acender"} a luz. Falta conectar com a marca das suas lâmpadas (próxima etapa).")
    }

    // ---------- criar compromisso por voz ----------

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
