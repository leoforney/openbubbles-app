package com.bluebubbles.messaging.objectbox
import io.objectbox.annotation.*
import io.objectbox.relation.ToOne
import io.objectbox.relation.ToMany

@Entity
@NameInDb("Attachment")
@Uid(2065429213543838585L)
data class AttachmentEntity(
    @Id(assignable = true)
    @Uid(1511554263230297197L)
    var id: Long = 0,
    @Uid(2178477183992316459L)
    var originalROWID: Long? = null,
    @Index
    @Unique
    @Uid(895699641082029197L)
    var guid: String? = null,
    @Uid(6047065591027683672L)
    var uti: String? = null,
    @Uid(8856072667843358452L)
    var mimeType: String? = null,
    @Uid(2217746424870068461L)
    var isOutgoing: Boolean? = null,
    @Uid(5943814485048944977L)
    var transferName: String? = null,
    @Uid(2227654525168892418L)
    var totalBytes: Long? = null,
    @Uid(171869175523313868L)
    var height: Long? = null,
    @Uid(2450795986531805384L)
    var width: Long? = null,
    @Uid(396659320299238462L)
    var webUrl: String? = null,
    @Uid(4627777114429677812L)
    var dbMetadata: String? = null,
    @Uid(2980761301143366690L)
    var hasLivePhoto: Boolean? = null,
    @Uid(4318192841720051066L)
    var ckRecordId: String? = null
) {
    @Uid(5777776419087046056L)
    lateinit var messageId: ToOne<MessageEntity>
}

@Entity
@NameInDb("Chat")
@Uid(9017250848141753702L)
data class ChatEntity(
    @Id(assignable = true)
    @Uid(297833828287439140L)
    var id: Long = 0,
    @Index
    @Unique
    @Uid(318412581013308394L)
    var guid: String? = null,
    @Uid(9099706644901956287L)
    var chatIdentifier: String? = null,
    @Uid(9117376896883192460L)
    var isArchived: Boolean? = null,
    @Uid(2937507201037513710L)
    var muteType: String? = null,
    @Uid(3354772670242853270L)
    var muteArgs: String? = null,
    @Uid(3734639758158862923L)
    var isPinned: Boolean? = null,
    @Uid(2937716363156975856L)
    var hasUnreadMessage: Boolean? = null,
    @Uid(526293286661780207L)
    var dbOnlyLatestMessageDate: java.util.Date? = null,
    @Uid(4266631519717388837L)
    var title: String? = null,
    @Uid(1181486482872028222L)
    var displayName: String? = null,
    @Uid(3666111733726849006L)
    var customAvatarPath: String? = null,
    @Uid(4234470006262207812L)
    var pinIndex: Long? = null,
    @Uid(2695161584801983484L)
    var autoSendReadReceipts: Boolean? = null,
    @Uid(55197157095191277L)
    var autoSendTypingIndicators: Boolean? = null,
    @Uid(8357397541593794158L)
    var textFieldText: String? = null,
    @Uid(7756745453707268462L)
    var textFieldAttachments: Array<String>? = null,
    @Uid(1609660383308913679L)
    var dateDeleted: java.util.Date? = null,
    @Uid(2054740103145852197L)
    var style: Long? = null,
    @Uid(4203733486781204313L)
    var lockChatName: Boolean? = null,
    @Uid(5967587520649064381L)
    var lockChatIcon: Boolean? = null,
    @Uid(3841196368520260614L)
    var lastReadMessageGuid: String? = null,
    @Uid(419554443603699639L)
    var groupVersion: Long? = null,
    @Uid(4623900363927990505L)
    var usingHandle: String? = null,
    @Uid(2890558203307402859L)
    var apnTitle: String? = null,
    @Uid(3790179132714024264L)
    var isRpSms: Boolean? = null,
    @Uid(7747554395546158964L)
    var guidRefs: Array<String>? = null,
    @Uid(6743935942891315857L)
    var telephonyId: Long? = null,
    @Uid(7774397079877699895L)
    var textFieldAnnotations: String? = null,
    @Uid(7143894652284556003L)
    var shareZenMode: Boolean? = null,
    @Uid(420507915079790972L)
    var notifsSilenced: Boolean? = null,
    @Uid(7629993644602445500L)
    var dateNotifiedAnyways: java.util.Date? = null,
    @Uid(1450447556768339740L)
    var zenModeIsShared: Long? = null,
    @Uid(5580492826274966240L)
    var senderIsKnown: Boolean? = null,
    @Uid(7342392293130604386L)
    var isRoutingStub: Boolean? = null,
    @Uid(4361624812823479978L)
    var transcriptPosterPath: String? = null,
    @Uid(9027611778744022691L)
    var transcriptBackgroundVersion: Long? = null,
    @Uid(6303048169382671264L)
    var cloudData: ByteArray? = null,
    @Uid(3549423414997445177L)
    var ckRecordId: String? = null,
    @Uid(2938538298825979541L)
    var ckSyncState: Boolean? = null,
    @Uid(3819874096338136746L)
    var photoAttachmentGuid: String? = null,
    @Uid(6101265134976618931L)
    var cloudGuid: String? = null
) {
    @Uid(7492985733214117623L)
    lateinit var handles: ToMany<HandleEntity>
}

