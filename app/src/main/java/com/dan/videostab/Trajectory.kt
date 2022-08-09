package com.dan.videostab

import org.opencv.core.Mat
import kotlin.math.cos
import kotlin.math.sin


class Trajectory(val x: List<Double>, val y: List<Double>, val a: List<Double>) {
    val size: Int
        get() = x.size

    fun getTransform(index: Int, T: Mat) {
        val a = this.a[index]

        T.put(0, 0, cos(a))
        T.put(0, 1, -sin(a))
        T.put(1, 0, sin(a))
        T.put(1, 1, cos(a))

        T.put(0, 2, x[index])
        T.put(1, 2, y[index])
    }
}


fun List<Double>.delta(to: List<Double>): List<Double> {
    val result = mutableListOf<Double>()
    for (index in this.indices) result.add(to[index] - this[index])
    return result.toList()
}


fun List<Double>.distribute(): List<Double> {
    val result = mutableListOf<Double>()
    val first = this.first()
    val last = this.last()
    for (index in this.indices) result.add( first + (last - first) * index / (size - 1) )
    return result.toList()
}


fun List<Double>.movingAverage(windowSize: Int): List<Double> {
    val result = mutableListOf<Double>()

    for(i in this.indices) {
        var sum = 0.0
        val from = if (i >= windowSize) (i - windowSize) else 0
        val to = if ((i + windowSize) < size) (i + windowSize) else (size - 1)
        val count = to - from + 1
        for(j in from..to) sum += this[j]
        result.add(sum / count)
    }

    return result.toList()
}