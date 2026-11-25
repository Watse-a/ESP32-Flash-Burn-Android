package com.espflash.burn

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** 圆角深色弹窗：选择设备 / 选择串口都走这里，风格统一 */
object DevicePicker {

    fun show(ctx: Context, title: String, items: List<String>, onPick: ((Int) -> Unit)?) {
        val d = Dialog(ctx)
        val v = android.view.LayoutInflater.from(ctx).inflate(R.layout.dialog_picker, null)
        v.findViewById<TextView>(R.id.dialogTitle).text = title
        val box = v.findViewById<LinearLayout>(R.id.dialogItems)

        items.forEachIndexed { i, label ->
            val row = TextView(ctx).apply {
                text = label
                textSize = 16f
                setTextColor(0xFFE9E6F4.toInt())
                setPadding(dp(ctx, 24), dp(ctx, 16), dp(ctx, 24), dp(ctx, 16))
                isClickable = true
                isFocusable = true
                setBackgroundDrawable(MoreMenu.accessRipple(ctx))
                setOnClickListener {
                    d.dismiss()
                    onPick?.invoke(i)
                }
            }
            box.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        v.findViewById<TextView>(R.id.dialogCancel).setOnClickListener { d.dismiss() }

        d.setContentView(v)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.CENTER)
            val metrics = ctx.resources.displayMetrics
            w.setLayout((metrics.widthPixels - dp(ctx, 48)).coerceAtLeast(dp(ctx, 200)), ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setDimAmount(0.6f)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        d.show()
    }

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
