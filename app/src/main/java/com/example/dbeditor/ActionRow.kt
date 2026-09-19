package com.example.dbeditor

import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * 一行「设置风格」的操作行（对应 res/layout/item_action_row.xml）。
 *
 * 因为布局用 <include> 复用，id 会重复，所以通过 include 的根 View 来取子控件。
 */
class ActionRow(private val root: View) {

    private val icon: ImageView = root.findViewById(R.id.ivIcon)
    private val title: TextView = root.findViewById(R.id.tvTitle)
    private val subtitle: TextView = root.findViewById(R.id.tvSubtitle)

    fun bind(
        titleText: String,
        subtitleText: String? = null,
        iconRes: Int? = null,
        onClick: (() -> Unit)? = null
    ) = apply {
        title.text = titleText
        if (subtitleText.isNullOrBlank()) {
            subtitle.visibility = View.GONE
        } else {
            subtitle.visibility = View.VISIBLE
            subtitle.text = subtitleText
        }
        iconRes?.let { icon.setImageResource(it) }
        root.setOnClickListener { onClick?.invoke() }
    }

    fun setOnClickListener(onClick: () -> Unit) = apply {
        root.setOnClickListener { onClick() }
    }

    fun visible(show: Boolean) = apply {
        root.visibility = if (show) View.VISIBLE else View.GONE
    }

    var visibility: Int
        get() = root.visibility
        set(value) {
            root.visibility = value
        }
}
