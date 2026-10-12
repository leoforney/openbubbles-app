//! UniFFI mirrors of the rustpush iMessage data model.
//!
//! These are lossless, by-value copies of the rustpush types so that Kotlin can
//! read incoming messages and build outgoing ones without going through FRB.
//! Deeply nested types that Kotlin rarely needs to inspect (extension apps,
//! rich link metadata, profile records, transcript backgrounds) are exposed as
//! opaque objects that are passed back to Rust unchanged.

use std::sync::Arc;

use rustpush::{
    Attachment, AttachmentType, CertifiedContext, ChangeParticipantMessage, ConversationData,
    DeleteTarget, EditMessage, ErrorMessage, ExtensionApp, IconChangeMessage, IndexedMessagePart,
    LinkMeta, MMCSFile, Message, MessageInst, MessagePart, MessageParts, MessageTarget,
    MessageType, MoveToRecycleBinMessage, NormalMessage, OperatedChat, PartExtension,
    PermanentDeleteMessage, PushError, ReactMessage, ReactMessageType, Reaction, RenameMessage,
    ScheduleMode, SetTranscriptBackgroundMessage, ShareProfileMessage, TextEffect, TextFlags,
    TextFormat, TypingApp, UnsendMessage, UpdateExtensionMessage, UpdateProfileMessage,
    UpdateProfileSharingMessage,
};

use crate::api::api::PushMessage;

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum NativeMsgError {
    /// Transient failure (network, timeout, connection). Safe to retry.
    #[error("{msg}")]
    Retryable { msg: String },
    /// Permanent failure. Retrying will not help.
    #[error("{msg}")]
    Permanent { msg: String },
    /// The message passed from Kotlin could not be converted.
    #[error("Invalid message: {msg}")]
    Invalid { msg: String },
}

impl From<PushError> for NativeMsgError {
    fn from(value: PushError) -> Self {
        let msg = value.to_string();
        let retryable = match &value {
            PushError::RequestError(_)
            | PushError::IoError(_)
            | PushError::TLSError(_)
            | PushError::SendTimedOut
            | PushError::APSSendError(_)
            | PushError::ConnectionClosed(_)
            | PushError::NotConnected
            | PushError::ResourceTimeout
            | PushError::ResourceGenTimeout
            | PushError::ResourceFailure(_)
            | PushError::MMCSUploadFailed(_)
            | PushError::MMCSGetFailed(_)
            | PushError::APSAckError(_) => true,
            PushError::StatusError(code) => code.is_server_error(),
            _ => false,
        };
        if retryable {
            NativeMsgError::Retryable { msg }
        } else {
            NativeMsgError::Permanent { msg }
        }
    }
}

impl From<std::io::Error> for NativeMsgError {
    fn from(value: std::io::Error) -> Self {
        PushError::IoError(value).into()
    }
}

fn invalid(msg: impl Into<String>) -> NativeMsgError {
    NativeMsgError::Invalid { msg: msg.into() }
}

// ---------------------------------------------------------------------------
// Opaque objects
// ---------------------------------------------------------------------------

#[derive(uniffi::Object)]
pub struct NativeExtensionApp(pub(crate) ExtensionApp);

#[uniffi::export]
impl NativeExtensionApp {
    pub fn name(&self) -> String { self.0.name.clone() }
    pub fn app_id(&self) -> Option<u64> { self.0.app_id }
    pub fn bundle_id(&self) -> String { self.0.bundle_id.clone() }
    pub fn has_balloon(&self) -> bool { self.0.balloon.is_some() }
    pub fn balloon_url(&self) -> Option<String> { self.0.balloon.as_ref().map(|b| b.url.clone()) }
    pub fn balloon_session(&self) -> Option<String> { self.0.balloon.as_ref().and_then(|b| b.session.clone()) }
    pub fn balloon_ld_text(&self) -> Option<String> { self.0.balloon.as_ref().and_then(|b| b.ld_text.clone()) }
    pub fn balloon_is_live(&self) -> bool { self.0.balloon.as_ref().is_some_and(|b| b.is_live) }
    pub fn balloon_icon(&self) -> Option<Vec<u8>> { self.0.balloon.as_ref().and_then(|b| b.icon.clone()) }
}

#[derive(uniffi::Object)]
pub struct NativeLinkMeta(pub(crate) LinkMeta);

#[uniffi::export]
impl NativeLinkMeta {
    pub fn title(&self) -> Option<String> { self.0.data.title.clone() }
    pub fn summary(&self) -> Option<String> { self.0.data.summary.clone() }
    pub fn url(&self) -> Option<String> { self.0.data.url.as_ref().map(|u| u.relative.clone()) }
    pub fn original_url(&self) -> Option<String> { self.0.data.original_url.as_ref().map(|u| u.relative.clone()) }
    pub fn attachments(&self) -> Vec<Vec<u8>> { self.0.attachments.clone() }
    /// "{w, h}" style size string as stored by Apple, if there is a preview image.
    pub fn image_size(&self) -> Option<String> { self.0.data.image_metadata.as_ref().map(|m| m.size.clone()) }
    pub fn image_url(&self) -> Option<String> { self.0.data.image_metadata.as_ref().map(|m| m.url.relative.clone()) }
    pub fn icon_url(&self) -> Option<String> { self.0.data.icon_metadata.as_ref().map(|m| m.url.relative.clone()) }
    /// Shared-passwords invites are rendered as an app balloon, not a URL preview.
    pub fn is_passwords_invite(&self) -> bool { self.0.data.specialization2.is_some() }
}

