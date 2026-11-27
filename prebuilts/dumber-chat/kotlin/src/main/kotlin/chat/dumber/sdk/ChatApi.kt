package chat.dumber.sdk

/** Blocking operations. Call from a worker thread. Policy and network behavior live in Rust. */
interface ChatApi {
    fun requestCode(phone: String): CodeRequest
    fun verify(phone: String, code: String): Session
    fun requestCode(phone: String, kind: RegistrationKind): CodeRequest =
        if (kind == RegistrationKind.MESSAGING) requestCode(phone) else throw ChatException("Separate calling registration unsupported", ErrorKind.UPGRADE_REQUIRED)
    fun verify(phone: String, code: String, kind: RegistrationKind): Session =
        if (kind == RegistrationKind.MESSAGING) verify(phone, code) else throw ChatException("Separate calling registration unsupported", ErrorKind.UPGRADE_REQUIRED)
    fun sessionStatus(): SessionStatus
    fun reviewPairing(code: String): PairingReview
    fun approvePairing(code: String): PairingReview
    fun startPairingVerification(code: String): PairingVerification = throw ChatException("Browser verification unsupported", ErrorKind.UPGRADE_REQUIRED)
    fun pairingVerification(id: String, action: VerificationAction = VerificationAction.POLL): PairingVerification = throw ChatException("Browser verification unsupported", ErrorKind.UPGRADE_REQUIRED)
    fun preparePairingHistory(review: PairingReview, conversations: List<HistoryConversation>): PreparedHistory =
        throw ChatException("History transfer unsupported", ErrorKind.UPGRADE_REQUIRED)
    fun preparePairingHistory(review: PairingReview, conversations: List<HistoryConversation>, contactNames: Map<String, String>): PreparedHistory =
        if (contactNames.isEmpty()) preparePairingHistory(review, conversations)
        else throw ChatException("Contact name transfer unsupported", ErrorKind.UPGRADE_REQUIRED)
    fun uploadPairingHistory(history: PreparedHistory) { throw ChatException("History transfer unsupported", ErrorKind.UPGRADE_REQUIRED) }
    fun recipientStatus(phone: String): RecipientStatus
    fun checkRecipient(phone: String, force: Boolean = false): RecipientStatus
    fun openChat(phone: String, cachedOnly: Boolean = false): Conversation
    fun conversations(): List<Conversation>
    fun conversationInfo(conversationId: String): Conversation
    fun createGroup(name: String, phones: List<String>, operationId: String): Conversation
    fun inviteMember(conversationId: String, phone: String)
    fun renameGroup(conversationId: String, name: String)
    fun removeMember(conversationId: String, contactId: String)
    fun grantAdmin(conversationId: String, contactId: String)
    fun join(conversationId: String)
    fun leave(conversationId: String)
    fun sendText(conversationId: String, body: String, transactionId: String): SentMessage
    fun setCallCapability(enabled: Boolean) { throw ChatException("Calling unsupported", ErrorKind.UPGRADE_REQUIRED) }
    fun callCapability(phone: String): Boolean = throw ChatException("Calling unsupported", ErrorKind.UPGRADE_REQUIRED)
    fun turnConfig(): TurnConfig = throw ChatException("Calling unsupported", ErrorKind.UPGRADE_REQUIRED)
    fun sendCall(conversationId: String, signal: CallSignal, transactionId: String) { throw ChatException("Calling unsupported", ErrorKind.UPGRADE_REQUIRED) }
    fun acknowledgeCalls(eventIds: List<String>) { throw ChatException("Calling unsupported", ErrorKind.UPGRADE_REQUIRED) }
    fun enableSmsSync() { throw ChatException("SMS sync unsupported", ErrorKind.UPGRADE_REQUIRED) }
    fun publishSms(messages: List<SmsMessage>, transactionId: String) { throw ChatException("SMS sync unsupported", ErrorKind.UPGRADE_REQUIRED) }
    fun publishSms(messages: List<SmsMessage>, progress: SmsProgress, transactionId: String) { throw ChatException("History progress unsupported", ErrorKind.UPGRADE_REQUIRED) }
    fun smsRecipientRoutes(): List<SmsRecipient> = throw ChatException("SIM sync unsupported", ErrorKind.UPGRADE_REQUIRED)
    fun publishSmsRoutes(sims: List<SmsSim>, recipients: List<SmsRecipient>, transactionId: String) { throw ChatException("SIM sync unsupported", ErrorKind.UPGRADE_REQUIRED) }
    fun acknowledgeSms(eventIds: List<String>) { throw ChatException("SMS sync unsupported", ErrorKind.UPGRADE_REQUIRED) }
    fun sync(): SyncUpdate
    fun devices(phone: String? = null): DeviceList
    fun exportKeys(path: String, passphrase: String)
    fun importKeys(path: String, passphrase: String): KeyImport
    fun logout()
}

internal object NativeBridge {
    init { System.loadLibrary("dumber_chat_core") }
    @JvmStatic external fun invoke(request: String): String
}
