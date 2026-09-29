package com.siams.kern_prototype.domain.model

/**
 * Статус отправки долбления на s900 (§7, §9). Хранится в Room.
 */
enum class SyncStatus {
    NOT_SENT,
    SENDING,
    SENT,
    ERROR
}