#[derive(uniffi::Object)]
pub struct NativeShareProfile(pub(crate) ShareProfileMessage);

#[uniffi::export]
impl NativeShareProfile {
    pub fn cloud_kit_record_key(&self) -> String { self.0.cloud_kit_record_key.clone() }
    pub fn cloud_kit_decryption_record_key(&self) -> Vec<u8> { self.0.cloud_kit_decryption_record_key.clone() }
    pub fn has_poster(&self) -> bool { self.0.poster.is_some() }
}

#[derive(uniffi::Object)]
pub struct NativeTranscriptBackground(pub(crate) SetTranscriptBackgroundMessage);

#[uniffi::export]
impl NativeTranscriptBackground {
    pub fn chat_id(&self) -> Option<String> {
        match &self.0 {
            SetTranscriptBackgroundMessage::Remove { chat_id, .. } => chat_id.clone(),
            SetTranscriptBackgroundMessage::Set { chat_id, .. } => chat_id.clone(),
        }
    }
    pub fn version(&self) -> u64 {
        match &self.0 {
            SetTranscriptBackgroundMessage::Remove { bid, .. } => *bid,
            SetTranscriptBackgroundMessage::Set { bid, .. } => *bid,
        }
    }
    pub fn is_remove(&self) -> bool {
        matches!(self.0, SetTranscriptBackgroundMessage::Remove { .. })
    }
    pub fn file(&self) -> Option<NativeMmcsFile> {
        self.0.to_mmcs().map(Into::into)
    }
}

// ---------------------------------------------------------------------------
// Records & enums
// ---------------------------------------------------------------------------

#[derive(uniffi::Record, Clone)]
pub struct NativeConversationData {
    pub participants: Vec<String>,
    pub cv_name: Option<String>,
    pub sender_guid: Option<String>,
    pub after_guid: Option<String>,
}

#[derive(uniffi::Enum, Clone)]
pub enum NativeTextFormat {
    Flags { bold: bool, italic: bool, underline: bool, strikethrough: bool },
    /// Raw `TextEffect` discriminant (Big = 5, Small = 11, Shake = 9, Nod = 8,
    /// Explode = 12, Ripple = 4, Bloom = 6, Jitter = 10).
    Effect { effect: u32 },
}

#[derive(uniffi::Record, Clone)]
pub struct NativeMmcsFile {
    pub signature: Vec<u8>,
    pub object_id: String,
    pub url: String,
    pub key: Vec<u8>,
    pub size: u64,
}

#[derive(uniffi::Enum, Clone)]
pub enum NativeAttachmentType {
    Inline { data: Vec<u8> },
    Mmcs { file: NativeMmcsFile },
}

#[derive(uniffi::Record, Clone)]
pub struct NativeAttachment {
    pub a_type: NativeAttachmentType,
    pub part: u64,
    pub uti_type: String,
    pub mime: String,
    pub name: String,
    pub iris: bool,
}

#[derive(uniffi::Enum, Clone)]
pub enum NativePartExtension {
    Sticker {
        msg_width: f64,
        rotation: f64,
        sai: u64,
        scale: f64,
        update: Option<bool>,
        sli: u64,
        normalized_x: f64,
        normalized_y: f64,
        version: u64,
        hash: String,
        safi: u64,
        effect_type: i64,
        sticker_id: String,
    },
}

#[derive(uniffi::Enum, Clone)]
pub enum NativeMessagePart {
    Text { text: String, format: NativeTextFormat },
    Attachment { attachment: NativeAttachment },
    Mention { uri: String, text: String },
    Object { value: String },
}

#[derive(uniffi::Record, Clone)]
pub struct NativeIndexedMessagePart {
    pub part: NativeMessagePart,
    pub idx: Option<u64>,
    pub ext: Option<NativePartExtension>,
}

#[derive(uniffi::Enum, Clone)]
pub enum NativeMessageType {
    IMessage,
    Sms { is_phone: bool, using_number: String, from_handle: Option<String> },
}

#[derive(uniffi::Record, Clone)]
pub struct NativeScheduleMode {
    pub ms: u64,
    pub schedule: bool,
}

#[derive(uniffi::Record, Clone)]
pub struct NativeNormalMessage {
    pub parts: Vec<NativeIndexedMessagePart>,
    pub effect: Option<String>,
    pub reply_guid: Option<String>,
    pub reply_part: Option<String>,
    pub service: NativeMessageType,
    pub subject: Option<String>,
    pub app: Option<Arc<NativeExtensionApp>>,
    pub link_meta: Option<Arc<NativeLinkMeta>>,
    pub voice: bool,
    pub scheduled: Option<NativeScheduleMode>,
    pub embedded_profile: Option<Arc<NativeShareProfile>>,
}

