package com.bluebubbles.messaging.socialite.model

data class ParticipantInfo(
    val address: String,
    val name: String,
    val avatarBytes: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ParticipantInfo

        if (address != other.address) return false
        if (name != other.name) return false
        if (avatarBytes != null) {
            if (other.avatarBytes == null) return false
            if (!avatarBytes.contentEquals(other.avatarBytes)) return false
        } else if (other.avatarBytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (avatarBytes?.contentHashCode() ?: 0)
        return result
    }
}

data class Chat(
    val id: Long,
    val name: String,
    val avatarPath: String?,
    val avatarBytes: ByteArray?,
    val date: Long,
    val lastMessage: String,
    val isGroup: Boolean,
    val participantInfo: List<ParticipantInfo>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Chat

        if (id != other.id) return false
        if (name != other.name) return false
        if (avatarPath != other.avatarPath) return false
        if (avatarBytes != null) {
            if (other.avatarBytes == null) return false
            if (!avatarBytes.contentEquals(other.avatarBytes)) return false
        } else if (other.avatarBytes != null) return false
        if (date != other.date) return false
        if (lastMessage != other.lastMessage) return false
        if (isGroup != other.isGroup) return false
        if (participantInfo != other.participantInfo) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (avatarPath?.hashCode() ?: 0)
        result = 31 * result + (avatarBytes?.contentHashCode() ?: 0)
        result = 31 * result + date.hashCode()
        result = 31 * result + lastMessage.hashCode()
        result = 31 * result + isGroup.hashCode()
        result = 31 * result + participantInfo.hashCode()
        return result
    }
}
