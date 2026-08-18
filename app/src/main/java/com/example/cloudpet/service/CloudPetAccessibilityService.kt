package com.example.cloudpet.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 小克的“眼睛”：reads window content and exposes screen text snapshot.
 * 无障碍服务，在系统设置里手动开启。OverlayService 每轮询读取这里的屏幕文字，喂给 AI。
 * 不做任何点击/输入，只读文字。
 */
class CloudPetAccessibilityService : AccessibilityService() {

    companion object {
        /** 当前屏幕可见文字摘要（供大脑读取） */
        @Volatile var screenText: String? = null

        /** 当前顶层窗口的标题/类名，用于识别“窗口是否切了”（如 聊天↔朋友圈） */
        @Volatile var windowTitle: String? = null

        /** 当前窗口所属包名 */
        @Volatile var windowPackage: String? = null

        private const val MAX_LEN = 500
        private const val THROTTLE_MS = 900L
        @Volatile private var lastCollect = 0L

        private fun collect(node: AccessibilityNodeInfo?, acc: StringBuilder, depth: Int) {
            if (node == null || acc.length >= MAX_LEN || depth > 26) return
            val t = node.text?.toString()?.trim()
            val cd = node.contentDescription?.toString()?.trim()
            if (!t.isNullOrEmpty()) {
                if (acc.isNotEmpty()) acc.append("；")
                acc.append(t)
            } else if (!cd.isNullOrEmpty()) {
                if (acc.isNotEmpty()) acc.append("；")
                acc.append(cd)
            }
            val n = node.childCount
            for (i in 0 until n) {
                if (acc.length >= MAX_LEN) break
                val c = node.getChild(i) ?: continue
                collect(c, acc, depth + 1)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val now = System.currentTimeMillis()
        // 窗口变化立刻收集；内容变化节流，避免滚动刷屏拖慢主线程
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            now - lastCollect < THROTTLE_MS) return
        try {
            val root = rootInActiveWindow ?: return
            val sb = StringBuilder()
            collect(root, sb, 0)
            if (sb.isNotEmpty()) screenText = sb.toString()
            event.packageName?.toString()?.let { windowPackage = it }
            event.className?.toString()?.substringAfterLast('.')?.let { windowTitle = it }
            lastCollect = now
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}
}