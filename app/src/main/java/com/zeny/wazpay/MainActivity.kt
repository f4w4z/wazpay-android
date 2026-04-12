package com.zeny.wazpay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.zeny.wazpay.ui.theme.WazpayTheme
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

private const val TAG = "WazPay-Main"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "App started")
        enableEdgeToEdge()
        setContent {
            WazpayTheme {
                MainContent()
            }
        }
    }
}

fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
    for (enabledService in enabledServices) {
        if (enabledService.resolveInfo.serviceInfo.packageName == context.packageName &&
            enabledService.resolveInfo.serviceInfo.name == service.name) {
            return true
        }
    }
    return false
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun MainContent() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sharedPreferences = remember { context.getSharedPreferences("wazpay_prefs", Context.MODE_PRIVATE) }
    
    val requiredPermissions = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CAMERA
    )
    
    var isAccessEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context, UssdService::class.java)) }
    var hasPermissions by remember {
        mutableStateOf(requiredPermissions.all { 
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED 
        })
    }
    
    var screenState by rememberSaveable { mutableStateOf("SPLASH") } 
    
    val bankIfscPrefix = remember { sharedPreferences.getString("bank_ifsc", "") ?: "" }
    val selectedSim = remember { sharedPreferences.getInt("selected_sim", 0) }
    
    var recipient by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var upiPin by rememberSaveable { mutableStateOf("") }

    var recentRecipients by remember { mutableStateOf(emptyList<String>()) }
    
    LaunchedEffect(Unit) {
        val saved = sharedPreferences.getStringSet("recent_recipients", emptySet()) ?: emptySet()
        recentRecipients = saved.toList()
    }

    var isScannedInput by remember { mutableStateOf(false) }

    LaunchedEffect(screenState) {
        if (screenState == "RECIPIENT" && !isScannedInput) {
            amount = ""
            upiPin = ""
        }
        if (screenState != "SCANNER" && screenState != "AMOUNT") {
            isScannedInput = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessEnabled = isAccessibilityServiceEnabled(context, UssdService::class.java)
                hasPermissions = requiredPermissions.all { 
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED 
                }
                
                if (sharedPreferences.getBoolean("last_payment_success", false)) {
                    screenState = "SUCCESS"
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = screenState != "RECIPIENT" && screenState != "SETUP" && screenState != "SPLASH") {
        when (screenState) {
            "SCANNER" -> screenState = "RECIPIENT"
            "AMOUNT" -> screenState = "RECIPIENT"
            "PIN" -> screenState = "AMOUNT"
            "SUCCESS" -> screenState = "RECIPIENT"
            "PROCESSING" -> screenState = "RECIPIENT"
        }
    }

    val currentView = when {
        screenState == "SPLASH" -> "SPLASH"
        !hasPermissions && screenState != "SETUP" -> "PERMISSION"
        !isAccessEnabled && screenState != "SETUP" -> "ACCESSIBILITY"
        else -> screenState
    }

    Surface(
        modifier = Modifier.fillMaxSize(), 
        color = MaterialTheme.colorScheme.background
    ) {
        AnimatedContent(
            targetState = currentView,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
            },
            label = "ScreenTransition",
            contentAlignment = Alignment.Center
        ) { state ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    "SPLASH" -> SplashScreen {
                        screenState = if (bankIfscPrefix.isEmpty()) "SETUP" else "RECIPIENT"
                    }
                    "PERMISSION" -> OnboardingScreen(
                        title = "Permissions Required",
                        description = "WazPay needs Phone, Call, and Camera permissions to process payments and scan QR codes securely.",
                        icon = Icons.Default.Security,
                        actionLabel = "Grant Permissions",
                        onAction = { permissionLauncher.launch(requiredPermissions) }
                    )
                    "ACCESSIBILITY" -> OnboardingScreen(
                        title = "Enable Accessibility",
                        description = "WazPay uses Accessibility Service to automate USSD menus. Please enable 'WazPay USSD Service' in Settings.",
                        icon = Icons.Default.Accessibility,
                        actionLabel = "Go to Settings",
                        onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    )
                    "SETUP" -> SetupScreen(onComplete = { ifsc, sim ->
                        sharedPreferences.edit { putString("bank_ifsc", ifsc); putInt("selected_sim", sim) }
                        screenState = "RECIPIENT"
                    })
                    "RECIPIENT" -> RecipientScreen(
                        value = recipient, 
                        onValueChange = { recipient = it }, 
                        onNext = { 
                            val currentRecipients = sharedPreferences.getStringSet("recent_recipients", emptySet()) ?: emptySet()
                            val updated = (setOf(recipient) + currentRecipients).take(5).toSet()
                            sharedPreferences.edit { putStringSet("recent_recipients", updated) }
                            recentRecipients = updated.toList()
                            screenState = "AMOUNT" 
                        },
                        onScanClick = { screenState = "SCANNER" },
                        recentRecipients = recentRecipients
                    )
                    "SCANNER" -> QrScannerScreen(
                        onScanned = { upiId ->
                            recipient = upiId
                            isScannedInput = true
                            screenState = "AMOUNT"
                        },
                        onBack = { screenState = "RECIPIENT" }
                    )
                    "AMOUNT" -> AmountScreen(amount, onValueChange = { amount = it }, onNext = { screenState = "PIN" }, onBack = { screenState = "RECIPIENT" })
                    "PIN" -> PinScreen(upiPin, onValueChange = { upiPin = it }, onPay = {
                        sharedPreferences.edit { 
                            putString("pending_recipient", recipient)
                            putString("pending_amount", amount)
                            putString("pending_pin", upiPin)
                            remove("last_payment_success")
                        }
                        screenState = "PROCESSING"
                        initiatePayment(context, recipient, amount, selectedSim)
                    }, onBack = { screenState = "AMOUNT" })
                    "PROCESSING" -> TransactionProcessingScreen { screenState = "RECIPIENT" }
                    "SUCCESS" -> {
                        val name = sharedPreferences.getString("last_recipient_name", recipient) ?: recipient
                        val refId = sharedPreferences.getString("last_ref_id", "N/A") ?: "N/A"
                        val successAmount = sharedPreferences.getString("pending_amount", amount) ?: amount
                        SuccessScreen(name, refId, successAmount) {
                            sharedPreferences.edit { 
                                remove("last_payment_success")
                                remove("last_recipient_name")
                                remove("last_ref_id")
                                remove("pending_amount")
                            }
                            recipient = ""; amount = ""; upiPin = ""; screenState = "RECIPIENT"
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SplashScreen(onDone: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(1000),
        label = "AlphaAnimation"
    )
    
    LaunchedEffect(Unit) {
        delay(100)
        startAnimation = true
        delay(2000)
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "WAZPAY",
                style = MaterialTheme.typography.displayMedium,
                letterSpacing = 8.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.alpha(alpha)
            )
        }
        
        LinearProgressIndicator(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .width(140.dp)
                .height(2.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
fun OnboardingScreen(
    title: String,
    description: String,
    icon: ImageVector,
    actionLabel: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        }
        Spacer(modifier = Modifier.height(40.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(64.dp))
        Button(
            onClick = onAction,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(actionLabel, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun SetupScreen(onComplete: (String, Int) -> Unit) {
    var ifsc by remember { mutableStateOf("") }
    var selectedSim by remember { mutableIntStateOf(0) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Welcome to WazPay",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            Text(
                "Secure USSD Payments, simplified.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(56.dp))
            
            Text(
                "BANK CONFIGURATION", 
                style = MaterialTheme.typography.labelLarge, 
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = ifsc,
                onValueChange = { if (it.length <= 4) ifsc = it.uppercase() },
                placeholder = { 
                    Text(
                        "Enter Bank IFSC Prefix", 
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    ) 
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                leadingIcon = { Spacer(modifier = Modifier.width(24.dp)) },
                trailingIcon = { Spacer(modifier = Modifier.width(24.dp)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            
            Spacer(modifier = Modifier.height(40.dp))
            
            Text(
                "SELECT PAYMENT SIM", 
                style = MaterialTheme.typography.labelLarge, 
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf("SIM 1", "SIM 2").forEachIndexed { index, label ->
                    val isSelected = selectedSim == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(80.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { selectedSim = index }
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(64.dp))
            
            Button(
                onClick = { onComplete(ifsc, selectedSim) },
                enabled = ifsc.length == 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Get Started", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
fun RecipientScreen(
    value: String,
    onValueChange: (String) -> Unit,
    onNext: () -> Unit,
    onScanClick: () -> Unit,
    recentRecipients: List<String> = emptyList()
) {
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
            Text(
                "WAZPAY",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(60.dp))
            
            Text(
                "Who are you\nsending to?", 
                style = MaterialTheme.typography.displayMedium, 
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { 
                    Text(
                        "UPI ID or Mobile Number", 
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyLarge
                    ) 
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                trailingIcon = {
                    Box(modifier = Modifier.padding(end = 8.dp)) {
                        IconButton(
                            onClick = onScanClick,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent,
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            )

            if (recentRecipients.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text(
                        "RECENT",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    recentRecipients.take(3).forEach { recent ->
                        Surface(
                            onClick = { onValueChange(recent) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            color = Color.Transparent,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.History,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    recent,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = onNext,
                enabled = value.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Continue", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

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

@Composable
fun TransactionProcessingScreen(onCancel: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "ProcessingTransition")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Rotation"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .graphicsLayer { rotationZ = rotation }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(80.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 6.dp,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
        Spacer(modifier = Modifier.height(48.dp))
        Text("Processing Transaction", style = MaterialTheme.typography.headlineMedium)
        Text("Automating USSD requests...", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        
        Spacer(modifier = Modifier.height(80.dp))
        
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .width(200.dp)
                .height(56.dp),
            shape = RoundedCornerShape(28.dp)
        ) {
            Text("Cancel", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun SuccessScreen(name: String, refId: String, amount: String, onDone: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(64.dp))
            }
            
            Spacer(modifier = Modifier.height(40.dp))
            
            Text("PAYMENT SUCCESSFUL", style = MaterialTheme.typography.labelLarge, color = Color(0xFF4CAF50), letterSpacing = 4.sp)
            Text("₹$amount", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black)
            Text("to $name", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Transaction Reference", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(refId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            
            Spacer(modifier = Modifier.height(64.dp))
            
            Button(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Done", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
fun CustomKeypad(onKeyClick: (String) -> Unit, onDeleteClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "del")
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
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

@Composable
fun QrScannerScreen(onScanned: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    
    var isScanning by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose {
            isScanning = false
            cameraExecutor.shutdown()
            try {
                if (cameraProviderFuture.isDone) {
                    cameraProviderFuture.get().unbindAll()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding camera", e)
            }
        }
    }
    
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    val scanner = BarcodeScanning.getClient()
                    
                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (isScanning) {
                            processImageProxy(scanner, imageProxy) { upiId ->
                                if (isScanning) {
                                    isScanning = false
                                    ContextCompat.getMainExecutor(context).execute {
                                        try {
                                            cameraProvider.unbindAll()
                                        } catch (e: Exception) { Log.e(TAG, "Unbind failed", e) }
                                        onScanned(upiId)
                                    }
                                }
                            }
                        } else {
                            imageProxy.close()
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                    } catch (e: Exception) { Log.e(TAG, "Camera binding failed", e) }
                }, ContextCompat.getMainExecutor(ctx))
            }
        }, modifier = Modifier.fillMaxSize())

        // Overlay
        Box(modifier = Modifier.fillMaxSize()) {
            // Scanner Frame
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .align(Alignment.Center)
                    .border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(32.dp))
            ) {
                // Animated Scanning Line
                val infiniteTransition = rememberInfiniteTransition(label = "ScanLine")
                val offsetY by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 280f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "LineOffset"
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .offset(y = offsetY.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, Color.White, Color.Transparent)
                            )
                        )
                )
            }
            
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(16.dp).background(Color.Black.copy(0.4f), CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                }
                Spacer(modifier = Modifier.weight(1f))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Scan UPI QR Code",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        modifier = Modifier.alpha(0.9f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Align the code within the frame to pay",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.LightGray.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
private fun processImageProxy(scanner: com.google.mlkit.vision.barcode.BarcodeScanner, imageProxy: ImageProxy, onScanned: (String) -> Unit) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image).addOnSuccessListener { barcodes ->
            for (barcode in barcodes) {
                barcode.rawValue?.let { rawValue ->
                    if (rawValue.contains("pa=")) {
                        val upiId = Uri.parse(rawValue).getQueryParameter("pa")
                        if (upiId != null) {
                            onScanned(upiId)
                            return@addOnSuccessListener
                        }
                    }
                }
            }
        }.addOnCompleteListener { imageProxy.close() }
    } else {
        imageProxy.close()
    }
}

private fun initiatePayment(context: Context, recipient: String, amount: String, simIndex: Int) {
    val isMobile = recipient.all { it.isDigit() } && recipient.length >= 10
    val ussdCode = if (isMobile) "*99*1*1*$recipient*$amount*1#" else "*99*1*3#"
    Log.d(TAG, "Dialing USSD: $ussdCode on SIM $simIndex")
    val encodedUssd = ussdCode.replace("#", Uri.encode("#"))
    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$encodedUssd")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra("com.android.phone.force.slot", simIndex)
        putExtra("com.android.phone.extra.slot", simIndex)
        putExtra("slot", simIndex)
        putExtra("simSlot", simIndex)
    }
    try {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val phoneAccounts = telecomManager.callCapablePhoneAccounts
        if (phoneAccounts != null && simIndex < phoneAccounts.size) {
            intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccounts[simIndex])
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error setting phone account handle", e)
    }
    
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
        context.startActivity(intent)
    } else {
        Log.w(TAG, "CALL_PHONE permission missing during initiatePayment")
    }
}
