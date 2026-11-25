package com.espflash.burn

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/** 烧录结果弹窗：底部抽屉样式，成功/失败两种 */
object FlashResult {

    private fun sheet(ctx: Context, layoutRes: Int): Pair<Dialog, View> {
        val d = Dialog(ctx)
        val v = LayoutInflater.from(ctx).inflate(layoutRes, null)
        d.setContentView(v)
        d.setCanceledOnTouchOutside(true)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setDimAmount(0.6f)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        d.show()
        return d to v
    }

    /**
     * 成功弹窗：每个固件一行，左边文件名，最右边版本号。
     * items 为空（整片擦除）时只显示一行「芯片擦除」。
     */
    fun showOk(ctx: Context, seconds: Double, items: List<Pair<String, String?>>, onSerial: () -> Unit) {
        val (d, v) = sheet(ctx, R.layout.dialog_flash_ok)
        v.findViewById<TextView>(R.id.subtitle).text = String.format("耗时 %.1fs", seconds)
        val rows = v.findViewById<android.widget.LinearLayout>(R.id.fwRows)
        rows.removeAllViews()
        val list = items.ifEmpty { listOf("芯片擦除" to null) }
        list.forEachIndexed { i, (name, ver) ->
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, if (i == 0) 0 else dp(ctx, 8), 0, 0)
            }
            val left = TextView(ctx).apply {
                text = name
                setTextColor(0xFFB9B9C4.toInt())
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            row.addView(left, android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val right = TextView(ctx).apply {
                text = ver ?: "—"
                setTextColor(0xFFE9E9F0.toInt())
                textSize = 14f
                maxLines = 1
            }
            row.addView(right, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
            rows.addView(row, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        v.findViewById<TextView>(R.id.btnDone).setOnClickListener { d.dismiss() }
        v.findViewById<View>(R.id.btnSerial).setOnClickListener {
            d.dismiss()
            onSerial()
        }
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    fun showFail(ctx: Context, seconds: Double, reason: String, raw: String) {
        val (d, v) = sheet(ctx, R.layout.dialog_flash_error)
        v.findViewById<TextView>(R.id.subtitle).text = String.format("耗时 %.1fs", seconds)
        v.findViewById<TextView>(R.id.reason).text = reason
        val detail = v.findViewById<TextView>(R.id.detail)
        detail.text = raw
        detail.movementMethod = ScrollingMovementMethod()
        val arrow = v.findViewById<android.widget.ImageView>(R.id.detailArrow)
        v.findViewById<View>(R.id.detailToggle).setOnClickListener {
            val show = detail.visibility != View.VISIBLE
            detail.visibility = if (show) View.VISIBLE else View.GONE
            arrow.rotation = if (show) 180f else 0f
        }
        v.findViewById<TextView>(R.id.btnClose).setOnClickListener { d.dismiss() }
        v.findViewById<TextView>(R.id.btnCopy).setOnClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("espburn error", raw))
            Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
        }
    }
}
