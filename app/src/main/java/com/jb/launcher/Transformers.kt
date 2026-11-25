package com.jb.launcher

import android.view.View
import androidx.viewpager2.widget.ViewPager2

object Transformers {
    private fun reset(v: View) {
        v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f; v.rotation = 0f; v.rotationX = 0f; v.rotationY = 0f
        v.translationX = 0f; v.translationY = 0f; v.pivotX = v.width / 2f; v.pivotY = v.height / 2f
    }

    fun get(name: String): ViewPager2.PageTransformer = ViewPager2.PageTransformer { v, pos ->
        reset(v)
        if (name != "simple") {
            if (Math.abs(pos) >= 1f) { v.alpha = 0f; return@PageTransformer }
            val a = Math.abs(pos)
            when (name) {
                "cube" -> { v.cameraDistance = 20000f; v.pivotX = if (pos < 0) v.width.toFloat() else 0f; v.rotationY = 90f * pos }
                "card_stack" -> if (pos > 0) {
                    v.translationX = -v.width * pos; v.scaleX = 1f - 0.25f * pos; v.scaleY = 1f - 0.25f * pos; v.alpha = 1f - pos
                }
                "tablet" -> { v.cameraDistance = 20000f; v.rotationY = -40f * pos; v.translationX = -v.width * pos * 0.15f }
                "rotate" -> { v.pivotY = v.height.toFloat(); v.rotation = -15f * pos }
                "accordion" -> { v.pivotX = if (pos < 0) v.width.toFloat() else 0f; v.scaleX = 1f - a }
                "flip" -> {
                    v.cameraDistance = 20000f; v.translationX = -v.width * pos; v.rotationY = -180f * pos
                    v.alpha = if (a > 0.5f) 0f else 1f
                }
                "zoom_fade" -> { val s = 1f - 0.3f * a; v.scaleX = s; v.scaleY = s; v.alpha = 1f - a }
                "windmill" -> { v.pivotY = v.height.toFloat(); v.rotation = 25f * pos }
                "fade" -> { v.translationX = -v.width * pos; v.alpha = 1f - a }
                "throw" -> if (pos < 0) { v.rotation = 30f * pos; v.translationY = -v.height * 0.3f * a; v.alpha = 1f - a }
                "glass" -> { v.scaleX = 1f - 0.12f * a; v.scaleY = 1f - 0.12f * a; v.alpha = 1f - 0.6f * a }
            }
        }
    }
}
