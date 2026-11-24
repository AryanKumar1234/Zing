package com.nlm.zingcontrol

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A device inside a group, with its own remembered Start settings. */
data class Device(
    var name: String,
    var duration: String = "",
    var spam: Boolean = false,
    var delay: String = "0"
)

data class Group(var name: String, val devices: MutableList<Device> = mutableListOf())

class MainActivity : Activity() {

    private lateinit var topic: EditText
    private lateinit var server: EditText
    private lateinit var content: LinearLayout
    private var groups = mutableListOf<Group>()
    private val expandedGroups = mutableSetOf<String>()
    private val expandedDevices = mutableSetOf<String>() // key: "<group>|<device>"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences("cfg", MODE_PRIVATE)

    // ---------- persistence ----------

    private fun loadGroups(): MutableList<Group> {
        val raw = prefs().getString("groups_json", null)
        if (raw != null) {
            val out = mutableListOf<Group>()
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val g = Group(o.getString("name"))
                val da = o.optJSONArray("devices") ?: JSONArray()
                for (j in 0 until da.length()) {
                    val d = da.getJSONObject(j)
                    g.devices.add(
                        Device(
                            d.getString("name"),
                            d.optString("duration", ""),
                            d.optBoolean("spam", false),
                            d.optString("delay", "0")
                        )
                    )
                }
                out.add(g)
            }
            return out
        }
        // migrate the old flat device list (pre-grouping version) into one default group
        val old = prefs().getString("devices", null)
        if (!old.isNullOrBlank()) {
            val g = Group("Devices")
            old.split("\n").filter { it.isNotBlank() }.forEach { g.devices.add(Device(it)) }
            return mutableListOf(g)
        }
        return mutableListOf()
    }

    private fun save() {
        val arr = JSONArray()
        for (g in groups) {
            val o = JSONObject()
            o.put("name", g.name)
            val da = JSONArray()
            for (d in g.devices) {
                val dj = JSONObject()
                dj.put("name", d.name)
                dj.put("duration", d.duration)
                dj.put("spam", d.spam)
                dj.put("delay", d.delay)
                da.put(dj)
            }
            o.put("devices", da)
            arr.put(o)
        }
        prefs().edit()
            .putString("groups_json", arr.toString())
            .putString("topic", topic.text.toString().trim())
            .putString("server", server.text.toString().trim())
            .apply()
    }

    // ---------- UI helpers ----------

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, dp(8), 0, 0) }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Color.GRAY); setPadding(0, dp(12), 0, 0)
    }

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 20f; setPadding(0, dp(24), 0, dp(4))
    }

    /** A text field + Add button; calls onAdd and clears the field. */
    private fun addRow(hint: String, onAdd: (String) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply { this.hint = hint; setSingleLine() }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "Add"
            setOnClickListener {
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) { onAdd(v); input.setText("") }
            }
        })
        return row
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groups = loadGroups()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        root.addView(TextView(this).apply { text = "Zing"; textSize = 28f })
        root.addView(label("ntfy topic"))
        topic = EditText(this).apply { setText(prefs().getString("topic", "nlm69")); setSingleLine() }
        root.addView(topic)
        root.addView(label("Server"))
        server = EditText(this).apply { setText(prefs().getString("server", "https://ntfy.sh")); setSingleLine() }
        root.addView(server)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content)
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onPause() { save(); super.onPause() }

    // ---------- rendering ----------

    private fun render() {
        content.removeAllViews()

        content.addView(header("Groups"))
        content.addView(addRow("New group name") { name ->
            if (groups.none { it.name.equals(name, ignoreCase = true) }) {
                groups.add(Group(name))
                expandedGroups.add(name)
                save(); render()
            }
        })

        for (g in groups) {
            content.addView(buildGroupCard(g))
        }
    }

    private fun card(bg: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.parseColor(bg))
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun buildGroupCard(g: Group): View {
        val outer = card("#EEEEEE")

        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val expanded = expandedGroups.contains(g.name)
        headerRow.addView(TextView(this).apply {
            text = (if (expanded) "▾ " else "▸ ") + g.name + "  (" + g.devices.size + ")"
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                if (expanded) expandedGroups.remove(g.name) else expandedGroups.add(g.name)
                render()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        headerRow.addView(Button(this).apply {
            text = "Remove"
            setOnClickListener {
                groups.remove(g); save(); render()
            }
        })
        outer.addView(headerRow)

        if (expanded) {
            outer.addView(addRow("Device name (as set in Bytello)") { name ->
                if (g.devices.none { it.name.equals(name, ignoreCase = true) }) {
                    g.devices.add(Device(name))
                    save(); render()
                }
            }, lp())
            for (d in g.devices) {
                outer.addView(buildDeviceCard(g, d), lp())
            }
        }
        return outer
    }

    private fun buildDeviceCard(g: Group, d: Device): View {
        val key = g.name + "|" + d.name
        val expanded = expandedDevices.contains(key)
        val outer = card("#FFFFFF")

        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        headerRow.addView(TextView(this).apply {
            text = d.name; textSize = 16f; gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        headerRow.addView(Button(this).apply {
            text = if (expanded) "Options ▲" else "Options ▾"
            setOnClickListener {
                if (expanded) expandedDevices.remove(key) else expandedDevices.add(key)
                render()
            }
        })
        headerRow.addView(Button(this).apply {
            text = "Remove"
            setOnClickListener { g.devices.remove(d); save(); render() }
        })
        outer.addView(headerRow)

        // Advanced fields (kept alive even when hidden, so Start always reads current values)
        val durationInput = EditText(this).apply {
            hint = "Duration per ring, seconds (blank = until stopped)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(d.duration)
        }
        val spamRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val spamSwitch = Switch(this).apply { isChecked = d.spam }
        spamRow.addView(TextView(this).apply {
            text = "Repeat (spam) until stopped"
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        spamRow.addView(spamSwitch)
        val delayInput = EditText(this).apply {
            hint = "Delay between repeats, seconds (default 0)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(d.delay)
        }
        val advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
            addView(durationInput, lp())
            addView(spamRow, lp())
            addView(delayInput, lp())
        }
        outer.addView(advanced)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Button(this).apply {
            text = "Start"
            setOnClickListener {
                d.duration = durationInput.text.toString().trim()
                d.spam = spamSwitch.isChecked
                d.delay = delayInput.text.toString().trim().ifEmpty { "0" }
                save()
                val durationArg = d.duration.ifEmpty { "0" }
                val spamArg = if (d.spam) "y" else "n"
                sendRaw("/start ${d.name} $durationArg $spamArg ${d.delay}")
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { sendRaw("/stop ${d.name}") }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        outer.addView(btnRow, lp())

        return outer
    }

    // ---------- networking ----------

    /** Publishes raw text as the ntfy message, e.g. "/start pixel 15 y 5". */
    private fun sendRaw(text: String) {
        val t = topic.text.toString().trim().ifEmpty { "nlm69" }
        val s = server.text.toString().trim().trimEnd('/').ifEmpty { "https://ntfy.sh" }
        Thread {
            val result = try {
                val c = URL("$s/${Uri.encode(t)}").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.outputStream.use { it.write(text.toByteArray()) }
                val code = c.responseCode
                if (code in 200..299) "Sent: $text" else "Failed (HTTP $code)"
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            runOnUiThread { Toast.makeText(this, result, Toast.LENGTH_SHORT).show() }
        }.start()
    }
}
