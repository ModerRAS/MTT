package com.mtt.app.data.local

import androidx.room.TypeConverter
import com.mtt.app.data.model.TranslationStatus

/**
 * Type converters for Room database.
 * Converts TranslationStatus sealed class to/from Int for storage.
 */
class Converters {
    @TypeConverter
    fun fromTranslationStatus(status: TranslationStatus): Int {
        return status.code
    }

    @TypeConverter
    fun toTranslationStatus(code: Int): TranslationStatus {
        return TranslationStatus.fromCode(code)
    }
}