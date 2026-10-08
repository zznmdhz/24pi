package com.twentyfourpi.lifelog.util

import kotlin.math.*

data class Coordinate(val latitude: Double, val longitude: Double)

fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val radius = 6_371_000.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val x = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
    return 2 * radius * asin(sqrt(x))
}

// 高德使用 GCJ-02；仅在中国大陆坐标范围内转换，原始点仍以系统坐标保存在本机。
fun wgs84ToGcj02(lat: Double, lon: Double): Coordinate {
    if (lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271) return Coordinate(lat, lon)
    var dLat = transformLat(lon - 105.0, lat - 35.0)
    var dLon = transformLon(lon - 105.0, lat - 35.0)
    val radLat = lat / 180.0 * Math.PI
    var magic = sin(radLat)
    magic = 1 - 0.00669342162296594323 * magic * magic
    val sqrtMagic = sqrt(magic)
    dLat = dLat * 180.0 / ((6_378_245.0 * (1 - 0.00669342162296594323)) / (magic * sqrtMagic) * Math.PI)
    dLon = dLon * 180.0 / (6_378_245.0 / sqrtMagic * cos(radLat) * Math.PI)
    return Coordinate(lat + dLat, lon + dLon)
}

private fun transformLat(x: Double, y: Double): Double =
    -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x)) +
        (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0 +
        (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0 +
        (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0

private fun transformLon(x: Double, y: Double): Double =
    300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x)) +
        (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0 +
        (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0 +
        (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
