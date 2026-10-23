package com.logicalcode.sahhab

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

enum class State { QUEUED, RUNNING, DONE, ERROR, CANCELED }

class Task(
    val id: String,
    val url: String,
    var title: String,
    val quality: String,
    var state: State,
    val created: Long,
) {
    @Volatile var progress: Float = 0f
    @Volatile var eta: Long = -1
    @Volatile var line: String = ""
    var fileUri: String? = null
    var fileName: String? = null
    var mime: String? = null
    var error: String? = null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("title", title); put("quality", quality)
        put("state", state.name); put("created", created); put("progress", progress.toDouble())
        put("fileUri", fileUri ?: ""); put("fileName", fileName ?: ""); put("mime", mime ?: "")
        put("error", error ?: "")
    }

    companion object {
        fun fromJson(o: JSONObject): Task {
            val t = Task(
                o.getString("id"), o.getString("url"), o.optString("title"),
                o.optString("quality", "best"), State.valueOf(o.optString("state", "ERROR")),
                o.optLong("created")
            )
            t.progress = o.optDouble("progress", 0.0).toFloat()
            t.fileUri = o.optString("fileUri").ifEmpty { null }
            t.fileName = o.optString("fileName").ifEmpty { null }
            t.mime = o.optString("mime").ifEmpty { null }
            t.error = o.optString("error").ifEmpty { null }
            return t
        }
    }
}

object Store {
    private val tasks = mutableListOf<Task>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    @Volatile private var posted = false

    fun load(ctx: Context) {
        prefs = ctx.getSharedPreferences("tasks", Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("list", "[]")) } catch (e: Exception) { JSONArray() }
        synchronized(tasks) {
            tasks.clear()
            for (i in 0 until arr.length()) {
                val t = try { Task.fromJson(arr.getJSONObject(i)) } catch (e: Exception) { null } ?: continue
                // التطبيق أُغلق أثناء التنزيل
                if (t.state == State.RUNNING || t.state == State.QUEUED) {
                    t.state = State.ERROR
                    t.error = "توقف التنزيل. اضغط «أعد المحاولة» ليكمل."
                }
                tasks.add(t)
            }
        }
    }

    fun all(): List<Task> = synchronized(tasks) { tasks.sortedByDescending { it.created } }
    fun get(id: String): Task? = synchronized(tasks) { tasks.firstOrNull { it.id == id } }

    fun add(t: Task) {
        synchronized(tasks) { tasks.add(t) }
        save(); notifyChanged()
    }

    fun remove(id: String) {
        synchronized(tasks) { tasks.removeAll { it.id == id } }
        save(); notifyChanged()
    }

    fun update(@Suppress("UNUSED_PARAMETER") t: Task, persist: Boolean) {
        if (persist) save()
        notifyChanged()
    }

    private fun save() {
        val arr = JSONArray()
        synchronized(tasks) { tasks.forEach { arr.put(it.toJson()) } }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }

    fun notifyChanged() {
        if (posted) return
        posted = true
        main.postDelayed({
            posted = false
            listeners.forEach { it() }
        }, 250)
    }
}
