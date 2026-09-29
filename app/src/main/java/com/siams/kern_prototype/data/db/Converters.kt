package com.siams.kern_prototype.data.db

import androidx.room.TypeConverter
import com.siams.kern_prototype.domain.model.CoreImageType
import com.siams.kern_prototype.domain.model.LampType
import com.siams.kern_prototype.domain.model.SyncStatus

/** Конвертеры enum ↔ String для Room. */
class Converters {
    @TypeConverter fun lampToString(v: LampType): String = v.name
    @TypeConverter fun stringToLamp(v: String): LampType = LampType.valueOf(v)

    @TypeConverter fun syncToString(v: SyncStatus): String = v.name
    @TypeConverter fun stringToSync(v: String): SyncStatus = SyncStatus.valueOf(v)

    @TypeConverter fun coreTypeToString(v: CoreImageType): String = v.name
    @TypeConverter fun stringToCoreType(v: String): CoreImageType = CoreImageType.valueOf(v)
}
