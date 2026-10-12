# Service Transition – Milestone 0 Inventory

This is the functional spec for porting Dart message handling to Kotlin (see `SERVICE_TRANSITION.md`, Milestones 3–6).
**RPS** = `lib/services/rustpush/rustpush_service.dart`. Line numbers refer to the tree at the time of writing; re-check before porting.

**Decisions recorded**
- BlueBubbles-server mode (FCM, Socket.IO, UnifiedPush) is **dropped**. Those `DartWorker` paths are deleted, not ported.
- Baseline benchmarks (push → notification latency, background RSS, APK size) still need a physical device. They are **not yet captured**.

---

## 0. Ingress & acknowledgement

**Kotlin.** `APNService.receievedMsg(ptr, retry)` (`APNService.kt:96`) calls `MethodCallHandler.invokeMethod("APNMsg")` when `MainActivity.engine != null`. Otherwise it calls `DartWorker.callMethod("APNMsg")`. SMS from `SMSObserver` follows the same pattern with `"SMSMsg"`.

**Dart flow:**
1. `method_channel_service.dart:173` → `pushService.recievedMsgPointer(pointer, retry)`.
2. `RPS:4483 recievedMsgPointer`:
   - Calls `ptrToDart`, then awaits `initFuture`.
   - Sets `isFinal = retry >= 3`.
   - Calls `handleMsg(msg, isFinal)`, then `markAsHandledAfter(ptr)`.
   - On exception it only completes when `isFinal`, then rethrows.
3. `RPS:4472 markAsHandledAfter` waits until the incoming queue is idle, then calls `completeMsg`.
4. `RPS:3143 handleMsg` runs `handleMsgInner` with a 3-minute timeout. Then `markCertified` runs (not awaited); after a failure it only runs if final.
5. `RPS:3083 markCertified`:
   - Computes `sendDelivered`. It is forced to false for a blocked 1:1 handle or `isRpSms`.
   - If there is no `certifiedContext` and `sendDelivered` is true: send `Message.Delivered` to `[sender]` with the same `id` and `target`. Skip ids that contain `temp`/`error`.
   - Otherwise call `certifyDelivery(context, notify: sendDelivered)`.
   - **Native API needed:** `certify_delivery` (not exported yet).

The native port keeps these semantics: complete after a successful persist, or after the final retry fails.

---

## 1. `handleMsgInner` dispatch (`RPS:3155-3820`)

### 1a. Top-level `PushMessage`

| Variant | Lines | Behaviour |
|---|---|---|
| CircleFinishEvent | 3156 | Sets `cachedInClique = true` if `isInClique`. No DB writes. |
| StatusUpdate | 3169 | `Chat.findByRust([user], soft)` → `notifsSilenced = !allowed`, save, update the UI. |
| TwoFaAuthEvent | 3180 | UI only. Kotlin already handles this through `MsgReceiver.twofa_event`. |
| Idms | 3188 | `RequestedSignIn` → sign-in notification. `TeardownSignIn` → `apple-account-login` intent. |
| FaceTime | 3201-3350 | Session-state refresh, ring/decline/join handling, missed-call notification, `answerFtRequest`. |
| RegistrationState | 3352 | `Registered` → `handleRegistered`. `Failed` → register-failed notification (once). If there is no `retryWait`, also `markFailedToLogin`. |
| BeaconShared | 3374 | Beacon invitation notification. |
| NewPhotostream | 3379 | Album invitation notification. |
| SendConfirm | 3385 | `findOne(uuid)` → `sendingServiceId = null`. **The `error` is ignored.** |

### 1b. `Message` variants handled before chat resolution