@Entity
@NameInDb("FCMData")
@Uid(5390756932993878582L)
data class FCMDataEntity(
    @Id(assignable = true)
    @Uid(843767696595301490L)
    var id: Long = 0,
    @Uid(4393840145875077709L)
    var projectID: String? = null,
    @Uid(7264245173656870052L)
    var storageBucket: String? = null,
    @Uid(3998672972942379984L)
    var apiKey: String? = null,
    @Uid(3295687739104688136L)
    var firebaseURL: String? = null,
    @Uid(8856592728926530171L)
    var clientID: String? = null,
    @Uid(4724783625435560776L)
    var applicationID: String? = null
) {
}

@Entity
@NameInDb("Handle")
@Uid(1716592500251888002L)
data class HandleEntity(
    @Id(assignable = true)
    @Uid(683096758365457558L)
    var id: Long = 0,
    @Uid(191994702917313644L)
    var originalROWID: Long? = null,
    @Uid(2544513926695389102L)
    var address: String? = null,
    @Uid(8884526609844353946L)
    var country: String? = null,
    @Uid(3522974353771163433L)
    var color: String? = null,
    @Uid(57094839621772204L)
    var defaultPhone: String? = null,
    @Uid(9192015144235467286L)
    var defaultEmail: String? = null,
    @Uid(7485620678567449081L)
    var formattedAddress: String? = null,
    @Index
    @Unique
    @Uid(7288262795934250307L)
    var uniqueAddressAndService: String? = null,
    @Uid(2987973275926761435L)
    var service: String? = null,
    @Uid(7319540363051031629L)
    var posterPath: String? = null,
    @Uid(3522348185317901483L)
    var blocked: Boolean? = null
) {
    @Uid(6784884866959942259L)
    lateinit var contactRelationId: ToOne<ContactEntity>
}

@Entity
@NameInDb("ThemeEntry")
@Uid(7380334062783734091L)
data class ThemeEntryEntity(
    @Id(assignable = true)
    @Uid(3364349183457626105L)
    var id: Long = 0,
    @Uid(378260260370164099L)
    var themeId: Long? = null,
    @Uid(1711484628702995090L)
    var name: String? = null,
    @Uid(5820153989252425651L)
    var isFont: Boolean? = null,
    @Uid(357044983311472123L)
    var fontSize: Long? = null,
    @Uid(205399809771216750L)
    var dbColor: String? = null,
    @Uid(2424613981822393823L)
    var fontWeight: Long? = null
) {
    @Uid(4809686302323910258L)
    lateinit var themeObjectId: ToOne<ThemeObjectEntity>
}

