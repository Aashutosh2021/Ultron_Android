package com.flexfly.ultron.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

object CountdownOverlay {

    private var overlayView: TextView? = null
    private var windowManager: WindowManager? = null

    fun show(context: Context, seconds: Int, onComplete: () -> Unit) {
        hide(context)
        val textView = TextView(context)
        textView.textSize = 120.0f
        textView.setTextColor(Color.WHITE)
        textView.gravity = Gravity.CENTER
        textView.setShadowLayer(10.0f, 0.0f, 0.0f, Color.BLACK)
        val wm = context.getSystemService("window") as WindowManager
        // RECOVERY NOTE: constants restored — 2038 = TYPE_APPLICATION_OVERLAY,
        // 280 = FLAG_NOT_FOCUSABLE(8) | FLAG_NOT_TOUCHABLE(16) | FLAG_LAYOUT_IN_SCREEN(256),
        // -3 = PixelFormat.TRANSLUCENT.
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.CENTER
        wm.addView(textView, layoutParams)
        overlayView = textView
        windowManager = wm
        startCountdown(context, textView, seconds, onComplete)
    }

    fun hide(context: Context) {
        try {
            val textView = overlayView
            val wm = windowManager
            if (textView != null && wm != null) {
                wm.removeView(textView)
            }
        } catch (unused: Exception) {
        }
        overlayView = null
        windowManager = null
    }

    private fun startCountdown(
        context: Context,
        textView: TextView,
        seconds: Int,
        onComplete: () -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        var remaining = seconds
        val runnable = object : Runnable {
            override fun run() {
                if (remaining > 0) {
                    textView.text = remaining.toString()
                    textView.alpha = 1.0f
                    textView.animate().alpha(0.3f).setDuration(800L).start()
                    remaining--
                    handler.postDelayed(this, 1000L)
                } else {
                    textView.text = "✓"
                    textView.alpha = 1.0f
                    handler.postDelayed({
                        hide(context)
                        onComplete()
                    }, 300L)
                }
            }
        }
        handler.post(runnable)
    }
}
