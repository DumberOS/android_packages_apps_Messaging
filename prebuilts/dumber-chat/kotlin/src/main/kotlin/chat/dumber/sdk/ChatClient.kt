package chat.dumber.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Typed Android/JVM binding. The serialized JNI transport is an implementation detail. */
class ChatClient internal constructor(private val config: ChatConfig, private val transport: (String) -> String) : ChatApi {
    constructor(config: ChatConfig) : this(config, { NativeBridge.invoke(it) })
    companion object {
        @JvmStatic fun isAvailable(): Boolean = try { Class.forName("chat.dumber.sdk.NativeBridge"); true }
            catch (_: LinkageError) { false } catch (_: Exception) { false }
    }
    private fun call(command: String, vararg arguments: Pair<String, Any?>): Any? {
        val operation = JSONObject().put("command", command)
        arguments.forEach { (key, value) -> operation.put(key, if(value is List<*>) JSONArray(value) else value) }
        val request = JSONObject().put("api_version", 2).put("service", config.service).put("operation", operation)
        config.session?.let { request.put("session", Wire.session(it)) }
        config.store?.let { request.put("store_path", it.path).put("store_password", it.password) }
        val response = try { JSONObject(transport(request.toString())) }
            catch (e: org.json.JSONException) { throw ChatException("Invalid response from messaging core") }
        if (!response.getBoolean("ok")) throw ChatException(response.getString("error"),
            Wire.enum(response.getString("error_kind"), ErrorKind.INTERNAL), response.nullableLong("retry_after_seconds"))
        return response.opt("data")
    }
    private fun obj(command: String, vararg arguments: Pair<String, Any?>) = call(command, *arguments) as JSONObject
    override fun requestCode(phone: String) = obj("request_code", "phone" to phone).let { CodeRequest(it.getString("status"), it.getBoolean("development")) }
    override fun verify(phone: String, code: String) = Wire.session(obj("verify", "phone" to phone, "code" to code))
    override fun requestCode(phone: String, kind: RegistrationKind) = obj("request_code", "phone" to phone, "registration_kind" to kind.name.lowercase(java.util.Locale.ROOT)).let { CodeRequest(it.getString("status"), it.getBoolean("development")) }
    override fun verify(phone: String, code: String, kind: RegistrationKind) = Wire.session(obj("verify", "phone" to phone, "code" to code, "registration_kind" to kind.name.lowercase(java.util.Locale.ROOT)))
    override fun sessionStatus() = obj("session_status").let { SessionStatus(Wire.enum(it.getString("role")), it.getBoolean("main_active"), it.optJSONObject("browser")?.let(Wire::browser)) }
    override fun reviewPairing(code: String) = Wire.pairing(obj("pairing_review", "code" to code))
    override fun approvePairing(code: String) = Wire.pairing(obj("pairing_approve", "code" to code))
    override fun startPairingVerification(code: String) = Wire.verification(obj("start_pairing_verification", "code" to code))
    override fun pairingVerification(id: String, action: VerificationAction) = Wire.verification(obj("pairing_verification", "id" to id, "action" to action.name.lowercase(java.util.Locale.ROOT)))
    override fun preparePairingHistory(review: PairingReview, conversations: List<HistoryConversation>) = preparePairingHistory(review, conversations, emptyMap())
    override fun preparePairingHistory(review: PairingReview, conversations: List<HistoryConversation>, contactNames: Map<String, String>) = Wire.history(obj("prepare_pairing_history",
        "review" to Wire.pairing(review), "contact_names" to JSONObject(contactNames), "conversations" to conversations.map { JSONObject().put("conversation", Wire.conversation(it.conversation))
            .put("messages", JSONArray(it.messages.map(Wire::message))) }))
    override fun uploadPairingHistory(history: PreparedHistory) { call("upload_pairing_history", "history" to Wire.history(history)) }
    override fun recipientStatus(phone: String) = Wire.recipient(obj("recipient_status", "phone" to phone))
    override fun checkRecipient(phone: String, force: Boolean) = Wire.recipient(obj("check_recipient", "phone" to phone, "force" to force))
    override fun openChat(phone: String, cachedOnly: Boolean) = Wire.conversation(obj("open_chat", "phone" to phone, "cached_only" to cachedOnly))
    override fun conversations() = (call("conversations") as JSONArray).objects(Wire::conversation)
    override fun conversationInfo(conversationId: String) = Wire.conversation(obj("conversation_info", "conversation_id" to conversationId))
    override fun createGroup(name: String, phones: List<String>, operationId: String) = Wire.conversation(obj("create_group", "name" to name, "phones" to phones, "operation_id" to operationId))
    override fun inviteMember(conversationId: String, phone: String) { call("invite_member", "conversation_id" to conversationId, "phone" to phone) }
    override fun renameGroup(conversationId: String, name: String) { call("rename_group", "conversation_id" to conversationId, "name" to name) }
    override fun removeMember(conversationId: String, contactId: String) { call("remove_member", "conversation_id" to conversationId, "contact_id" to contactId) }
    override fun grantAdmin(conversationId: String, contactId: String) { call("grant_admin", "conversation_id" to conversationId, "contact_id" to contactId) }
    override fun join(conversationId: String) { call("join", "conversation_id" to conversationId) }
    override fun leave(conversationId: String) { call("leave", "conversation_id" to conversationId) }
    override fun sendText(conversationId: String, body: String, transactionId: String) = obj("send_text", "conversation_id" to conversationId, "body" to body, "transaction_id" to transactionId).let { SentMessage(it.getString("event_id"), it.getBoolean("encrypted")) }
    override fun setCallCapability(enabled: Boolean) { call("set_call_capability", "enabled" to enabled) }
    override fun callCapability(phone: String) = obj("call_capability", "phone" to phone).getBoolean("supported")
    override fun turnConfig() = obj("turn_config").let { v -> TurnConfig(v.getJSONArray("uris").let { a -> (0 until a.length()).map { a.getString(it) } }, v.getString("username"), v.getString("password"), v.getLong("ttl")) }
    override fun sendCall(conversationId: String, signal: CallSignal, transactionId: String) { call("send_call", "conversation_id" to conversationId, "signal" to CallWire.encode(signal), "transaction_id" to transactionId) }
    override fun acknowledgeCalls(eventIds: List<String>) { call("acknowledge_calls", "event_ids" to eventIds) }
    override fun configureBrowserSms(pairingId: String, enabled: Boolean) = Wire.browser(obj("configure_browser_sms", "pairing_id" to pairingId, "enabled" to enabled))
    override fun unpairBrowser(pairingId: String) { call("unpair_browser", "pairing_id" to pairingId) }
    override fun enableSmsSync() { call("enable_sms_sync") }
    override fun publishSms(messages: List<SmsMessage>, transactionId: String) = publishSmsBatch(messages, null, transactionId)
    override fun publishSms(messages: List<SmsMessage>, progress: SmsProgress, transactionId: String) = publishSmsBatch(messages, progress, transactionId)
    private fun publishSmsBatch(messages: List<SmsMessage>, progress: SmsProgress?, transactionId: String) { call("publish_sms", "transaction_id" to transactionId,
        "progress" to progress?.let { JSONObject().put("generation",it.generation).put("request_ids",JSONArray(it.requestIds)).put("offset",it.offset).put("total",it.total).put("complete",it.complete) }, "messages" to messages.map { v -> JSONObject()
        .put("id",v.id).put("address",v.address).put("body",v.body).put("timestamp_ms",v.timestampMs).put("incoming",v.incoming).put("status",v.status).put("name",v.name).put("deleted",v.deleted).put("sim_id",v.simId).put("transport",v.transport).put("matrix_event_id",v.matrixEventId).put("matrix_room_id",v.matrixRoomId).put("matrix_account",v.matrixAccount) }) }
    override fun smsRecipientRoutes() = (call("sms_recipient_routes") as JSONArray).objects { v -> SmsRecipient(v.getString("sim_id"),v.getString("address"),v.getString("transport")) }
    override fun publishSmsRoutes(sims: List<SmsSim>, recipients: List<SmsRecipient>, transactionId: String) {
        call("publish_sms_routes", "transaction_id" to transactionId,
            "sims" to sims.map { v -> JSONObject().put("id",v.id).put("label",v.label).put("phone",v.phone).put("account_id",v.accountId) },
            "recipients" to recipients.map { v -> JSONObject().put("sim_id",v.simId).put("address",v.address).put("transport",v.transport) })
    }
    override fun acknowledgeSms(eventIds: List<String>) { call("acknowledge_sms", "event_ids" to eventIds) }
    override fun sync() = obj("sync").let { SyncUpdate(it.getJSONArray("conversations").objects { update ->
        ConversationUpdate(Wire.conversation(update.getJSONObject("conversation")), update.getJSONArray("messages").objects(Wire::message), update.getBoolean("limited"))
    }, it.getBoolean("contacts_pending"), it.optJSONArray("calls")?.objects(CallWire::event) ?: emptyList(), it.optJSONArray("sms_requests")?.objects { v ->
        SmsRequest(v.getString("event_id"),v.getString("request_id"),v.getString("kind"),v.getString("address"),v.getString("body"),v.getLong("created_at_ms"),v.getLong("received_at_ms"),v.optString("sim_id"),v.optString("transport"),v.optString("pairing_id")) } ?: emptyList()) }
    override fun devices(phone: String?) = obj("devices", "phone" to phone).let { data -> DeviceList(Wire.contact(data.getJSONObject("contact")), data.getString("current_device_id"), data.nullableString("current_device_fingerprint"), data.getJSONArray("devices").objects {
        Device(it.getString("device_id"), it.nullableString("name"), it.getBoolean("verified"), it.nullableString("fingerprint"))
    }) }
    override fun exportKeys(path: String, passphrase: String) { call("export_keys", "path" to path, "passphrase" to passphrase) }
    override fun importKeys(path: String, passphrase: String) = obj("import_keys", "path" to path, "passphrase" to passphrase).let { KeyImport(it.getLong("imported"), it.getLong("total")) }
    override fun logout() { call("logout") }
}

