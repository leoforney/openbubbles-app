use std::{collections::{BTreeMap, HashMap}, fmt::Debug, io::{Cursor, Seek, Write}, sync::{Arc, LazyLock, OnceLock, RwLock, Weak}, time::{Duration, SystemTime}};

use flexi_logger::{FileSpec, Logger, WriteMode};
use log::{error, info, warn};
use openssl::{ec::EcKey, pkey::PKey};
use rustpush::{EntitlementAuthState, GenerateVerificationTokenRequest, PushError, get_gateways_for_mccmnc, passwords::{Passkey, PasskeyCriteria, PasswordCriteria, PasswordManager, PasswordManagerMeta, PasswordManagerMetaChange, PasswordManagerMetaData, PasswordManagerMetaDataCtx, PasswordRawEntry}};
use tokio::{runtime::{Handle, Runtime}, sync::Mutex};

use futures::FutureExt;
use rustpush::{Attachment, MMCSFile, MessageInst};
use uuid::Uuid;
use crate::native_types::{NativeAttachment, NativeCertifiedContext, NativeConversationData, NativeMsgError, NativeMessage, NativeMessageInst, NativeMmcsFile, NativePushMessage};
use crate::{RUNTIME, api::api::{APSWatcher, DaemonData, PollResult, PushMessage, SharedPushState, approve_circle, decline_facetime, do_first_time_init, get_2fa_code, get_entitlements, recv_wait, set_status, teardown_2fa}, frb_generated::FLUTTER_RUST_BRIDGE_HANDLER, init_logger};

#[derive(uniffi::Record)] 
pub struct FileInfo {
    pub duration: Option<f64>,
    pub width: u32,
    pub height: u32,
    pub thumbnail: Option<Vec<u8>>,
}

#[derive(uniffi::Enum)]
pub enum PackagedFile {
    Info(FileInfo),
    Failure(String),
}

#[uniffi::export(with_foreign)]
pub trait KotlinFilePackager: Send + Sync + Debug {
    fn get_file(&self, path: String) -> PackagedFile;
    fn scan_files(&self, paths: Vec<String>);
}

pub static PACKAGER_LOCK: OnceLock<Arc<dyn KotlinFilePackager>> = OnceLock::new();

#[uniffi::export(with_foreign)]
pub trait MsgReceiver: Send + Sync + Debug {
    fn receieved_msg(&self, msg: u64, retry: u64);
    fn native_ready(&self, state: Option<Arc<NativePushState>>);
    fn twofa_event(&self, success: bool);
    fn finish(&self);
}

#[uniffi::export(with_foreign)]
pub trait CarrierHandler: Send + Sync + Debug {
    fn got_gateway(&self, gateway: Option<String>, error: Option<String>);
}

#[uniffi::export(with_foreign)]
pub trait InsertKeychainCallback: Send + Sync + Debug {
    fn done(&self, error: Option<String>);
}

#[uniffi::export(with_foreign)]
pub trait AvailableGroupsCallback: Send + Sync + Debug {
    fn groups(&self, groups: HashMap<String, String>);
}

#[derive(uniffi::Record)]
pub struct SavedPassword {
    cred_id: String,
    username: String,
    password: String,
    otp: Option<u32>,
}

#[derive(uniffi::Record)]
pub struct SavedPasskey {
    cred_id: String,
    id: Vec<u8>,
    tag: Vec<u8>,
    key: Vec<u8>,
}

#[uniffi::export(with_foreign)]
pub trait RetrieveKeysCallback: Send + Sync + Debug {
    fn keys(&self, passwords: Vec<SavedPassword>, passkeys: Vec<SavedPasskey>);
}

#[uniffi::export(with_foreign)]
pub trait SpecialAppleAuthCallback: Send + Sync + Debug {
    fn got_verification(&self, token: HashMap<String, String>, error: Option<String>);
}

pub static HANDLE_WIFI_NETWORKS: OnceLock<Arc<dyn HandleWifiNetworksCallback>> = OnceLock::new();

