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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.zeny.wazpay.ui.theme.WazpayTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
        Log.i(TAG, "Permissions updated: $hasPermissions")
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessEnabled = isAccessibilityServiceEnabled(context, UssdService::class.java)
                hasPermissions = requiredPermissions.all { 
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED 
                }
                
                if (sharedPreferences.getBoolean("last_payment_success", false)) {
                    Log.i(TAG, "Payment success detected on resume")
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
            "HISTORY" -> screenState = "RECIPIENT"
            "PROCESSING" -> screenState = "RECIPIENT"
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (screenState) {
                "SPLASH" -> SplashScreen {
                    screenState = if (bankIfscPrefix.isEmpty()) "SETUP" else "RECIPIENT"
                }
                else -> {
                    if (!hasPermissions && screenState != "SETUP") {
                        PermissionRequestScreen {
                            permissionLauncher.launch(requiredPermissions)
                        }
                    } else if (!isAccessEnabled && screenState != "SETUP") {
                        AccessibilityRequestScreen(onGoToSettings = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        })
                    } else {
                        when (screenState) {
                            "SETUP" -> SetupScreen(onComplete = { ifsc, sim ->
                                Log.i(TAG, "Setup complete: IFSC=$ifsc, SIM=$sim")
                                sharedPreferences.edit { putString("bank_ifsc", ifsc); putInt("selected_sim", sim) }
                                screenState = "RECIPIENT"
                            })
                            "RECIPIENT" -> RecipientScreen(
                                value = recipient, 
                                onValueChange = { recipient = it }, 
                                onNext = { screenState = "AMOUNT" },
                                onScanClick = { screenState = "SCANNER" },
                                onHistoryClick = { screenState = "HISTORY" }
                            )
                            "SCANNER" -> QrScannerScreen(
                                onScanned = { upiId ->
                                    Log.i(TAG, "QR Scanned: $upiId")
                                    recipient = upiId
                                    screenState = "AMOUNT"
                                },
                                onBack = { screenState = "RECIPIENT" }
                            )
                            "AMOUNT" -> AmountScreen(amount, onValueChange = { amount = it }, onNext = { screenState = "PIN" }, onBack = { screenState = "RECIPIENT" })
                            "PIN" -> PinScreen(upiPin, onValueChange = { upiPin = it }, onPay = {
                                Log.i(TAG, "Payment initiated for ₹$amount to $recipient")
                                sharedPreferences.edit { 
                                    putString("pending_recipient", recipient)
                                    putString("pending_amount", amount)
                                    putString("pending_pin", upiPin)
                                    remove("last_payment_success")
                                }
                                screenState = "PROCESSING"
                                initiatePayment(context, recipient, amount, selectedSim)
                            }, onBack = { screenState = "AMOUNT" })
                            "HISTORY" -> HistoryScreen(context, onBack = { screenState = "RECIPIENT" })
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
    }
}

@Composable
fun SplashScreen(onDone: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (progress < 1f) {
            delay(20)
            progress += 0.02f
        }
        onDone()
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding(), contentAlignment = Alignment.Center) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(200.dp).height(2.dp), color = Color.White, trackColor = Color.DarkGray)
    }
}

@Composable
fun PermissionRequestScreen(onRequest: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Security, null, tint = Color.White, modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text("Permissions Required", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))
        Text("WazPay needs Phone, Call, and Camera permissions to process payments and scan QR codes securely.", textAlign = TextAlign.Center, color = Color.Gray)
        Spacer(modifier = Modifier.height(48.dp))
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
            Text("Grant Permissions")
        }
    }
}

@Composable
fun AccessibilityRequestScreen(onGoToSettings: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Accessibility, null, tint = Color.White, modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text("Enable Accessibility", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))
        Text("WazPay uses Accessibility Service to automate USSD menus. Please enable 'WazPay USSD Service' in Settings.", textAlign = TextAlign.Center, color = Color.Gray)
        Spacer(modifier = Modifier.height(48.dp))
        Button(onClick = onGoToSettings, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
            Text("Go to Settings")
        }
    }
}

@Composable
fun SetupScreen(onComplete: (String, Int) -> Unit) {
    var ifsc by remember { mutableStateOf("") }
    var selectedSim by remember { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(32.dp), verticalArrangement = Arrangement.Center) {
        Text("Welcome to WazPay", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Secure USSD Payments", color = Color.Gray)
        Spacer(modifier = Modifier.height(48.dp))
        OutlinedTextField(value = ifsc, onValueChange = { if (it.length <= 4) ifsc = it.uppercase() }, label = { Text("Bank IFSC Prefix (e.g., PYTM)") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedBorderColor = Color.White, unfocusedBorderColor = Color.DarkGray))
        Spacer(modifier = Modifier.height(24.dp))
        Text("Select Payment SIM", color = Color.Gray, fontSize = 14.sp)
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("SIM 1", "SIM 2").forEachIndexed { index, label ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.selectable(selected = (selectedSim == index), onClick = { selectedSim = index }, role = Role.RadioButton).padding(8.dp)) {
                    RadioButton(selected = (selectedSim == index), onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Color.White))
                    Text(label, color = Color.White, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(48.dp))
        Button(onClick = { onComplete(ifsc, selectedSim) }, enabled = ifsc.length == 4, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
            Text("Complete Setup")
        }
    }
}

@Composable
fun RecipientScreen(value: String, onValueChange: (String) -> Unit, onNext: () -> Unit, onScanClick: () -> Unit, onHistoryClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(24.dp).imePadding().verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("WazPay", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.White)
            IconButton(onClick = onHistoryClick) { Icon(Icons.Default.History, null, tint = Color.White) }
        }
        Spacer(modifier = Modifier.height(48.dp))
        Text("Send Money", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedTextField(value = value, onValueChange = onValueChange, placeholder = { Text("Enter UPI ID or Mobile Number", color = Color.DarkGray) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Color(0xFF111111), unfocusedContainerColor = Color(0xFF111111), focusedBorderColor = Color.White, unfocusedBorderColor = Color.DarkGray), trailingIcon = { IconButton(onClick = onScanClick) { Icon(Icons.Default.QrCodeScanner, null, tint = Color.White) } })
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onNext, enabled = value.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black), shape = RoundedCornerShape(12.dp)) {
            Text("Next", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("crafted and secured by fawaz", modifier = Modifier.fillMaxWidth().padding(top = 16.dp), textAlign = TextAlign.Center, fontSize = 12.sp, fontStyle = FontStyle.Italic, color = Color.Gray)
    }
}

