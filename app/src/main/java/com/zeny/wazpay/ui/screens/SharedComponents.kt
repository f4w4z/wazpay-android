package com.zeny.wazpay.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CustomKeypad(onKeyClick: (String) -> Unit, onDeleteClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "del")
    
    Column(modifier = Modifier.fillMaxWidth()) {
        keys.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { key ->
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (key == "del") onDeleteClick() else onKeyClick(key)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(70.dp)
                            .padding(4.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (key == "del") {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, modifier = Modifier.size(32.dp))
                            } else {
                                Text(key, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}
