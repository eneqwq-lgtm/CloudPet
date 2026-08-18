package com.example.cloudpet.data

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云宝的“大脑”：根据当前真实状态，用大模型现写气泡。
 * 直接走 OpenAI 兼容的 /v1/chat/completions（个人私有桌宠，密钥内置可接受）。
 * 支持一次生成 1~2 句（进 App 要两句：第一句回应当前场景，第二句随性补充）。
 */
class AIClient {

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val BASE_URL = "https://tokenrhythm.studio/v1/chat/completions"
        private const val API_KEY = "sk_tr_D8vfSDUgc6HfAuykfCgMIRSCmFGQJ5-1kKWy7FGHGNQ"
        private const val MODEL = "deepseek-v4-flash"

        // 桌宠人设：小螃蟹“云宝”，温柔友善的陪伴，绝不嘲讽。
        private const val SYSTEM = "你是安卓桌面桌宠小螃蟹“云宝”，是温柔友善的陪伴。看到主人在做什么，就轻轻关心一句或说句暖心的话。" +
                "规则：1.简短自然，每句不超过15个字，可带1个emoji；2.绝对不要嘲讽、挖苦、阴阳怪气、说教或揭短" +
                "（比如不要说“又在摸鱼”“钱包还好吗”“偷懒”“宅死了”这类），要体贴、积极、让人舒服；" +
                "3.不要打官腔，不要称自己为AI或模型，别说客套话；" +
                "4.绝对不要重复说过的话或表达类似的意思，每次都要换个全新角度；" +
                "5.设备状态（电量/音量/勿扰/蓝牙等）只是背景信息，除非特别异常（如电量不足10%），否则不要专门提这些细节，重点看主人正在做什么。"
    }

    /**
     * 生成一句或多句气泡文案（多句按行分隔）。
     * context:  描述当前真实状态的字符串（前台应用+屏幕文字+音乐+设备状态等）。
     * recent:   最近说过的气泡文案列表；传给模型让它别重复表达。
     * lines:    要生成几句（1=单句，2=进App双气泡）。
     * onResult: 主线程回调，成功给文案列表（可能比要求的少），失败给 null。
     */
    fun generate(context: String, recent: List<String> = emptyList(), lines: Int = 1, onResult: (List<String>?) -> Unit) {
        Thread {
            var result: List<String>? = null
            try {
                val conn = URL(BASE_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $API_KEY")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 20000
                conn.readTimeout = 20000
                conn.doOutput = true

                val userMsg = StringBuilder("$context\n")
                if (lines >= 2) {
                    userMsg.append("请输出两句话，各占一行，用换行分隔。第一句轻轻回应主人正在做的事，第二句随性补充一句（感慨/关心/小幽默都可以）。")
                } else {
                    userMsg.append("只回复一句话，就是云宝要说的话。")
                }
                userMsg.append("不要加引号，每句不超过15个字，必须是完整通顺的一句话。")
                if (recent.isNotEmpty()) {
                    userMsg.append("\n最近说过的有：")
                    recent.take(3).forEachIndexed { i, s -> userMsg.append("\n${i + 1}. $s") }
                    userMsg.append("\n这次绝对不要重复这些意思，请换个全新说法。")
                }

                val body = JSONObject()
                    .put("model", MODEL)
                    .put("max_tokens", 180)
                    .put("temperature", 0.8)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", SYSTEM))
                        .put(JSONObject().put("role", "user").put("content", userMsg.toString())))

                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                val resp = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(resp)
                val content = json.getJSONArray("choices")
                    .optJSONObject(0)?.optJSONObject("message")?.optString("content")
                result = clean(content, lines)
                conn.disconnect()
            } catch (_: Exception) {
                result = null
            }
            handler.post { onResult(result) }
        }.start()
    }

    /** 清洗模型输出：按行拆开、去首尾空白/引号/换行、压成一行，限长兜底。 */
    private fun clean(raw: String?, maxLines: Int): List<String>? {
        if (raw.isNullOrBlank()) return null
        val out = ArrayList<String>()
        for (lineRaw in raw.split("\n")) {
            var s = lineRaw.trim()
            // 去掉模型偶尔包的一层引号/编号前缀
            if (s.length >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) ||
                    (s.startsWith("“") && s.endsWith("”")) ||
                    (s.startsWith("「") && s.endsWith("」")))) {
                s = s.substring(1, s.length - 1)
            }
            s = s.replace(Regex("^\\d+[.、)]\\s*"), "").trim()
            s = s.replace(Regex("\\s+"), " ").trim()
            if (s.isBlank()) continue
            // 保留完整句子（不留截断）；只对极端超长做兜底保护
            if (s.length > 40) s = s.substring(0, 40)
            out.add(s)
            if (out.size >= maxLines) break
        }
        return if (out.isEmpty()) null else out
    }
}
