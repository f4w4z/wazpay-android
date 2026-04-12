package com.zeny.wazpay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

@Composable
fun PinScreen(value: String, onValueChange: (String) -> Unit, onPay: () -> Unit, onBack: () -> Unit) {
    var pinVisible by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Secure UPI PIN", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (i in 0 until 6) {
                    val char = value.getOrNull(i)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                width = if (i == value.length) 2.dp else 0.dp,
                                color = if (i == value.length) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (char != null) {
                            Text(
                                if (pinVisible) char.toString() else "•",
                                style = MaterialTheme.typography.headlineMedium
                            )
                        }
                    }
                }
            }
            
            TextButton(onClick = { pinVisible = !pinVisible }) {
                Icon(if (pinVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (pinVisible) "Hide PIN" else "Show PIN", style = MaterialTheme.typography.labelLarge)
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            CustomKeypad(
                onKeyClick = { 
                    if (value.length < 6) {
                        onValueChange(value + it)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                onDeleteClick = { 
                    if (value.isNotEmpty()) {
                        onValueChange(value.dropLast(1))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = onPay,
                enabled = value.length >= 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Pay Securely", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