internal object CallWire {
    fun encode(s: CallSignal) = JSONObject().put("kind", s.kind.name.lowercase(java.util.Locale.ROOT))
        .put("call_id", s.callId).put("party_id", s.partyId).put("sent_at_ms", s.sentAtMs)
        .put("sdp", s.sdp).put("description_type", s.descriptionType).put("selected_party_id", s.selectedPartyId)
        .put("reason", s.reason).put("candidates", JSONArray(s.candidates.map { c -> JSONObject()
            .put("candidate", c.candidate).put("sdp_mid", c.sdpMid).put("sdp_m_line_index", c.sdpMLineIndex) }))
    fun decode(v: JSONObject) = CallSignal(Wire.enum(v.getString("kind")), v.getString("call_id"), v.getString("party_id"),
        v.getLong("sent_at_ms"), v.nullableString("sdp"), v.nullableString("description_type"),
        v.getJSONArray("candidates").objects { c -> IceCandidate(c.getString("candidate"), c.getString("sdp_mid"), c.getInt("sdp_m_line_index")) },
        v.nullableString("selected_party_id"), v.nullableString("reason"))
    fun event(v: JSONObject) = CallEvent(v.getString("event_id"), v.getString("conversation_id"), Wire.contact(v.getJSONObject("sender")), v.getLong("expires_at_ms"), decode(v.getJSONObject("signal")))
}
