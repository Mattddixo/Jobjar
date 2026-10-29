package com.mattdixon.jobjar.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromPriority(priority: Priority): String = priority.name

    @TypeConverter
    fun toPriority(value: String): Priority = Priority.valueOf(value)

    @TypeConverter
    fun fromRepeatUnit(unit: RepeatUnit): String = unit.name

    @TypeConverter
    fun toRepeatUnit(value: String): RepeatUnit = RepeatUnit.entries.find { it.name == value } ?: RepeatUnit.DAY

    @TypeConverter
    fun fromRepeatAnchor(anchor: RepeatAnchor): String = anchor.name

    @TypeConverter
    fun toRepeatAnchor(value: String): RepeatAnchor =
        RepeatAnchor.entries.find { it.name == value } ?: RepeatAnchor.COMPLETION

    @TypeConverter
    fun fromChecklist(items: List<ChecklistItem>): String = encodeChecklist(items)

    @TypeConverter
    fun toChecklist(value: String): List<ChecklistItem> = decodeChecklist(value)
}
