---
name: DuoShield ConversationMetaUpdater signature
description: update() takes Context as first param — callers must pass ctx
---

**Signature:** `ConversationMetaUpdater.update(Context ctx, String convId, String senderUid, String recipientUid, String preview)`

**Why:** The method encrypts the `lastMessage` preview before writing to Firestore (#bug15). It needs a Context to call `EncryptionHelper.encrypt(ctx, ...)`.

**How to apply:** Any caller (MessageBuilder, ChatMediaActivity, etc.) must pass a Context as the first argument. Missing this causes a compile error — do not add a no-context overload.
