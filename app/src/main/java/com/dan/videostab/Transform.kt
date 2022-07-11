package com.dan.videostab

import org.opencv.core.Mat
import kotlin.math.cos
import kotlin.math.sin


class Transform(val x: Double, val y: Double, val a: Double) {
    fun getTransform(T: Mat) {
        T.put(0, 0, cos(a))
        T.put(0, 1, -sin(a))
        T.put(1, 0, sin(a))
        T.put(1, 1, cos(a))

        T.put(0, 2, x)
        T.put(1, 2, y)
    }
}


fun List<Transform>.cumsum(): List<Transform> {
    val result = mutableListOf<Transform>()

    var a = 0.0
    var x = 0.0
    var y = 0.0

    for (transform in this) {
        x += transform.x
        y += transform.y
        a += transform.a
        result.add(Transform(x, y, a))
    }

    return result.toList()
}


fun List<Transform>.smooth(radius: Int): List<Transform> {
    val result = mutableListOf<Transform>()

    for(i in this.indices) {
        var sumX = 0.0
        var sumY = 0.0
        var sumA = 0.0
        var count = 0

        for(j in -radius..radius) {
            if(i+j >= 0 && i+j < this.size) {
                sumX += this[i+j].x
                sumY += this[i+j].y
                sumA += this[i+j].a
                count++
            }
        }

        result.add(Transform(sumX / count, sumY / count, sumA / count))
    }

    return result.toList()
}