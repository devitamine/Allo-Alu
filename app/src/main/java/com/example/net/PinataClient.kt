package com.example.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

object PinataClient {
    private val client = OkHttpClient()

    fun testAuthentication(jwtToken: String): Boolean {
        val request = Request.Builder()
            .url("https://api.pinata.cloud/data/testAuthentication")
            .addHeader("Authorization", "Bearer $jwtToken")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    fun uploadEncryptedFile(jwtToken: String, encryptedBytes: ByteArray, filename: String = "file.enc"): String {
        val mediaType = "application/octet-stream".toMediaType()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, encryptedBytes.toRequestBody(mediaType))
            .build()

        val request = Request.Builder()
            .url("https://api.pinata.cloud/pinning/pinFileToIPFS")
            .addHeader("Authorization", "Bearer $jwtToken")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException("Pinata upload failed (code ${response.code}): $bodyStr")
            }
            val json = JSONObject(bodyStr)
            return json.getString("IpfsHash")
        }
    }
}