#[derive(uniffi::Enum, Clone)]
pub enum NativeReaction {
    Heart,
    Like,
    Dislike,
    Laugh,
    Emphasize,
    Question,
    Emoji { emoji: String },
    /// Receive only; rustpush does not support sending sticker reactions.
    Sticker { spec: Option<Arc<NativeExtensionApp>>, body: Vec<NativeIndexedMessagePart> },
}

#[derive(uniffi::Enum, Clone)]
pub enum NativeReactMessageType {
    React { reaction: NativeReaction, enable: bool },
    Extension { spec: Arc<NativeExtensionApp>, body: Vec<NativeIndexedMessagePart>, is_meta: bool },
}

#[derive(uniffi::Record, Clone)]
pub struct NativeReactMessage {
    pub to_uuid: String,
    pub to_part: Option<u64>,
    pub reaction: NativeReactMessageType,
    pub to_text: String,
    pub embedded_profile: Option<Arc<NativeShareProfile>>,
}

#[derive(uniffi::Record, Clone)]
pub struct NativeTypingApp {
    pub bundle_id: String,
    pub icon: Vec<u8>,
}

#[derive(uniffi::Record, Clone)]
pub struct NativeOperatedChat {
    pub participants: Vec<String>,
    pub group_id: String,
    pub guid: String,
    pub delete_incoming_messages: Option<bool>,
    pub was_reported_as_junk: Option<bool>,
}

#[derive(uniffi::Enum, Clone)]
pub enum NativeDeleteTarget {
    Chat { chat: NativeOperatedChat },
    Messages { guids: Vec<String> },
}

#[derive(uniffi::Enum, Clone)]
pub enum NativeMessage {
    Message { msg: NativeNormalMessage },
    RenameMessage { new_name: String },
    ChangeParticipants { new_participants: Vec<String>, group_version: u64 },
    React { msg: NativeReactMessage },
    Delivered,
    Read,
    Typing { typing: bool, app: Option<NativeTypingApp> },
    Unsend { tuuid: String, edit_part: u64 },
    Edit { tuuid: String, edit_part: u64, new_parts: Vec<NativeIndexedMessagePart> },
    IconChange { file: Option<NativeMmcsFile>, group_version: u64 },
    EnableSmsActivation { enabled: bool },
    MessageReadOnDevice,
    SmsConfirmSent { status: bool },
    MarkUnread,
    PeerCacheInvalidate,
    UpdateExtension { for_uuid: String, ext: NativePartExtension },
    Error { for_uuid: String, status: u64, status_str: String },
    MoveToRecycleBin { target: NativeDeleteTarget, recoverable_delete_date: u64 },
    RecoverChat { chat: NativeOperatedChat },
    PermanentDelete { target: NativeDeleteTarget, is_scheduled: bool },
    Unschedule,
    UpdateProfile { profile: Option<Arc<NativeShareProfile>>, share_contacts: bool },
    UpdateProfileSharing { shared_dismissed: Vec<String>, shared_all: Vec<String>, version: u64 },
    ShareProfile { profile: Arc<NativeShareProfile> },
    NotifyAnyways,
    SetTranscriptBackground { background: Arc<NativeTranscriptBackground> },
}

#[derive(uniffi::Enum, Clone)]
pub enum NativeMessageTarget {
    Token { token: Vec<u8> },
    Uuid { uuid: String },
}

#[derive(uniffi::Record, Clone)]
pub struct NativeCertifiedContext {
    pub version: u32,
    pub receipt: Vec<u8>,
    pub sender: String,
    pub target: String,
    pub uuid: Vec<u8>,
    pub token: Vec<u8>,
}

#[derive(uniffi::Record, Clone)]
pub struct NativeMessageInst {
    pub id: String,
    pub sender: Option<String>,
    pub conversation: Option<NativeConversationData>,
    pub message: NativeMessage,
    pub sent_timestamp: u64,
    pub target: Option<Vec<NativeMessageTarget>>,
    pub send_delivered: bool,
    pub verification_failed: bool,
    pub certified_context: Option<NativeCertifiedContext>,
}

/// Top-level push payload delivered through `MsgReceiver.receieved_msg`.
///
/// Variants that only the Flutter UI handles today (photostreams, FaceTime,
/// StatusKit, IDMS, Find My beacons, registration state) are reported as
/// `Unhandled` until their native ports land.
#[derive(uniffi::Enum, Clone)]
pub enum NativePushMessage {
    IMessage { msg: NativeMessageInst },
    SendConfirm { uuid: String, error: Option<String> },
    Unhandled { kind: String },
}

// ---------------------------------------------------------------------------
// rustpush -> native
// ---------------------------------------------------------------------------

impl From<ConversationData> for NativeConversationData {
    fn from(v: ConversationData) -> Self {
        Self { participants: v.participants, cv_name: v.cv_name, sender_guid: v.sender_guid, after_guid: v.after_guid }
    }
}

impl From<TextFormat> for NativeTextFormat {
    fn from(v: TextFormat) -> Self {
        match v {
            TextFormat::Flags(f) => Self::Flags { bold: f.bold, italic: f.italic, underline: f.underline, strikethrough: f.strikethrough },
            TextFormat::Effect(e) => Self::Effect { effect: e as u32 },
        }
    }
}