#[uniffi::export(with_foreign)]
pub trait HandleWifiNetworksCallback: Send + Sync + Debug {
    fn handle_wifi_networks(&self, networks: HashMap<String, String>, user_approve: bool);
}

#[derive(uniffi::Object)] 
pub struct NativePushState {
    state: Arc<SharedPushState>,
    watcher: Mutex<APSWatcher>,
}

#[uniffi::export]
pub fn start(dir: String, packager: Arc<dyn KotlinFilePackager>, wifi: Arc<dyn HandleWifiNetworksCallback>) {
    let _ = PACKAGER_LOCK.set(packager);
    let _ = HANDLE_WIFI_NETWORKS.set(wifi);
    do_first_time_init(dir);
}

#[uniffi::export]
pub fn init_native(dir: String, handle: Option<String>, handler: Arc<dyn MsgReceiver>) {
    info!("rpljslf start");
    RUNTIME.spawn(async move {
        info!("rpljslf initting");

        let result = if let Some(handle) = handle {
            let parsed: u64 = handle.parse().expect("bad handle??");
            info!("consuming pointer {handle} {parsed}");
            let daemondata: DaemonData = *unsafe { Box::from_raw(parsed as *mut DaemonData) };
            Some(Arc::new(NativePushState {
                state: Arc::new(daemondata.state),
                watcher: Mutex::new(daemondata.watcher),
            }))
        } else {
            SharedPushState::restore(dir).await.map(|a| Arc::new(NativePushState {
                state: Arc::new(a.0),
                watcher: Mutex::new(a.1),
            }))
        };

        info!("rpljslf raed");
        handler.native_ready(result);
        info!("rpljslf dom");
    });
}

#[uniffi::export]
pub fn get_carrier(handler: Arc<dyn CarrierHandler>, mccmnc: String) {
    RUNTIME.spawn(async move {
        match get_gateways_for_mccmnc(&mccmnc).await {
            Ok(gateway) => handler.got_gateway(Some(gateway.gateway), None),
            Err(err) => handler.got_gateway(None, Some(err.to_string())),
        }
    });
}



pub fn plist_to_buf<T: serde::Serialize>(value: &T) -> Result<Vec<u8>, plist::Error> {
    let mut buf: Vec<u8> = Vec::new();
    let writer = Cursor::new(&mut buf);
    plist::to_writer_xml(writer, &value)?;
    Ok(buf)
}

pub fn plist_to_string<T: serde::Serialize>(value: &T) -> Result<String, plist::Error> {
    plist_to_buf(value).map(|val| String::from_utf8(val).unwrap())
}


pub static QUEUED_MESSAGES: LazyLock<Mutex<(u64, HashMap<u64, PushMessage>)>> = LazyLock::new(|| Mutex::new((0, HashMap::new())));

#[uniffi::export]
impl NativePushState {

