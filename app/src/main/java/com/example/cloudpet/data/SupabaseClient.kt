package com.example.cloudpet.data

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SupabaseClient {
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val SUPABASE_URL = "https://qgtsfgviyagkeafqyqyo.supabase.co"
        private const val API_KEY = "sb_publishable_dxfFmg840ZpDF-8atWJTtw_uPdehf_v"
    }

    fun fetchPetState(callback: (String?) -> Unit) {
        Thread {
            try {
                val url = URL("$SUPABASE_URL/rest/v1/pet_state?select=state_value&state_key=eq.mood&order=updated_at.desc&limit=1")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("apikey", API_KEY)
                conn.setRequestProperty("Authorization", "Bearer $API_KEY")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val response = conn.inputStream.bufferedReader().readText()
                val json = gson.fromJson(response, Array::class.java) as? List<Map<String, Any>>
                val mood = json?.firstOrNull()?.get("state_value") as? String

                handler.post { callback(mood ?: "idle") }
            } catch (e: Exception) {
                handler.post { callback(null) }
            }
        }.start()
    }

    fun logGesture(type: String) {
        Thread {
            try {
                val url = URL("$SUPABASE_URL/rest/v1/gesture_log")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", API_KEY)
                conn.setRequestProperty("Authorization", "Bearer $API_KEY")
                conn.doOutput = true
                conn.connectTimeout = 10000

                val body = """{"gesture_type":"$type","x":0,"y":0}"""
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
                conn.inputStream.bufferedReader().readText()
                conn.disconnect()
            } catch (_: Exception) {}
        }.start()
    }
}
