package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.db.Contact
import com.example.db.LocalMessage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import java.util.concurrent.TimeUnit
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import com.example.crypto.AlgorandCrypto
import androidx.compose.foundation.BorderStroke
import com.example.ui.ChatViewModel
import com.example.ui.WalletState
import com.example.ui.QRScannerDialog
import com.example.ui.ShowMyQrDialog
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.example.net.PinataClient
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.*

import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : FragmentActivity() {
    private lateinit var viewModelRef: ChatViewModel
    private var shouldLogoutOnViewModelInit = false

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Notification permission denied. Background alerts won't be shown.", Toast.LENGTH_LONG).show()
        }
    }

    private fun scheduleAlgorandCheckWork() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<com.example.worker.AlgorandCheckWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "AlgorandCheckWork",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Log.d("MainActivity", "Successfully enqueued AlgorandCheckWork")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to schedule AlgorandCheckWork", e)
        }
    }

    fun requestIgnoreBatteryOptimizations() {
        val packageName = packageName
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    } catch (ex: Exception) {
                        Toast.makeText(this, "Could not open battery settings.", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Battery optimization is already disabled.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Battery optimization not required on this Android version.", Toast.LENGTH_SHORT).show()
        }
    }

    fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(this)
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun triggerBiometricPrompt(
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errString.toString())
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onError("Fingerprint not recognized.")
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Biometric Authentication")
            .setSubtitle("Use your fingerprint to continue")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            onError(e.message ?: "Biometric authentication error.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots and screen recording (privacy protection)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        checkAutoLogoutBeforeInit()

        // Start background checking service
        scheduleAlgorandCheckWork()

        // Ask for notifications permission on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val vm: ChatViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return ChatViewModel(context.applicationContext) as T
                    }
                })
                viewModelRef = vm

                if (shouldLogoutOnViewModelInit) {
                    shouldLogoutOnViewModelInit = false
                    vm.logout()
                    Toast.makeText(context, "Logged out automatically due to inactivity.", Toast.LENGTH_LONG).show()
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(vm)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val prefs = getSharedPreferences("algopriv_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_background_time", System.currentTimeMillis()).apply()
    }

    override fun onStart() {
        super.onStart()
        if (::viewModelRef.isInitialized) {
            checkAutoLogout()
            viewModelRef.syncNetworkTransactions(silent = true)
        } else {
            checkAutoLogoutBeforeInit()
        }
    }

    private fun checkAutoLogoutBeforeInit() {
        val prefs = getSharedPreferences("algopriv_prefs", Context.MODE_PRIVATE)
        val timeoutMinutes = prefs.getInt("auto_logout_minutes", -1)
        if (timeoutMinutes > 0) {
            val lastBackgroundTime = prefs.getLong("last_background_time", 0L)
            if (lastBackgroundTime > 0L) {
                val elapsedMs = System.currentTimeMillis() - lastBackgroundTime
                val elapsedMinutes = elapsedMs / (1000 * 60)
                if (elapsedMinutes >= timeoutMinutes) {
                    shouldLogoutOnViewModelInit = true
                }
            }
        }
        prefs.edit().putLong("last_background_time", 0L).apply()
    }

    private fun checkAutoLogout() {
        if (!::viewModelRef.isInitialized) return
        val prefs = getSharedPreferences("algopriv_prefs", Context.MODE_PRIVATE)
        val timeoutMinutes = prefs.getInt("auto_logout_minutes", -1)
        if (timeoutMinutes > 0) {
            val lastBackgroundTime = prefs.getLong("last_background_time", 0L)
            if (lastBackgroundTime > 0L) {
                val elapsedMs = System.currentTimeMillis() - lastBackgroundTime
                val elapsedMinutes = elapsedMs / (1000 * 60)
                if (elapsedMinutes >= timeoutMinutes) {
                    viewModelRef.logout()
                    Toast.makeText(this, "Logged out automatically due to inactivity.", Toast.LENGTH_LONG).show()
                }
            }
        }
        prefs.edit().putLong("last_background_time", 0L).apply()
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AppNavigation(viewModel: ChatViewModel) {
    val walletState by viewModel.walletState.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current

    // Observe and display errors gracefully
    LaunchedEffect(error) {
        error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    when (val state = walletState) {
        is WalletState.NotInitialized -> {
            WalletSetupScreen(viewModel)
        }
        is WalletState.Locked -> {
            WalletLockScreen(viewModel)
        }
        is WalletState.Unlocked -> {
            MainChatDashboard(viewModel, state)
        }
    }
}

// ── 1. WALLET SETUP SCREEN ──

@Composable
fun WalletSetupScreen(viewModel: ChatViewModel) {
    var setupMode by remember { mutableStateOf(0) } // 0: Create, 1: Import
    var mnemonicInput by remember { mutableStateOf("") }
    var passcode by remember { mutableStateOf("") }
    var confirmPasscode by remember { mutableStateOf("") }
    var showMnemonicDialog by remember { mutableStateOf(false) }
    var passcodeVisible by remember { mutableStateOf(false) }

    val generatedMnemonic by viewModel.generatedMnemonic.collectAsState()
    val isGeneratingWallet by viewModel.isGeneratingWallet.collectAsState()

    val context = LocalContext.current

    LaunchedEffect(generatedMnemonic) {
        if (generatedMnemonic != null) {
            showMnemonicDialog = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header Logo
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "App Logo",
                    modifier = Modifier.size(72.dp)
                )

                Text(
                    text = "Allo•Alu",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE2E2E6),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "P2P client with full AES-GCM encryption on the Algorand blockchain ledger. Keys are saved securely on device.",
                    fontSize = 13.sp,
                    color = Color(0xFFE2E2E6).copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            // Form Content Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF242629)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Mode Selector Tabs (2 options: Create, Import)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1C1E), RoundedCornerShape(12.dp))
                            .padding(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (setupMode == 0) Color(0xFF303034) else Color.Transparent)
                                .clickable { setupMode = 0 }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Create",
                                color = if (setupMode == 0) Color(0xFF80CBC4) else Color(0xFFE2E2E6).copy(alpha = 0.6f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (setupMode == 1) Color(0xFF303034) else Color.Transparent)
                                .clickable { setupMode = 1 }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Import",
                                color = if (setupMode == 1) Color(0xFF80CBC4) else Color(0xFFE2E2E6).copy(alpha = 0.6f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }

                    if (setupMode == 1) {
                        OutlinedTextField(
                            value = mnemonicInput,
                            onValueChange = { mnemonicInput = it },
                            label = { Text("25-word Algorand Mnemonic") },
                            placeholder = { Text("word1 word2 ... word25") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFFE2E2E6),
                                unfocusedTextColor = Color(0xFFE2E2E6),
                                focusedContainerColor = Color(0xFF1A1C1E),
                                unfocusedContainerColor = Color(0xFF1A1C1E),
                                focusedBorderColor = Color(0xFF80CBC4),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("import_mnemonic_input"),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                        )
                    }

                    if (setupMode == 0 || setupMode == 1) {
                        OutlinedTextField(
                            value = passcode,
                            onValueChange = { passcode = it },
                            label = { Text("Passcode to secure wallet") },
                            placeholder = { Text("At least 6 digits") },
                            visualTransformation = if (passcodeVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Passcode Icon",
                                    tint = Color(0xFF80CBC4).copy(alpha = 0.6f)
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { passcodeVisible = !passcodeVisible }) {
                                    Icon(
                                        imageVector = Icons.Default.Visibility,
                                        contentDescription = "Toggle Passcode Visibility",
                                        tint = if (passcodeVisible) Color(0xFF80CBC4) else Color(0xFFE2E2E6).copy(alpha = 0.3f)
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFFE2E2E6),
                                unfocusedTextColor = Color(0xFFE2E2E6),
                                focusedContainerColor = Color(0xFF1A1C1E),
                                unfocusedContainerColor = Color(0xFF1A1C1E),
                                focusedBorderColor = Color(0xFF80CBC4),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                cursorColor = Color(0xFF80CBC4)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("setup_passcode_input"),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = confirmPasscode,
                            onValueChange = { confirmPasscode = it },
                            label = { Text("Confirm passcode") },
                            placeholder = { Text("Confirm passcode") },
                            visualTransformation = if (passcodeVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Confirm Passcode Icon",
                                    tint = Color(0xFF80CBC4).copy(alpha = 0.6f)
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { passcodeVisible = !passcodeVisible }) {
                                    Icon(
                                        imageVector = Icons.Default.Visibility,
                                        contentDescription = "Toggle Passcode Visibility",
                                        tint = if (passcodeVisible) Color(0xFF80CBC4) else Color(0xFFE2E2E6).copy(alpha = 0.3f)
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFFE2E2E6),
                                unfocusedTextColor = Color(0xFFE2E2E6),
                                focusedContainerColor = Color(0xFF1A1C1E),
                                unfocusedContainerColor = Color(0xFF1A1C1E),
                                focusedBorderColor = Color(0xFF80CBC4),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                cursorColor = Color(0xFF80CBC4)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("setup_confirm_passcode_input"),
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    if (setupMode == 0) {
                        Button(
                            onClick = {
                                if (passcode.length < 6 || !passcode.all { it.isDigit() }) {
                                    Toast.makeText(context, "Passcode must be at least 6 digits", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (passcode != confirmPasscode) {
                                    Toast.makeText(context, "Passcodes do not match", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                viewModel.startWalletGeneration()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF80CBC4),
                                contentColor = Color(0xFF00332C)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("generate_wallet_button")
                        ) {
                            if (isGeneratingWallet) {
                                CircularProgressIndicator(color = Color(0xFF00332C), modifier = Modifier.size(24.dp))
                            } else {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(imageVector = Icons.Default.Key, contentDescription = "Key")
                                    Text("Generate 25-Word Mnemonic", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else if (setupMode == 1) {
                        Button(
                            onClick = {
                                if (mnemonicInput.trim().split(Regex("\\s+")).size != 25) {
                                    Toast.makeText(context, "Mnemonic must be exactly 25 words", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (passcode.length < 6 || !passcode.all { it.isDigit() }) {
                                    Toast.makeText(context, "Passcode must be at least 6 digits", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (passcode != confirmPasscode) {
                                    Toast.makeText(context, "Passcodes do not match", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                viewModel.importWallet(mnemonicInput, passcode)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF80CBC4),
                                contentColor = Color(0xFF00332C)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("import_wallet_button")
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(imageVector = Icons.Default.InstallMobile, contentDescription = "Import")
                                Text("Import Secure Mnemonic", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = "Powered by Algorand",
            fontSize = 12.sp,
            color = Color(0xFFE2E2E6).copy(alpha = 0.4f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }

    // ── Mnemonic Backup View dialog ──
    if (showMnemonicDialog && generatedMnemonic != null) {
        AlertDialog(
            onDismissRequest = { /* Force user to acknowledge seed backup */ },
            containerColor = Color(0xFF242629),
            title = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = "Warning", tint = Color(0xFFFFB4AB))
                    Text("Secure Mnemonic Seed Backup", color = Color(0xFFE2E2E6), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "WARNING: Write down these 25 words. If you lose them, you will lose access to your chat account forever. There is no password recovery mechanism.",
                        color = Color(0xFFE2E2E6).copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1C1E), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = generatedMnemonic!!,
                            color = Color(0xFF80CBC4),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                    }

                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Algorand Mnemonic", generatedMnemonic)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Mnemonic copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF303034)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                            Text("Copy Mnemonic", fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.completeWalletCreation(passcode)
                        showMnemonicDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF80CBC4))
                ) {
                    Text("I Have Saved This Safely", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

// ── 2. WALLET LOCK SCREEN ──

@Composable
fun WalletLockScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("algopriv_prefs", Context.MODE_PRIVATE) }
    var passcode by remember { mutableStateOf("") }
    var showResetConfirm by remember { mutableStateOf(false) }
    var passcodeVisible by remember { mutableStateOf(false) }
    val lockoutRemaining by viewModel.lockoutTimeRemaining.collectAsState()
    val isLockedOut = lockoutRemaining > 0

    val useBiometrics = remember { prefs.getBoolean("use_biometrics", false) }
    val biometricPasscode = remember { prefs.getString("biometric_passcode", "") ?: "" }

    LaunchedEffect(Unit) {
        if (useBiometrics && biometricPasscode.isNotEmpty() && !isLockedOut) {
            val mainActivity = context as? MainActivity
            if (mainActivity?.isBiometricAvailable() == true) {
                delay(300)
                mainActivity.triggerBiometricPrompt(
                    onSuccess = {
                        viewModel.unlockWallet(biometricPasscode)
                    },
                    onError = { err ->
                        // Silent fail on cancel
                    }
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header Logo with premium glowing border
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "App Logo",
                    modifier = Modifier.size(90.dp)
                )

                Text(
                    text = "Allo•Alu",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE2E2E6),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "End-to-End Encrypted Peer-to-Peer Messenger",
                    fontSize = 12.sp,
                    color = Color(0xFFE2E2E6).copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }

            // Beautiful glowing card form
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF242629)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Security Verification",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE2E2E6)
                        )
                        Text(
                            text = "Unlock with passcode",
                            fontSize = 12.sp,
                            color = Color(0xFFE2E2E6).copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                    }

                    OutlinedTextField(
                        value = passcode,
                        onValueChange = { passcode = it },
                        enabled = !isLockedOut,
                        placeholder = { 
                            if (isLockedOut) {
                                Text("Application locked", color = Color(0xFFFFB4AB).copy(alpha = 0.6f))
                            } else {
                                Text("Passcode", color = Color(0xFFE2E2E6).copy(alpha = 0.3f))
                            }
                        },
                        visualTransformation = if (passcodeVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Security Passcode",
                                tint = Color(0xFF80CBC4).copy(alpha = 0.6f)
                            )
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (useBiometrics && biometricPasscode.isNotEmpty() && !isLockedOut) {
                                    IconButton(onClick = {
                                        val mainActivity = context as? MainActivity
                                        if (mainActivity?.isBiometricAvailable() == true) {
                                            mainActivity.triggerBiometricPrompt(
                                                onSuccess = {
                                                    viewModel.unlockWallet(biometricPasscode)
                                                },
                                                onError = { err ->
                                                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        }
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Fingerprint,
                                            contentDescription = "Unlock with Biometrics",
                                            tint = Color(0xFF80CBC4)
                                        )
                                    }
                                }
                                IconButton(onClick = { passcodeVisible = !passcodeVisible }) {
                                    Icon(
                                        imageVector = Icons.Default.Visibility,
                                        contentDescription = "Toggle Passcode Visibility",
                                        tint = if (passcodeVisible) Color(0xFF80CBC4) else Color(0xFFE2E2E6).copy(alpha = 0.3f)
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFE2E2E6),
                            unfocusedTextColor = Color(0xFFE2E2E6),
                            focusedContainerColor = Color(0xFF1A1C1E),
                            unfocusedContainerColor = Color(0xFF1A1C1E),
                            focusedBorderColor = Color(0xFF80CBC4),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                            disabledTextColor = Color(0xFFE2E2E6).copy(alpha = 0.3f),
                            disabledContainerColor = Color(0xFF1A1C1E).copy(alpha = 0.8f),
                            disabledBorderColor = Color.White.copy(alpha = 0.05f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("unlock_passcode_input"),
                        keyboardActions = KeyboardActions(
                            onDone = { 
                                if (!isLockedOut) {
                                    viewModel.unlockWallet(passcode) 
                                }
                            }
                        ),
                        singleLine = true
                    )

                    if (isLockedOut) {
                        Text(
                            text = "Too many failed attempts. Try again in $lockoutRemaining s",
                            color = Color(0xFFFFB4AB),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Button(
                        onClick = { viewModel.unlockWallet(passcode) },
                        enabled = !isLockedOut,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF80CBC4),
                            contentColor = Color(0xFF00332C),
                            disabledContainerColor = Color(0xFF1E2022),
                            disabledContentColor = Color.White.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("unlock_wallet_button")
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.Default.Lock, contentDescription = "Unlock", modifier = Modifier.size(18.dp))
                            Text("Unlock Secure Chat", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }

                    TextButton(
                        onClick = { showResetConfirm = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFFB4AB).copy(alpha = 0.8f)),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "Reset Icon", modifier = Modifier.size(14.dp))
                            Text("Reset Secure App", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Text(
            text = "Powered by Algorand",
            fontSize = 12.sp,
            color = Color(0xFFE2E2E6).copy(alpha = 0.4f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = Color(0xFF242629),
            title = { Text("Reset App?", color = Color(0xFFE2E2E6)) },
            text = {
                Text(
                    "This will completely erase your wallet keys, contacts, and message history locally. This operation is irreversible.",
                    color = Color(0xFFE2E2E6).copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetWallet()
                        showResetConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFFB4AB))
                ) {
                    Text("Erase All Data")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel", color = Color(0xFFE2E2E6))
                }
            }
        )
    }
}

// ── 3. MAIN DASHBOARD SCREEN (ADAPTIVE SPLIT PANE) ──

@Composable
fun MainChatDashboard(viewModel: ChatViewModel, state: WalletState.Unlocked) {
    val activeContact by viewModel.activeContact.collectAsState()

    androidx.activity.compose.BackHandler(enabled = activeContact != null) {
        viewModel.selectContact(null)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth > 650.dp

        if (isWideScreen) {
            // Side-by-side tablet / DeX layout
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF1A1C1E))
                ) {
                    ContactsListView(viewModel, state)
                }

                VerticalDivider(
                    color = Color.White.copy(alpha = 0.05f),
                    modifier = Modifier.width(1.dp).fillMaxHeight()
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (activeContact != null) {
                        ChatConversationView(viewModel, state, activeContact!!, onBack = { viewModel.selectContact(null) })
                    } else {
                        NoChatSelectedPlaceholder()
                    }
                }
            }
        } else {
            // Mobile navigation flow
            if (activeContact != null) {
                ChatConversationView(viewModel, state, activeContact!!, onBack = { viewModel.selectContact(null) })
            } else {
                ContactsListView(viewModel, state)
            }
        }
    }
}

// ── 4. CONTACTS LIST PANEL ──

@Composable
fun ContactsListView(viewModel: ChatViewModel, state: WalletState.Unlocked) {
    val contacts by viewModel.contacts.collectAsState()
    val otherChats by viewModel.otherChats.collectAsState()
    val unreadContacts by viewModel.unreadContacts.collectAsState()
    val balance by viewModel.balance.collectAsState()
    val isMainnet by viewModel.isMainnet.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    var showAddContactDialog by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<Contact?>(null) }
    var contactToDelete by remember { mutableStateOf<Contact?>(null) }
    var showMyQr by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showDonationDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    if (showMyQr) {
        ShowMyQrDialog(address = state.address, onDismiss = { showMyQr = false })
    }

    if (showSettingsDialog) {
        SettingsDialog(viewModel = viewModel, state = state, onDismiss = { showSettingsDialog = false })
    }

    if (showDonationDialog) {
        DonationDialog(onDismiss = { showDonationDialog = false })
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF1A1C1E),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Header Profile Info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "My Wallet Profile Logo",
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .border(1.dp, Color(0xFF80CBC4).copy(alpha = 0.3f), CircleShape)
                                .clickable {
                                    showMyQr = true
                                },
                            contentScale = ContentScale.Crop
                        )

                        Column {
                            Text("My Wallet", color = Color(0xFFE2E2E6), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Row(
                                modifier = Modifier.clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("My Address", state.address)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Address copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${state.address.take(4)}...${state.address.takeLast(4)}",
                                    color = Color(0xFFE2E2E6).copy(alpha = 0.5f),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = Color(0xFF80CBC4).copy(alpha = 0.6f),
                                    modifier = Modifier.size(12.dp)
                                )
                                Icon(
                                    imageVector = Icons.Default.QrCode,
                                    contentDescription = "Show QR",
                                    tint = Color(0xFF81C784),
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { showMyQr = true }
                                )
                            }
                        }
                    }

                    // Network/Action Control Toggle
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { showDonationDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Favorite, contentDescription = "Support Project", tint = Color(0xFFE57373))
                        }

                        IconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFFE2E2E6).copy(alpha = 0.7f))
                        }

                        IconButton(
                            onClick = { viewModel.logout() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Lock, contentDescription = "Lock", tint = Color(0xFFE2E2E6).copy(alpha = 0.7f))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ALGO Balance Panel
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("ACCOUNT BALANCE", fontSize = 9.sp, color = Color(0xFFE2E2E6).copy(alpha = 0.4f), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                        Text(
                            text = "%.3f ALGO".format(balance / 1000000.0),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF80CBC4)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { viewModel.syncNetworkTransactions() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(color = Color(0xFF80CBC4), modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF80CBC4))
                            }
                        }
                    }
                }

                if (balance < 101000L) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFE57373).copy(alpha = 0.15f))
                            .border(1.dp, Color(0xFFE57373).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = Color(0xFFE57373),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Low balance. Fund account to send messages.",
                            color = Color(0xFFF2B8B5),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddContactDialog = true },
                containerColor = Color(0xFF80CBC4),
                contentColor = Color(0xFF00332C),
                shape = CircleShape,
                modifier = Modifier.testTag("add_contact_fab")
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Contact")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            var searchQuery by remember { mutableStateOf("") }
            var selectedTab by remember { mutableStateOf(0) } // 0 = Kontakty, 1 = Inne
            val listToDisplay = if (selectedTab == 0) contacts else otherChats

            if (listToDisplay.size > 6 || searchQuery.isNotEmpty()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search by name or address...", color = Color(0xFFE2E2E6).copy(alpha = 0.4f), fontSize = 14.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = Color(0xFFE2E2E6).copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = Color(0xFFE2E2E6).copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE2E2E6),
                        unfocusedTextColor = Color(0xFFE2E2E6),
                        focusedContainerColor = Color(0xFF16181A),
                        unfocusedContainerColor = Color(0xFF16181A),
                        focusedBorderColor = Color(0xFF80CBC4).copy(alpha = 0.7f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("contact_search_input")
                )
            }

            // Dynamic Tab Switcher for Kontakty vs Inne (Others)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF242629))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Tab 0: Kontakty
                val isTab0Selected = selectedTab == 0
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isTab0Selected) Color(0xFF80CBC4) else Color.Transparent)
                        .clickable { selectedTab = 0 }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.People,
                            contentDescription = null,
                            tint = if (isTab0Selected) Color(0xFF00332C) else Color(0xFFE2E2E6).copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Contacts",
                            fontWeight = FontWeight.Bold,
                            color = if (isTab0Selected) Color(0xFF00332C) else Color(0xFFE2E2E6),
                            fontSize = 13.sp
                        )
                    }
                }

                // Tab 1: Inne (Others)
                val isTab1Selected = selectedTab == 1
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isTab1Selected) Color(0xFF80CBC4) else Color.Transparent)
                        .clickable { selectedTab = 1 }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChatBubbleOutline,
                            contentDescription = null,
                            tint = if (isTab1Selected) Color(0xFF00332C) else Color(0xFFE2E2E6).copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Others",
                                fontWeight = FontWeight.Bold,
                                color = if (isTab1Selected) Color(0xFF00332C) else Color(0xFFE2E2E6),
                                fontSize = 13.sp
                            )
                            
                            val otherUnreadCount = otherChats.count { unreadContacts.contains(it.address) }
                            if (otherUnreadCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFE57373)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = otherUnreadCount.toString(),
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val filteredList = remember(listToDisplay, searchQuery) {
                if (searchQuery.isBlank()) {
                    listToDisplay
                } else {
                    listToDisplay.filter {
                        it.nickname.contains(searchQuery, ignoreCase = true) ||
                        it.address.contains(searchQuery, ignoreCase = true) ||
                        it.note.contains(searchQuery, ignoreCase = true)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectedTab == 0) "SECURED SECURE CHATS" else "OTHER MESSAGES",
                    fontSize = 10.sp,
                    color = Color(0xFFE2E2E6).copy(alpha = 0.4f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                if (selectedTab == 1 && otherChats.any { unreadContacts.contains(it.address) }) {
                    Text(
                        text = "Mark all as read",
                        fontSize = 10.sp,
                        color = Color(0xFF80CBC4),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                viewModel.markAllOtherChatsAsRead()
                            }
                            .padding(vertical = 4.dp, horizontal = 8.dp)
                    )
                }
            }

            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isNotEmpty()) {
                            "No matching chats found"
                        } else if (selectedTab == 0) {
                            "No contacts found.\nTap + to add an Algorand address."
                        } else {
                            "No messages from outside of contacts."
                        },
                        color = Color(0xFFE2E2E6).copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(filteredList) { contact ->
                        val isSelf = contact.address == state.address
                        val hasUnread = unreadContacts.contains(contact.address)
                        ContactRow(
                            contact = contact,
                            showDelete = selectedTab == 0 && !isSelf,
                            hasUnread = hasUnread,
                            onClick = { viewModel.selectContact(contact) },
                            onPinToggle = { if (selectedTab == 0) viewModel.toggleContactPinned(contact.address, contact.pinned) },
                            onEdit = { if (selectedTab == 0) editingContact = contact },
                            onDelete = { if (selectedTab == 0) contactToDelete = contact }
                        )
                    }
                }
            }
        }
    }

    if (showAddContactDialog) {
        var nickname by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        var showQrScanner by remember { mutableStateOf(false) }
 
        if (showQrScanner) {
            QRScannerDialog(
                onQrCodeScanned = { scanned ->
                    address = scanned
                    showQrScanner = false
                },
                onDismiss = { showQrScanner = false }
            )
        }
 
        AlertDialog(
            onDismissRequest = { showAddContactDialog = false },
            containerColor = Color(0xFF1F2124),
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(16.dp),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = null,
                        tint = Color(0xFF80CBC4)
                    )
                    Text(
                        "Add Contact",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        label = { Text("Nickname") },
                        placeholder = { Text("e.g. Alice") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF1A1C1E),
                            unfocusedContainerColor = Color(0xFF1A1C1E),
                            focusedBorderColor = Color(0xFF80CBC4),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("contact_nickname_input")
                    )
 
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Algorand Address") },
                        placeholder = { Text("58 characters") },
                        trailingIcon = {
                            IconButton(onClick = { showQrScanner = true }) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = "Scan QR Code",
                                    tint = Color(0xFF80CBC4)
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF1A1C1E),
                            unfocusedContainerColor = Color(0xFF1A1C1E),
                            focusedBorderColor = Color(0xFF80CBC4),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("contact_address_input")
                    )
 
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Note (Optional)") },
                        placeholder = { Text("e.g. Meetup friend") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF1A1C1E),
                            unfocusedContainerColor = Color(0xFF1A1C1E),
                            focusedBorderColor = Color(0xFF80CBC4),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("contact_note_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (nickname.trim().isEmpty() || address.trim().isEmpty()) {
                            Toast.makeText(context, "Fill in all fields", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        viewModel.addContact(address, nickname, note)
                        showAddContactDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF80CBC4),
                        contentColor = Color(0xFF00332C)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Add Contact", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddContactDialog = false }) {
                    Text("Cancel", color = Color(0xFFE2E2E6))
                }
            }
        )
    }

    if (editingContact != null) {
        var nickname by remember { mutableStateOf(editingContact!!.nickname) }
        var note by remember { mutableStateOf(editingContact!!.note) }

        AlertDialog(
            onDismissRequest = { editingContact = null },
            containerColor = Color(0xFF1F2124),
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(16.dp),
            title = { Text("Edit Contact", color = Color(0xFFE2E2E6), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        label = { Text("Nickname") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFE2E2E6),
                            unfocusedTextColor = Color(0xFFE2E2E6),
                            focusedContainerColor = Color(0xFF1A1C1E),
                            unfocusedContainerColor = Color(0xFF1A1C1E),
                            focusedBorderColor = Color(0xFF80CBC4),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Additional info / Notes (Optional)") },
                        placeholder = { Text("e.g. Meetup friend") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFE2E2E6),
                            unfocusedTextColor = Color(0xFFE2E2E6),
                            focusedContainerColor = Color(0xFF1A1C1E),
                            unfocusedContainerColor = Color(0xFF1A1C1E),
                            focusedBorderColor = Color(0xFF80CBC4),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (nickname.trim().isEmpty()) {
                            Toast.makeText(context, "Nickname cannot be empty", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        viewModel.updateContactDetails(editingContact!!.address, nickname, note)
                        editingContact = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF80CBC4))
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingContact = null }) {
                    Text("Cancel", color = Color(0xFFE2E2E6))
                }
            }
        )
    }

    if (contactToDelete != null) {
        AlertDialog(
            onDismissRequest = { contactToDelete = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(16.dp),
            title = { Text("Delete Contact", color = Color.White) },
            text = { Text("Are you sure you want to delete contact ${contactToDelete!!.nickname}?", color = Color.White.copy(alpha = 0.8f)) },
            containerColor = Color(0xFF1F2124),
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteContact(contactToDelete!!.address)
                        contactToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFFB4AB))
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { contactToDelete = null }) {
                    Text("Cancel", color = Color(0xFFE2E2E6))
                }
            }
        )
    }
}