    pub fn start_loop(self: Arc<NativePushState>, handler: Arc<dyn MsgReceiver>) {
        RUNTIME.spawn(async move {
            let mut watcher = self.watcher.lock().await;
            loop {
                match std::panic::AssertUnwindSafe(recv_wait(&mut watcher, &self.state)).catch_unwind().await {
                    Ok(yes) => {
                        match yes {
                            PollResult::Cont(Some(msg)) => {
                                if let PushMessage::TwoFaAuthEvent(event) = &msg {
                                    handler.twofa_event(*event);
                                    continue;
                                }

                                let mut locked_messages = QUEUED_MESSAGES.lock().await;
                                let key = locked_messages.0;
                                locked_messages.1.insert(key, msg);
                                locked_messages.0 = locked_messages.0.wrapping_add(1);
                                drop(locked_messages);

                                let handler_ref = handler.clone();
                                tokio::spawn(async move {
                                    let mut retry = 0;
                                    // sheesh, downloads take time...
                                    tokio::time::sleep(Duration::from_secs(30)).await;
                                    while QUEUED_MESSAGES.lock().await.1.contains_key(&key) {
                                        retry += 1;
                                        if retry > 5 {
                                            warn!("Excessive retries, dropping pointer {key}");
                                            QUEUED_MESSAGES.lock().await.1.remove(&key);
                                            break;
                                        }
                                        info!("re-emitting pointer {key}, retry {retry}");
                                        // we still haven't been handled, attempt to handle again
                                        handler_ref.receieved_msg(key, retry);
                                        tokio::time::sleep(Duration::from_secs(30)).await;
                                    }
                                });

                                info!("emitting pointer {key}");
                                handler.receieved_msg(key, 0);
                            },
                            PollResult::Cont(None) => continue,
                            PollResult::Stop => break
                        }
                    },
                    Err(payload) => {
                        let panic = match payload.downcast_ref::<&'static str>() {
                            Some(msg) => Some(*msg),
                            None => match payload.downcast_ref::<String>() {
                                Some(msg) => Some(msg.as_str()),
                                // Copy what rustc does in the default panic handler
                                None => None,
                            },
                        };
                        error!("Failed {:?}", panic);
                    }
                }
            }
            info!("finishing loop");
            handler.finish();
        });
    }

    pub fn get_available_groups(&self, groups_callback: Arc<dyn AvailableGroupsCallback>) {
        let passwords = self.state.icloud_services.as_ref().and_then(|i| i.passwords.clone()).expect("no icloud");
        RUNTIME.spawn(async move {
            let state = passwords.state.read().await;
            let result = state.groups.iter().filter_map(|g| Some((g.1.share.as_ref()?.display_name.clone(), g.0.clone()))).collect::<HashMap<_, _>>();
            groups_callback.groups(result);
        });
    }

    pub fn keychain_password_insert(&self, site: String, user: String, password: String, callback: Arc<dyn InsertKeychainCallback>, group: Option<String>) {
        let passwords = self.state.icloud_services.as_ref().and_then(|i| i.passwords.clone()).expect("no icloud");
        RUNTIME.spawn(async move {
            let criteria = PasswordCriteria {
                site,
                account: user,
            };
            let a = passwords.modify_password_entry::<PasswordRawEntry>(&criteria, |pw| {
                pw.data = password.as_bytes().to_vec();
            }, group.clone()).await.err();
            let b = passwords.modify_password_entry::<PasswordManagerMeta>(&criteria, |pw| {
                let mut data = pw.get_password_data().expect("Failed to get data?");
                data.set_last_used(SystemTime::now());
                data.change_password(password);
            }, group.clone()).await.err();
            let e = a.or(b);
            callback.done(e.map(|e| format!("{e}")));
        });
    }

    pub fn keychain_passkey_insert(&self, site: String, record_id: String, id: Vec<u8>, tag: Vec<u8>, key: Vec<u8>, callback: Arc<dyn InsertKeychainCallback>, group: Option<String>) {
        let passwords = self.state.icloud_services.as_ref().and_then(|i| i.passwords.clone()).expect("no icloud");
        RUNTIME.spawn(async move {
            // techincally this may insert into wrong group, but does anyone really ever update a passkey with the same ID??
            // and if they do, damn right it may go into the wrong group. Not my issue at this time lol
            let result = passwords.insert_password_entry(&record_id, &Passkey {
                cdat: SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap().as_millis() as u64,
                mdat: SystemTime::now().duration_since(SystemTime::UNIX_EPOCH).unwrap().as_millis() as u64,
                agrp: "com.apple.webkit.webauthn".to_string(),
                labl: site,
                atag: tag,
                data: Passkey::encode_key(PKey::private_key_from_pkcs8(&key).expect("Invalid EC key??").ec_key().expect("not ec key??")),
                klbl: id,
            }, group.clone()).await.err();
            callback.done(result.map(|e| format!("{e}")));
        });
    }

    pub fn get_site_config(&self, site: String, callback: Arc<dyn RetrieveKeysCallback>) {
        let passwords = self.state.icloud_services.as_ref().and_then(|i| i.passwords.clone()).expect("no icloud");
        RUNTIME.spawn(async move {
            let passwords = passwords.get_password_for_site(site).await;
            callback.keys(passwords.passwords.into_iter().map(|(k, p)| SavedPassword {
                cred_id: k,
                username: p.acct.clone(),
                password: String::from_utf8(p.data.clone()).expect("password not utf8??"),
                otp: passwords.passwords_meta.values().find_map(|m| {
                    if p.acct != m.acct { return None }
                    let totp = m.get_password_data().ok()?.totp?;
                    Some(totp.generate_otp().ok()?.0)
                })
            }).collect(), passwords.passkeys.into_iter().map(|(k, p)| SavedPasskey {
                cred_id: k,
                id: p.klbl.clone(),
                tag: p.atag.clone(),
                key: PKey::from_ec_key(p.get_key()).unwrap().private_key_to_pkcs8().unwrap(),
            }).collect());
        });
    }

    pub fn do_special_apple_auth(&self, client_data_hash: String, callback: Arc<dyn SpecialAppleAuthCallback>) {
        let token = self.state.icloud_services.as_ref().map(|i| i.token_provider.clone()).expect("no token");
        RUNTIME.spawn(async move {
            let result = std::panic::AssertUnwindSafe(token.generate_verification_token(GenerateVerificationTokenRequest::Passkey { 
                client_data_hash,
            })).catch_unwind().await;
            match result {
                Ok(Ok(success)) => {
                    let (key, value) = success.split_once(":").expect("Bad token form??");
                    callback.got_verification(HashMap::from_iter([
                        (key.to_string(), value.to_string())
                    ]), None);
                },
                Ok(Err(e)) => {
                    callback.got_verification(HashMap::new(), Some(format!("{e}")));
                }
                Err(payload) => {
                    let panic = match payload.downcast_ref::<&'static str>() {
                        Some(msg) => Some(*msg),
                        None => match payload.downcast_ref::<String>() {
                            Some(msg) => Some(msg.as_str()),
                            // Copy what rustc does in the default panic handler
                            None => None,
                        },
                    };
                    error!("Failed {:?}", panic);
                    callback.got_verification(HashMap::new(), Some(format!("{panic:?}")));
                }
            }
        });
    }

    pub fn get_state(self: Arc<NativePushState>) -> u64 {
        let arc_val = Arc::downgrade(&self.state).into_raw() as u64;
        info!("emitting state {arc_val}");
        arc_val
    }

    pub fn decline_facetime(&self, guid: String) {
        let state_ref = self.state.ft_client.clone();
        RUNTIME.spawn(async move {
            if let Err(e) = decline_facetime(&state_ref, guid).await {
                warn!("Failed to decline facetime {e}");
            }
        });
    }

    pub fn teardown_2fa(&self, action: String, txnid: String) {
        let state_ref = self.state.icloud_services.as_ref().expect("no icloud!").account.clone();
        RUNTIME.spawn(async move {
            if let Err(e) = teardown_2fa(&state_ref, action, txnid).await {
                warn!("Failed to teardown 2fa {e}");
            }
        });
    }

    pub fn get_auth_code(&self, txnid: String) -> u32 {
        let icloud = self.state.icloud_services.as_ref().expect("no icloud!");
        let state_ref = icloud.account.clone();
        let data_ref = self.state.active_circle_sessions.clone();
        RUNTIME.block_on(async move {
            match approve_circle(&data_ref, &state_ref, txnid).await {
                Ok(e) => e,
                Err(e) => {
                    warn!("Failed to get auth code {e}");
                    0
                }
            }
        })
    }
    
    pub fn publish_status(&self, guid: Option<String>) {
        let state_ref = self.state.icloud_services.as_ref().expect("no icloud!").statuskit_client.clone();
        RUNTIME.spawn(async move {
            if let Err(e) = set_status(&state_ref, guid).await {
                warn!("Failed to decline publish status {e}");
            }
        });
    }
}
#[uniffi::export(with_foreign)]
pub trait NativeProgressCallback: Send + Sync + Debug {
    fn on_progress(&self, progress: u64, total: u64);
}