impl From<MMCSFile> for NativeMmcsFile {
    fn from(v: MMCSFile) -> Self {
        Self { signature: v.signature, object_id: v.object, url: v.url, key: v.key, size: v.size as u64 }
    }
}

impl From<Attachment> for NativeAttachment {
    fn from(v: Attachment) -> Self {
        Self {
            a_type: match v.a_type {
                AttachmentType::Inline(data) => NativeAttachmentType::Inline { data },
                AttachmentType::MMCS(file) => NativeAttachmentType::Mmcs { file: file.into() },
            },
            part: v.part,
            uti_type: v.uti_type,
            mime: v.mime,
            name: v.name,
            iris: v.iris,
        }
    }
}

impl From<PartExtension> for NativePartExtension {
    fn from(v: PartExtension) -> Self {
        let PartExtension::Sticker { msg_width, rotation, sai, scale, update, sli, normalized_x, normalized_y, version, hash, safi, effect_type, sticker_id } = v;
        Self::Sticker { msg_width, rotation, sai, scale, update, sli, normalized_x, normalized_y, version, hash, safi, effect_type, sticker_id }
    }
}

impl From<MessagePart> for NativeMessagePart {
    fn from(v: MessagePart) -> Self {
        match v {
            MessagePart::Text(text, format) => Self::Text { text, format: format.into() },
            MessagePart::Attachment(a) => Self::Attachment { attachment: a.into() },
            MessagePart::Mention(uri, text) => Self::Mention { uri, text },
            MessagePart::Object(value) => Self::Object { value },
        }
    }
}

fn parts_to_native(v: MessageParts) -> Vec<NativeIndexedMessagePart> {
    v.0.into_iter().map(|p| NativeIndexedMessagePart {
        part: p.part.into(),
        idx: p.idx.map(|i| i as u64),
        ext: p.ext.map(Into::into),
    }).collect()
}

impl From<MessageType> for NativeMessageType {
    fn from(v: MessageType) -> Self {
        match v {
            MessageType::IMessage => Self::IMessage,
            MessageType::SMS { is_phone, using_number, from_handle } => Self::Sms { is_phone, using_number, from_handle },
        }
    }
}

impl From<NormalMessage> for NativeNormalMessage {
    fn from(v: NormalMessage) -> Self {
        Self {
            parts: parts_to_native(v.parts),
            effect: v.effect,
            reply_guid: v.reply_guid,
            reply_part: v.reply_part,
            service: v.service.into(),
            subject: v.subject,
            app: v.app.map(|a| Arc::new(NativeExtensionApp(a))),
            link_meta: v.link_meta.map(|l| Arc::new(NativeLinkMeta(l))),
            voice: v.voice,
            scheduled: v.scheduled.map(|s| NativeScheduleMode { ms: s.ms, schedule: s.schedule }),
            embedded_profile: v.embedded_profile.map(|p| Arc::new(NativeShareProfile(p))),
        }
    }
}

impl From<Reaction> for NativeReaction {
    fn from(v: Reaction) -> Self {
        match v {
            Reaction::Heart => Self::Heart,
            Reaction::Like => Self::Like,
            Reaction::Dislike => Self::Dislike,
            Reaction::Laugh => Self::Laugh,
            Reaction::Emphasize => Self::Emphasize,
            Reaction::Question => Self::Question,
            Reaction::Emoji(emoji) => Self::Emoji { emoji },
            Reaction::Sticker { spec, body } => Self::Sticker {
                spec: spec.map(|s| Arc::new(NativeExtensionApp(s))),
                body: parts_to_native(body),
            },
        }
    }
}

impl From<ReactMessage> for NativeReactMessage {
    fn from(v: ReactMessage) -> Self {
        Self {
            to_uuid: v.to_uuid,
            to_part: v.to_part,
            reaction: match v.reaction {
                ReactMessageType::React { reaction, enable } => NativeReactMessageType::React { reaction: reaction.into(), enable },
                ReactMessageType::Extension { spec, body, is_meta } => NativeReactMessageType::Extension {
                    spec: Arc::new(NativeExtensionApp(spec)),
                    body: parts_to_native(body),
                    is_meta,
                },
            },
            to_text: v.to_text,
            embedded_profile: v.embedded_profile.map(|p| Arc::new(NativeShareProfile(p))),
        }
    }
}

impl From<OperatedChat> for NativeOperatedChat {
    fn from(v: OperatedChat) -> Self {
        Self {
            participants: v.participants,
            group_id: v.group_id,
            guid: v.guid,
            delete_incoming_messages: v.delete_incoming_messages,
            was_reported_as_junk: v.was_reported_as_junk,
        }
    }
}

impl From<DeleteTarget> for NativeDeleteTarget {
    fn from(v: DeleteTarget) -> Self {
        match v {
            DeleteTarget::Chat(chat) => Self::Chat { chat: chat.into() },
            DeleteTarget::Messages(guids) => Self::Messages { guids },
        }
    }
}

