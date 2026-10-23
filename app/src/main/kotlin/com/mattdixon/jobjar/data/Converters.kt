package com.mattdixon.jobjar.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromPriority(priority: Priority): String = priority.name

    @TypeConverter
    fun toPriority(value: String): Priority = Priority.valueOf(value)

    @TypeConverter
    fun fromChecklist(items: List<ChecklistItem>): String = encodeChecklist(items)

    @TypeConverter
    fun toChecklist(value: String): List<ChecklistItem> = decodeChecklist(value)
}
