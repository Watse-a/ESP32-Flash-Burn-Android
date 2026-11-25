package com.espflash.burn

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** 统一弹窗：全部跟「设置烧录参数」一个样式（深色圆角卡片 + 右下淡蓝按钮） */
object Dialogs {

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun card(ctx: Context, title: String, content: View): LinearLayout {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_dialog)
            setPadding(dp(ctx, 18), dp(ctx, 22), dp(ctx, 18), dp(ctx, 10))
        }
        root.addView(TextView(ctx).apply {
            text = title
            textSize = 20f
            setTextColor(0xFFE9E6F4.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(ctx, 6), 0, 0, dp(ctx, 16))
        })
        root.addView(content, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun buttonRow(ctx: Context, buttons: List<Pair<String, (() -> Unit)?>>): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(ctx, 6), 0, 0)
        }
        buttons.forEach { (label, action) ->
            row.addView(TextView(ctx).apply {
                text = label
                textSize = 15f
                setTextColor(if (action == null) 0xFFAECBFA.toInt() else 0xFFAECBFA.toInt())
                setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 10), dp(ctx, 12))
                isClickable = true
                isFocusable = true
                setBackgroundDrawable(MoreMenu.accessRipple(ctx))
                setOnClickListener { action?.invoke() }
            })
        }
        return row
    }

    private fun show(ctx: Context, root: LinearLayout): Dialog {
        val d = Dialog(ctx)
        d.setContentView(root)
        d.setCanceledOnTouchOutside(true)
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.CENTER)
            val m = ctx.resources.displayMetrics
            w.setLayout((m.widthPixels - dp(ctx, 48)).coerceAtLeast(dp(ctx, 220)), ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setDimAmount(0.6f)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        d.show()
        return d
    }

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    /** 纯文字说明 */
    fun message(ctx: Context, title: String, msg: String, okText: String = "确定", onOk: (() -> Unit)? = null) {
        val body = TextView(ctx).apply {
            text = msg
            textSize = 14.5f
            setTextColor(0xFFC9C9D2.toInt())
            setLineSpacing(dp(ctx, 4).toFloat(), 1f)
            setPadding(dp(ctx, 6), 0, dp(ctx, 6), dp(ctx, 6))
        }
        val root = card(ctx, title, body)
        var dlg: Dialog? = null
        root.addView(buttonRow(ctx, listOf(okText to {
            dlg?.dismiss()
            onOk?.invoke()
        })))
        dlg = show(ctx, root)
    }

    /** 二次确认 */
    fun confirm(ctx: Context, title: String, msg: String, okText: String = "确定",
                onCancel: (() -> Unit)? = null, onOk: () -> Unit) {
        val body = TextView(ctx).apply {
            text = msg
            textSize = 14.5f
            setTextColor(0xFFC9C9D2.toInt())
            setLineSpacing(dp(ctx, 4).toFloat(), 1f)
            setPadding(dp(ctx, 6), 0, dp(ctx, 6), dp(ctx, 6))
        }
        val root = card(ctx, title, body)
        var dlg: Dialog? = null
        root.addView(buttonRow(ctx, listOf(
            "取消" to { dlg?.dismiss(); onCancel?.invoke() },
            okText to {
                dlg?.dismiss()
                onOk()
            }
        )))
        dlg = show(ctx, root)
    }

    /** 列表选择（点击某项回调下标） */
    fun list(ctx: Context, title: String, items: List<String>, onPick: (Int) -> Unit) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var dlg: Dialog? = null
        items.forEachIndexed { i, label ->
            box.addView(TextView(ctx).apply {
                text = label
                textSize = 16f
                setTextColor(0xFFE9E6F4.toInt())
                setPadding(dp(ctx, 10), dp(ctx, 15), dp(ctx, 10), dp(ctx, 15))
                isClickable = true
                isFocusable = true
                setBackgroundDrawable(MoreMenu.accessRipple(ctx))
                setOnClickListener {
                    dlg?.dismiss()
                    onPick(i)
                }
            })
        }
        val scroll = android.widget.ScrollView(ctx).apply {
            addView(box, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val root = card(ctx, title, scroll)
        root.addView(buttonRow(ctx, listOf("取消" to { dlg?.dismiss() })))
        dlg = show(ctx, root)
    }

    /** 自定义内容（表单等），可选「取消 / 确定」 */
    fun custom(ctx: Context, title: String, content: View, okText: String = "确定",
               cancelText: String? = "取消", onOk: () -> Unit) {
        val root = card(ctx, title, content)
        var dlg: Dialog? = null
        val buttons = ArrayList<Pair<String, (() -> Unit)?>>()
        if (cancelText != null) buttons.add(cancelText to { dlg?.dismiss() })
        buttons.add(okText to {
            dlg?.dismiss()
            onOk()
        })
        root.addView(buttonRow(ctx, buttons))
        dlg = show(ctx, root)
    }
}
