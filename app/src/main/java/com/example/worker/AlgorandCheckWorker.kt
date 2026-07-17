package com.example.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.crypto.AlgorandCrypto
import com.example.net.AlgorandClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.max

class AlgorandCheckWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val client = OkHttpClient()

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("algopriv_prefs", Context.MODE_PRIVATE)
        val address = prefs.getString("cached_address", "") ?: ""

        if (address.isEmpty()) {
            Log.d("AlgorandCheckWorker", "No wallet address configured. Skipping check.")
            return Result.success()
        }

        val lastCheckedRound = prefs.getLong("last_checked_round", 0L)
        val isFirstRun = lastCheckedRound == 0L

        Log.d("AlgorandCheckWorker", "Starting check for address: $address, lastCheckedRound: $lastCheckedRound")

        val indexerUrl = if (AlgorandClient.isTestnet) {
            "https://testnet-idx.algonode.cloud/v2/accounts/$address/transactions?tx-type=pay"
        } else {
            "https://mainnet-idx.algonode.cloud/v2/accounts/$address/transactions?tx-type=pay"
        }

        val url = if (!isFirstRun) {
            "$indexerUrl&min-round=${lastCheckedRound + 1}"
        } else {
            indexerUrl
        }

        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("AlgorandCheckWorker", "Indexer API returned non-success code: ${response.code}")
                    return Result.retry()
                }

                val responseBody = response.body?.string() ?: ""
                val json = JSONObject(responseBody)
                val transactions = json.optJSONArray("transactions")

                if (transactions != null && transactions.length() > 0) {
                    var maxRound = lastCheckedRound

                    for (i in 0 until transactions.length()) {
                        val tx = transactions.getJSONObject(i)
                        val confirmedRound = tx.optLong("confirmed-round", 0L)
                        maxRound = max(maxRound, confirmedRound)

                        if (!isFirstRun) {
                            val sender = tx.optString("sender", "")
                            val payment = tx.optJSONObject("payment-transaction")
                            if (payment != null) {
                                val receiver = payment.optString("receiver", "")
                                if (receiver == address) {
                                    val noteB64 = tx.optString("note", "")
                                    if (noteB64.isNotEmpty()) {
                                        try {
                                            val rawNoteBytes = android.util.Base64.decode(noteB64, android.util.Base64.DEFAULT)
                                            val noteStr = String(rawNoteBytes, Charsets.UTF_8)
                                            var messageText = noteStr

                                            if (noteStr.startsWith("AP1:")) {
                                                try {
                                                    messageText = AlgorandCrypto.decryptMsg(noteStr, receiver, sender)
                                                } catch (e: Exception) {
                                                    messageText = "Secure encrypted message received"
                                                }
                                            } else if (noteStr.startsWith("AP_DH1:")) {
                                                messageText = "Secure DH encrypted message received"
                                            }

                                            showNotification(sender, messageText)
                                        } catch (e: Exception) {
                                            Log.e("AlgorandCheckWorker", "Error processing transaction note", e)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (maxRound > lastCheckedRound) {
                        prefs.edit().putLong("last_checked_round", maxRound).apply()
                        Log.d("AlgorandCheckWorker", "Updated last_checked_round to $maxRound")
                    }
                } else {
                    // No new transactions
                    if (isFirstRun) {
                        // Initialize last_checked_round with the current node round
                        val statusUrl = if (AlgorandClient.isTestnet) {
                            "https://testnet-api.algonode.cloud/v2/status"
                        } else {
                            "https://mainnet-api.algonode.cloud/v2/status"
                        }
                        try {
                            val statusRequest = Request.Builder().url(statusUrl).get().build()
                            client.newCall(statusRequest).execute().use { statusResponse ->
                                if (statusResponse.isSuccessful) {
                                    val statusJson = JSONObject(statusResponse.body?.string() ?: "")
                                    val currentRound = statusJson.optLong("last-round", 0L)
                                    if (currentRound > 0) {
                                        prefs.edit().putLong("last_checked_round", currentRound).apply()
                                        Log.d("AlgorandCheckWorker", "First run initialization of last_checked_round to $currentRound")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AlgorandCheckWorker", "Failed to fetch current round during first run", e)
                        }
                    }
                }
            }
            return Result.success()
        } catch (e: Exception) {
            Log.e("AlgorandCheckWorker", "Exception in AlgorandCheckWorker", e)
            return Result.retry()
        }
    }

    private fun showNotification(sender: String, messageText: String) {
        val context = applicationContext
        val channelId = "algorand_tx_notifications"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Algorand Messages",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for incoming Algorand messaging transactions"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Shorten sender address for clean display
        val shortSender = if (sender.length > 12) {
            "${sender.take(6)}...${sender.takeLast(6)}"
        } else {
            sender
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("New message from $shortSender")
            .setContentText(messageText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(sender.hashCode(), notification)
    }
}
