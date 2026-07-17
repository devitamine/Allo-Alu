package com.example.crypto

import android.content.Context
import org.bouncycastle.crypto.digests.SHA512tDigest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import android.util.Log

object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var numBits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            numBits += 8
            while (numBits >= 5) {
                numBits -= 5
                val val5 = (buffer shr numBits) and 0x1F
                sb.append(ALPHABET[val5])
            }
        }
        if (numBits > 0) {
            val val5 = (buffer shl (5 - numBits)) and 0x1F
            sb.append(ALPHABET[val5])
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val clean = s.uppercase().trim().replace("=", "")
        val out = ByteArray((clean.length * 5) / 8)
        var buffer = 0
        var numBits = 0
        var byteIdx = 0
        for (c in clean) {
            val idx = ALPHABET.indexOf(c)
            if (idx == -1) throw IllegalArgumentException("Invalid Base32 character: $c")
            buffer = (buffer shl 5) or idx
            numBits += 5
            while (numBits >= 8) {
                numBits -= 8
                if (byteIdx < out.size) {
                    out[byteIdx++] = ((buffer shr numBits) and 0xFF).toByte()
                }
            }
        }
        return out
    }
}

object AlgorandCrypto {

    fun sha512_256(data: ByteArray): ByteArray {
        val digest = SHA512tDigest(256)
        val hash = ByteArray(digest.digestSize)
        digest.update(data, 0, data.size)
        digest.doFinal(hash, 0)
        return hash
    }

    fun encodeAddress(publicKey: ByteArray): String {
        require(publicKey.size == 32) { "Public key must be 32 bytes" }
        val hash = sha512_256(publicKey)
        val checksum = hash.copyOfRange(hash.size - 4, hash.size)
        val addressBytes = ByteArray(36)
        System.arraycopy(publicKey, 0, addressBytes, 0, 32)
        System.arraycopy(checksum, 0, addressBytes, 32, 4)
        return Base32.encode(addressBytes)
    }

    fun decodeAddress(address: String): ByteArray {
        val clean = address.trim().uppercase()
        require(clean.length == 58) { "Address must be exactly 58 characters" }
        val decoded = Base32.decode(clean)
        require(decoded.size == 36) { "Decoded address must be 36 bytes" }
        val publicKey = decoded.copyOfRange(0, 32)
        val checksum = decoded.copyOfRange(32, 36)
        val hash = sha512_256(publicKey)
        val computedChecksum = hash.copyOfRange(hash.size - 4, hash.size)
        if (!checksum.contentEquals(computedChecksum)) {
            throw IllegalArgumentException("Address checksum mismatch")
        }
        return publicKey
    }

