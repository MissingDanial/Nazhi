package com.nazhi.app.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun Long.toLocalDateId(zoneId: ZoneId = ZoneId.systemDefault()): String {
    return Instant.ofEpochMilli(this)
        .atZone(zoneId)
        .toLocalDate()
        .format(DateTimeFormatter.ISO_LOCAL_DATE)
}

fun todayDateId(): String {
    return System.currentTimeMillis().toLocalDateId()
}

fun monthStartDateId(dateId: String): String {
    return YearMonth.from(LocalDate.parse(dateId)).atDay(1).toString()
}

fun nextMonthStartDateId(dateId: String): String {
    return YearMonth.from(LocalDate.parse(dateId)).plusMonths(1).atDay(1).toString()
}

fun previousMonthDateId(dateId: String): String {
    return YearMonth.from(LocalDate.parse(dateId)).minusMonths(1).atDay(1).toString()
}

fun nextMonthDateId(dateId: String): String {
    return YearMonth.from(LocalDate.parse(dateId)).plusMonths(1).atDay(1).toString()
}

fun monthTitle(dateId: String): String {
    val month = YearMonth.from(LocalDate.parse(dateId))
    return "${month.year}年${month.monthValue}月"
}

fun displayDateLabel(dateId: String, todayId: String = todayDateId()): String {
    val date = LocalDate.parse(dateId)
    val today = LocalDate.parse(todayId)
    return when (date) {
        today -> "今天 $dateId"
        today.minusDays(1) -> "昨天 $dateId"
        else -> dateId
    }
}

fun localDateFromId(dateId: String): LocalDate {
    return LocalDate.parse(dateId)
}

