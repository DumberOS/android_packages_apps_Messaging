# dumber.chat in DumberOS Messaging

The backend is `https://dumber.chat`. Messaging registers and owns its sessions; no separate services app is installed. Each connected number is bound to a SIM subscription and card identity.

Open **Settings → dumber.chat** to activate a number. Activation shows one step at a time: allow phone-number detection or choose manual entry, select the number/SIM, then **Waiting for activation SMS**. If the SIM has no readable number, the page requests it. A matching incoming SMS on the selected subscription completes registration automatically; the code field and activation button appear only after choosing **Enter manually**. Expired requests offer another SMS. Manual number entry skips phone-number permission, but SIM access is still required to bind the account to the correct SIM. Connected-account controls let you activate another SIM, link a browser, inspect devices, or disconnect. SIM removal or replacement must remain stable for five seconds before prompting **Keep connection** or **Deregister**. The session remains connected while the choice is pending. Keeping the connection is remembered until the SIM returns; a later removal prompts again. When the app is in the background, a notification opens the choice. Temporary telephony state and airplane mode do not trigger the prompt.

The compose hint shows ‘Encrypted message’ or ‘Text message’ for the selected SIM, and the send button carries a lock only for confirmed encrypted availability. Unknown availability shows ‘Checking encryption…’ without a lock. The SIM picker labels each SIM with its recipient-specific transport (for example ‘Encrypted · Orange’ or ‘SMS · Comviq’). There is no transport bar above the composer; the Message transport overflow item retains rediscovery, recipient-device verification, and queued-message actions. Opening the conversation checks availability. Sending only reads the Rust core's cached result: known unavailable recipients use the existing carrier path; unknown recipients queue for review. The outgoing composer has no subject editor; long-press is reserved for SIM selection. Subjects from existing or externally supplied drafts become visible body text, while received MMS subjects remain displayed. Subjects and attachments are blocked for direct messages that would use dumber.chat, including unknown encrypted availability; the composer keeps the draft and asks the user to remove unsupported content. The send action repeats this guard before creating any carrier message. Ordinary carrier recipients and carrier group messages retain MMS/SMS behavior. Notification replies enter the same send action as the composer.

Queued messages offer retry and discard. Direct messages also offer an explicitly confirmed SMS fallback using the original SIM and account. Uncertain delivery is distinguished from a confirmed send: retries reuse the transaction ID, and confirmed sends only reconcile local history. A device change prevents retrying an old uncertain send. SMS fallback has its own durable handoff token and can resume without creating a second carrier provider row.

Encrypted direct messages appear in ordinary threads with an **Encrypted** label. Their provider rows carry a transport marker in `service_center` and disable reply-path routing. That marker is not used as an SMS service center for carrier transmission. The private transport index is rebuilt from the provider and corrected on sync. Replay tombstones survive deletion of a conversation and rebuilding Bugle's cache.

**New encrypted group** appears in the conversation-list menu only while at least one dumber.chat account is connected. Groups appear in the regular and archived lists, sorted with carrier conversations, with separate identities and storage. The group screen supports invitations, encrypted text, pending sends, participants, rename, invite, remove, grant admin, leave, archive, mute, pin, and deleting local history. Creation asks only for a group name and starts with the creator alone. The chat’s **Add participants** button accepts a phone number or a phone number selected from Contacts. Invitations require dumber.chat availability; invalid or unavailable numbers show an error without closing the dialog or deleting the group. Creation retries reuse the saved operation ID and display the saved name. Older saved creation requests with initial participants must be explicitly discarded before starting a new creator-only group. Group history and app-specific presentation state stay outside the shared library and outside Bugle's rebuildable database.

## Code boundaries

- `prebuilts/dumber-chat`: precompiled ARM64/ARM32 Rust JNI libraries, matching `chat.dumber.sdk` Kotlin bindings, checksums, provenance and third-party notices. Refresh this entire bundle together. Rust source stays in the separate dumber.chat repository, not this app tree.
- `src/com/android/messaging/dumber`: typed Android account, SIM, queue, encrypted group storage, services and UI. This code does not construct JSON or know Matrix commands.
- `ChatHost.java` and the small Bugle hooks: carrier sending, provider synchronization, notifications, settings and conversation-list integration.
- `ChatDelivery.kt`: testable delivery state transitions with a typed `ChatApi` dependency.

