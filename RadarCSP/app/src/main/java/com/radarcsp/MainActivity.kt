package com.radarcsp

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var toggle: Switch
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(20), px(20), px(20))
        }

        fun label(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, px(6), 0, px(6))
        }

        fun legend(text: String, bg: Int, fg: Int) = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(fg)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(bg)
            setPadding(px(14), px(10), px(14), px(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(6) }
        }

        root.addView(label("RADAR DE CORRIDAS", 26f, Color.parseColor("#2ECC71"), true))

        status = label("", 16f, Color.WHITE, true)
        root.addView(status)

        toggle = Switch(this).apply {
            text = "Radar ligado"
            setTextColor(Color.WHITE)
            isChecked = RadarPrefs.isOn(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> RadarPrefs.setOn(this@MainActivity, checked) }
            setPadding(0, px(8), 0, px(8))
        }
        root.addView(toggle)

        root.addView(Button(this).apply {
            text = "ABRIR AJUSTES DE ACESSIBILIDADE"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        root.addView(label("Cores (valor por km):", 15f, Color.parseColor("#AAB3C0")))
        root.addView(legend("PÉSSIMA  •  abaixo de R$ 1,50/km", 0xFFD32F2F.toInt(), Color.WHITE))
        root.addView(legend("MAIS OU MENOS  •  R$ 1,50 a R$ 1,99/km", 0xFFFBC02D.toInt(), Color.BLACK))
        root.addView(legend("ÓTIMA  •  R$ 2,00/km ou mais", 0xFF2E7D32.toInt(), Color.WHITE))

        root.addView(
            label(
                "Como usar: ligue o Radar de Corridas em Acessibilidade (Apps instalados). " +
                    "Se a chave estiver cinza: Ajustes > Apps > Radar de Corridas > ⋮ > Permitir configurações restritas. " +
                    "Depois abra o Uber Driver: a faixa colorida aparece quando chegar uma oferta.",
                14f, Color.parseColor("#AAB3C0")
            )
        )

        root.addView(label("LOG", 18f, Color.parseColor("#2ECC71"), true))
        logView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
        }
        root.addView(logView)

        root.addView(Button(this).apply {
            text = "LIMPAR LOG"
            setOnClickListener {
                RadarLog.clear()
                render()
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        toggle.isChecked = RadarPrefs.isOn(this)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun render() {
        status.text = if (isAccessibilityOn()) {
            "Acessibilidade: LIGADA ✅"
        } else {
            "Acessibilidade: DESLIGADA ❌ (use o botão abaixo)"
        }
        val log = RadarLog.all()
        logView.text = if (log.isEmpty()) "Nenhum evento ainda." else log
    }

    private fun isAccessibilityOn(): Boolean {
        val c = ComponentName(this, RadarService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            it.equals(c.flattenToString(), ignoreCase = true) ||
                it.equals(c.flattenToShortString(), ignoreCase = true)
        }
    }
}