| Variant | Lines | Behaviour |
|---|---|---|
| EnableSmsActivation | 3396 | Skipped if verification failed. Gets `peerUuid` from `convertTokenToUuid(sender, target[0].token)`. Then adds or removes `settings.smsForwardingTargets[sender]`. |
| SetTranscriptBackground | 3415 | Chat lookup: `chatId` (handle or rust guid) or the sender handle. `Set` → `downloadMmcs` + `parseTranscriptPoster`; dynamic/gradient posters count as removal. Otherwise save the poster, update `transcriptPosterPath`/`transcriptBackgroundVersion` and queue a synthetic `itemType 7` message. Does not check `verificationFailed`. |
| ShareProfile | 3491 | `handleSharedProfile` (`RPS:2831`): `fetchProfile` → shared `Contact` + poster, linked to the handle. If the sender is one of my handles, add to `settings.sharedContacts` instead. |
| UpdateProfileSharing | 3497 | Settings `sharedContacts`/`dismissedContacts`/`shareVersion`. |
| UpdateProfile | 3505 | Own profile: `nameAndPhotoSharing`, avatar/poster files, names. |
| Error | 3558 | Target is not delivered and the sender is mine → `markFailed` (`RPS:2057`): guid becomes `error-protocol: <statusStr>`, `stagingGuid` = the old guid, failed-to-send notification. |
| UpdateExtension | 3570 | Sticker update: rewrites `attributedBody[0].runs[0].attributes.sticker`. |
| PeerCacheInvalidate | 3583 | `invalidatePeerCaches` (`RPS:2995`): re-sends `PeerCacheInvalidate` to iMessage chats active in the last 12h. |
| SmsConfirmSent | 3587 | `true` → `guid = stagingGuid`. `false` → `handleSendError`. Force-unwraps `findOne` (crash risk). |
| MoveToRecycleBin | 3610 | Messages → `dateDeleted`. Chat → `findOperatedChat` + `Chat.softDelete`. |
| RecoverChat | 3630 | `Chat.unDelete` + restore the transcript. |
| PermanentDelete | 3640 | Chat → hard delete (or purge recycle-bin messages). Messages → delete them and their attachment files. |
| Delivered / Read | 3684 | Message missing or verification failed → ignore. If the sender is mine and the chat is iMessage: on `Read`, clear unread + notification; then return. Otherwise set `dateDelivered`/`dateRead = sentTimestamp`. `notifsSilenced` → `wasDeliveredQuietly` rule (5 min since notify-anyways). Save, then emit an update. |

### 1c. After `chatForMessage` (`RPS:3715`)

| Variant | Lines | Behaviour |
|---|---|---|
| RenameMessage | 3716 | `displayName` (unless `lockChatName`), `apnTitle`. Falls through to reflect. |
| MarkUnread | 3726 | `hasUnreadMessage = true`. |
| Typing | 3731 | UI-only indicator with a 1-minute timeout. The icon comes from the extension cache or `TypingApp.icon`. |
| Message (pre) | 3769 | Clears typing. SMS-router relay (forward to other devices + `forwardIfNessesary`, not saved). Embedded profile → `handleSharedProfile`. Messages with no text and no attachments are dropped. |
| MessageReadOnDevice, Unschedule | — | **Not handled.** They throw inside `reflectMessageDyn`, are retried, then acked. The native port should treat them as explicit no-ops. |

### 1d. `reflectMessageDyn` (`RPS:1618-1902`) → incoming queue

- **NotifyAnyways:** `wasDeliveredQuietly = false` (not persisted, which is a bug) and `handleNotification(notifyAnyways: true)`.
- **Message:**
  - The body comes from `indexedPartsToAttributedBodyDyn` (`RPS:1406`).
  - Attachments get guid `"<msgId>_<partIdx>"` and `metadata.rustpush = saveAttachment(att)` (plist XML). Iris parts and SMIL are skipped. **Nothing is downloaded.**
  - SMS staging: `stagingGuid` and `hasBeenForwarded`.
  - Fields: `threadOriginator*`, `expressiveSendStyleId`, `dateScheduled`, `subject`, `balloonBundleId`/`payloadData` (app balloon or link meta), `amkSessionId`, `verificationFailed`.
  - SMS: `inferReaction`.
