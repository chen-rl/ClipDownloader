package com.clipdownloader.view

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** 高度跟随宽度的 FrameLayout：作品库卡片与文件网格里的正方形封面位 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredWidth)
    }
}