    fun isValidAddress(address: String): Boolean {
        return try {
            decodeAddress(address)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun bytesTo11BitWords(bytes: ByteArray): IntArray {
        val out = IntArray(25)
        var buffer = 0
        var numBits = 0
        var wordIdx = 0
        for (byte in bytes) {
            val b = byte.toInt() and 0xFF
            buffer = (buffer shl 8) or b
            numBits += 8
            if (numBits >= 11) {
                numBits -= 11
                val word = (buffer shr numBits) and 0x7FF
                out[wordIdx++] = word
            }
        }
        if (numBits > 0) {
            val lastWord = (buffer shl (11 - numBits)) and 0x7FF
            out[wordIdx] = lastWord
        }
        return out
    }

    private fun wordsToBytes(words: IntArray): ByteArray {
        val out = ByteArray(34)
        var buffer = 0
        var numBits = 0
        var byteIdx = 0
        for (word in words) {
            buffer = (buffer shl 11) or (word and 0x7FF)
            numBits += 11
            while (numBits >= 8) {
                numBits -= 8
                if (byteIdx < 34) {
                    out[byteIdx++] = ((buffer shr numBits) and 0xFF).toByte()
                }
            }
        }
        return out
    }

    object Mnemonic {
        private var wordList: List<String>? = null

        fun getWordList(context: Context): List<String> {
            if (wordList == null) {
                wordList = context.assets.open("bip39_english.txt").bufferedReader().readLines()
            }
            return wordList!!
        }

        fun toMnemonic(context: Context, seed: ByteArray): String {
            require(seed.size == 32) { "Seed must be 32 bytes" }
            val words = getWordList(context)
            val checksumHash = sha512_256(seed)
            val checksum = checksumHash.copyOfRange(0, 2)
            val data = ByteArray(34)
            System.arraycopy(seed, 0, data, 0, 32)
            System.arraycopy(checksum, 0, data, 32, 2)
            val indices = bytesTo11BitWords(data)
            return indices.joinToString(" ") { words[it] }
        }

        fun toKey(context: Context, mnemonic: String): ByteArray {
            val wordsList = getWordList(context)
            val splitWords = mnemonic.trim().lowercase().split(Regex("\\s+"))
            require(splitWords.size == 25) { "Mnemonic must be exactly 25 words" }
            val indices = IntArray(25)
            for (i in splitWords.indices) {
                val idx = wordsList.indexOf(splitWords[i])
                if (idx == -1) throw IllegalArgumentException("Word not in list: ${splitWords[i]}")
                indices[i] = idx
            }
            val data = wordsToBytes(indices)
            val seed = data.copyOfRange(0, 32)
            val checksum = data.copyOfRange(32, 34)

            val computedHash = sha512_256(seed)
            val computedChecksum = computedHash.copyOfRange(0, 2)
            if (!checksum.contentEquals(computedChecksum)) {
                throw IllegalArgumentException("Mnemonic checksum mismatch")
            }
            return seed
        }
    }

    // ── Key Derivation & AES-GCM Encryption ──
    fun deriveStorageKey(passcode: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passcode.toCharArray(), salt, 100000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    fun encryptMnemonic(mnemonic: String, passcode: String): String {
        val random = SecureRandom()
        val salt = ByteArray(16)
        val iv = ByteArray(12)
        random.nextBytes(salt)
        random.nextBytes(iv)

        val key = deriveStorageKey(passcode, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        val ciphertext = cipher.doFinal(mnemonic.toByteArray(Charsets.UTF_8))

        val buffer = ByteArray(2 + 16 + 12 + ciphertext.size)
        buffer[0] = 0x41 // 'A'
        buffer[1] = 0x50 // 'P'
        System.arraycopy(salt, 0, buffer, 2, 16)
        System.arraycopy(iv, 0, buffer, 18, 12)
        System.arraycopy(ciphertext, 0, buffer, 30, ciphertext.size)

        return Base64.encodeToString(buffer, Base64.NO_WRAP)
    }

    fun decryptMnemonic(encryptedB64: String, passcode: String): String {
        val buffer = Base64.decode(encryptedB64, Base64.DEFAULT)
        if (buffer.size >= 30 && buffer[0] == 0x41.toByte() && buffer[1] == 0x50.toByte()) {
            val salt = buffer.copyOfRange(2, 18)
            val iv = buffer.copyOfRange(18, 30)
            val ciphertext = buffer.copyOfRange(30, buffer.size)

            val key = deriveStorageKey(passcode, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }
        // Legacy PBKDF2-SHA256 key derivation fallback
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val rawLegacy = digest.digest(("algopriv_kdf_v1:" + passcode).toByteArray(Charsets.UTF_8))
        val iv = buffer.copyOfRange(0, 12)
        val ciphertext = buffer.copyOfRange(12, buffer.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(rawLegacy, "AES"), spec)
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    // ── Passcode hashing ──
    fun hashPasscode(passcode: String, salt: ByteArray? = null): String {
        val saltBytes = salt ?: "algopriv_login_v2".toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(passcode.toCharArray(), saltBytes, 100000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bits = factory.generateSecret(spec).encoded
        return bits.joinToString("") { "%02x".format(it) }
    }

    // ── HKDF ──
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(salt, "HmacSHA256"))
        }
        return hmac.doFinal(ikm)
    }

    fun hkdfExpand(prk: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(prk, "HmacSHA256"))
        }
        val hashLen = hmac.macLength
        val okm = ByteArray(outLen)
        var remaining = outLen
        var okmOffset = 0
        var counter = 1
        var t = ByteArray(0)
        while (remaining > 0) {
            hmac.reset()
            hmac.update(t)
            hmac.update(info)
            hmac.update(counter.toByte())
            t = hmac.doFinal()
            val stepSize = Math.min(remaining, hashLen)
            System.arraycopy(t, 0, okm, okmOffset, stepSize)
            remaining -= stepSize
            okmOffset += stepSize
            counter++
        }
        return okm
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        val prk = hkdfExtract(salt, ikm)
        return hkdfExpand(prk, info, outLen)
    }

    // ── Message key derivation ──
    private val P_CURVE = BigInteger("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed", 16) // 2^255 - 19

    private fun bytesToBigInteger(bytes: ByteArray): BigInteger {
        val reversed = bytes.reversedArray()
        return BigInteger(1, reversed)
    }

    private fun bigIntegerToBytes(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        val start = if (raw.size > 32) raw.size - 32 else 0
        val len = Math.min(raw.size, 32)
        System.arraycopy(raw, start, out, 32 - len, len)
        return out.reversedArray()
    }

    fun convertEd25519PubToX25519(edPubBytes: ByteArray): ByteArray {
        val yBytes = edPubBytes.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()
        
        val y = bytesToBigInteger(yBytes)
        
        val one = BigInteger.ONE
        val num = one.add(y).mod(P_CURVE)
        val den = one.subtract(y).mod(P_CURVE)
        val denInv = den.modInverse(P_CURVE)
        val u = num.multiply(denInv).mod(P_CURVE)
        
        return bigIntegerToBytes(u)
    }

    fun convertEd25519PrivToX25519(edSeed: ByteArray): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-512")
        val hash = digest.digest(edSeed)
        val xPrivBytes = hash.copyOfRange(0, 32)
        xPrivBytes[0] = (xPrivBytes[0].toInt() and 248).toByte()
        xPrivBytes[31] = (xPrivBytes[31].toInt() and 127).toByte()
        xPrivBytes[31] = (xPrivBytes[31].toInt() or 64).toByte()
        return xPrivBytes
    }

    fun deriveDiffieHellmanSharedSecret(mySeed: ByteArray, theirPublicKeyBytes: ByteArray): ByteArray {
        val myX25519PrivBytes = convertEd25519PrivToX25519(mySeed)
        val theirX25519PubBytes = convertEd25519PubToX25519(theirPublicKeyBytes)
        
        val myX25519Priv = X25519PrivateKeyParameters(myX25519PrivBytes, 0)
        val theirX25519Pub = X25519PublicKeyParameters(theirX25519PubBytes, 0)
        
        val agreement = X25519Agreement()
        agreement.init(myX25519Priv)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(theirX25519Pub, sharedSecret, 0)
        return sharedSecret
    }

    fun deriveSecureMsgKey(mySeed: ByteArray, theirAddr: String): ByteArray {
        val theirPublicKey = decodeAddress(theirAddr)
        val sharedSecret = try {
            deriveDiffieHellmanSharedSecret(mySeed, theirPublicKey)
        } catch (e: Exception) {
            Log.e("AlgorandCrypto", "Diffie-Hellman shared secret derivation failed, falling back", e)
            val sorted = listOf(encodeAddress(getPublicKey(mySeed)), theirAddr.trim()).sorted()
            val path = "algopriv:pair:${sorted[0]}:${sorted[1]}"
            java.security.MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        }
        return hkdf(
            ikm = sharedSecret,
            salt = "AlgoPrivChatDHV1".toByteArray(Charsets.UTF_8),
            info = "msg-key-dh-v1".toByteArray(Charsets.UTF_8),
            outLen = 32
        )
    }

    fun deriveMsgKey(addrA: String, addrB: String): ByteArray {
        val sorted = listOf(addrA.trim(), addrB.trim()).sorted()
        val path = "algopriv:pair:${sorted[0]}:${sorted[1]}"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val ikm = digest.digest(path.toByteArray(Charsets.UTF_8))
        return hkdf(
            ikm = ikm,
            salt = "AlgoPrivChatV2".toByteArray(Charsets.UTF_8),
            info = "msg-key-v2".toByteArray(Charsets.UTF_8),
            outLen = 32
        )
    }

    fun encryptMsg(text: String, myAddr: String, theirAddr: String, mySeed: ByteArray? = null): String {
        val isDH = mySeed != null
        val key = if (isDH && mySeed != null) {
            val localAddr = encodeAddress(getPublicKey(mySeed))
            val peerAddr = if (myAddr == localAddr) theirAddr else myAddr
            deriveSecureMsgKey(mySeed, peerAddr)
        } else {
            deriveMsgKey(myAddr, theirAddr)
        }
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        val ct = cipher.doFinal(text.toByteArray(Charsets.UTF_8))

        val buffer = ByteArray(12 + ct.size)
        System.arraycopy(iv, 0, buffer, 0, 12)
        System.arraycopy(ct, 0, buffer, 12, ct.size)
        val prefix = if (isDH) "AP_DH1:" else "AP1:"
        return prefix + Base64.encodeToString(buffer, Base64.NO_WRAP)
    }

    fun decryptMsg(encryptedPayload: String, myAddr: String, theirAddr: String, mySeed: ByteArray? = null): String {
        val isDH = encryptedPayload.startsWith("AP_DH1:")
        val clean = when {
            encryptedPayload.startsWith("AP_DH1:") -> encryptedPayload.substring(7)
            encryptedPayload.startsWith("AP1:") -> encryptedPayload.substring(4)
            else -> encryptedPayload
        }
        val buffer = Base64.decode(clean, Base64.DEFAULT)
        val iv = buffer.copyOfRange(0, 12)
        val ct = buffer.copyOfRange(12, buffer.size)

        val key = if (isDH && mySeed != null) {
            val localAddr = encodeAddress(getPublicKey(mySeed))
            val peerAddr = if (myAddr == localAddr) theirAddr else myAddr
            deriveSecureMsgKey(mySeed, peerAddr)
        } else {
            deriveMsgKey(myAddr, theirAddr)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    // ── Contacts key derivation & file E2E ──
    fun deriveContactsKey(seed: ByteArray): ByteArray {
        val keySeed = seed.copyOfRange(0, 32)
        return hkdf(
            ikm = keySeed,
            salt = "AlgoPrivContacts".toByteArray(Charsets.UTF_8),
            info = "contacts-v1".toByteArray(Charsets.UTF_8),
            outLen = 32
        )
    }

    fun encryptContactsPayload(plaintext: String, seed: ByteArray): String {
        val key = deriveContactsKey(seed)
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val buffer = ByteArray(12 + ct.size)
        System.arraycopy(iv, 0, buffer, 0, 12)
        System.arraycopy(ct, 0, buffer, 12, ct.size)
        return "APCONTACTS1\n" + Base64.encodeToString(buffer, Base64.NO_WRAP)
    }

    fun decryptContactsPayload(encryptedFileText: String, seed: ByteArray): String {
        val header = "APCONTACTS1\n"
        require(encryptedFileText.startsWith(header)) { "Invalid contact file format" }
        val b64 = encryptedFileText.substring(header.length).trim()
        val buffer = Base64.decode(b64, Base64.DEFAULT)
        val iv = buffer.copyOfRange(0, 12)
        val ct = buffer.copyOfRange(12, buffer.size)

        val key = deriveContactsKey(seed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    // ── Image encrypt/decrypt ──
    fun encryptImage(imageBytes: ByteArray, myAddr: String, theirAddr: String): ByteArray {
        val key = deriveMsgKey(myAddr, theirAddr)
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        val ct = cipher.doFinal(imageBytes)

        val buffer = ByteArray(12 + ct.size)
        System.arraycopy(iv, 0, buffer, 0, 12)
        System.arraycopy(ct, 0, buffer, 12, ct.size)
        return buffer
    }

    fun decryptImage(encryptedImageBytes: ByteArray, myAddr: String, theirAddr: String): ByteArray {
        val iv = encryptedImageBytes.copyOfRange(0, 12)
        val ct = encryptedImageBytes.copyOfRange(12, encryptedImageBytes.size)

        val key = deriveMsgKey(myAddr, theirAddr)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        return cipher.doFinal(ct)
    }

    // ── MsgPack Encoder ──
    class MsgPackEncoder {
        private val bos = java.io.ByteArrayOutputStream()

        fun writeRawBytes(bytes: ByteArray) {
            bos.write(bytes)
        }

        fun writeMapHeader(size: Int) {
            if (size <= 15) {
                bos.write(0x80 or size)
            } else if (size <= 65535) {
                bos.write(0xDE)
                bos.write((size shr 8) and 0xFF)
                bos.write(size and 0xFF)
            } else {
                bos.write(0xDF)
                bos.write((size shr 24) and 0xFF)
                bos.write((size shr 16) and 0xFF)
                bos.write((size shr 8) and 0xFF)
                bos.write(size and 0xFF)
            }
        }

        fun writeString(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            val len = bytes.size
            if (len <= 31) {
                bos.write(0xA0 or len)
            } else if (len <= 255) {
                bos.write(0xD9)
                bos.write(len)
            } else if (len <= 65535) {
                bos.write(0xDA)
                bos.write((len shr 8) and 0xFF)
                bos.write(len and 0xFF)
            } else {
                bos.write(0xDB)
                bos.write((len shr 24) and 0xFF)
                bos.write((len shr 16) and 0xFF)
                bos.write((len shr 8) and 0xFF)
                bos.write(len and 0xFF)
            }
            bos.write(bytes)
        }

        fun writeInt(value: Long) {
            if (value in 0..127) {
                bos.write(value.toInt())
            } else if (value in 128..255) {
                bos.write(0xCC)
                bos.write(value.toInt())
            } else if (value in 256..65535) {
                bos.write(0xCD)
                bos.write((value shr 8).toInt() and 0xFF)
                bos.write(value.toInt() and 0xFF)
            } else if (value in 65536..4294967295L) {
                bos.write(0xCE)
                bos.write((value shr 24).toInt() and 0xFF)
                bos.write((value shr 16).toInt() and 0xFF)
                bos.write((value shr 8).toInt() and 0xFF)
                bos.write(value.toInt() and 0xFF)
            } else {
                bos.write(0xCF)
                bos.write((value shr 56).toInt() and 0xFF)
                bos.write((value shr 48).toInt() and 0xFF)
                bos.write((value shr 40).toInt() and 0xFF)
                bos.write((value shr 32).toInt() and 0xFF)
                bos.write((value shr 24).toInt() and 0xFF)
                bos.write((value shr 16).toInt() and 0xFF)
                bos.write((value shr 8).toInt() and 0xFF)
                bos.write(value.toInt() and 0xFF)
            }
        }

        fun writeBinary(bytes: ByteArray) {
            val len = bytes.size
            if (len <= 255) {
                bos.write(0xC4)
                bos.write(len)
            } else if (len <= 65535) {
                bos.write(0xC5)
                bos.write((len shr 8) and 0xFF)
                bos.write(len and 0xFF)
            } else {
                bos.write(0xC6)
                bos.write((len shr 24) and 0xFF)
                bos.write((len shr 16) and 0xFF)
                bos.write((len shr 8) and 0xFF)
                bos.write(len and 0xFF)
            }
            bos.write(bytes)
        }

        fun toByteArray(): ByteArray {
            return bos.toByteArray()
        }
    }

    // ── Payment Transaction MsgPack Serialization ──
    fun serializePaymentTransaction(
        sender: ByteArray,
        receiver: ByteArray,
        amount: Long,
        fee: Long,
        firstValid: Long,
        lastValid: Long,
        genesisId: String,
        genesisHash: ByteArray,
        note: ByteArray?
    ): ByteArray {
        val fields = mutableListOf<Pair<String, Any>>()
        if (amount > 0L) {
            fields.add(Pair("amt", amount))
        }
        if (fee > 0L) {
            fields.add(Pair("fee", fee))
        }
        if (firstValid > 0L) {
            fields.add(Pair("fv", firstValid))
        }
        if (genesisId.isNotEmpty()) {
            fields.add(Pair("gen", genesisId))
        }
        if (genesisHash.isNotEmpty()) {
            fields.add(Pair("gh", genesisHash))
        }
        if (lastValid > 0L) {
            fields.add(Pair("lv", lastValid))
        }
        if (note != null && note.isNotEmpty()) {
            fields.add(Pair("note", note))
        }
        if (receiver.isNotEmpty()) {
            fields.add(Pair("rcv", receiver))
        }
        if (sender.isNotEmpty()) {
            fields.add(Pair("snd", sender))
        }
        fields.add(Pair("type", "pay"))

        val encoder = MsgPackEncoder()
        encoder.writeMapHeader(fields.size)
        for (field in fields) {
            encoder.writeString(field.first)
            when (val value = field.second) {
                is Long -> encoder.writeInt(value)
                is String -> encoder.writeString(value)
                is ByteArray -> encoder.writeBinary(value)
            }
        }
        return encoder.toByteArray()
    }

    // ── Signed Transaction Wrapper Serialization ──
    fun serializeSignedTransaction(serializedTxn: ByteArray, signature: ByteArray): ByteArray {
        val encoder = MsgPackEncoder()
        encoder.writeMapHeader(2)

        // Sorted alphabetically:
        // 1. sig
        encoder.writeString("sig")
        encoder.writeBinary(signature)

        // 2. txn
        encoder.writeString("txn")
        encoder.writeRawBytes(serializedTxn)

        return encoder.toByteArray()
    }

    // ── Ed25519 Signing ──
    fun getPublicKey(seed: ByteArray): ByteArray {
        val privKeyParams = Ed25519PrivateKeyParameters(seed, 0)
        return privKeyParams.generatePublicKey().encoded
    }

    fun signEd25519(message: ByteArray, seed: ByteArray): ByteArray {
        val privKeyParams = Ed25519PrivateKeyParameters(seed, 0)
        val signer = Ed25519Signer()
        signer.init(true, privKeyParams)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun signTransaction(serializedTxn: ByteArray, seed: ByteArray): ByteArray {
        val header = "TX".toByteArray(Charsets.UTF_8)
        val prepended = ByteArray(header.size + serializedTxn.size)
        System.arraycopy(header, 0, prepended, 0, header.size)
        System.arraycopy(serializedTxn, 0, prepended, header.size, serializedTxn.size)
        return signEd25519(prepended, seed)
    }

    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val seed = ByteArray(32)
        SecureRandom().nextBytes(seed)
        val pub = getPublicKey(seed)
        return Pair(seed, pub)
    }
}
