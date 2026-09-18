package com.example.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

class HistoryActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("browser_data", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        title = "Historia"

        val listView = findViewById<ListView>(R.id.listView)
        val btnClear = findViewById<Button>(R.id.btnClear)

        refreshList(listView)

        btnClear.setOnClickListener {
            prefs.edit().putString("history", "[]").apply()
            refreshList(listView)
        }
    }

    private fun refreshList(listView: ListView) {
        val arr = JSONArray(prefs.getString("history", "[]"))
        val items = mutableListOf<String>()
        val urls = mutableListOf<String>()
        for (i in arr.length() - 1 downTo 0) {
            val obj = arr.getJSONObject(i)
            items.add("${obj.getString("title")}\n${obj.getString("url")}")
            urls.add(obj.getString("url"))
        }

        if (items.isEmpty()) {
            items.add("Historia jest pusta")
            urls.add("")
        }

        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        listView.setOnItemClickListener { _, _, position, _ ->
            val url = urls.getOrNull(position)
            if (!url.isNullOrEmpty()) {
                val intent = Intent(this, MainActivity::class.java)
                intent.putExtra("OPEN_URL", url)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                startActivity(intent)
                finish()
            }
        }
    }
}
