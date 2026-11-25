package com.espflash.burn

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/** 右上角三个点的深色圆角菜单：图标 + 文字 */
object MoreMenu {

    data class Item(val icon: Int, val label: String, val action: () -> Unit)

    fun show(anchor: View, items: List<Item>) {
        val ctx = anchor.context
        val root = android.view.LayoutInflater.from(ctx).inflate(R.layout.popup_menu, null)
        val box = root.findViewById<LinearLayout>(R.id.menuBox)
        val pw = PopupWindow(
            root, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true
        )

        items.forEach { item ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 18), dp(ctx, 13), dp(ctx, 26), dp(ctx, 13))
                isClickable = true
                setBackgroundDrawable(MoreMenu.accessRipple(ctx))
            }
            row.addView(ImageView(ctx).apply {
                setImageResource(item.icon)
                setColorFilter(0xFFE6E6E8.toInt())
            }, LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)))
            row.addView(TextView(ctx).apply {
                text = item.label
                textSize = 15f
                setTextColor(0xFFE6E6E8.toInt())
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(ctx, 12) })
            row.setOnClickListener { pw.dismiss(); item.action() }
            box.addView(row, LinearLayout.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.elevation = dp(ctx, 8).toFloat()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val w = root.measuredWidth.coerceAtLeast(1)
        pw.showAsDropDown(anchor, anchor.width - w, dp(ctx, 6))
    }


    /** ?attr/selectableItemBackground 是属性 id，不能直接当 drawable 用 */
    fun accessRipple(ctx: Context): android.graphics.drawable.Drawable? = runCatching {
        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        if (tv.resourceId != 0) ctx.getDrawable(tv.resourceId) else null
    }.getOrNull()

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