- **RenameMessage:** synthetic `itemType 2 / groupActionType 2`.
- **ChangeParticipants:** `updateChatParticipants` (`RPS:1351`) emits `itemType 1/3` events and sets `groupVersion`.
- **IconChange:** if `groupVersion` is newer and the icon is not locked: `downloadMmcs` → `customAvatarPath`, or remove the icon. Emits `itemType 3 / groupActionType 1`.
- **React:** maps to `associatedMessageType`/`Emoji`/`Part`. Extension balloon updates re-link poll votes and reuse the original body.
- **Unsend:** `dateEdited` + `messageSummaryInfo.retractedParts`.
- **Edit:** merged body, `editedParts`, `editedContent[part]` history.

**Persistence** goes through `ah.handleNewMessage` (`action_handler.dart:497`):
- Dedup with `Message.findOne(guid)`, which also matches `stagingGuid`. A match becomes `replaceMessage` (merge rules at `message.dart:869-908`).
- New messages trigger the notification check, then `forwardIfNessesary`, then `chat.addMessage` (`chat.dart:1281`). `addMessage` updates the latest message, un-deletes, un-archives, sets `senderIsKnown` and handles unread.
- `Message.save` (`message.dart:753`) keeps existing delivered/read dates and sets `hasReactions` on the target.

**Attachments** are downloaded lazily when displayed (`attachments_service.dart:32-73`), gated by `autoDownload`/`onlyWifiDownload`. CAF is converted to m4a with **FFmpeg** (`RPS:467`).

---

## 2. Chat resolution

- **`chatForMessageInner` (`RPS:1927`):**
  1. Replay match by message id (or Edit/Unsend target).
  2. `afterGuid` match.
  3. Rename/ChangeParticipants fallbacks.
  4. SMS removes `usingNumber`.
  5. `Chat.findByRust`.
- **`chatForMessage` (`RPS:1980`):**
  - Adds `senderGuid` to `guidRefs` and fixes `usingHandle`.
  - Participant consensus via `updateChatParticipants`.
  - If `cvName` changed: rename + synthetic message.
  - Un-deletes the chat.
- **`Chat.findByRust` (`chat.dart:1540`):**
  - Lookup order: `senderGuid` → `guid`/`guidRefs`, then an exact handle-set match (`isRoutingStub`, `apnTitle == cvName`).
  - Otherwise `createChat(existingGuid: senderGuid)`.
- **Other helpers:** `rustHandleToBB` (`RPS:121`), `findOperatedChat` (`RPS:2070`), `Chat.getChatForTel` (`chat.dart:932`).
- **Known issue:** `reflectMessageDyn` calls `chatForMessage` a second time, so its side effects run twice. Do not reproduce this.

---

## 3. Outgoing

- **`Chat.getConversationData()` (`chat.dart:1075`):** `participants` = handles + `ensureHandle()`, `cvName = apnTitle`, `senderGuid = chat.guid`, `afterGuid = last sent stagingGuid ?? guid`.
- **`Chat.ensureHandle()` (`chat.dart:495`):** picks the sender handle (SMS rules, otherwise the default handle).
- **`getService` (`RPS:386`):** SMS vs iMessage `MessageType`.
- **`getSMSTargets` (`RPS:498`).**
- **`sendMsg` (`RPS:401`):** sets `sendingServiceId`, which is cleared later by SendConfirm.
- **`sendMessage` (`RPS:846`):**
  - Link-preview fetch.
  - Parts from `attributedBody` (`partsFromBody`, `RPS:824`).
  - `NormalMessage` fields: reply, effect, subject, app, linkMeta, scheduled, embeddedProfile (`RPS:2819`).
  - Id reuse for staging/scheduled messages.
  - Sets `stagingGuid = msg.id` **before** sending, so an early Delivered can match.
  - SMS keeps a temp guid until `SmsConfirmSent`.
