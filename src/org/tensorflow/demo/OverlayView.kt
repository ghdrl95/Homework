package org.tensorflow.demo

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import java.util.*

/**
 * Created by Shin on 2017-12-17.
 */
class OverlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val callbacks = LinkedList<DrawCallback>()

    /**
     * Interface defining the callback for client classes.
     */
    interface DrawCallback {
        fun drawCallback(canvas: Canvas)
    }

    fun addCallback(callback: DrawCallback) {
        callbacks.add(callback)
    }

    override fun draw(canvas: Canvas) {
        for (callback in callbacks) {
            callback.drawCallback(canvas)
        }
    }
}