impl From<Message> for NativeMessage {
    fn from(v: Message) -> Self {
        match v {
            Message::Message(msg) => Self::Message { msg: msg.into() },
            Message::RenameMessage(RenameMessage { new_name }) => Self::RenameMessage { new_name },
            Message::ChangeParticipants(ChangeParticipantMessage { new_participants, group_version }) => Self::ChangeParticipants { new_participants, group_version },
            Message::React(msg) => Self::React { msg: msg.into() },
            Message::Delivered => Self::Delivered,
            Message::Read => Self::Read,
            Message::Typing(typing, app) => Self::Typing { typing, app: app.map(|a| NativeTypingApp { bundle_id: a.bundle_id, icon: a.icon }) },
            Message::Unsend(UnsendMessage { tuuid, edit_part }) => Self::Unsend { tuuid, edit_part },
            Message::Edit(EditMessage { tuuid, edit_part, new_parts }) => Self::Edit { tuuid, edit_part, new_parts: parts_to_native(new_parts) },
            Message::IconChange(IconChangeMessage { file, group_version }) => Self::IconChange { file: file.map(Into::into), group_version },
            Message::EnableSmsActivation(enabled) => Self::EnableSmsActivation { enabled },
            Message::MessageReadOnDevice => Self::MessageReadOnDevice,
            Message::SmsConfirmSent(status) => Self::SmsConfirmSent { status },
            Message::MarkUnread => Self::MarkUnread,
            Message::PeerCacheInvalidate => Self::PeerCacheInvalidate,
            Message::UpdateExtension(UpdateExtensionMessage { for_uuid, ext }) => Self::UpdateExtension { for_uuid, ext: ext.into() },
            Message::Error(ErrorMessage { for_uuid, status, status_str }) => Self::Error { for_uuid, status, status_str },
            Message::MoveToRecycleBin(MoveToRecycleBinMessage { target, recoverable_delete_date }) => Self::MoveToRecycleBin { target: target.into(), recoverable_delete_date },
            Message::RecoverChat(chat) => Self::RecoverChat { chat: chat.into() },
            Message::PermanentDelete(PermanentDeleteMessage { target, is_scheduled }) => Self::PermanentDelete { target: target.into(), is_scheduled },
            Message::Unschedule => Self::Unschedule,
            Message::UpdateProfile(UpdateProfileMessage { profile, share_contacts }) => Self::UpdateProfile { profile: profile.map(|p| Arc::new(NativeShareProfile(p))), share_contacts },
            Message::UpdateProfileSharing(UpdateProfileSharingMessage { shared_dismissed, shared_all, version }) => Self::UpdateProfileSharing { shared_dismissed, shared_all, version },
            Message::ShareProfile(p) => Self::ShareProfile { profile: Arc::new(NativeShareProfile(p)) },
            Message::NotifyAnyways => Self::NotifyAnyways,
            Message::SetTranscriptBackground(b) => Self::SetTranscriptBackground { background: Arc::new(NativeTranscriptBackground(b)) },
        }
    }
}

impl From<MessageTarget> for NativeMessageTarget {
    fn from(v: MessageTarget) -> Self {
        match v {
            MessageTarget::Token(token) => Self::Token { token },
            MessageTarget::Uuid(uuid) => Self::Uuid { uuid },
        }
    }
}

impl From<CertifiedContext> for NativeCertifiedContext {
    fn from(v: CertifiedContext) -> Self {
        Self { version: v.version, receipt: v.receipt, sender: v.sender, target: v.target, uuid: v.uuid, token: v.token }
    }
}

impl From<MessageInst> for NativeMessageInst {
    fn from(v: MessageInst) -> Self {
        Self {
            id: v.id,
            sender: v.sender,
            conversation: v.conversation.map(Into::into),
            message: v.message.into(),
            sent_timestamp: v.sent_timestamp,
            target: v.target.map(|t| t.into_iter().map(Into::into).collect()),
            send_delivered: v.send_delivered,
            verification_failed: v.verification_failed,
            certified_context: v.certified_context.map(Into::into),
        }
    }
}

impl From<&PushMessage> for NativePushMessage {
    fn from(v: &PushMessage) -> Self {
        match v {
            PushMessage::IMessage(msg) => Self::IMessage { msg: msg.clone().into() },
            PushMessage::SendConfirm { uuid, error } => Self::SendConfirm { uuid: uuid.clone(), error: error.clone() },
            PushMessage::RegistrationState(_) => Self::Unhandled { kind: "RegistrationState".into() },
            PushMessage::NewPhotostream(_) => Self::Unhandled { kind: "NewPhotostream".into() },
            PushMessage::FaceTime(_) => Self::Unhandled { kind: "FaceTime".into() },
            PushMessage::StatusUpdate(_) => Self::Unhandled { kind: "StatusUpdate".into() },
            PushMessage::Idms(_) => Self::Unhandled { kind: "Idms".into() },
            PushMessage::TwoFaAuthEvent(_) => Self::Unhandled { kind: "TwoFaAuthEvent".into() },
            PushMessage::CircleFinishEvent => Self::Unhandled { kind: "CircleFinishEvent".into() },
            PushMessage::BeaconShared { .. } => Self::Unhandled { kind: "BeaconShared".into() },
        }
    }
}

