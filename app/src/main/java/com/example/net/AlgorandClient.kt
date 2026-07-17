package com.example.net

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

object AlgorandClient {
    private val client = OkHttpClient()

    var isTestnet: Boolean = false

    private val apiHost: String
        get() = if (isTestnet) "https://testnet-api.algonode.cloud" else "https://mainnet-api.algonode.cloud"

    private val idxHost: String
        get() = if (isTestnet) "https://testnet-idx.algonode.cloud" else "https://mainnet-idx.algonode.cloud"

    private val expectedGenesisId: String
        get() = if (isTestnet) "testnet-v1.0" else "mainnet-v1.0"

    data class TxParams(
        val genesisId: String,
        val genesisHash: ByteArray,
        val firstValid: Long,
        val lastValid: Long,
        val fee: Long
    )

    fun fetchTxParams(): TxParams {
        val request = Request.Builder()
            .url("$apiHost/v2/transactions/params")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to fetch params: $response")
            val json = JSONObject(response.body?.string() ?: "")
            val genesisId = json.getString("genesis-id")
            if (genesisId != expectedGenesisId) {
                throw SecurityException("Security Exception: Client is strictly configured for $expectedGenesisId. Found network: $genesisId")
            }
            val genesisHashB64 = json.getString("genesis-hash")
            val genesisHash = android.util.Base64.decode(genesisHashB64, android.util.Base64.DEFAULT)
            val lastRound = json.getLong("last-round")
            val minFee = json.optLong("min-fee", 1000L)

            return TxParams(
                genesisId = genesisId,
                genesisHash = genesisHash,
                firstValid = lastRound,
                lastValid = lastRound + 1000,
                fee = minFee
            )
        }
    }

    fun fetchBalance(address: String): Long {
        val request = Request.Builder()
            .url("$apiHost/v2/accounts/$address")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return 0L
                if (!response.isSuccessful) throw IOException("Failed to fetch balance: $response")
                val json = JSONObject(response.body?.string() ?: "")
                json.getLong("amount")
            }
        } catch (e: Exception) {
            Log.e("AlgorandClient", "Error fetching balance", e)
            0L
        }
    }

    fun submitTransaction(signedTxnBytes: ByteArray): String {
        val mediaType = "application/x-binary".toMediaType()
        val request = Request.Builder()
            .url("$apiHost/v2/transactions")
            .post(signedTxnBytes.toRequestBody(mediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException("Submit transaction failed (code ${response.code}): $bodyStr")
            }
            val json = JSONObject(bodyStr)
            return json.getString("txId")
        }
    }

    data class ApiTransaction(
        val txId: String,
        val sender: String,
        val receiver: String,
        val amount: Long,
        val timestamp: Long,
        val noteB64: String?
    )

    fun fetchTransactions(address: String): List<ApiTransaction> {
        val url = "$idxHost/v2/accounts/$address/transactions?tx-type=pay&limit=100"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val list = mutableListOf<ApiTransaction>()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Failed to fetch transactions: $response")
                val responseStr = response.body?.string() ?: ""
                val json = JSONObject(responseStr)
                val txArray = json.optJSONArray("transactions") ?: return emptyList()
                for (i in 0 until txArray.length()) {
                    val tx = txArray.getJSONObject(i)
                    val id = tx.getString("id")
                    val sender = tx.getString("sender")
                    val payTx = tx.optJSONObject("payment-transaction") ?: continue
                    val receiver = payTx.getString("receiver")
                    val amount = payTx.getLong("amount")
                    val roundTime = tx.optLong("round-time", 0L)
                    val note = tx.optString("note", null)

                    list.add(ApiTransaction(id, sender, receiver, amount, roundTime, note))
                }
            }
        } catch (e: Exception) {
            Log.e("AlgorandClient", "Error fetching transactions", e)
        }
        return list
    }
}