/// Builds a new outgoing message with a fresh client-generated id. The id is final:
/// store it as the message GUID right away, there is no server-assigned GUID.
#[uniffi::export]
pub fn new_message_inst(conversation: NativeConversationData, sender: String, message: NativeMessage) -> Result<NativeMessageInst, NativeMsgError> {
    Ok(MessageInst::new(conversation.into(), &sender, message.try_into()?).into())
}

/// Creates `<path>.part`, lets `write` fill it, then renames it to `path`.
/// A file at `path` is therefore always complete.
async fn write_atomically<F, Fut>(path: &str, write: F) -> Result<(), NativeMsgError>
    where F: FnOnce(std::fs::File) -> Fut, Fut: std::future::Future<Output = Result<std::fs::File, NativeMsgError>> {
    if let Some(parent) = std::path::Path::new(path).parent() {
        std::fs::create_dir_all(parent)?;
    }
    let tmp = format!("{path}.part");
    let file = std::fs::File::create(&tmp)?;
    let result = async {
        let mut file = write(file).await?;
        file.flush()?;
        Ok::<_, NativeMsgError>(())
    }.await;
    match result {
        Ok(()) => Ok(std::fs::rename(&tmp, path)?),
        Err(e) => {
            let _ = std::fs::remove_file(&tmp);
            Err(e)
        }
    }
}

