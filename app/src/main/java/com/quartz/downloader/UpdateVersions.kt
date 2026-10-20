package com.quartz.downloader

object UpdateVersions {
    fun valid(value: String) = Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}").matches(value)
    fun compare(first: String, second: String): Int {
        require(valid(first) && valid(second))
        val a = first.split('.').map(String::toInt); val b = second.split('.').map(String::toInt)
        for (i in 0..2) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }
}
