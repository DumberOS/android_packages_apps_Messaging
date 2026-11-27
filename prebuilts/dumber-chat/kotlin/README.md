# Kotlin binding

`chat.dumber.sdk.ChatApi` is the Android-facing API for the Rust core. It exposes
blocking methods and immutable models, including enum errors and sealed message
content. Call it from a worker thread. JSON is private to the JNI implementation;
applications do not construct operations, parse responses, or import `org.json`
to use this API.

```kotlin
val registration = ChatClient(ChatConfig(serviceUrl))
registration.requestCode(phone)
val session = registration.verify(phone, code)
// Save this opaque value in the host's encrypted account storage.
val savedSession = ChatStorage.encodeSession(session)

val client: ChatApi = ChatClient(ChatConfig(
    serviceUrl,
    ChatStorage.decodeSession(savedSession),
    CryptoStore(privateStorePath, persistentStorePassword),
))
val conversation = client.openChat(recipientPhone)
val sent = client.sendText(conversation.id, "Hello", stableTransactionId)
for (update in client.sync().conversations) {
    for (message in update.messages) {
        when (val content = message.content) {
            is MessageContent.Text -> println(content.body)
            MessageContent.UnableToDecrypt -> { /* Show a pending-decryption placeholder. */ }
            MessageContent.Unsupported -> { /* Hide or show an unsupported-message placeholder. */ }
        }
    }
}
```

`ChatStorage` also encodes/decodes conversations and messages as opaque strings.
The host chooses where to store them, encrypts sensitive local data, owns history
and deletion rules, and keeps pending-send transaction IDs stable across retries.
Session credentials and store passwords must never be inspected or logged.
`Session` and `CryptoStore` redact secrets from their string representations.

## History when linking a browser

For new browser links, call `approvePairing(code)`, then poll
`startPairingVerification(code)` until it returns an ID. Poll
`pairingVerification(id)` while the normal account sync loop runs. Display the
three `numbers` and submit `VerificationAction.CONFIRM` only after explicit user
comparison with the browser; use `MISMATCH` or `CANCEL` otherwise. Continue to
SMS sharing and history export only after state `verified`. The SDK binds the
target to the approved pairing and persists Matrix device trust. An interrupted
or expired flow requires re-linking, not automatic confirmation.

After verification, pass its pairing ID and the user's Yes/No choice to
`configureBrowserSms(pairingId, enabled)`. `SessionStatus.browser` identifies the
single current browser and authorized SMS room. Each returned `SmsRequest`
carries a `pairingId`; hosts must persist it with the durable send ledger and
check it against the current enabled grant before carrier submission. Unpairing
clears the SDK's old room and request inbox; retain host deduplication tombstones.
The legacy `enableSmsSync()` only adopts existing consent and cannot grant it.
Use `unpairBrowser(pairingId)` to sign out the displayed browser from its main
phone. It rejects a stale pairing ID if a different browser has since linked;
the phone's registration stays active. Drive the Link/Sign out button from
`sessionStatus().browser`, rather than a completed history-copy record.

After `reviewPairing` and the user's confirmation, supply the host's local records
as `List<HistoryConversation>` to `preparePairingHistory(review, conversations)`.
This encrypts up to 8 MiB of confirmed readable text plus conversation metadata
for the reviewed browser; it does not export login credentials or Matrix keys.
The overload accepting `contactNames: Map<String, String>` includes local contact
names keyed by SDK contact ID. Only names referenced by exported conversations or
messages are retained. Rust translates opaque conversation/contact IDs through
the device's encrypted address book for the browser; hosts never handle Matrix IDs.
Retain the crypto store alongside local history. An unmappable saved conversation
fails the export explicitly while browser approval can still proceed.
Older browsers have a null `historyPublicKey` and can still be approved normally.

Persist `ChatStorage.encodePreparedHistory(prepared)` in encrypted account storage
before sending. Approve with `approvePairing(code)`, persist the successful
approval, then call `uploadPairingHistory(prepared)`. Retry the exact saved envelope
on ambiguous failures; regenerating it after a successful upload causes a conflict.
Remove pending work on account replacement/logout. Uploads expire 15 minutes after
approval, independently of the five-minute pairing code. Export failures should
not prevent approval. The Android sample implements this flow and foreground retries.

The browser commits history to IndexedDB before acknowledging receipt; the relay
then deletes the payload. This is a one-time copy, not a server-history API or
ongoing synchronization between local stores.
No Android context, SMS provider, database, lifecycle, UI, or retry scheduler is
part of this binding. Discovery, rate limiting, identity resolution, and protocol
interpretation remain in Rust.

## Build integration

The binding ships as source with the Rust core so the JNI library and Kotlin API
can be updated together without another binary release or protocol version.
Compile `src/main/kotlin` into the host and package the matching
`libdumber_chat_core.so` in `jniLibs/<abi>`. Android provides `org.json`; Kotlin
requires only its standard library. No serialization compiler plugin is needed.
Include `consumer-rules.pro` in builds that run R8/ProGuard.

DPAD includes this directory from its vendored Rust snapshot. The demo includes
the original source directly. For AGP 9's built-in Kotlin, configure
`android.sourceSets.main.kotlin.srcDir`; older Kotlin Android plugin builds can
include the directory in their existing Kotlin/Java source set.

The internal JNI entry point is `chat.dumber.sdk.NativeBridge.invoke`. The former
app-owned `chat.dumber.app.NativeCore` and JSON request facade have been removed.
Do not mix old binaries with this binding. No local-data migration is supplied.

`src/test/kotlin` contains Robolectric/JUnit binding tests. DPAD runs these together
with its chat integration tests. Application tests should inject `ChatApi` fakes;
only binding tests need knowledge of the serialized envelope.


`ChatApi` also provides `setCallCapability`, `callCapability`, `turnConfig`,
`sendCall`, and `acknowledgeCalls`. `SyncUpdate.calls` is separate from chat
messages. Process and acknowledge these typed events from a worker thread;
`CallSignal`/`CallKind` and `IceCandidate` keep wire event names out of the host.
Account credentials stay in the SDK owner. A media host only needs the call
signals and temporary `TurnConfig`; its `toString` redacts credentials.


Select `RegistrationKind.MESSAGING`, `CALLS`, or `BOTH` in both
`requestCode(phone, kind)` and `verify(phone, code, kind)` for protocol-3 registration.
`BOTH` creates one shared session occupying both slots. Replacing either slot logs
that combined client out completely. Existing overloads keep protocol-2 compatibility;
new servers interpret them as messaging registrations, with legacy calling support
able to reserve a free calling slot. Protocol 3 never falls back on older servers.
Slots are replacement policies, not cryptographic access scopes.