Account credentials, crypto-store passwords, pending sends and group records are encrypted with an Android Keystore AES-GCM key in the app's no-backup directory. SDK models are persisted through `ChatStorage`; app-owned models use a private versioned binary format. Protocol updates belong in Rust/the shared binding, not in both messaging applications. No migration from DPAD or a previous API is included.

## Build

This integration targets the Android 14 ROM. The native libraries are included in the repository. App and ROM image builds require no Cargo, Rust target installation, external NDK, or native dependency download:

```sh
bash packages/apps/Messaging/tools/build-dumber-messaging.sh
```

The wrapper verifies the prebuilt checksums, then builds `messaging` and `messagingtests` for `lineage_vanilla31-ap2a-userdebug`. Another lunch target can be passed as its argument. `scripts/build_all_images.sh` verifies both Messaging and Dialer prebuilts before building ROM images.

Using `m messaging` directly also consumes the shipped ARM64 and ARM32 libraries. `tools/prepare-dumber-chat.sh` remains a compatibility entry point for checksum verification only. The exact JNI class name is preserved through R8. Include the complete `prebuilts/dumber-chat` directory in commits and source distributions; it is not ignored by Git.

### Updating the native library (maintainers only)

Ordinary ROM builders skip this step. To update the core, obtain the separate dumber.chat source checkout, install Rust 1.96+ with both Android targets and a Linux NDK, then run:

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi
python3 packages/apps/Messaging/tools/update-dumber-chat-prebuilts.py \
  --source /path/to/dumber.chat/crates/chat-core \
  --ndk /path/to/android-ndk-r30
```

The updater builds both ABIs with the source checkout's Cargo lockfile and packages their matching Kotlin bindings. It records source/artifact SHA-256 hashes, compiler/NDK versions and dependency notices. It validates the staged bundle before replacing the installed bundle; failed builds leave the existing bundle intact. Run it without a concurrent ROM build and commit the entire resulting bundle together. Kotlin/JNI API changes require regenerating this bundle, not editing the copied bindings alone.

## Validation

```sh
bash packages/apps/Messaging/tools/test-dumber-chat.sh
```

The host tests use typed fake clients to cover cached routing, uncertain sends, transaction reuse, confirmed-message reconciliation, encryption confirmation, account/device changes, group permissions, SIM identities, verification parsing and activation-step transitions (permissions, missing numbers, multiple SIMs, manual fallback, expiry and completion).

`ChatStorageTest` in `messagingtests` additionally exercises real Android Keystore/SQLite storage, group replay tombstones, and loading the native bridge without a network request. As with the existing Bugle instrumentation suite, run against a build with the test ProGuard configuration enabled (`messaging.build_variant_eng=true`), so the target app's test interfaces are retained. An optimized APK build verifies packaging separately.

Live verification still requires an Android device and service accounts: registration and browser pairing, two-SIM selection and removal, direct send/receive and notification replies, explicit fallback after disconnecting the network, incoming group invitations and management, and process restart/provider-cache rebuild. No ROM flashing is part of this change.

Voice, encrypted attachments, group notification replies, and scheduled encrypted sends are outside this integration.

Encrypted group chats use a persistent composer with the encrypted send icon on the right and incoming/outgoing message bubbles. The header shows the group name; its overflow menu contains Group details and Manage participants (enabled for connected, joined admins). Group details retains rename, local preferences, leave, and history deletion. Queued messages appear as outgoing bubbles with retry/discard actions on tap. Incoming sync updates the timeline without recreating the draft field or resetting its cursor, and only follows new messages when already at the bottom.

Invitation actions use the saved invitation rather than requesting full group details before joining. After a successful join, details refresh when accessible; a refresh failure does not turn success into an error. Declining or leaving uses the updated local membership snapshot, without requesting details after access has ended. Participant policy is checked before joining, including unresolved numbers when a phone policy is configured.