@Composable
fun ContactRow(
    contact: Contact,
    showDelete: Boolean = true,
    hasUnread: Boolean = false,
    onClick: () -> Unit,
    onPinToggle: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: () -> Unit
) {
    val isEncryptedNotes = contact.id == -999 || contact.nickname == "Encrypted Notes"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .then(
                if (isEncryptedNotes) {
                    Modifier.drawWithContent {
                        drawContent()
                        drawRect(
                            color = Color(0xFF80CBC4),
                            topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                            size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)
                        )
                    }
                } else {
                    Modifier
                }
            )
            .padding(start = if (isEncryptedNotes) 24.dp else 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val avatarColorPair = remember(contact.address) {
                val avatarColors = listOf(
                    Color(0xFF1B5E20) to Color(0xFFC8E6C9),
                    Color(0xFF0D47A1) to Color(0xFFBBDEFB),
                    Color(0xFF311B92) to Color(0xFFD1C4E9),
                    Color(0xFFE65100) to Color(0xFFFFE0B2),
                    Color(0xFF006064) to Color(0xFFB2DFDB),
                    Color(0xFF4A148C) to Color(0xFFE1BEE7),
                    Color(0xFF880E4F) to Color(0xFFF8BBD0),
                    Color(0xFF3E2723) to Color(0xFFD7CCC8)
                )
                val idx = kotlin.math.abs(contact.address.hashCode()) % avatarColors.size
                avatarColors[idx]
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (!showDelete) Color(0xFF004D40) else avatarColorPair.first),
                contentAlignment = Alignment.Center
            ) {
                if (!showDelete) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = "Encrypted Notes",
                        tint = Color(0xFF80CBC4),
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Text(
                        text = contact.nickname.take(2).uppercase(),
                        color = avatarColorPair.second,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
 
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = contact.nickname,
                        color = Color(0xFFE2E2E6),
                        fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!showDelete) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF004D40))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "ME",
                                color = Color(0xFF80CBC4),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (hasUnread) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF81C784))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "NOWE",
                                color = Color(0xFF112D15),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                if (!showDelete) {
                    Text(
                        text = contact.note,
                        color = Color(0xFFE2E2E6).copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = "${contact.address.take(8)}...${contact.address.takeLast(8)}",
                        color = Color(0xFFE2E2E6).copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (contact.note.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF00373A))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = contact.note,
                                color = Color(0xFF4DB6AC),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
 
        if (showDelete) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(onClick = { onPinToggle?.invoke() }) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Przypnij",
                        tint = if (contact.pinned) Color(0xFF81C784) else Color(0xFFE2E2E6).copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = { onEdit?.invoke() }) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edytuj",
                        tint = Color(0xFFE2E2E6).copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = Color(0xFFFFB4AB).copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        } else {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Osobisty notatnik",
                tint = Color(0xFF81C784).copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp).padding(end = 4.dp)
            )
        }
    }
}

