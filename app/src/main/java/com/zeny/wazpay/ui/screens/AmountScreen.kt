package com.zeny.wazpay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun AmountScreen(value: String, onValueChange: (String) -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
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
            Text("Amount to send", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "₹", 
                    style = MaterialTheme.typography.displayLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.padding(end = 12.dp)
                )
                
                Text(
                    value.ifEmpty { "0" },
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
                
                // Balancer to keep the amount digit perfectly centered
                Text(
                    "₹", 
                    style = MaterialTheme.typography.displayLarge.copy(color = Color.Transparent),
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(60.dp))
            
            CustomKeypad(
                onKeyClick = { 
                    if (value.length < 7) {
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
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Button(
                onClick = onNext,
                enabled = value.isNotBlank() && value != "0",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Confirm Amount", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
