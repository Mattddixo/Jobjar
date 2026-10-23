package com.mattdixon.jobjar.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One line of a job's checklist. Checklists are lighter than subtasks: no time, no cost, never
 * drawn, and ticking them doesn't finish the job.
 */
data class ChecklistItem(val text: String, val checked: Boolean = false)

/** Stored in one column as JSON: `[{"text": "Sandpaper", "checked": true}, ...]`. */
fun checklistToJson(items: List<ChecklistItem>): JSONArray =
    JSONArray().apply { items.forEach { put(JSONObject().put("text", it.text).put("checked", it.checked)) } }

/** Lenient: anything unreadable comes back as no checklist rather than an error. */
fun checklistFromJson(array: JSONArray?): List<ChecklistItem> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        val text = item.optString("text").trim()
        if (text.isEmpty()) null else ChecklistItem(text, item.optBoolean("checked", false))
    }
}

fun encodeChecklist(items: List<ChecklistItem>): String = checklistToJson(items).toString()

fun decodeChecklist(value: String): List<ChecklistItem> =
    try {
        checklistFromJson(JSONArray(value))
    } catch (e: JSONException) {
        emptyList()
    }

/** Trims items and drops blank ones, so the stored list is always clean. */
fun cleanChecklist(items: List<ChecklistItem>): List<ChecklistItem> =
    items.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