/// Messaging API used by the native (Kotlin) backend.
///
/// All async methods run on the shared rustpush `RUNTIME`; the returned futures can be
/// awaited from any Kotlin coroutine dispatcher.
#[uniffi::export]
impl NativePushState {
    /// Returns a copy of a queued push message without removing it from the queue.
    /// Returns `None` if the key was already completed or dropped.
    pub async fn take_push_message(&self, key: u64) -> Option<NativePushMessage> {
        RUNTIME.spawn(async move {
            QUEUED_MESSAGES.lock().await.1.get(&key).map(NativePushMessage::from)
        }).await.ok().flatten()
    }

    /// Removes a push message from the queue. Only call this once the message has been
    /// durably handled; until then rustpush re-emits it (every 30s, up to 5 times).
    pub async fn complete_msg(&self, key: u64) {
        let _ = RUNTIME.spawn(async move {
            info!("finishing pointer {key}");
            QUEUED_MESSAGES.lock().await.1.remove(&key);
        }).await;
    }

    pub async fn get_handles(&self) -> Vec<String> {
        let client = self.state.client.clone();
        RUNTIME.spawn(async move {
            client.identity.get_handles().await.to_vec()
        }).await.unwrap_or_default()
    }

    pub async fn get_my_phone_handles(&self) -> Vec<String> {
        let client = self.state.client.clone();
        RUNTIME.spawn(async move {
            client.identity.get_my_phone_handles().await.to_vec()
        }).await.unwrap_or_default()
    }

    /// Sends any message (normal message, reaction, read receipt, typing, edit, unsend, ...)
    /// and waits for delivery confirmation.
    ///
    /// Returns the message as sent: rustpush fills in `sent_timestamp` and, for new
    /// conversations, `conversation.sender_guid`, both of which should be persisted.
    ///
    /// Unlike the FRB `send`, this does not broadcast `PushMessage::SendConfirm`; the
    /// result is returned to the caller directly.
    pub async fn send(&self, msg: NativeMessageInst) -> Result<NativeMessageInst, NativeMsgError> {
        let mut inst: MessageInst = msg.try_into()?;
        let client = self.state.client.clone();
        RUNTIME.spawn(async move {
            let job = client.send(&mut inst).await?;
            if let Some(handle) = job.handle {
                match handle.await {
                    Ok(result) => result?,
                    Err(e) => return Err(NativeMsgError::Permanent { msg: format!("send task failed: {e}") }),
                }
            }
            info!("native send finished {}", inst.id);
            Ok(inst.into())
        }).await.map_err(|e| NativeMsgError::Permanent { msg: format!("send task failed: {e}") })?
    }