// ---------------------------------------------------------------------------
// native -> rustpush
// ---------------------------------------------------------------------------

fn ext_app(v: Arc<NativeExtensionApp>) -> ExtensionApp { v.0.clone() }
fn link_meta(v: Arc<NativeLinkMeta>) -> LinkMeta { v.0.clone() }
fn share_profile(v: Arc<NativeShareProfile>) -> ShareProfileMessage { v.0.clone() }

impl From<NativeConversationData> for ConversationData {
    fn from(v: NativeConversationData) -> Self {
        Self { participants: v.participants, cv_name: v.cv_name, sender_guid: v.sender_guid, after_guid: v.after_guid }
    }
}

impl TryFrom<NativeTextFormat> for TextFormat {
    type Error = NativeMsgError;
    fn try_from(v: NativeTextFormat) -> Result<Self, NativeMsgError> {
        Ok(match v {
            NativeTextFormat::Flags { bold, italic, underline, strikethrough } => TextFormat::Flags(TextFlags { bold, italic, underline, strikethrough }),
            NativeTextFormat::Effect { effect } => TextFormat::Effect(TextEffect::try_from(effect).map_err(|_| invalid(format!("unknown text effect {effect}")))?),
        })
    }
}

impl From<NativeMmcsFile> for MMCSFile {
    fn from(v: NativeMmcsFile) -> Self {
        Self { signature: v.signature, object: v.object_id, url: v.url, key: v.key, size: v.size as usize }
    }
}

impl From<NativeAttachment> for Attachment {
    fn from(v: NativeAttachment) -> Self {
        Self {
            a_type: match v.a_type {
                NativeAttachmentType::Inline { data } => AttachmentType::Inline(data),
                NativeAttachmentType::Mmcs { file } => AttachmentType::MMCS(file.into()),
            },
            part: v.part,
            uti_type: v.uti_type,
            mime: v.mime,
            name: v.name,
            iris: v.iris,
        }
    }
}

impl From<NativePartExtension> for PartExtension {
    fn from(v: NativePartExtension) -> Self {
        let NativePartExtension::Sticker { msg_width, rotation, sai, scale, update, sli, normalized_x, normalized_y, version, hash, safi, effect_type, sticker_id } = v;
        PartExtension::Sticker { msg_width, rotation, sai, scale, update, sli, normalized_x, normalized_y, version, hash, safi, effect_type, sticker_id }
    }
}

fn parts_from_native(v: Vec<NativeIndexedMessagePart>) -> Result<MessageParts, NativeMsgError> {
    Ok(MessageParts(v.into_iter().map(|p| Ok(IndexedMessagePart {
        part: match p.part {
            NativeMessagePart::Text { text, format } => MessagePart::Text(text, format.try_into()?),
            NativeMessagePart::Attachment { attachment } => MessagePart::Attachment(attachment.into()),
            NativeMessagePart::Mention { uri, text } => MessagePart::Mention(uri, text),
            NativeMessagePart::Object { value } => MessagePart::Object(value),
        },
        idx: p.idx.map(|i| i as usize),
        ext: p.ext.map(Into::into),
    })).collect::<Result<Vec<_>, NativeMsgError>>()?))
}

impl From<NativeMessageType> for MessageType {
    fn from(v: NativeMessageType) -> Self {
        match v {
            NativeMessageType::IMessage => MessageType::IMessage,
            NativeMessageType::Sms { is_phone, using_number, from_handle } => MessageType::SMS { is_phone, using_number, from_handle },
        }
    }
}

impl TryFrom<NativeNormalMessage> for NormalMessage {
    type Error = NativeMsgError;
    fn try_from(v: NativeNormalMessage) -> Result<Self, NativeMsgError> {
        Ok(NormalMessage {
            parts: parts_from_native(v.parts)?,
            effect: v.effect,
            reply_guid: v.reply_guid,
            reply_part: v.reply_part,
            service: v.service.into(),
            subject: v.subject,
            app: v.app.map(ext_app),
            link_meta: v.link_meta.map(link_meta),
            voice: v.voice,
            scheduled: v.scheduled.map(|s| ScheduleMode { ms: s.ms, schedule: s.schedule }),
            embedded_profile: v.embedded_profile.map(share_profile),
        })
    }
}

impl TryFrom<NativeReaction> for Reaction {
    type Error = NativeMsgError;
    fn try_from(v: NativeReaction) -> Result<Self, NativeMsgError> {
        Ok(match v {
            NativeReaction::Heart => Reaction::Heart,
            NativeReaction::Like => Reaction::Like,
            NativeReaction::Dislike => Reaction::Dislike,
            NativeReaction::Laugh => Reaction::Laugh,
            NativeReaction::Emphasize => Reaction::Emphasize,
            NativeReaction::Question => Reaction::Question,
            NativeReaction::Emoji { emoji } => Reaction::Emoji(emoji),
            NativeReaction::Sticker { spec, body } => Reaction::Sticker { spec: spec.map(ext_app), body: parts_from_native(body)? },
        })
    }
}