@Entity
@NameInDb("Message")
@Uid(4148278195232901830L)
data class MessageEntity(
    @Id(assignable = true)
    @Uid(2871568629586737055L)
    var id: Long = 0,
    @Uid(1582361484724166619L)
    var originalROWID: Long? = null,
    @Index
    @Unique
    @Uid(8669586978455730639L)
    var guid: String? = null,
    @Uid(9142895361605464091L)
    var handleId: Long? = null,
    @Uid(7874018753129857845L)
    var otherHandle: Long? = null,
    @Uid(4547092920121180060L)
    var text: String? = null,
    @Uid(2233028199063732172L)
    var subject: String? = null,
    @Uid(3994409138392851632L)
    var country: String? = null,
    @Index
    @Uid(5531207057664871058L)
    var dateCreated: java.util.Date? = null,
    @Uid(3811604236432549817L)
    var dateRead: java.util.Date? = null,
    @Uid(7356445736232735146L)
    var dateDelivered: java.util.Date? = null,
    @Uid(626638148258506930L)
    var isFromMe: Boolean? = null,
    @Uid(5938275663538454436L)
    var hasDdResults: Boolean? = null,
    @Uid(8082781591108942969L)
    var datePlayed: java.util.Date? = null,
    @Uid(7621327072355688575L)
    var itemType: Long? = null,
    @Uid(7649146516477763838L)
    var groupTitle: String? = null,
    @Uid(1515848557190125128L)
    var groupActionType: Long? = null,
    @Uid(4765948580516232913L)
    var balloonBundleId: String? = null,
    @Uid(8696985708980146227L)
    var associatedMessageGuid: String? = null,
    @Uid(574575341112766208L)
    var associatedMessageType: String? = null,
    @Uid(7125401610778464945L)
    var expressiveSendStyleId: String? = null,
    @Uid(397296906153054772L)
    var hasAttachments: Boolean? = null,
    @Uid(3092611940313786692L)
    var hasReactions: Boolean? = null,
    @Uid(3672248506437712660L)
    var dateDeleted: java.util.Date? = null,
    @Uid(5022296267274326910L)
    var threadOriginatorGuid: String? = null,
    @Uid(2742958821028567046L)
    var threadOriginatorPart: String? = null,
    @Uid(5359949882738167432L)
    var bigEmoji: Boolean? = null,
    @Uid(2993306046047010488L)
    var errorVal: Long? = null,
    @Uid(5039085638407799291L)
    var dbAttributedBody: String? = null,
    @Uid(4740279940509235463L)
    var associatedMessagePart: Long? = null,
    @Uid(5528059355711105816L)
    var hasApplePayloadData: Boolean? = null,
    @Uid(3013626603452809145L)
    var dateEdited: java.util.Date? = null,
    @Uid(317511503043460053L)
    var dbMessageSummaryInfo: String? = null,
    @Uid(9157460167218193355L)
    var dbPayloadData: String? = null,
    @Uid(55112057161128513L)
    var dbMetadata: String? = null,
    @Uid(2946661728932667087L)
    var wasDeliveredQuietly: Boolean? = null,
    @Uid(8353240741127563871L)
    var didNotifyRecipient: Boolean? = null,
    @Uid(4357660909480898880L)
    var isBookmarked: Boolean? = null,
    @Uid(6516059048310614016L)
    var hasBeenForwarded: Boolean? = null,
    @Uid(2206983384887094884L)
    var stagingGuid: String? = null,
    @Uid(2416690828698123601L)
    var amkSessionId: String? = null,
    @Uid(2179513762379822209L)
    var isDelivered: Boolean? = null,
    @Uid(684200636816633360L)
    var verificationFailed: Boolean? = null,
    @Uid(5249321850957672701L)
    var sendingServiceId: String? = null,
    @Uid(1614237679156627573L)
    var associatedMessageEmoji: String? = null,
    @Uid(5276795239995260938L)
    var dateScheduled: java.util.Date? = null,
    @Uid(5464844875358527751L)
    var ckRecordId: String? = null,
    @Uid(8549583242275112644L)
    var ckSyncState: Boolean? = null
) {
    @Uid(1372898255926257108L)
    lateinit var chatId: ToOne<ChatEntity>
}

@Entity
@NameInDb("ThemeObject")
@Uid(7753273527865539946L)
data class ThemeObjectEntity(
    @Id(assignable = true)
    @Uid(7682268131019867778L)
    var id: Long = 0,
    @Index
    @Unique
    @Uid(3805851416487185429L)
    var name: String? = null,
    @Uid(621312794123477095L)
    var selectedLightTheme: Boolean? = null,
    @Uid(2380808371075786065L)
    var selectedDarkTheme: Boolean? = null,
    @Uid(7474338997382293502L)
    var gradientBg: Boolean? = null,
    @Uid(7459499326275659791L)
    var previousLightTheme: Boolean? = null,
    @Uid(7718171125722310355L)
    var previousDarkTheme: Boolean? = null
) {
}

@Entity
@NameInDb("ThemeStruct")
@Uid(1815690088052698449L)
data class ThemeStructEntity(
    @Id(assignable = true)
    @Uid(7043518911115753096L)
    var id: Long = 0,
    @Index
    @Unique
    @Uid(6644283342768530799L)
    var name: String? = null,
    @Uid(8412087896868925153L)
    var gradientBg: Boolean? = null,
    @Uid(1129102363347301238L)
    var dbThemeData: String? = null,
    @Uid(5263969220071020408L)
    var googleFont: String? = null
) {
}

@Entity
@NameInDb("Contact")
@Uid(2547083341603323785L)
data class ContactEntity(
    @Id(assignable = true)
    @Uid(755766469677376751L)
    var dbId: Long = 0,
    @Index
    @Unique
    @Uid(708918913606465689L)
    var stringId: String? = null,
    @Uid(3572501485957552445L)
    var displayName: String? = null,
    @Uid(4938259396546143140L)
    var phones: Array<String>? = null,
    @Uid(2909427687923431080L)
    var emails: Array<String>? = null,
    @Uid(765867408744391809L)
    var avatar: ByteArray? = null,
    @Uid(3612117664568604198L)
    var dbStructuredName: String? = null,
    @Uid(3140177684889918471L)
    var isShared: Boolean? = null,
    @Uid(761555548860852325L)
    var isDismissed: Boolean? = null,
    @Uid(6585143364094241703L)
    var posterPath: String? = null
) {
}

