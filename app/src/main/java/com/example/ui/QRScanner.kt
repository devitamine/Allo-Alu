package com.example.ui

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import com.example.crypto.AlgorandCrypto
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.common.BitMatrix

fun extractAddress(raw: String): String? {
    val trimmed = raw.trim()
    
    // 1. Check if the whole string itself (after making uppercase) is a valid 58-character Algorand address
    val cleanRaw = trimmed.uppercase()
    if (cleanRaw.length == 58 && AlgorandCrypto.isValidAddress(cleanRaw)) {
        return cleanRaw
    }
    
    // 2. Try removing common wallet schemes
    var temp = trimmed
    val schemes = listOf("perawallet://", "perawallet:", "algorand://", "algorand:", "peraconnect://", "peraconnect:")
    for (scheme in schemes) {
        if (temp.startsWith(scheme, ignoreCase = true)) {
            temp = temp.substring(scheme.length)
            break
        }
    }
    
    // 3. Remove query parameters if any (starting with ?)
    temp = temp.substringBefore("?")
    
    // 4. Remove any trailing slash
    temp = temp.substringBefore("/")
    
    val candidate = temp.trim().uppercase()
    if (candidate.length == 58 && AlgorandCrypto.isValidAddress(candidate)) {
        return candidate
    }
    
    // 5. Fallback: search for any continuous 58-character alphanumeric sequence matching Base32 chars
    val base32Regex = Regex("[A-Z2-7]{58}", RegexOption.IGNORE_CASE)
    val match = base32Regex.find(trimmed)
    if (match != null) {
        val matchedCandidate = match.value.uppercase()
        if (AlgorandCrypto.isValidAddress(matchedCandidate)) {
            return matchedCandidate
        }
    }
    
    return null
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun QRScannerDialog(
    onQrCodeScanned: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1C1E),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxSize(),
        title = null,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Scan Algorand Wallet QR",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (cameraPermissionState.status.isGranted) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color.Black, RoundedCornerShape(12.dp))
                    ) {
                        CameraPreview(onQrCodeScanned = onQrCodeScanned)

                        // Outer frame indicator
                        Box(
                            modifier = Modifier
                                .size(240.dp)
                                .align(Alignment.Center)
                                .border(2.dp, Color(0xFF81C784), RoundedCornerShape(12.dp))
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF242629), RoundedCornerShape(12.dp))
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Camera permission is required to scan QR codes",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        Button(
                            onClick = { cameraPermissionState.launchPermissionRequest() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF80CBC4), contentColor = Color(0xFF00332C))
                        ) {
                            Text("Grant Permission")
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun CameraPreview(onQrCodeScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var qrScannedTriggered by remember { mutableStateOf(false) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = Executors.newSingleThreadExecutor()

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage != null && !qrScannedTriggered) {
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        val scanner = BarcodeScanning.getClient()
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                for (barcode in barcodes) {
                                    val rawValue = barcode.rawValue
                                    if (rawValue != null) {
                                        Log.d("QRScanner", "Scanned QR Code value: $rawValue")
                                        
                                        val parsedAddress = extractAddress(rawValue)

                                        if (parsedAddress != null) {
                                            qrScannedTriggered = true
                                            onQrCodeScanned(parsedAddress)
                                        }
                                    }
                                }
                            }
                            .addOnFailureListener {
                                Log.e("QRScanner", "Barcode scanning failed", it)
                            }
                            .addOnCompleteListener {
                                imageProxy.close()
                            }
                    } else {
                        imageProxy.close()
                    }
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    Log.e("QRScanner", "Camera binding failed", e)
                }
            }, ContextCompat.getMainExecutor(context))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun ShowMyQrDialog(address: String, onDismiss: () -> Unit) {
    val bitMatrix = remember(address) {
        try {
            QRCodeWriter().encode(address, BarcodeFormat.QR_CODE, 200, 200)
        } catch (e: Exception) {
            Log.e("QRScanner", "Failed to generate QR code matrix", e)
            null
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1F2124))
                .border(1.5.dp, Color(0xFF80CBC4).copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            // Close button in top-right
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2B2D31))
                    .border(1.dp, Color(0xFF80CBC4).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color(0xFF80CBC4),
                    modifier = Modifier.size(16.dp)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "My Wallet QR Code",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                // High-fidelity generated QR canvas based on ZXing matrix
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .background(Color.White, RoundedCornerShape(12.dp))
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
                        } else {
                            drawRect(color = Color.Gray)
                        }
                    }
                }

                Text(
                    text = address,
                    color = Color(0xFF80CBC4),
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 16.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Let another person scan this QR code to add you as a contact instantly.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF80CBC4),
                        contentColor = Color(0xFF00332C)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