@Composable
fun AmountScreen(value: String, onValueChange: (String) -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(24.dp).imePadding().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
        }
        Spacer(modifier = Modifier.weight(0.5f))
        Text("Amount", color = Color.Gray, fontSize = 16.sp)
        Text("₹${value.ifEmpty { "0" }}", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.weight(1f))
        CustomKeypad(onKeyClick = { if (value.length < 7) onValueChange(value + it) }, onDeleteClick = { if (value.isNotEmpty()) onValueChange(value.dropLast(1)) })
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onNext, enabled = value.isNotBlank() && value != "0", modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black), shape = RoundedCornerShape(12.dp)) {
            Text("Confirm", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun PinScreen(value: String, onValueChange: (String) -> Unit, onPay: () -> Unit, onBack: () -> Unit) {
    var pinVisible by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(24.dp).imePadding().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
        }
        Spacer(modifier = Modifier.weight(0.5f))
        Text("UPI PIN", color = Color.Gray, fontSize = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (pinVisible) value else "•".repeat(value.length), fontSize = 48.sp, color = Color.White, letterSpacing = 8.sp)
            IconButton(onClick = { pinVisible = !pinVisible }) { Icon(if (pinVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = Color.White) }
        }
        Spacer(modifier = Modifier.weight(1f))
        CustomKeypad(onKeyClick = { if (value.length < 6) onValueChange(value + it) }, onDeleteClick = { if (value.isNotEmpty()) onValueChange(value.dropLast(1)) })
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onPay, enabled = value.length >= 4, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black), shape = RoundedCornerShape(12.dp)) {
            Text("Pay Now", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TransactionProcessingScreen(onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(32.dp))
        Text("Processing...", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Light)
        Spacer(modifier = Modifier.height(64.dp))
        TextButton(onClick = onCancel) {
            Text("Return to App", color = Color.Gray)
        }
    }
}

@Composable
fun SuccessScreen(name: String, refId: String, amount: String, onDone: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(modifier = Modifier.size(100.dp).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(64.dp)) }
        Spacer(modifier = Modifier.height(48.dp))
        Text("SUCCESS", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray, letterSpacing = 4.sp)
        Text("₹$amount", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))
        Text(name, fontSize = 20.sp, color = Color.White)
        Spacer(modifier = Modifier.height(48.dp))
        Text("REF: $refId", fontSize = 12.sp, color = Color.DarkGray)
        Spacer(modifier = Modifier.height(64.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black), shape = RoundedCornerShape(12.dp)) { Text("Done", fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun CustomKeypad(onKeyClick: (String) -> Unit, onDeleteClick: () -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "del")
    Column(modifier = Modifier.fillMaxWidth()) {
        keys.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { key ->
                    Box(modifier = Modifier.size(80.dp).clickable { if (key == "del") onDeleteClick() else onKeyClick(key) }, contentAlignment = Alignment.Center) {
                        if (key == "del") Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = Color.White, modifier = Modifier.size(32.dp))
                        else Text(key, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Color.White)
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
    
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                    val imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    val scanner = BarcodeScanning.getClient()
                    
                    imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        processImageProxy(scanner, imageProxy, onScanned)
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                    } catch (e: Exception) { Log.e(TAG, "Camera binding failed", e) }
                }, ContextCompat.getMainExecutor(ctx))
            }
        }, modifier = Modifier.fillMaxSize())

        // Overlay UI - using systemBarsPadding to keep controls in safe area
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.background(Color.Black.copy(0.5f), CircleShape)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Box(modifier = Modifier.size(250.dp).align(Alignment.CenterHorizontally).border(2.dp, Color.White.copy(0.5f), RoundedCornerShape(24.dp)))
            Spacer(modifier = Modifier.weight(1f))
            Text("Scan any UPI QR Code", modifier = Modifier.fillMaxWidth().background(Color.Black.copy(0.5f)).padding(24.dp), textAlign = TextAlign.Center, color = Color.White)
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

@Composable
fun HistoryScreen(context: Context, onBack: () -> Unit) {
    val db = remember { AppDatabase.getDatabase(context) }
    val transactions by db.transactionDao().getAll().collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Text("Transaction History", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(modifier = Modifier.height(24.dp))
        
        if (transactions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No transactions found", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(transactions) { transaction ->
                    TransactionItem(transaction)
                }
            }
        }
    }
}

@Composable
fun TransactionItem(transaction: Transaction) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(transaction.recipient, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(transaction.timestamp)),
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                if (!transaction.refId.isNullOrEmpty()) {
                    Text("Ref: ${transaction.refId}", color = Color.DarkGray, fontSize = 10.sp)
                }
            }
            Text(
                "₹${transaction.amount}",
                color = if (transaction.status == "SUCCESS") Color.White else Color.Red,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp
            )
        }
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
