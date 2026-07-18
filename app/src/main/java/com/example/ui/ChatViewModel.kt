package com.example.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.crypto.AlgorandCrypto
import com.example.db.AppDatabase
import com.example.db.ChatRepository
import com.example.db.Contact
import com.example.db.LocalMessage
import com.example.net.AlgorandClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import com.example.db.PeerWithTimestamp

sealed class WalletState {
    object NotInitialized : WalletState()
    object Locked : WalletState()
    data class Unlocked(val address: String, val mnemonic: String) : WalletState()
}

class ChatViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val db = AppDatabase.getDatabase(appContext)
    private val repository = ChatRepository(db)

    private val prefs = appContext.getSharedPreferences("algopriv_prefs", Context.MODE_PRIVATE)

    private val _walletState = MutableStateFlow<WalletState>(WalletState.NotInitialized)
    val walletState: StateFlow<WalletState> = _walletState.asStateFlow()

    private val _isMainnet = MutableStateFlow(true)
    val isMainnet: StateFlow<Boolean> = _isMainnet.asStateFlow()

    private val _balance = MutableStateFlow(10000000L) // 10 ALGO default for demo
    val balance: StateFlow<Long> = _balance.asStateFlow()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _otherChats = MutableStateFlow<List<Contact>>(emptyList())
    val otherChats: StateFlow<List<Contact>> = _otherChats.asStateFlow()

    private val _unreadContacts = MutableStateFlow<Set<String>>(emptySet())
    val unreadContacts: StateFlow<Set<String>> = _unreadContacts.asStateFlow()

    private val _activeContact = MutableStateFlow<Contact?>(null)
    val activeContact: StateFlow<Contact?> = _activeContact.asStateFlow()

    private val _messages = MutableStateFlow<List<LocalMessage>>(emptyList())
    val messages: StateFlow<List<LocalMessage>> = _messages.asStateFlow()

    private val _chatCutoffs = MutableStateFlow<Map<String, Long>>(emptyMap())
    val chatCutoffs: StateFlow<Map<String, Long>> = _chatCutoffs.asStateFlow()

    fun clearChatForSession(address: String) {
        val now = System.currentTimeMillis() / 1000
        _chatCutoffs.value = _chatCutoffs.value + (address to now)
    }

    fun restoreChatForSession(address: String) {
        _chatCutoffs.value = _chatCutoffs.value - address
    }

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isGeneratingWallet = MutableStateFlow(false)
    val isGeneratingWallet: StateFlow<Boolean> = _isGeneratingWallet.asStateFlow()

    private val _generatedMnemonic = MutableStateFlow<String?>(null)
    val generatedMnemonic: StateFlow<String?> = _generatedMnemonic.asStateFlow()

    private val _lockoutTimeRemaining = MutableStateFlow(0L)
    val lockoutTimeRemaining: StateFlow<Long> = _lockoutTimeRemaining.asStateFlow()

    private var lockoutJob: kotlinx.coroutines.Job? = null

    private fun startLockoutCountdown() {
        lockoutJob?.cancel()
        lockoutJob = viewModelScope.launch {
            while (true) {
                val lockoutUntil = prefs.getLong("lockout_until", 0L)
                val now = System.currentTimeMillis()
                val remaining = if (lockoutUntil > now) {
                    (lockoutUntil - now) / 1000 + 1
                } else {
                    0L
                }
                _lockoutTimeRemaining.value = remaining
                if (remaining <= 0) {
                    break
                }
                delay(1000)
            }
        }
    }

    var lastUsedPasscode: String? = null

    init {
        loadWalletState()
        loadContacts()
        loadOtherChats()
        observeActiveMessages()
        startPeriodicSync()
        if (prefs.getLong("lockout_until", 0L) > System.currentTimeMillis()) {
            startLockoutCountdown()
        }
    }

    private fun startPeriodicSync() {
        viewModelScope.launch {
            while (true) {
                delay(30000) // check every 30 seconds
                val state = _walletState.value
                if (state is WalletState.Unlocked) {
                    val timeoutMinutes = prefs.getInt("auto_logout_minutes", -1)
                    if (timeoutMinutes > 0) {
                        val lastBackgroundTime = prefs.getLong("last_background_time", 0L)
                        if (lastBackgroundTime > 0L) {
                            val elapsedMs = System.currentTimeMillis() - lastBackgroundTime
                            val elapsedMinutes = elapsedMs / (1000 * 60)
                            if (elapsedMinutes >= timeoutMinutes) {
                                logout()
                                continue
                            }
                        }
                    }
                    try {
                        syncNetworkTransactions(silent = true)
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "Periodic background sync failed", e)
                    }
                }
            }
        }
    }

    fun updateUnreadStatuses() {
        val currentState = _walletState.value
        if (currentState !is WalletState.Unlocked) return
        val myAddr = currentState.address
        viewModelScope.launch(Dispatchers.IO) {
            val contactList = _contacts.value + _otherChats.value
            val unreadSet = mutableSetOf<String>()
            for (contact in contactList) {
                val latestReceived = db.messageDao().getLatestReceivedMessageTimestamp(myAddr, contact.address) ?: 0L
                val lastRead = prefs.getLong("last_read_ts_${contact.address}", 0L)
                val isActive = _activeContact.value?.address == contact.address
                if (isActive) {
                    val latestInChat = db.messageDao().getLatestReceivedMessageTimestamp(myAddr, contact.address) ?: 0L
                    prefs.edit().putLong("last_read_ts_${contact.address}", latestInChat).apply()
                } else if (latestReceived > lastRead) {
                    unreadSet.add(contact.address)
                }
            }
            withContext(Dispatchers.Main) {
                _unreadContacts.value = unreadSet
            }
        }
    }

    private fun loadWalletState() {
        val hasPasscode = prefs.contains("passcode_hash")
        val hasEncryptedSeed = prefs.contains("encrypted_seed")
        if (hasPasscode && hasEncryptedSeed) {
            _walletState.value = WalletState.Locked
        } else {
            _walletState.value = WalletState.NotInitialized
        }
        _isMainnet.value = true
    }

    private fun loadContacts() {
        viewModelScope.launch(Dispatchers.IO) {
            combine(repository.contacts, _walletState) { dbContacts, walletState ->
                val list = dbContacts

                val updatedList = mutableListOf<Contact>()
                if (walletState is WalletState.Unlocked) {
                    val selfContact = Contact(
                        id = -999,
                        address = walletState.address,
                        nickname = "Encrypted Notes",
                        note = "Auto-encrypted cloud & scratchpad",
                        pinned = true
                    )
                    updatedList.add(selfContact)
                }

                for (c in list) {
                    if (walletState is WalletState.Unlocked && c.address == walletState.address) {
                        continue
                    }
                    updatedList.add(c)
                }
                updatedList
            }.collectLatest { updatedList ->
                withContext(Dispatchers.Main) {
                    _contacts.value = updatedList
                    updateUnreadStatuses()
                }
            }
        }
    }

    private fun loadOtherChats() {
        viewModelScope.launch(Dispatchers.IO) {
            _walletState.collectLatest { walletState ->
                if (walletState is WalletState.Unlocked) {
                    val myAddr = walletState.address
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    combine(repository.contacts, db.messageDao().getAllMessagePeersWithTimestamp(myAddr)) { dbContacts, peersWithTs ->
                        val contactAddresses = dbContacts.map { it.address }.toSet()
                        val strangers = peersWithTs.filter { it.peer != myAddr && it.peer != "MY_ADDRESS_PLACEHOLDER" && !contactAddresses.contains(it.peer) }
                        
                        strangers.map { peerWithTs ->
                            val formattedTime = if (peerWithTs.max_ts > 0L) {
                                sdf.format(Date(peerWithTs.max_ts * 1000L))
                            } else {
                                "Unknown"
                            }
                            Contact(
                                id = -1, // temporary ID
                                address = peerWithTs.peer,
                                nickname = "${peerWithTs.peer.take(6)}...${peerWithTs.peer.takeLast(6)}",
                                note = formattedTime,
                                pinned = false
                            )
                        }
                    }.collectLatest { strangerContacts ->
                        withContext(Dispatchers.Main) {
                            _otherChats.value = strangerContacts
                            updateUnreadStatuses()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _otherChats.value = emptyList()
                    }
                }
            }
        }
    }

    private fun observeActiveMessages() {
        viewModelScope.launch {
            _activeContact.collectLatest { contact ->
                if (contact == null) {
                    _messages.value = emptyList()
                } else {
                    val currentState = _walletState.value
                    if (currentState is WalletState.Unlocked) {
                        repository.getMessagesForChat(currentState.address, contact.address).collectLatest { msgs ->
                            // Replace placeholders with real address
                            val processed = msgs.map { m ->
                                val sender = if (m.sender == "MY_ADDRESS_PLACEHOLDER") currentState.address else m.sender
                                val receiver = if (m.receiver == "MY_ADDRESS_PLACEHOLDER") currentState.address else m.receiver
                                m.copy(sender = sender, receiver = receiver)
                            }
                            _messages.value = processed
                            updateUnreadStatuses()
                        }
                    }
                }
            }
        }
    }

    fun toggleNetworkMode() {
        // Network mode is locked to Algorand Mainnet
        _isMainnet.value = true
        refreshBalance()
    }

    fun clearError() {
        _error.value = null
    }

    fun verifyPasscode(passcode: String): Boolean {
        val lockoutUntil = prefs.getLong("lockout_until", 0L)
        val now = System.currentTimeMillis()
        if (now < lockoutUntil) {
            val rem = (lockoutUntil - now) / 1000 + 1
            _error.value = "Too many failed attempts. Try again in $rem seconds."
            return false
        }

        return try {
            val storedHash = prefs.getString("passcode_hash", "") ?: ""
            val storedSaltB64 = prefs.getString("passcode_salt", "") ?: ""
            val saltBytes = if (storedSaltB64.isNotEmpty()) {
                android.util.Base64.decode(storedSaltB64, android.util.Base64.DEFAULT)
            } else {
                null
            }
            val enteredHash = AlgorandCrypto.hashPasscode(passcode, saltBytes)
            val correct = storedHash == enteredHash
            if (correct) {
                prefs.edit()
                    .putInt("failed_attempts", 0)
                    .putLong("lockout_until", 0L)
                    .apply()
            } else {
                val currentFailed = prefs.getInt("failed_attempts", 0) + 1
                if (currentFailed >= 3) {
                    val lockUntilTime = System.currentTimeMillis() + 60_000
                    prefs.edit()
                        .putInt("failed_attempts", 0)
                        .putLong("lockout_until", lockUntilTime)
                        .apply()
                    _error.value = "Incorrect passcode. 3 failed attempts. Locked for 1 minute."
                    startLockoutCountdown()
                } else {
                    prefs.edit().putInt("failed_attempts", currentFailed).apply()
                    _error.value = "Incorrect passcode (Attempt $currentFailed/3)"
                }
            }
            correct
        } catch (e: Exception) {
            false
        }
    }

    // ── Wallet Management ──

    fun unlockWallet(passcode: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lockoutUntil = prefs.getLong("lockout_until", 0L)
                val now = System.currentTimeMillis()
                if (now < lockoutUntil) {
                    val rem = (lockoutUntil - now) / 1000 + 1
                    _error.value = "Too many failed attempts. Try again in $rem seconds."
                    return@launch
                }

                val storedHash = prefs.getString("passcode_hash", "") ?: ""
                val storedSaltB64 = prefs.getString("passcode_salt", "") ?: ""
                val saltBytes = if (storedSaltB64.isNotEmpty()) {
                    android.util.Base64.decode(storedSaltB64, android.util.Base64.DEFAULT)
                } else {
                    null
                }
                val enteredHash = AlgorandCrypto.hashPasscode(passcode, saltBytes)
                if (storedHash != enteredHash) {
                    val currentFailed = prefs.getInt("failed_attempts", 0) + 1
                    if (currentFailed >= 3) {
                        val lockUntilTime = System.currentTimeMillis() + 60_000
                        prefs.edit()
                            .putInt("failed_attempts", 0)
                            .putLong("lockout_until", lockUntilTime)
                            .apply()
                        _error.value = "Incorrect passcode. 3 failed attempts. Locked for 1 minute."
                        startLockoutCountdown()
                    } else {
                        prefs.edit().putInt("failed_attempts", currentFailed).apply()
                        _error.value = "Incorrect passcode (Attempt $currentFailed/3)"
                    }
                    return@launch
                }

                prefs.edit()
                    .putInt("failed_attempts", 0)
                    .putLong("lockout_until", 0L)
                    .apply()

                val encryptedSeed = prefs.getString("encrypted_seed", "") ?: ""
                val mnemonic = AlgorandCrypto.decryptMnemonic(encryptedSeed, passcode)
                val seed = AlgorandCrypto.Mnemonic.toKey(appContext, mnemonic)
                val address = AlgorandCrypto.encodeAddress(AlgorandCrypto.getPublicKey(seed))

                withContext(Dispatchers.Main) {
                    lastUsedPasscode = passcode
                    _walletState.value = WalletState.Unlocked(address, mnemonic)
                    refreshBalance()
                    syncNetworkTransactions(silent = true)
                    onSuccess()
                    // Reload active chat to bind to actual unlocked address
                    val currentContact = _activeContact.value
                    _activeContact.value = null
                    _activeContact.value = currentContact
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Unlock failed", e)
                _error.value = "Unlock failed: ${e.message}"
            }
        }
    }

    fun startWalletGeneration() {
        _isGeneratingWallet.value = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val seed = ByteArray(32)
                SecureRandom().nextBytes(seed)
                val mnemonic = AlgorandCrypto.Mnemonic.toMnemonic(appContext, seed)
                _generatedMnemonic.value = mnemonic
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Mnemonic generation failed", e)
                _error.value = "Mnemonic generation failed: ${e.message}"
            } finally {
                _isGeneratingWallet.value = false
            }
        }
    }

    fun completeWalletCreation(passcode: String, onSuccess: () -> Unit = {}) {
        val mnemonic = _generatedMnemonic.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val saltBytes = ByteArray(16)
                java.security.SecureRandom().nextBytes(saltBytes)
                val saltB64 = android.util.Base64.encodeToString(saltBytes, android.util.Base64.NO_WRAP)
                
                val encryptedSeed = AlgorandCrypto.encryptMnemonic(mnemonic, passcode)
                val passcodeHash = AlgorandCrypto.hashPasscode(passcode, saltBytes)
                val seed = AlgorandCrypto.Mnemonic.toKey(appContext, mnemonic)
                val address = AlgorandCrypto.encodeAddress(AlgorandCrypto.getPublicKey(seed))

                prefs.edit()
                    .putString("passcode_hash", passcodeHash)
                    .putString("passcode_salt", saltB64)
                    .putString("encrypted_seed", encryptedSeed)
                    .putString("cached_address", address)
                    .apply()

                withContext(Dispatchers.Main) {
                    lastUsedPasscode = passcode
                    _walletState.value = WalletState.Unlocked(address, mnemonic)
                    _generatedMnemonic.value = null
                    refreshBalance()
                    syncNetworkTransactions(silent = true)
                    onSuccess()
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Complete wallet failed", e)
                _error.value = "Wallet save failed: ${e.message}"
            }
        }
    }

    fun importWallet(mnemonic: String, passcode: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Validate mnemonic by deriving key
                val seed = AlgorandCrypto.Mnemonic.toKey(appContext, mnemonic)
                val address = AlgorandCrypto.encodeAddress(AlgorandCrypto.getPublicKey(seed))

                val saltBytes = ByteArray(16)
                java.security.SecureRandom().nextBytes(saltBytes)
                val saltB64 = android.util.Base64.encodeToString(saltBytes, android.util.Base64.NO_WRAP)

                val encryptedSeed = AlgorandCrypto.encryptMnemonic(mnemonic, passcode)
                val passcodeHash = AlgorandCrypto.hashPasscode(passcode, saltBytes)

                prefs.edit()
                    .putString("passcode_hash", passcodeHash)
                    .putString("passcode_salt", saltB64)
                    .putString("encrypted_seed", encryptedSeed)
                    .putString("cached_address", address)
                    .apply()

                withContext(Dispatchers.Main) {
                    lastUsedPasscode = passcode
                    _walletState.value = WalletState.Unlocked(address, mnemonic)
                    refreshBalance()
                    syncNetworkTransactions(silent = true)
                    onSuccess()
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Import wallet failed", e)
                _error.value = "Invalid mnemonic or import failed"
            }
        }
    }



    fun logout() {
        _walletState.value = WalletState.Locked
        _activeContact.value = null
        _chatCutoffs.value = emptyMap()
    }

    fun resetWallet() {
        prefs.edit().clear().apply()
        _walletState.value = WalletState.NotInitialized
        _activeContact.value = null
        _chatCutoffs.value = emptyMap()
        viewModelScope.launch(Dispatchers.IO) {
            db.clearAllTables()
            loadContacts()
        }
    }

    // ── Contacts ──

    fun addContact(address: String, nickname: String, note: String = "") {
        if (!AlgorandCrypto.isValidAddress(address)) {
            _error.value = "Invalid Algorand Address"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val trimmedAddr = address.trim()
            val existing = repository.getContactByAddress(trimmedAddr)
            if (existing != null) {
                withContext(Dispatchers.Main) {
                    _error.value = "Contact with this address already exists"
                }
                return@launch
            }
            val contact = Contact(
                address = trimmedAddr,
                nickname = nickname.trim(),
                note = note.trim()
            )
            repository.insertContact(contact)
        }
    }

    fun addContactAndSelect(address: String, nickname: String, note: String = "") {
        if (!AlgorandCrypto.isValidAddress(address)) {
            _error.value = "Invalid Algorand Address"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val trimmedAddr = address.trim()
            val existing = repository.getContactByAddress(trimmedAddr)
            if (existing != null) {
                withContext(Dispatchers.Main) {
                    _error.value = "Contact with this address already exists"
                }
                return@launch
            }
            val contact = Contact(
                address = trimmedAddr,
                nickname = nickname.trim(),
                note = note.trim()
            )
            repository.insertContact(contact)
            withContext(Dispatchers.Main) {
                _activeContact.value = contact
            }
        }
    }

    fun updateContactDetails(address: String, nickname: String, note: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateContactDetails(address.trim(), nickname.trim(), note.trim())
            val currentActive = _activeContact.value
            if (currentActive != null && currentActive.address == address) {
                withContext(Dispatchers.Main) {
                    _activeContact.value = currentActive.copy(nickname = nickname.trim(), note = note.trim())
                }
            }
        }
    }

    fun toggleContactPinned(address: String, currentPinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateContactPinned(address, !currentPinned)
            val currentActive = _activeContact.value
            if (currentActive != null && currentActive.address == address) {
                withContext(Dispatchers.Main) {
                    _activeContact.value = currentActive.copy(pinned = !currentPinned)
                }
            }
        }
    }

    fun importContactsFromText(text: String, onCompleted: (imported: Int, skipped: Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            var imported = 0
            var skipped = 0
            val lines = text.lines()
            for (line in lines) {
                val cleanLine = line.trim()
                if (cleanLine.isEmpty() || cleanLine.startsWith("#")) continue
                
                val parts = cleanLine.split(",")
                var nickname = ""
                var address = ""
                
                if (parts.size >= 2) {
                    val first = parts[0].trim()
                    val second = parts[1].trim()
                    if (AlgorandCrypto.isValidAddress(first)) {
                        address = first
                        nickname = second
                    } else if (AlgorandCrypto.isValidAddress(second)) {
                        address = second
                        nickname = first
                    }
                } else if (parts.size == 1) {
                    val single = parts[0].trim()
                    if (AlgorandCrypto.isValidAddress(single)) {
                        address = single
                        nickname = "${single.take(4)}...${single.takeLast(4)}"
                    }
                }
                
                if (address.isNotEmpty() && AlgorandCrypto.isValidAddress(address)) {
                    val existing = repository.getContactByAddress(address)
                    if (existing == null) {
                        val contact = Contact(address = address, nickname = nickname)
                        repository.insertContact(contact)
                        imported++
                    } else {
                        skipped++
                    }
                } else {
                    skipped++
                }
            }
            withContext(Dispatchers.Main) {
                onCompleted(imported, skipped)
            }
        }
    }

    fun deleteContact(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteContactByAddress(address)
            if (_activeContact.value?.address == address) {
                _activeContact.value = null
            }
        }
    }

    fun selectContact(contact: Contact?) {
        _activeContact.value = contact
        updateUnreadStatuses()
    }

    fun markAllOtherChatsAsRead() {
        val currentState = _walletState.value
        if (currentState !is WalletState.Unlocked) return
        val myAddr = currentState.address
        viewModelScope.launch(Dispatchers.IO) {
            for (contact in _otherChats.value) {
                val latestInChat = db.messageDao().getLatestReceivedMessageTimestamp(myAddr, contact.address) ?: 0L
                prefs.edit().putLong("last_read_ts_${contact.address}", latestInChat).apply()
            }
            updateUnreadStatuses()
        }
    }

    // ── Messaging & ALGO Balances ──

    fun refreshBalance() {
        val state = _walletState.value
        if (state !is WalletState.Unlocked) return

        if (_isMainnet.value) {
            viewModelScope.launch(Dispatchers.IO) {
                val bal = AlgorandClient.fetchBalance(state.address)
                _balance.value = bal
            }
        } else {
            // Emulated / Demo balance
            _balance.value = prefs.getLong("demo_balance", 10000000L)
        }
    }

    fun addDemoFunds() {
        if (_isMainnet.value) return
        val newBal = _balance.value + 10000000L // Add 10 ALGO
        _balance.value = newBal
        prefs.edit().putLong("demo_balance", newBal).apply()
    }

    fun sendMessage(text: String, amountMicroAlgos: Long = 0L) {
        val state = _walletState.value
        val contact = _activeContact.value
        if (state !is WalletState.Unlocked || contact == null) return

        val trimmedText = text.trim()
        if (trimmedText.isEmpty() && amountMicroAlgos == 0L) return

        viewModelScope.launch(Dispatchers.IO) {
            val seed = AlgorandCrypto.Mnemonic.toKey(appContext, state.mnemonic)
            val txId = "TX_" + UUID.randomUUID().toString().substring(0, 8).uppercase()
            val timestamp = System.currentTimeMillis() / 1000

            // 1. Encrypt text note
            val encryptedNote = if (trimmedText.isNotEmpty()) {
                // Use standard non-DH encryption ("AP1:") to ensure perfect compatibility with the Chromium extension
                AlgorandCrypto.encryptMsg(trimmedText, state.address, contact.address, null)
            } else {
                ""
            }

            val localMsg = LocalMessage(
                txId = txId,
                sender = state.address,
                receiver = contact.address,
                amount = amountMicroAlgos,
                timestamp = timestamp,
                encryptedNote = encryptedNote,
                decryptedText = trimmedText,
                status = "PENDING"
            )

            // Optimistically insert
            repository.insertMessage(localMsg)

            if (_isMainnet.value) {
                val currentBal = _balance.value
                val required = amountMicroAlgos + 101000L // 0.1 ALGO MBR + 0.001 ALGO tx fee
                if (currentBal < required) {
                    _error.value = "Insufficient funds. Please fund your wallet (minimum 0.101 ALGO required) to send messages."
                    repository.insertMessage(localMsg.copy(status = "FAILED"))
                    return@launch
                }
                try {
                    val params = AlgorandClient.fetchTxParams()
                    val senderBytes = AlgorandCrypto.decodeAddress(state.address)
                    val receiverBytes = AlgorandCrypto.decodeAddress(contact.address)
                    val noteBytes = if (encryptedNote.isNotEmpty()) encryptedNote.toByteArray(Charsets.UTF_8) else null

                    val unsignedTx = AlgorandCrypto.serializePaymentTransaction(
                        sender = senderBytes,
                        receiver = receiverBytes,
                        amount = amountMicroAlgos,
                        fee = params.fee,
                        firstValid = params.firstValid,
                        lastValid = params.lastValid,
                        genesisId = params.genesisId,
                        genesisHash = params.genesisHash,
                        note = noteBytes
                    )

                    val signature = AlgorandCrypto.signTransaction(unsignedTx, seed)
                    val signedTx = AlgorandCrypto.serializeSignedTransaction(unsignedTx, signature)

                    val realTxId = AlgorandClient.submitTransaction(signedTx)

                    // Replace temporary ID with actual network TxID and mark as SENT
                    repository.deleteMessageByTxId(txId)
                    repository.insertMessage(
                        localMsg.copy(
                            txId = realTxId,
                            status = "SENT"
                        )
                    )

                    refreshBalance()
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Send testnet tx failed", e)
                    val rawMsg = e.message ?: ""
                    val friendlyMsg = if (rawMsg.contains("overspend", ignoreCase = true) || 
                        rawMsg.contains("below min", ignoreCase = true) || 
                        rawMsg.contains("insufficient", ignoreCase = true) ||
                        rawMsg.contains("balance", ignoreCase = true)) {
                        "Insufficient ALGO funds to cover the transaction. Please fund your wallet."
                    } else {
                        "Failed to send message: ${e.message}"
                    }
                    _error.value = friendlyMsg
                    repository.insertMessage(localMsg.copy(status = "FAILED"))
                }
            } else {
                // Demo Mode Emulated transmission
                delay(800) // Aesthetic delay to make it feel real
                repository.insertMessage(localMsg.copy(status = "SENT"))
                
                // Deduct emulated balance (transfer amount + fee)
                val cost = amountMicroAlgos + 1000L // 0.001 ALGO fee
                val currentBal = _balance.value
                val newBal = if (currentBal >= cost) currentBal - cost else 0L
                _balance.value = newBal
                prefs.edit().putLong("demo_balance", newBal).apply()

                // High fidelity auto-response generator in Demo Mode
                delay(1500)
                val replyTxId = "TX_" + UUID.randomUUID().toString().substring(0, 8).uppercase()
                val replyText = when {
                    trimmedText.lowercase().contains("hello") || trimmedText.lowercase().contains("hi") -> {
                        "Hello! Peer connection secured securely. Ask me anything about this encrypted Algorand channel. 🔒"
                    }
                    trimmedText.lowercase().contains("balance") || trimmedText.lowercase().contains("algo") -> {
                        "Your transaction was processed. Since we are in Demo Mode, the fee of 0.001 ALGO is simulated but fully validated."
                    }
                    else -> {
                        "Understood. The payload was encrypted with AES-GCM and stored as a payment note in our secure transaction history."
                    }
                }
                val replyEncrypted = AlgorandCrypto.encryptMsg(replyText, contact.address, state.address, null)
                val replyMsg = LocalMessage(
                    txId = replyTxId,
                    sender = contact.address,
                    receiver = state.address,
                    amount = 0L,
                    timestamp = System.currentTimeMillis() / 1000,
                    encryptedNote = replyEncrypted,
                    decryptedText = replyText,
                    status = "SENT"
                )
                repository.insertMessage(replyMsg)
            }
        }
    }

    fun syncNetworkTransactions(silent: Boolean = false) {
        val state = _walletState.value ?: return
        if (state !is WalletState.Unlocked) return

        if (!silent) _isRefreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                refreshBalance()
                if (_isMainnet.value) {
                    val seed = AlgorandCrypto.Mnemonic.toKey(appContext, state.mnemonic)
                    val apiTxns = AlgorandClient.fetchTransactions(state.address)
                    for (tx in apiTxns) {
                        val note = tx.noteB64 ?: continue
                        var decrypted = ""
                        try {
                            val rawNoteBytes = android.util.Base64.decode(note, android.util.Base64.DEFAULT)
                            val noteStr = String(rawNoteBytes, Charsets.UTF_8)
                            if (noteStr.startsWith("AP1:") || noteStr.startsWith("AP_DH1:")) {
                                // Decrypt message
                                val peerAddress = if (tx.sender == state.address) tx.receiver else tx.sender
                                decrypted = AlgorandCrypto.decryptMsg(noteStr, state.address, peerAddress, seed)
                            }
                        } catch (e: Exception) {
                            Log.e("ChatViewModel", "Decrypting synced txn note failed", e)
                        }

                        if (decrypted.isNotEmpty()) {
                            val isNew = repository.getMessageCountByTxId(tx.txId) == 0
                            val localMsg = LocalMessage(
                                txId = tx.txId,
                                sender = tx.sender,
                                receiver = tx.receiver,
                                amount = tx.amount,
                                timestamp = tx.timestamp,
                                encryptedNote = tx.noteB64 ?: "",
                                decryptedText = decrypted,
                                status = "SENT"
                            )
                            repository.insertMessage(localMsg)
                        }
                    }
                } else {
                    // Demo Mode simulation sync
                    if (!silent) delay(1000)
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Sync failed", e)
                if (!silent) _error.value = "Sync failed: ${e.message}"
            } finally {
                if (!silent) _isRefreshing.value = false
                updateUnreadStatuses()
            }
        }
    }

}
