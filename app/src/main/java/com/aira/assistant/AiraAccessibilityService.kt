package com.aira.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AiraAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun replaceFocusedText(text: String): Boolean {
        if (text.isBlank()) return false
        val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (field?.isEditable != true || field.isPassword) return false
        return field.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
        )
    }

    companion object {
        @Volatile
        private var instance: AiraAccessibilityService? = null

        fun typeIntoFocusedField(text: String): Boolean =
            instance?.replaceFocusedText(text) == true
    }
}