    /// Confirms delivery of a received message that carried a certified context (newer clients),
    /// optionally notifying the sender ("Delivered").
    pub async fn certify_delivery(&self, context: NativeCertifiedContext, notify: bool) -> Result<(), NativeMsgError> {
        let client = self.state.client.clone();
        let context: rustpush::CertifiedContext = context.into();
        RUNTIME.spawn(async move {
            client.identity.certify_delivery("com.apple.madrid", &context, notify).await?;
            Ok(())
        }).await.map_err(|e| NativeMsgError::Permanent { msg: format!("certify task failed: {e}") })?
    }

    /// Downloads an attachment to `path`. The file only appears at `path` once the
    /// download completed successfully.
    pub async fn download_attachment(&self, attachment: NativeAttachment, path: String, progress: Arc<dyn NativeProgressCallback>) -> Result<(), NativeMsgError> {
        let state = self.state.clone();
        let attachment: Attachment = attachment.into();
        RUNTIME.spawn(async move {
            write_atomically(&path, |mut file| async move {
                attachment.get_attachment(&state.conn, &mut file, |prog, total| {
                    progress.on_progress(prog as u64, total as u64);
                }).await?;
                Ok(file)
            }).await
        }).await.map_err(|e| NativeMsgError::Permanent { msg: format!("download task failed: {e}") })?
    }

    /// Downloads a bare MMCS file (group icons, transcript backgrounds) to `path`.
    pub async fn download_mmcs(&self, file: NativeMmcsFile, path: String, progress: Arc<dyn NativeProgressCallback>) -> Result<(), NativeMsgError> {
        let state = self.state.clone();
        let mmcs: MMCSFile = file.into();
        RUNTIME.spawn(async move {
            write_atomically(&path, |mut file| async move {
                mmcs.get_attachment(&state.conn, &mut file, |prog, total| {
                    progress.on_progress(prog as u64, total as u64);
                }).await?;
                Ok(file)
            }).await
        }).await.map_err(|e| NativeMsgError::Permanent { msg: format!("download task failed: {e}") })?
    }

    /// Uploads a file to MMCS and returns an attachment ready to be put in a message part.
    pub async fn upload_attachment(&self, path: String, mime: String, uti: String, name: String, progress: Arc<dyn NativeProgressCallback>) -> Result<NativeAttachment, NativeMsgError> {
        let state = self.state.clone();
        RUNTIME.spawn(async move {
            let mut file = std::fs::File::open(&path)?;
            let prepared = MMCSFile::prepare_put(&mut file).await?;
            file.rewind()?;
            let attachment = Attachment::new_mmcs(&state.conn, &prepared, file, &mime, &uti, &name, |prog, total| {
                progress.on_progress(prog as u64, total as u64);
            }).await?;
            Ok(attachment.into())
        }).await.map_err(|e| NativeMsgError::Permanent { msg: format!("upload task failed: {e}") })?
    }
}

/// Serializes an attachment the same way FRB's `save_attachment` does. Stored in Attachment
/// `metadata["rustpush"]` so Flutter can render and re-download natively sent attachments.
#[uniffi::export]
pub fn attachment_to_plist(attachment: NativeAttachment) -> Result<String, NativeMsgError> {
    let att: Attachment = attachment.into();
    plist_to_string(&att).map_err(|e| NativeMsgError::Invalid { msg: format!("attachment plist: {e}") })
}

/// Inverse of [attachment_to_plist]; same format as FRB's `restore_attachment`.
#[uniffi::export]
pub fn attachment_from_plist(data: String) -> Result<NativeAttachment, NativeMsgError> {
    let att: Attachment = plist::from_reader_xml(Cursor::new(data))
        .map_err(|e| NativeMsgError::Invalid { msg: format!("attachment plist: {e}") })?;
    Ok(att.into())
}