impl TryFrom<NativeReactMessage> for ReactMessage {
    type Error = NativeMsgError;
    fn try_from(v: NativeReactMessage) -> Result<Self, NativeMsgError> {
        Ok(ReactMessage {
            to_uuid: v.to_uuid,
            to_part: v.to_part,
            reaction: match v.reaction {
                NativeReactMessageType::React { reaction, enable } => ReactMessageType::React { reaction: reaction.try_into()?, enable },
                NativeReactMessageType::Extension { spec, body, is_meta } => ReactMessageType::Extension { spec: ext_app(spec), body: parts_from_native(body)?, is_meta },
            },
            to_text: v.to_text,
            embedded_profile: v.embedded_profile.map(share_profile),
        })
    }
}

impl From<NativeOperatedChat> for OperatedChat {
    fn from(v: NativeOperatedChat) -> Self {
        OperatedChat {
            participants: v.participants,
            group_id: v.group_id,
            guid: v.guid,
            delete_incoming_messages: v.delete_incoming_messages,
            was_reported_as_junk: v.was_reported_as_junk,
        }
    }
}

impl From<NativeDeleteTarget> for DeleteTarget {
    fn from(v: NativeDeleteTarget) -> Self {
        match v {
            NativeDeleteTarget::Chat { chat } => DeleteTarget::Chat(chat.into()),
            NativeDeleteTarget::Messages { guids } => DeleteTarget::Messages(guids),
        }
    }
}

impl TryFrom<NativeMessage> for Message {
    type Error = NativeMsgError;
    fn try_from(v: NativeMessage) -> Result<Self, NativeMsgError> {
        Ok(match v {
            NativeMessage::Message { msg } => Message::Message(msg.try_into()?),
            NativeMessage::RenameMessage { new_name } => Message::RenameMessage(RenameMessage { new_name }),
            NativeMessage::ChangeParticipants { new_participants, group_version } => Message::ChangeParticipants(ChangeParticipantMessage { new_participants, group_version }),
            NativeMessage::React { msg } => Message::React(msg.try_into()?),
            NativeMessage::Delivered => Message::Delivered,
            NativeMessage::Read => Message::Read,
            NativeMessage::Typing { typing, app } => Message::Typing(typing, app.map(|a| TypingApp { bundle_id: a.bundle_id, icon: a.icon })),
            NativeMessage::Unsend { tuuid, edit_part } => Message::Unsend(UnsendMessage { tuuid, edit_part }),
            NativeMessage::Edit { tuuid, edit_part, new_parts } => Message::Edit(EditMessage { tuuid, edit_part, new_parts: parts_from_native(new_parts)? }),
            NativeMessage::IconChange { file, group_version } => Message::IconChange(IconChangeMessage { file: file.map(Into::into), group_version }),
            NativeMessage::EnableSmsActivation { enabled } => Message::EnableSmsActivation(enabled),
            NativeMessage::MessageReadOnDevice => Message::MessageReadOnDevice,
            NativeMessage::SmsConfirmSent { status } => Message::SmsConfirmSent(status),
            NativeMessage::MarkUnread => Message::MarkUnread,
            NativeMessage::PeerCacheInvalidate => Message::PeerCacheInvalidate,
            NativeMessage::UpdateExtension { for_uuid, ext } => Message::UpdateExtension(UpdateExtensionMessage { for_uuid, ext: ext.into() }),
            NativeMessage::Error { for_uuid, status, status_str } => Message::Error(ErrorMessage { for_uuid, status, status_str }),
            NativeMessage::MoveToRecycleBin { target, recoverable_delete_date } => Message::MoveToRecycleBin(MoveToRecycleBinMessage { target: target.into(), recoverable_delete_date }),
            NativeMessage::RecoverChat { chat } => Message::RecoverChat(chat.into()),
            NativeMessage::PermanentDelete { target, is_scheduled } => Message::PermanentDelete(PermanentDeleteMessage { target: target.into(), is_scheduled }),
            NativeMessage::Unschedule => Message::Unschedule,
            NativeMessage::UpdateProfile { profile, share_contacts } => Message::UpdateProfile(UpdateProfileMessage { profile: profile.map(share_profile), share_contacts }),
            NativeMessage::UpdateProfileSharing { shared_dismissed, shared_all, version } => Message::UpdateProfileSharing(UpdateProfileSharingMessage { shared_dismissed, shared_all, version }),
            NativeMessage::ShareProfile { profile } => Message::ShareProfile(share_profile(profile)),
            NativeMessage::NotifyAnyways => Message::NotifyAnyways,
            NativeMessage::SetTranscriptBackground { background } => Message::SetTranscriptBackground(background.0.clone()),
        })
    }
}

impl From<NativeMessageTarget> for MessageTarget {
    fn from(v: NativeMessageTarget) -> Self {
        match v {
            NativeMessageTarget::Token { token } => MessageTarget::Token(token),
            NativeMessageTarget::Uuid { uuid } => MessageTarget::Uuid(uuid),
        }
    }
}

impl From<NativeCertifiedContext> for CertifiedContext {
    fn from(v: NativeCertifiedContext) -> Self {
        CertifiedContext { version: v.version, receipt: v.receipt, sender: v.sender, target: v.target, uuid: v.uuid, token: v.token }
    }
}

