# Vendored objectbox 4.0.3 (pub.dev) with one OpenBubbles patch

The native Android layer opens the database with ObjectBox Java and Dart attaches to that
same native store via `Store.fromReference` (see `lib/database/database.dart` and
`android/.../objectbox/NativeStoreBridge.kt`). A single native store instance is required:
two instances on the same files keep separate ID counters and overwrite each other's rows.

Stores owned by ObjectBox Java are not registered for attach-by-id (`obx_store_id` returns 0,
`obx_store_attach_id`/`obx_store_clone` fail). Upstream Dart uses attach-by-id for lazy
`ToOne`/`ToMany` loading and isolates, which therefore failed with
"ObjectBoxException: failed to create store".

Patch (search for "OpenBubbles patch"):
- `StoreConfiguration` carries `externalStoreAddress` (0 = normal store).
- `Store.fromReference` records the referenced native pointer there.
- `Store._attachByConfiguration` reuses that pointer instead of attaching by id, and such
  instances never close the native store.

When upgrading objectbox, re-apply this patch (and keep objectbox-android's version equal).
