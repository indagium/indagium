package com.openlog.ui

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed

val isMacOs: Boolean = System.getProperty("os.name").lowercase().startsWith("mac")

val KeyEvent.isActionKey: Boolean get() = if (isMacOs) isMetaPressed else isCtrlPressed
