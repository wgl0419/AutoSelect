package com.autoselect.widgeter.recycl.bean

data class SeekBean(
    var title: String = "Seek", var min: Float = Float.MIN_VALUE, var max: Float = Float.MAX_VALUE,
    var left: String = "", var right: String = "", var isHint: Boolean = true
)