- **`sendAttachment` (`RPS:511`):** upload → `metadata.rustpush` → `[object(ldText)?, attachment]`, `voice` for audio.
- **Other senders:**
  - Messages and attachments: tapback (`RPS:1078`), extension updates (`RPS:1126`), unsend/edit (`RPS:1183`/`1198`), `forwardMMSAttachment` (`RPS:573`).
  - Chat changes: rename (`RPS:1015`), participants (`RPS:1035`), icons (`RPS:735`/`658`).
  - Read state and typing: `markRead` (`RPS:973`), `markUnread` (`RPS:995`), typing (`RPS:1272`/`1286`).
  - Deletion: recycle bin, restore, delete (`RPS:782-810`).
  - SMS forwarding: `broadcastSmsForwardingState` (`RPS:627`), `confirmSmsSent` (`RPS:639`).
- **Errors:**
  - `handleSendError` (`network_error_handler.dart:5`) sets guid `error-<msg>` and the `error` int.
  - Protocol errors arrive as `Message.Error`.
  - The native `send` returns the confirmation result directly, so the port can finally record `SendConfirm` errors.

---

## 4. Kotlin → Dart method inventory

| Method | Sender | Native plan |
|---|---|---|
| `APNMsg` | `APNService` | Milestone 3 (`IncomingMessageHandler`) |
| `SMSMsg` | `APNService`/`SMSObserver` | **Descoped**: SMS forwarding stays in Flutter (`Chat.deliverSMS` `chat.dart:965`) |
| `execute-rust-send` | `MessageSender.kt` | Milestone 4 (`OutgoingMessageQueue`) |
| `ReplyChat`, `MarkChatRead` | `InternalIntentReceiver` | Milestone 4 / 6 |
| `new-message`, `updated-message`, `group-*`, `participant-*`, `scheduled-message-error`, `socket-event`, `unifiedpush-settings`, `NewServerUrl`, `incoming-facetime` (legacy), `ft-call-status-changed`, `imessage-aliases-removed`, `chat-read-status-changed` | FCM / UnifiedPush / Socket.IO | **Drop** (server mode removed) |
| `getChats`, `getMessages`, `send-message` | old native UI | Superseded by `ChatRepository` / `OutgoingMessageQueue` |
| `sim-info`, `extension-*`, `MediaColors`, `answer-facetime` | foreground only (`invokeMethod`) | Stay as foreground calls until those UIs move to Compose |

---

## 5. Notification rules (`MessageHelper.handleNotification`, `message_helper.dart:91`)

Skip the notification when any of these hold:
- From me, no handle, or the handle is blocked.
- The extension session is suppressed.
- Kept audio (`itemType 5` with a subject).
- The message already exists (when `findExisting` is set).
- `chat.shouldMuteNotification` (`chat.dart:1132`): unknown-sender filter, global text detection, mute / mute_individuals / temporary_mute (auto-unmutes) / text_detection, and reactions when `!notifyReactions`.
- The chat is active.
- The chat list is open and `!notifyOnChatList`.

Then `createNotification` (`notifications_service.dart:202`) applies `hideContent` and calls `create-incoming-message-notification` with the first image attachment.

`notifsSilenced` and `wasDeliveredQuietly` are **not** used to suppress notifications.

---

## 6. Gaps to fix (not copy) in the port

- Handle `MessageReadOnDevice`/`Unschedule` as no-ops.
- Record `SendConfirm` errors.
- No force unwraps: `SmsConfirmSent`, `Unsend`, `NotifyAnyways`, `SetTranscriptBackground`.
- Apply the `verificationFailed` checks consistently (SetTranscriptBackground, IconChange, React, NotifyAnyways, deletes).
- Persist the `wasDeliveredQuietly = false` reset on NotifyAnyways.
- Resolve the chat once per message.

## 7. Native API still missing for the port

`certify_delivery`, `convert_token_to_uuid`, `fetch_profile`, `parse_transcript_poster`, and validate-targets (participant changes). Add these to UniFFI as the Milestone 3 port reaches them.
