package com.example.browser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

class DownloadsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("browser_data", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        title = "Pobrane pliki"

        val listView = findViewById<ListView>(R.id.listView)
        val btnClear = findViewById<Button>(R.id.btnClear)
        btnClear.text = "Wyczyść listę"

        refreshList(listView)

        btnClear.setOnClickListener {
            prefs.edit().putString("downloads", "[]").apply()
            refreshList(listView)
        }
    }

    private fun refreshList(listView: ListView) {
        val arr = JSONArray(prefs.getString("downloads", "[]"))
        val items = mutableListOf<String>()
        val downloadIds = mutableListOf<Long>()

        for (i in arr.length() - 1 downTo 0) {
            val obj = arr.getJSONObject(i)
            items.add(obj.getString("filename"))
            downloadIds.add(obj.optLong("downloadId", -1))
        }

        if (items.isEmpty()) {
            items.add("Brak pobranych plików")
            downloadIds.add(-1)
        }

        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        listView.setOnItemClickListener { _, _, position, _ ->
            val id = downloadIds.getOrNull(position) ?: -1
            if (id == -1L) return@setOnItemClickListener
            try {
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val uri = dm.getUriForDownloadedFile(id)
                val mime = dm.getMimeTypeForDownloadedFile(id) ?: "*/*"
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setDataAndType(uri, mime)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Nie można otworzyć pliku: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