impl TryFrom<NativeMessageInst> for MessageInst {
    type Error = NativeMsgError;
    fn try_from(v: NativeMessageInst) -> Result<Self, NativeMsgError> {
        Ok(MessageInst {
            id: v.id,
            sender: v.sender,
            conversation: v.conversation.map(Into::into),
            message: v.message.try_into()?,
            sent_timestamp: v.sent_timestamp,
            target: v.target.map(|t| t.into_iter().map(Into::into).collect()),
            send_delivered: v.send_delivered,
            verification_failed: v.verification_failed,
            certified_context: v.certified_context.map(Into::into),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_parts() -> MessageParts {
        MessageParts(vec![
            IndexedMessagePart {
                part: MessagePart::Text("hello".into(), TextFormat::Flags(TextFlags { bold: true, ..Default::default() })),
                idx: Some(0),
                ext: None,
            },
            IndexedMessagePart {
                part: MessagePart::Attachment(Attachment {
                    a_type: AttachmentType::MMCS(MMCSFile { signature: vec![1, 2], object: "obj".into(), url: "https://x/obj".into(), key: vec![3], size: 42 }),
                    part: 1,
                    uti_type: "public.jpeg".into(),
                    mime: "image/jpeg".into(),
                    name: "a.jpg".into(),
                    iris: false,
                }),
                idx: Some(1),
                ext: None,
            },
            IndexedMessagePart { part: MessagePart::Mention("mailto:a@b.c".into(), "A".into()), idx: Some(2), ext: None },
        ])
    }

    fn roundtrip(inst: MessageInst) -> MessageInst {
        let native: NativeMessageInst = inst.into();
        native.try_into().expect("roundtrip failed")
    }

    #[test]
    fn normal_message_roundtrip() {
        let mut normal = NormalMessage::new(String::new(), MessageType::IMessage);
        normal.parts = sample_parts();
        normal.reply_guid = Some("REPLY".into());
        normal.effect = Some("com.apple.MobileSMS.expressivesend.loud".into());
        let conv = ConversationData { participants: vec!["tel:+1".into(), "mailto:a@b.c".into()], cv_name: Some("G".into()), sender_guid: Some("SG".into()), after_guid: None };
        let inst = MessageInst::new(conv, "mailto:a@b.c", Message::Message(normal));
        let id = inst.id.clone();

        let back = roundtrip(inst);
        assert_eq!(back.id, id);
        assert_eq!(back.sender.as_deref(), Some("mailto:a@b.c"));
        let conv = back.conversation.unwrap();
        assert_eq!(conv.participants.len(), 2);
        assert_eq!(conv.cv_name.as_deref(), Some("G"));
        let Message::Message(msg) = back.message else { panic!("wrong variant") };
        assert_eq!(msg.reply_guid.as_deref(), Some("REPLY"));
        assert_eq!(msg.parts.0.len(), 3);
        assert_eq!(msg.parts.raw_text(), sample_parts().raw_text());
        match &msg.parts.0[0].part {
            MessagePart::Text(t, TextFormat::Flags(f)) => { assert_eq!(t, "hello"); assert!(f.bold && !f.italic); }
            _ => panic!("wrong part"),
        }
        match &msg.parts.0[1].part {
            MessagePart::Attachment(Attachment { a_type: AttachmentType::MMCS(m), name, .. }) => {
                assert_eq!(m.size, 42);
                assert_eq!(m.object, "obj");
                assert_eq!(name, "a.jpg");
            }
            _ => panic!("wrong part"),
        }
    }

    #[test]
    fn misc_variants_roundtrip() {
        let conv = ConversationData { participants: vec!["tel:+1".into()], cv_name: None, sender_guid: None, after_guid: None };
        let cases = vec![
            Message::React(ReactMessage { to_uuid: "T".into(), to_part: Some(0), reaction: ReactMessageType::React { reaction: Reaction::Emoji("🔥".into()), enable: true }, to_text: "hi".into(), embedded_profile: None }),
            Message::Typing(true, None),
            Message::Read,
            Message::Delivered,
            Message::Edit(EditMessage { tuuid: "T".into(), edit_part: 0, new_parts: sample_parts() }),
            Message::Unsend(UnsendMessage { tuuid: "T".into(), edit_part: 0 }),
            Message::RenameMessage(RenameMessage { new_name: "N".into() }),
            Message::PermanentDelete(PermanentDeleteMessage { target: DeleteTarget::Messages(vec!["A".into()]), is_scheduled: false }),
            Message::Error(ErrorMessage { for_uuid: "T".into(), status: 4, status_str: "x".into() }),
        ];
        for msg in cases {
            let before = msg.to_string();
            let c = msg.get_c();
            let back = roundtrip(MessageInst::new(conv.clone(), "tel:+1", msg));
            assert_eq!(back.message.to_string(), before);
            assert_eq!(back.message.get_c(), c);
        }
    }

    #[test]
    fn invalid_text_effect_rejected() {
        let fmt = NativeTextFormat::Effect { effect: 999 };
        assert!(TextFormat::try_from(fmt).is_err());
    }
}
