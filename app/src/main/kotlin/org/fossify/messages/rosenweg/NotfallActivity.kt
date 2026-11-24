package org.fossify.messages.rosenweg

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/** Rotes Vollbild beim Notfall (auch auf dem Sperrbildschirm). "Verstanden" stoppt die Sirene. */
class NotfallActivity : Activity() {

    companion object {
        private var offen = WeakReference<NotfallActivity>(null)

        /** bei Entwarnung */
        fun schliesseAlle() {
            offen.get()?.let { a -> a.runOnUiThread { a.finish() } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        offen = WeakReference(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val (titel, absender, text) = RosenwegNotfall.letzterAlarm ?: Triple("Notfall", "", "")
        val dp = resources.displayMetrics.density
        fun tv(t: String, sp: Float, fett: Boolean = false) = TextView(this).apply {
            this.text = t
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            gravity = Gravity.CENTER
            if (fett) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        val inhalt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFD32F2F.toInt())
            setPadding((24 * dp).toInt(), (48 * dp).toInt(), (24 * dp).toInt(), (48 * dp).toInt())
            addView(tv("🚨 NOTFALL", 40f, true))
            addView(tv(listOf(titel, absender).filter { it.isNotBlank() }.joinToString(" · "), 18f))
            addView(tv(text, 26f, true))
            addView(Button(this@NotfallActivity).apply {
                this.text = "Verstanden"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setOnClickListener {
                    RosenwegNotfall.quittieren(this@NotfallActivity)
                    finish()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (32 * dp).toInt()
            })
        }
        setContentView(inhalt)
    }

    override fun onDestroy() {
        if (offen.get() === this) offen = WeakReference(null)
        super.onDestroy()
    }
}
