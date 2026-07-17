package com.example

import com.example.crypto.AlgorandCrypto
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testDiffieHellmanCommutativeProperty() {
    // Generate Alice's KeyPair (seed and public key)
    val aliceSeed = ByteArray(32)
    SecureRandom().nextBytes(aliceSeed)
    val alicePub = AlgorandCrypto.getPublicKey(aliceSeed)

    // Generate Bob's KeyPair (seed and public key)
    val bobSeed = ByteArray(32)
    SecureRandom().nextBytes(bobSeed)
    val bobPub = AlgorandCrypto.getPublicKey(bobSeed)

    // 1. Alice derives shared secret using Alice's seed and Bob's public key
    val aliceShared = AlgorandCrypto.deriveDiffieHellmanSharedSecret(aliceSeed, bobPub)

    // 2. Bob derives shared secret using Bob's seed and Alice's public key
    val bobShared = AlgorandCrypto.deriveDiffieHellmanSharedSecret(bobSeed, alicePub)

    // 3. Assert they are exactly the same
    assertArrayEquals("The DH shared secrets must be identical in both directions", aliceShared, bobShared)
    assertNotNull(aliceShared)
    assertEquals(32, aliceShared.size)
  }
}

