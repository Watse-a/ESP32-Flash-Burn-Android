package com.espflash.burn

import android.content.Context
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast

/** 统一样式的小提示：深色圆角气泡，取代系统方角 Toast */
object ToastKit {
    fun show(ctx: Context, msg: String) {
        runCatching {
            val tv = TextView(ctx).apply {
                text = msg
                setTextColor(0xFFEDEDF2.toInt())
                textSize = 13f
                setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10))
                background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_toast_round)
                maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.8f).toInt()
            }
            Toast(ctx.applicationContext).apply {
                view = tv
                duration = Toast.LENGTH_SHORT
                setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(ctx, 96))
            }.show()
        }.onFailure {
            Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