// ── 5. SECURE CHAT CONVERSATION VIEW ──

fun resizeBitmapToMaxBounds(bitmap: Bitmap, maxWidth: Int = 1080, maxHeight: Int = 1920): Bitmap {
    val originalWidth = bitmap.width
    val originalHeight = bitmap.height
    
    val scaleX = maxWidth.toFloat() / originalWidth
    val scaleY = maxHeight.toFloat() / originalHeight
    val scale = Math.min(scaleX, scaleY)
    
    if (scale >= 1.0f) {
        return bitmap
    }
    
    val newWidth = (originalWidth * scale).toInt()
    val newHeight = (originalHeight * scale).toInt()
    
    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
}

@Composable
fun ChatConversationView(
    viewModel: ChatViewModel,
    state: WalletState.Unlocked,
    contact: Contact,
    onBack: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val chatCutoffs by viewModel.chatCutoffs.collectAsState()
    val cutoff = chatCutoffs[contact.address]
    val isChatCleared = cutoff != null
    var showClearChatConfirm by remember { mutableStateOf(false) }
    val displayedMessages = remember(messages, cutoff, contact) {
        if (cutoff != null) {
            messages.filter { it.timestamp >= cutoff }
        } else {
            messages
        }
    }
    val lastImageMessageId = remember(displayedMessages, contact) {
        displayedMessages.lastOrNull { it.decryptedText.startsWith("[SECURE_IMAGE_CID:") }?.txId
    }
    var textInput by remember(contact.address) { mutableStateOf("") }
    val lazyListState = remember(contact.address) { androidx.compose.foundation.lazy.LazyListState() }
    var initialScrollDone by remember(contact.address) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val isMainnet by viewModel.isMainnet.collectAsState()
    val balance by viewModel.balance.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    val prefs = remember { context.getSharedPreferences("algopriv_prefs", Context.MODE_PRIVATE) }
    val hasPinata = (prefs.getString("pinata_jwt_token", "") ?: "").isNotEmpty() &&
                    (prefs.getString("pinata_gateway_url", "") ?: "").isNotEmpty()
    var isUploadingImage by remember { mutableStateOf(false) }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { selectedUri ->
            selectedImageUri = selectedUri
        }
    }

    var showSendAlgosDialog by remember { mutableStateOf(false) }

    if (isUploadingImage) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = Color(0xFF1F2124),
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(16.dp),
            title = { Text("Securing Media...", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    CircularProgressIndicator(color = Color(0xFF80CBC4))
                    Text("Encrypting locally and uploading to Pinata IPFS...", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
            },
            confirmButton = {}
        )
    }

    var lastSeenMessageCount by remember { mutableStateOf(displayedMessages.size) }
    var hasUnreadMessages by remember { mutableStateOf(false) }

    val isAtBottom by remember {
        derivedStateOf {
            if (displayedMessages.isEmpty()) {
                true
            } else {
                val layoutInfo = lazyListState.layoutInfo
                val visibleItemsInfo = layoutInfo.visibleItemsInfo
                if (visibleItemsInfo.isEmpty()) {
                    true
                } else {
                    val lastVisibleItem = visibleItemsInfo.last()
                    lastVisibleItem.index >= layoutInfo.totalItemsCount - 1
                }
            }
        }
    }

    // Scroll to bottom on entering the chat as soon as messages load
    LaunchedEffect(displayedMessages) {
        if (displayedMessages.isNotEmpty() && !initialScrollDone) {
            delay(150) // wait for database emission and layout composition
            if (displayedMessages.isNotEmpty() && !initialScrollDone) {
                lazyListState.scrollToItem(displayedMessages.size - 1)
                initialScrollDone = true
                hasUnreadMessages = false
                lastSeenMessageCount = displayedMessages.size
            }
        }
    }

    // Auto scroll to bottom when new messages arrive (after initial load)
    LaunchedEffect(displayedMessages.size) {
        if (initialScrollDone && displayedMessages.isNotEmpty()) {
            val lastMsg = displayedMessages.lastOrNull()
            val isMyMessage = lastMsg != null && lastMsg.sender == state.address
            val isIncoming = lastMsg != null && lastMsg.sender != state.address
            
            val isAtNewest = isAtBottom

            if (isMyMessage || isAtNewest) {
                // Smooth scroll to bottom for sent messages or if already at bottom
                scope.launch {
                    delay(100)
                    if (displayedMessages.isNotEmpty()) {
                        lazyListState.animateScrollToItem(displayedMessages.size - 1)
                    }
                }
                hasUnreadMessages = false
                lastSeenMessageCount = displayedMessages.size
            } else {
                if (isIncoming) {
                    hasUnreadMessages = true
                }
            }
        }
    }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            hasUnreadMessages = false
            lastSeenMessageCount = displayedMessages.size
        }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val keyboardHeight = WindowInsets.ime.getBottom(density)
    LaunchedEffect(keyboardHeight) {
        if (keyboardHeight > 0 && displayedMessages.isNotEmpty()) {
            delay(150) // wait for keyboard/layout resize
            if (displayedMessages.isNotEmpty()) {
                lazyListState.animateScrollToItem(displayedMessages.size - 1)
            }
        }
    }

    LaunchedEffect(textInput) {
        if (textInput.isNotEmpty() && displayedMessages.isNotEmpty()) {
            delay(100)
            if (displayedMessages.isNotEmpty()) {
                lazyListState.animateScrollToItem(if (contact.id == -1) 0 else displayedMessages.size - 1)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF1A1C1E),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(0xFF80CBC4))
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF80CBC4).copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = contact.nickname.take(2).uppercase(),
                            color = Color(0xFF80CBC4),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Column {
                        Text(
                            text = contact.nickname,
                            color = Color(0xFFE2E2E6),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50))
                            )
                            Text(
                                text = "addr...${contact.address.takeLast(4)}",
                                color = Color(0xFFE2E2E6).copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = { showSendAlgosDialog = true }) {
                        Icon(imageVector = Icons.Default.MonetizationOn, contentDescription = "Pay ALGO", tint = Color(0xFF80CBC4))
                    }
                    IconButton(onClick = { showClearChatConfirm = true }) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Clear Chat Session", tint = Color(0xFFE57373))
                    }
                }
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding()
                    .padding(8.dp)
            ) {
                // If chat is cleared, show the restore bar
                if (isChatCleared) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .background(Color(0xFF242629), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "History cleared",
                                tint = Color(0xFF80CBC4).copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Chat cleared locally for this session.",
                                color = Color(0xFFE2E2E6).copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        
                        Text(
                            text = "Restore",
                            color = Color(0xFF81C784),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    viewModel.restoreChatForSession(contact.address)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Attached Image Preview
                if (selectedImageUri != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .background(Color(0xFF242629), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.2f))
                            ) {
                                coil.compose.AsyncImage(
                                    model = selectedImageUri,
                                    contentDescription = "Attached Image Preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            
                            Column {
                                Text(
                                    text = "Attached Image",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Will be encrypted and sent with your message",
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        
                        IconButton(
                            onClick = { selectedImageUri = null }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove Attached Image",
                                tint = Color(0xFFE57373),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Quick Action Button ("+" to attach ALGO transaction)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF303034))
                            .clickable { showSendAlgosDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Add Tx", tint = Color(0xFF80CBC4))
                    }
 
                    if (hasPinata) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF303034))
                                .clickable {
                                    prefs.edit().putBoolean("ignore_next_lock", true).apply()
                                    imagePickerLauncher.launch("image/*")
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Default.Image, contentDescription = "Attach Image", tint = Color(0xFF80CBC4))
                        }
                    }
 
                    val isBalanceSufficient = balance >= 101000L
                    val canSend = (textInput.trim().isNotEmpty() || selectedImageUri != null) && isBalanceSufficient

                    // Input Pill with inline Send action
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .background(Color(0xFF303034), CircleShape)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            textStyle = TextStyle(color = Color(0xFFE2E2E6), fontSize = 14.sp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("message_input_field"),
                            decorationBox = @Composable { innerTextField ->
                                if (textInput.isEmpty()) {
                                    Text("Message", color = Color.White.copy(alpha = 0.3f), fontSize = 14.sp)
                                }
                                innerTextField()
                            }
                        )
 
                        IconButton(
                            onClick = {
                                if (canSend) {
                                    val currentText = textInput
                                    val currentImageUri = selectedImageUri
                                    
                                    // Reset inputs immediately
                                    textInput = ""
                                    selectedImageUri = null
                                    keyboardController?.hide()
                                    
                                    scope.launch {
                                        if (currentImageUri != null) {
                                            isUploadingImage = true
                                            withContext(Dispatchers.IO) {
                                                try {
                                                    val inputStream = context.contentResolver.openInputStream(currentImageUri)
                                                    val originalBitmap = BitmapFactory.decodeStream(inputStream)
                                                    inputStream?.close()
                                                    
                                                    if (originalBitmap == null) {
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(context, "Failed to read image data", Toast.LENGTH_SHORT).show()
                                                            isUploadingImage = false
                                                        }
                                                        return@withContext
                                                    }
                                                    
                                                    // Resize bitmap to max width 1080 or max height 1920
                                                    val resized = resizeBitmapToMaxBounds(originalBitmap)
                                                    
                                                    // Compress to JPEG with 85% quality
                                                    val outputStream = java.io.ByteArrayOutputStream()
                                                    resized.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                                                    val compressedBytes = outputStream.toByteArray()
                                                    
                                                    // Encrypt image bytes
                                                    val encryptedImageBytes = AlgorandCrypto.encryptImage(compressedBytes, state.address, contact.address)
                                                    
                                                    // Create and encrypt thumbnail (max 180x180, 70% quality)
                                                    val thumbResized = resizeBitmapToMaxBounds(originalBitmap, 180, 180)
                                                    val thumbOutputStream = java.io.ByteArrayOutputStream()
                                                    thumbResized.compress(Bitmap.CompressFormat.JPEG, 70, thumbOutputStream)
                                                    val thumbBytes = thumbOutputStream.toByteArray()
                                                    val encryptedThumbBytes = AlgorandCrypto.encryptImage(thumbBytes, state.address, contact.address)
                                                    
                                                    // Upload to Pinata
                                                    val jwt = prefs.getString("pinata_jwt_token", "") ?: ""
                                                    val cid = PinataClient.uploadEncryptedFile(jwt, encryptedImageBytes)
                                                    val thumbCid = PinataClient.uploadEncryptedFile(jwt, encryptedThumbBytes)
                                                    
                                                    withContext(Dispatchers.Main) {
                                                        viewModel.sendMessage("[SECURE_IMAGE_CID:$cid][SECURE_THUMB_CID:$thumbCid]")
                                                        if (currentText.trim().isNotEmpty()) {
                                                            viewModel.sendMessage(currentText)
                                                        }
                                                        isUploadingImage = false
                                                    }
                                                } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(context, "Secure image transfer failed: ${e.message}", Toast.LENGTH_LONG).show()
                                                        isUploadingImage = false
                                                    }
                                                }
                                            }
                                        } else {
                                            if (currentText.trim().isNotEmpty()) {
                                                viewModel.sendMessage(currentText)
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = canSend
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (canSend) Color(0xFF80CBC4) else Color(0xFF80CBC4).copy(alpha = 0.4f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Decryption banner inside current Chat Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF004D40).copy(alpha = 0.15f))
                    .border(1.dp, Color(0xFF004D40).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock",
                        tint = Color(0xFF80CBC4),
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "E2E ALGORAND ENCRYPTED CHANNEL",
                        color = Color(0xFF80CBC4),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (contact.id == -1) {
                var showAddContactFromChatDialog by remember { mutableStateOf(false) }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3B2F1D)),
                    border = BorderStroke(1.dp, Color(0xFFE5A93B).copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Warning",
                                tint = Color(0xFFE5A93B),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "This is an unknown sender (not in contacts)",
                                color = Color(0xFFE2E2E6),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        Text(
                            text = "Messages from this address may be unwanted. If you recognize this address, you can add it to your contacts to easily identify it.",
                            color = Color(0xFFE2E2E6).copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = { showAddContactFromChatDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF80CBC4)),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 8.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Not spam, add contact", color = Color(0xFF00332C), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { viewModel.selectContact(null) },
                                border = BorderStroke(1.dp, Color(0xFFFFB4AB)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB4AB)),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 8.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Go back to list", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                if (showAddContactFromChatDialog) {
                    var nickname by remember { mutableStateOf("") }
                    var note by remember { mutableStateOf("") }

                    AlertDialog(
                        onDismissRequest = { showAddContactFromChatDialog = false },
                        containerColor = Color(0xFF1F2124),
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(imageVector = Icons.Default.PersonAdd, contentDescription = null, tint = Color(0xFF80CBC4))
                                Text("Add to contacts", color = Color(0xFFE2E2E6))
                            }
                        },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Address: ${contact.address}",
                                    color = Color(0xFFE2E2E6).copy(alpha = 0.7f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )

                                OutlinedTextField(
                                    value = nickname,
                                    onValueChange = { nickname = it },
                                    label = { Text("Contact Name / Nickname") },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color(0xFFE2E2E6),
                                        unfocusedTextColor = Color(0xFFE2E2E6),
                                        focusedBorderColor = Color(0xFF80CBC4),
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = note,
                                    onValueChange = { note = it },
                                    label = { Text("Note (optional)") },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color(0xFFE2E2E6),
                                        unfocusedTextColor = Color(0xFFE2E2E6),
                                        focusedBorderColor = Color(0xFF80CBC4),
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    if (nickname.isNotBlank()) {
                                        viewModel.addContactAndSelect(contact.address, nickname, note)
                                        showAddContactFromChatDialog = false
                                    }
                                },
                                enabled = nickname.isNotBlank()
                            ) {
                                Text("Add", fontWeight = FontWeight.Bold, color = if (nickname.isNotBlank()) Color(0xFF80CBC4) else Color.Gray)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAddContactFromChatDialog = false }) {
                                Text("Cancel", color = Color(0xFFE2E2E6))
                            }
                        }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = if (displayedMessages.isEmpty()) Alignment.Center else Alignment.BottomCenter
            ) {
                if (displayedMessages.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = "No messages",
                            tint = Color(0xFF80CBC4).copy(alpha = 0.25f),
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "No messages yet. Send something!",
                            color = Color(0xFFE2E2E6).copy(alpha = 0.4f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(displayedMessages) { msg ->
                            if (msg.amount > 0) {
                                // Custom Transaction card from HTML template Design rules
                                TransactionPreviewCard(msg, isMyTx = (msg.sender == state.address), isMainnet = isMainnet)
                            } else {
                                MessageBubble(
                                    msg = msg,
                                    isMyMessage = (msg.sender == state.address),
                                    isLatestImage = (msg.txId == lastImageMessageId),
                                    onImageLoaded = {
                                        scope.launch {
                                            delay(150)
                                            if (displayedMessages.isNotEmpty()) {
                                                lazyListState.animateScrollToItem(displayedMessages.size - 1)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = hasUnreadMessages,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { it })
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF00B0FF)),
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .clickable {
                                scope.launch {
                                    if (displayedMessages.isNotEmpty()) {
                                        lazyListState.animateScrollToItem(displayedMessages.size - 1)
                                    }
                                }
                            }
                            .testTag("scroll_to_bottom_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "Scroll to bottom",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "New messages",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSendAlgosDialog) {
        var algoAmount by remember { mutableStateOf("") }
        var attachText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showSendAlgosDialog = false },
            containerColor = Color(0xFF1F2124),
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(16.dp),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MonetizationOn,
                        contentDescription = "ALGO",
                        tint = Color(0xFFFFD54F)
                    )
                    Text(
                        "Secure ALGO Asset Transfer",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "This signs and broadcasts a real payment transaction onto the Algorand ledger containing your securely encrypted text message.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        lineHeight = 17.sp
                    )

                    OutlinedTextField(
                        value = algoAmount,
                        onValueChange = { algoAmount = it },
                        label = { Text("Amount (ALGO)") },
                        placeholder = { Text("e.g. 5.0") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF1A1C1E),
                            unfocusedContainerColor = Color(0xFF1A1C1E),
                            focusedBorderColor = Color(0xFF80CBC4),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("algo_transfer_amount")
                    )

                    OutlinedTextField(
                        value = attachText,
                        onValueChange = { attachText = it },
                        label = { Text("Attached Encrypted Message (Optional)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF1A1C1E),
                            unfocusedContainerColor = Color(0xFF1A1C1E),
                            focusedBorderColor = Color(0xFF80CBC4),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("algo_transfer_message")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amountVal = algoAmount.toDoubleOrNull()
                        if (amountVal == null || amountVal < 0) {
                            Toast.makeText(context, "Invalid amount", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val microAlgos = (amountVal * 1000000).toLong()
                        viewModel.sendMessage(attachText, microAlgos)
                        showSendAlgosDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF80CBC4),
                        contentColor = Color(0xFF00332C)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Broadcast & Send", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSendAlgosDialog = false }) {
                    Text("Cancel", color = Color(0xFFE2E2E6))
                }
            }
        )
    }

    if (showClearChatConfirm) {
        AlertDialog(
            onDismissRequest = { showClearChatConfirm = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(16.dp),
            title = {
                Text(
                    text = "Clear Local History?",
                    color = Color(0xFFE2E2E6),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will temporarily hide your chat history with this contact for this session. It will NOT delete any data from the blockchain ledger. You can restore your full chat history at any time using the 'Restore' button.",
                    color = Color(0xFFE2E2E6).copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearChatForSession(contact.address)
                        showClearChatConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE57373),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Clear Locally", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearChatConfirm = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE2E2E6).copy(alpha = 0.6f))
                ) {
                    Text("Cancel")
                }
            },
            containerColor = Color(0xFF1F2124),
            tonalElevation = 6.dp
        )
    }
}

// ── 6. CHAT BUBBLE COMPONENT ──

object SecureImageCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    fun get(cid: String): Bitmap? = cache[cid]
    fun put(cid: String, bitmap: Bitmap) {
        cache[cid] = bitmap
    }
}

sealed class ImageLoadState {
    object Placeholder : ImageLoadState()
    object Loading : ImageLoadState()
    data class Success(val bitmap: Bitmap) : ImageLoadState()
    data class Error(val message: String) : ImageLoadState()
}

@Composable
fun FullScreenImageDialog(
    mainCid: String,
    msg: LocalMessage,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("algopriv_prefs", Context.MODE_PRIVATE) }
    val gatewayUrl = remember { prefs.getString("pinata_gateway_url", "") ?: "" }
    val jwt = remember { prefs.getString("pinata_jwt_token", "") ?: "" }
    
    val cachedBitmap = SecureImageCache.get(mainCid)
    var fullImageState by remember(mainCid) {
        mutableStateOf<ImageLoadState>(
            if (cachedBitmap != null) ImageLoadState.Success(cachedBitmap)
            else ImageLoadState.Loading
        )
    }
    
    LaunchedEffect(mainCid) {
        if (fullImageState is ImageLoadState.Loading) {
            val cached = SecureImageCache.get(mainCid)
            if (cached != null) {
                fullImageState = ImageLoadState.Success(cached)
                return@LaunchedEffect
            }
            withContext(Dispatchers.IO) {
                try {
                    val base = if (gatewayUrl.isNotEmpty()) gatewayUrl else "https://gateway.pinata.cloud"
                    val url = if (base.endsWith("/")) "${base}ipfs/$mainCid" else "$base/ipfs/$mainCid"
                    
                    val request = Request.Builder()
                        .url(url)
                        .apply {
                            if (jwt.isNotEmpty()) {
                                addHeader("Authorization", "Bearer $jwt")
                            }
                        }
                        .get()
                        .build()
                    
                    val client = OkHttpClient()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw java.io.IOException("HTTP error code ${response.code}")
                        }
                        val encryptedBytes = response.body?.bytes() ?: throw java.io.IOException("Empty response body")
                        
                        val decryptedBytes = AlgorandCrypto.decryptImage(encryptedBytes, msg.sender, msg.receiver)
                        val bitmap = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                        if (bitmap != null) {
                            SecureImageCache.put(mainCid, bitmap)
                            fullImageState = ImageLoadState.Success(bitmap)
                        } else {
                            fullImageState = ImageLoadState.Error("Failed to parse full image data")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FullScreenImageDialog", "Error loading/decrypting full image", e)
                    fullImageState = ImageLoadState.Error(e.message ?: "Unknown error")
                }
            }
        }
    }

    // Hide system notification/status/navigation bars for true immersive full screen experience
    val activity = context as? android.app.Activity
    DisposableEffect(Unit) {
        activity?.window?.let { win ->
            androidx.core.view.WindowCompat.getInsetsController(win, win.decorView).apply {
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            activity?.window?.let { win ->
                androidx.core.view.WindowCompat.getInsetsController(win, win.decorView).apply {
                    show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                when (val state = fullImageState) {
                    is ImageLoadState.Loading -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(color = Color(0xFF81C784))
                            Text(
                                "Decrypting full image...",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        }
                    }
                    is ImageLoadState.Success -> {
                        Image(
                            bitmap = state.bitmap.asImageBitmap(),
                            contentDescription = "Full Screen Decrypted Image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                    is ImageLoadState.Error -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.BrokenImage,
                                contentDescription = "Error",
                                tint = Color(0xFFE57373),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                "Failed to load full image",
                                color = Color(0xFFE57373),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                state.message,
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun SecureImageBubble(
    msg: LocalMessage,
    isMyMessage: Boolean,
    isLatestImage: Boolean,
    onImageLoaded: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("algopriv_prefs", Context.MODE_PRIVATE) }
    val gatewayUrl = remember { prefs.getString("pinata_gateway_url", "") ?: "" }
    val jwt = remember { prefs.getString("pinata_jwt_token", "") ?: "" }
    
    val mainCid = remember(msg.decryptedText) {
        msg.decryptedText.substringAfter("[SECURE_IMAGE_CID:").substringBefore("]")
    }
    val thumbCid = remember(msg.decryptedText) {
        if (msg.decryptedText.contains("[SECURE_THUMB_CID:")) {
            msg.decryptedText.substringAfter("[SECURE_THUMB_CID:").substringBefore("]")
        } else {
            null
        }
    }
    val previewCid = thumbCid ?: mainCid
    
    val cachedBitmap = SecureImageCache.get(previewCid)
    var imageState by remember(previewCid, isLatestImage) {
        mutableStateOf<ImageLoadState>(
            if (cachedBitmap != null) {
                ImageLoadState.Success(cachedBitmap)
            } else if (isLatestImage) {
                ImageLoadState.Loading
            } else {
                ImageLoadState.Placeholder
            }
        )
    }
    
    LaunchedEffect(imageState) {
        if (imageState is ImageLoadState.Success && isLatestImage) {
            onImageLoaded()
        }
    }
    
    LaunchedEffect(previewCid, imageState) {
        if (imageState is ImageLoadState.Loading) {
            val cached = SecureImageCache.get(previewCid)
            if (cached != null) {
                imageState = ImageLoadState.Success(cached)
                return@LaunchedEffect
            }
            withContext(Dispatchers.IO) {
                try {
                    val base = if (gatewayUrl.isNotEmpty()) gatewayUrl else "https://gateway.pinata.cloud"
                    val url = if (base.endsWith("/")) "${base}ipfs/$previewCid" else "$base/ipfs/$previewCid"
                    
                    val request = Request.Builder()
                        .url(url)
                        .apply {
                            if (jwt.isNotEmpty()) {
                                addHeader("Authorization", "Bearer $jwt")
                            }
                        }
                        .get()
                        .build()
                    
                    val client = OkHttpClient()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw java.io.IOException("HTTP error code ${response.code}")
                        }
                        val encryptedBytes = response.body?.bytes() ?: throw java.io.IOException("Empty response body")
                        
                        val decryptedBytes = AlgorandCrypto.decryptImage(encryptedBytes, msg.sender, msg.receiver)
                        val bitmap = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                        if (bitmap != null) {
                            SecureImageCache.put(previewCid, bitmap)
                            imageState = ImageLoadState.Success(bitmap)
                        } else {
                            imageState = ImageLoadState.Error("Failed to parse image data")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SecureImageBubble", "Error loading/decrypting image", e)
                    imageState = ImageLoadState.Error(e.message ?: "Unknown error")
                }
            }
        }
    }
    
    var showFullScreen by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .size(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF242629)),
        contentAlignment = Alignment.Center
    ) {
        when (val state = imageState) {
            is ImageLoadState.Placeholder -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { imageState = ImageLoadState.Loading }
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = "Decrypt Image",
                            tint = Color(0xFF81C784),
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = if (thumbCid != null) "Decrypt thumbnail" else "Decrypt secure image",
                            color = Color(0xFF81C784),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "(Click to decrypt & view)",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 9.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            is ImageLoadState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF81C784),
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = if (thumbCid != null) "Decrypting thumbnail..." else "Decrypting image...",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            is ImageLoadState.Success -> {
                Image(
                    bitmap = state.bitmap.asImageBitmap(),
                    contentDescription = "Decrypted secure image",
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { showFullScreen = true }
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
                
                if (showFullScreen) {
                    FullScreenImageDialog(
                        mainCid = mainCid,
                        msg = msg,
                        onDismiss = { showFullScreen = false }
                    )
                }
            }
            is ImageLoadState.Error -> {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = "Error",
                        tint = Color(0xFFE57373)
                    )
                    Text(
                        "Decryption Failed",
                        color = Color(0xFFE57373),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        state.message,
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    msg: LocalMessage,
    isMyMessage: Boolean,
    isLatestImage: Boolean = false,
    onImageLoaded: () -> Unit = {}
) {
    val formatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val timeString = remember(msg.timestamp) { formatter.format(Date(msg.timestamp * 1000)) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMyMessage) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.85f),
            horizontalAlignment = if (isMyMessage) Alignment.End else Alignment.Start
        ) {
            val isImage = msg.decryptedText.startsWith("[SECURE_IMAGE_CID:")
            if (isImage) {
                SecureImageBubble(msg = msg, isMyMessage = isMyMessage, isLatestImage = isLatestImage, onImageLoaded = onImageLoaded)
            } else {
                Box(
                    modifier = Modifier
                        .clip(
                            RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = if (isMyMessage) 16.dp else 0.dp,
                                bottomEnd = if (isMyMessage) 0.dp else 16.dp
                            )
                        )
                        .background(if (isMyMessage) Color(0xFF80CBC4) else Color(0xFF303034))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = msg.decryptedText,
                        color = if (isMyMessage) Color(0xFF00332C) else Color(0xFFE2E2E6),
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = if (isMyMessage) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }

            Row(
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = timeString,
                    color = Color(0xFFE2E2E6).copy(alpha = 0.5f),
                    fontSize = 10.sp
                )
                if (isMyMessage) {
                    Icon(
                        imageVector = if (msg.status == "SENT") Icons.Default.DoneAll else Icons.Default.Done,
                        contentDescription = "Status",
                        tint = if (msg.status == "SENT") Color(0xFF81C784) else Color(0xFFE2E2E6).copy(alpha = 0.3f),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

// ── 7. RECENT TRANSACTION CARD COMPONENT (Strictly matching HTML mockup styling) ──

@Composable
fun TransactionPreviewCard(msg: LocalMessage, isMyTx: Boolean, isMainnet: Boolean = true) {
    val formatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val timeString = remember(msg.timestamp) { formatter.format(Date(msg.timestamp * 1000)) }
    val isDemo = msg.txId.startsWith("DEMO") || msg.txId.startsWith("TX_")
    val context = LocalContext.current

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF242629)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF004D40)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "Transfer",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Column {
                        Text(
                            text = if (isMyTx) "- %.2f ALGO".format(msg.amount / 1000000.0) else "+ %.2f ALGO".format(msg.amount / 1000000.0),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE2E2E6)
                        )
                        Text(
                            text = if (msg.decryptedText.isNotEmpty()) msg.decryptedText else "Asset Transfer Note",
                            fontSize = 11.sp,
                            color = Color(0xFFE2E2E6).copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x1A4CAF50))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "SUCCESS",
                        color = Color(0xFF81C784),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeString,
                    fontSize = 10.sp,
                    color = Color(0xFFE2E2E6).copy(alpha = 0.5f)
                )
            }
        }
    }
}

// ── 8. PLACEHOLDERS ──

@Composable
fun NoChatSelectedPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1C1E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.VerifiedUser,
                contentDescription = "Security Shield",
                tint = Color(0xFF80CBC4).copy(alpha = 0.2f),
                modifier = Modifier.size(72.dp)
            )
            Text(
                text = "Select a secure contact to begin\nend-to-end encrypted messaging.",
                color = Color(0xFFE2E2E6).copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

enum class SettingsAuthAction {
    REVEAL_SEED,
    EXPORT_CONTACTS_FILE,
    REVEAL_MANUAL_CONTACTS
}

@Composable
fun SettingsAuthDialog(
    isBiometricEnabled: Boolean,
    onVerifyPasscode: (String) -> Boolean,
    onDecryptBiometric: () -> String?,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    var passcode by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1F2124),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = Color(0xFF80CBC4))
                Text("Security Authorization", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Please enter your passcode to verify your identity.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp
                )

                OutlinedTextField(
                    value = passcode,
                    onValueChange = {
                        passcode = it
                        pinError = false
                    },
                    placeholder = { Text("Enter Passcode", color = Color.White.copy(alpha = 0.3f)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = pinError,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color(0xFF1A1C1E),
                        unfocusedContainerColor = Color(0xFF1A1C1E),
                        focusedBorderColor = if (pinError) Color(0xFFFFB4AB) else Color(0xFF80CBC4),
                        unfocusedBorderColor = if (pinError) Color(0xFFFFB4AB).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (pinError) {
                    Text("Incorrect passcode. Please try again.", color = Color(0xFFFFB4AB), fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (onVerifyPasscode(passcode)) {
                        onSuccess()
                    } else {
                        pinError = true
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF80CBC4),
                    contentColor = Color(0xFF00332C)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Confirm", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFFE2E2E6))
            }
        }
    )
}

@Composable
fun DonationDialog(onDismiss: () -> Unit) {
    val address = "RHSABLHWXLBBO6BRM6KKJL3M7TTVOYATFWOTITW7PMDTRFH6HEY2HKTMSA"
    val context = LocalContext.current
    
    val bitMatrix = remember(address) {
        try {
            QRCodeWriter().encode(address, BarcodeFormat.QR_CODE, 200, 200)
        } catch (e: Exception) {
            android.util.Log.e("DonationDialog", "Failed to generate QR code matrix", e)
            null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF0F0B13)) // Extremely dark cosmic purple
                .border(1.5.dp, Color(0xFFD81B60).copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            // Close Button at top right
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1A24))
                    .border(1.dp, Color(0xFFD81B60).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color(0xFFD81B60),
                    modifier = Modifier.size(16.dp)
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Heart",
                        tint = Color(0xFFE91E63),
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Support Allo•Alu!",
                        color = Color(0xFFE91E63),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }

                // Subtitle
                Text(
                    text = "Support Allo•Alu development with a donation in ALGO!",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                // Description
                Text(
                    text = "Scan the QR code with your mobile wallet to send any amount. Thank you! ☕",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                // QR Code
                Box(
                    modifier = Modifier
                        .size(250.dp)
                        .background(Color.White, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        if (bitMatrix != null) {
                            val matrixWidth = bitMatrix.width
                            val matrixHeight = bitMatrix.height
                            val cellWidth = this.size.width / matrixWidth
                            val cellHeight = this.size.height / matrixHeight

                            for (y in 0 until matrixHeight) {
                                for (x in 0 until matrixWidth) {
                                    if (bitMatrix.get(x, y)) {
                                        drawRect(
                                            color = Color.Black,
                                            topLeft = androidx.compose.ui.geometry.Offset(x * cellWidth, y * cellHeight),
                                            size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Monospace Address with Dashed Border
                val stroke = remember {
                    Stroke(
                        width = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            drawRoundRect(
                                color = Color(0xFFE91E63).copy(alpha = 0.5f),
                                style = stroke,
                                cornerRadius = CornerRadius(12.dp.toPx())
                            )
                        }
                        .padding(vertical = 12.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = address,
                        color = Color(0xFFF48FB1),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 14.sp
                    )
                }

                // Copy Wallet Address button
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Allo•Alu Address", address)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Wallet address copied to clipboard!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE91E63),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Copy Wallet Address",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(
    viewModel: ChatViewModel,
    state: WalletState.Unlocked,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val contacts by viewModel.contacts.collectAsState()
    val prefs = remember { context.getSharedPreferences("algopriv_prefs", Context.MODE_PRIVATE) }
    var isBiometricEnabled by remember { mutableStateOf(prefs.getBoolean("use_biometrics", false)) }
    var showBiometricConfirmDialog by remember { mutableStateOf(false) }
    var biometricPasscodeConfirmation by remember { mutableStateOf("") }
    var biometricConfirmError by remember { mutableStateOf(false) }
    
    var showSeedPhrase by remember { mutableStateOf(false) }
    var seedPhraseCopied by remember { mutableStateOf(false) }
    var showManualPanel by remember { mutableStateOf(false) }
    
    var pendingAuthAction by remember { mutableStateOf<SettingsAuthAction?>(null) }
    var isSeedAuthorized by remember { mutableStateOf(false) }
    var isExportAuthorized by remember { mutableStateOf(false) }
    var isManualAuthorized by remember { mutableStateOf(false) }
    var showDeleteAccountConfirm by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri ->
            uri?.let {
                try {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        val contactsString = contacts.joinToString("\n") { c -> "${c.nickname},${c.address}" }
                        outputStream.write(contactsString.toByteArray())
                    }
                    Toast.makeText(context, "Contacts exported successfully!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    )

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let {
                try {
                    context.contentResolver.openInputStream(it)?.use { inputStream ->
                        val text = inputStream.bufferedReader().use { r -> r.readText() }
                        viewModel.importContactsFromText(text) { imported, skipped ->
                            Toast.makeText(context, "Import complete! Imported: $imported, Skipped/Duplicates: $skipped", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Import error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    )

    if (pendingAuthAction != null) {
        SettingsAuthDialog(
            isBiometricEnabled = false,
            onVerifyPasscode = { passcode ->
                viewModel.verifyPasscode(passcode)
            },
            onDecryptBiometric = { null },
            onSuccess = {
                when (pendingAuthAction) {
                    SettingsAuthAction.REVEAL_SEED -> {
                        isSeedAuthorized = true
                        showSeedPhrase = true
                    }
                    SettingsAuthAction.EXPORT_CONTACTS_FILE -> {
                        isExportAuthorized = true
                        context.getSharedPreferences("algopriv_prefs", Context.MODE_PRIVATE).edit().putBoolean("ignore_next_lock", true).apply()
                        exportLauncher.launch("contacts_algopriv.txt")
                    }
                    SettingsAuthAction.REVEAL_MANUAL_CONTACTS -> {
                        isManualAuthorized = true
                        showManualPanel = true
                    }
                    null -> {}
                }
                pendingAuthAction = null
            },
            onDismiss = {
                pendingAuthAction = null
            }
        )
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1F2124))
                .border(1.5.dp, Color(0xFF80CBC4).copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFF80CBC4))
                        Text("Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                // Scrollable Content
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                // Sec 1: Profil / Adres
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2D31)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = Color(0xFF80CBC4),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "My Wallet Address",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = state.address,
                                color = Color(0xFF80CBC4),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // Sec 2: View Seed Phrase
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2D31)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Key,
                                        contentDescription = null,
                                        tint = Color(0xFF80CBC4),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        "Seed Phrase / Recovery Key",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        if (!showSeedPhrase && !isSeedAuthorized) {
                                            pendingAuthAction = SettingsAuthAction.REVEAL_SEED
                                        } else {
                                            showSeedPhrase = !showSeedPhrase
                                        }
                                    }
                                ) {
                                    Text(
                                        if (showSeedPhrase) "Hide" else "Show",
                                        color = Color(0xFF80CBC4),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            
                            if (showSeedPhrase) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "Save these words in a safe place. Anyone with access can take over your account and decrypt your messages.",
                                    color = Color(0xFFE57373),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                
                                // Elegant word grid
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF1E2022), RoundedCornerShape(8.dp))
                                        .padding(12.dp)
                                        .fillMaxWidth()
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val words = state.mnemonic.split(" ")
                                        words.chunked(3).forEachIndexed { rowIndex, wordRow ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                wordRow.forEachIndexed { colIndex, word ->
                                                    val wordIndex = rowIndex * 3 + colIndex + 1
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .background(Color(0xFF2B2D31), RoundedCornerShape(4.dp))
                                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                                    ) {
                                                        Text(
                                                            text = "$wordIndex. $word",
                                                            color = Color.White,
                                                            fontSize = 11.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                                if (wordRow.size < 3) {
                                                    repeat(3 - wordRow.size) {
                                                        Spacer(modifier = Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Seed Phrase", state.mnemonic)
                                        clipboard.setPrimaryClip(clip)
                                        seedPhraseCopied = true
                                        Toast.makeText(context, "Seed phrase copied!", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF004D40),
                                        contentColor = Color(0xFF80CBC4)
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(
                                        imageVector = if (seedPhraseCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                        contentDescription = "Copy",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Copy Seed Phrase", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // Sec 2.5: Auto-Lock / Inactivity Timeout
                item {
                    var autoLogoutMinutes by remember { mutableStateOf(prefs.getInt("auto_logout_minutes", -1)) }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2D31)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = Color(0xFF80CBC4),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "Auto-Lock / Logout Timeout",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Automatically logs you out and locks the app after being in the background or inactive for the selected time.",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val options = listOf(
                                    "Never" to -1,
                                    "5 min" to 5,
                                    "15 min" to 15,
                                    "1 hr" to 60
                                )
                                options.forEach { (label, minutes) ->
                                    val isSelected = autoLogoutMinutes == minutes
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isSelected) Color(0xFF004D40) else Color(0xFF1E2022)
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (isSelected) Color(0xFF80CBC4) else Color.Transparent,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                prefs.edit().putInt("auto_logout_minutes", minutes).apply()
                                                autoLogoutMinutes = minutes
                                                Toast.makeText(context, "Auto-lock timeout set to: $label", Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Sec 3: Export and Import Contacts
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2D31)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.People,
                                    contentDescription = null,
                                    tint = Color(0xFF80CBC4),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "Contacts Backup",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Export or import your contacts as a text file (CSV format: Nickname,Address).",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Vertical stack of wide buttons so they never clash on small screens
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (!isExportAuthorized) {
                                            pendingAuthAction = SettingsAuthAction.EXPORT_CONTACTS_FILE
                                        } else {
                                            context.getSharedPreferences("algopriv_prefs", Context.MODE_PRIVATE).edit().putBoolean("ignore_next_lock", true).apply()
                                            exportLauncher.launch("contacts_algopriv.txt")
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF004D40).copy(alpha = 0.8f),
                                        contentColor = Color(0xFF80CBC4)
                                    ),
                                    modifier = Modifier.fillMaxWidth().height(44.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Share, contentDescription = "Export", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Export Contacts to File", fontSize = 12.sp)
                                }

                                Button(
                                    onClick = {
                                        context.getSharedPreferences("algopriv_prefs", Context.MODE_PRIVATE).edit().putBoolean("ignore_next_lock", true).apply()
                                        importLauncher.launch(arrayOf("text/plain"))
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF80CBC4),
                                        contentColor = Color(0xFF00332C)
                                    ),
                                    modifier = Modifier.fillMaxWidth().height(44.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Add, contentDescription = "Import", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Import Contacts from File", fontSize = 12.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        if (!showManualPanel) {
                                            if (!isManualAuthorized) {
                                                pendingAuthAction = SettingsAuthAction.REVEAL_MANUAL_CONTACTS
                                            } else {
                                                showManualPanel = true
                                            }
                                        } else {
                                            showManualPanel = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(44.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF80CBC4)),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF80CBC4).copy(alpha = 0.3f))
                                ) {
                                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Manual Copy", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        if (showManualPanel) "Hide Manual Panel" else "Manual Text Backup & Import",
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            
                            if (showManualPanel) {
                                Spacer(modifier = Modifier.height(16.dp))
                                var manualText by remember { mutableStateOf("") }
                                val contactsCsv = remember(contacts) {
                                    contacts.joinToString("\n") { c -> "${c.nickname},${c.address}" }
                                }
                                
                                Text(
                                    "Current contacts (copy this text):",
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 10.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = contactsCsv,
                                    onValueChange = {},
                                    readOnly = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = Color(0xFF1E2022),
                                        unfocusedContainerColor = Color(0xFF1E2022),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(100.dp),
                                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Contacts CSV", contactsCsv)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Contact list copied!", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF303034),
                                        contentColor = Color(0xFFE2E2E6)
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Copy Contacts List", fontSize = 11.sp)
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "Paste contact list to import:",
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 10.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = manualText,
                                    onValueChange = { manualText = it },
                                    placeholder = { Text("Friend,ADDRESS...\nAnotherFriend,ADDRESS...", color = Color.White.copy(alpha = 0.3f), fontSize = 10.sp) },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = Color(0xFF1E2022),
                                        unfocusedContainerColor = Color(0xFF1E2022),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(100.dp),
                                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        if (manualText.isBlank()) return@Button
                                        viewModel.importContactsFromText(manualText) { imported, skipped ->
                                            Toast.makeText(context, "Imported manually: $imported, skipped: $skipped", Toast.LENGTH_SHORT).show()
                                            manualText = ""
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF80CBC4),
                                        contentColor = Color(0xFF00332C)
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Confirm and Import", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                // Sec 3.5: Pinata IPFS Integration
                item {
                    var pinataJwt by remember { mutableStateOf(prefs.getString("pinata_jwt_token", "") ?: "") }
                    var pinataGateway by remember { mutableStateOf(prefs.getString("pinata_gateway_url", "") ?: "") }
                    var isTestingPinata by remember { mutableStateOf(false) }
                    var showPinataToken by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2D31)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cloud,
                                    contentDescription = null,
                                    tint = Color(0xFF80CBC4),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "Pinata IPFS Integration",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))

                            // Informational card with cloud icon
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF80CBC4).copy(alpha = 0.1f))
                                    .border(1.dp, Color(0xFF80CBC4).copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cloud,
                                    contentDescription = null,
                                    tint = Color(0xFF80CBC4),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "To attach images, provide your Pinata API credentials (pinata.cloud). Content is encrypted before being uploaded.",
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))

                            // JWT Token input
                            Text(
                                "JWT TOKEN",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = pinataJwt,
                                onValueChange = { pinataJwt = it },
                                placeholder = { Text("eyJhbGci...", color = Color.White.copy(alpha = 0.2f), fontSize = 12.sp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF1E2022),
                                    unfocusedContainerColor = Color(0xFF1E2022),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF80CBC4),
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true,
                                visualTransformation = if (showPinataToken) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showPinataToken = !showPinataToken }) {
                                        Icon(
                                            imageVector = if (showPinataToken) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = if (showPinataToken) "Hide" else "Show",
                                            tint = Color.White.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // Gateway URL input
                            Text(
                                "GATEWAY URL",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = pinataGateway,
                                onValueChange = { pinataGateway = it },
                                placeholder = { Text("https://your-gateway.mypinata.cloud", color = Color.White.copy(alpha = 0.2f), fontSize = 12.sp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF1E2022),
                                    unfocusedContainerColor = Color(0xFF1E2022),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF80CBC4),
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = TextStyle(fontSize = 11.sp),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            // Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        prefs.edit()
                                            .putString("pinata_jwt_token", pinataJwt.trim())
                                            .putString("pinata_gateway_url", pinataGateway.trim())
                                            .apply()
                                        Toast.makeText(context, "Pinata credentials saved!", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF004D40),
                                        contentColor = Color(0xFF80CBC4)
                                    ),
                                    modifier = Modifier.weight(1f).height(40.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = {
                                        if (pinataJwt.trim().isEmpty()) {
                                            Toast.makeText(context, "Please enter a JWT Token first", Toast.LENGTH_SHORT).show()
                                        } else {
                                            isTestingPinata = true
                                            scope.launch(Dispatchers.IO) {
                                                val success = PinataClient.testAuthentication(pinataJwt.trim())
                                                withContext(Dispatchers.Main) {
                                                    isTestingPinata = false
                                                    if (success) {
                                                        Toast.makeText(context, "Connection Successful! Pinata API is authorized.", Toast.LENGTH_LONG).show()
                                                    } else {
                                                        Toast.makeText(context, "Connection Failed. Please check your JWT Token.", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    enabled = !isTestingPinata,
                                    modifier = Modifier.weight(1.3f).height(40.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                                ) {
                                    if (isTestingPinata) {
                                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(imageVector = Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Test Connection", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // Sec 3.9: Biometric Authentication
                item {
                    val mainActivity = context as? MainActivity
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2D31)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Fingerprint,
                                        contentDescription = null,
                                        tint = Color(0xFF80CBC4),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        "Biometric Authentication",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Switch(
                                    checked = isBiometricEnabled,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            if (mainActivity?.isBiometricAvailable() != true) {
                                                Toast.makeText(context, "Biometric authentication is not available or configured on this device.", Toast.LENGTH_LONG).show()
                                            } else {
                                                // Ask to verify passcode first before turning on
                                                showBiometricConfirmDialog = true
                                                biometricPasscodeConfirmation = ""
                                                biometricConfirmError = false
                                            }
                                        } else {
                                            // Turn off
                                            prefs.edit()
                                                .putBoolean("use_biometrics", false)
                                                .remove("biometric_passcode")
                                                .apply()
                                            isBiometricEnabled = false
                                            Toast.makeText(context, "Biometric authentication disabled.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF80CBC4),
                                        checkedTrackColor = Color(0xFF004D40),
                                        uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                                        uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Allow biometric authentication using your fingerprint. Your passcode remains the main cryptographic key to secure and decrypt your wallet.",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }

                // Sec 3.10: Battery Optimization (WorkManager reliability)
                item {
                    val mainActivity = context as? MainActivity
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2D31)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BatteryChargingFull,
                                    contentDescription = null,
                                    tint = Color(0xFF80CBC4),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    "Background Task Optimization",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Disable Android battery optimization for this app to ensure reliable 15-minute background checking for new secure messages.",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    mainActivity?.requestIgnoreBatteryOptimizations()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF004D40),
                                    contentColor = Color(0xFF80CBC4)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = Color(0xFF80CBC4)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Configure Battery Optimization",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Sec 4: Account Deletion / Clear Data
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2D31)),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFE57373).copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                             ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = Color(0xFFE57373),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "Danger Zone",
                                    color = Color(0xFFE57373),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "If you want to permanently erase all data, delete cryptographic keys, and reset the app on this device, use the option below.",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { showDeleteAccountConfirm = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFC62828),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth().height(42.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Erase All Data & Delete Account", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
                }

                // Close Button at bottom
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF80CBC4),
                        contentColor = Color(0xFF00332C)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Close Settings", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showDeleteAccountConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountConfirm = false },
            containerColor = Color(0xFF1F2124),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color(0xFFE57373), modifier = Modifier.size(24.dp))
                    Text("Confirm deletion", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Text(
                    text = "This action is IRREVERSIBLE. All your keys, contacts, and local chat history will be permanently deleted from this device. Are you sure you want to continue?",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAccountConfirm = false
                        onDismiss()
                        viewModel.resetWallet()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("YES, DELETE EVERYTHING", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAccountConfirm = false }
                ) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                }
            }
        )
    }

    if (showBiometricConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showBiometricConfirmDialog = false },
            containerColor = Color(0xFF1F2124),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(imageVector = Icons.Default.Lock, contentDescription = null, tint = Color(0xFF80CBC4))
                    Text("Confirm with Passcode", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter your current passcode to link it with biometric authentication.",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = biometricPasscodeConfirmation,
                        onValueChange = { 
                            biometricPasscodeConfirmation = it
                            biometricConfirmError = false
                        },
                        placeholder = { Text("Passcode", color = Color.White.copy(alpha = 0.3f)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        isError = biometricConfirmError,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF1A1C1E),
                            unfocusedContainerColor = Color(0xFF1A1C1E),
                            focusedBorderColor = if (biometricConfirmError) Color(0xFFFFB4AB) else Color(0xFF80CBC4),
                            unfocusedBorderColor = if (biometricConfirmError) Color(0xFFFFB4AB).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (biometricConfirmError) {
                        Text(
                            text = "Incorrect passcode. Try again.",
                            color = Color(0xFFFFB4AB),
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val correct = viewModel.verifyPasscode(biometricPasscodeConfirmation)
                        if (correct) {
                            prefs.edit()
                                .putBoolean("use_biometrics", true)
                                .putString("biometric_passcode", biometricPasscodeConfirmation)
                                .apply()
                            isBiometricEnabled = true
                            showBiometricConfirmDialog = false
                            Toast.makeText(context, "Biometric authentication enabled successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            biometricConfirmError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF80CBC4), contentColor = Color(0xFF00332C)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Confirm", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBiometricConfirmDialog = false }
                ) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                }
            }
        )
    }
}
