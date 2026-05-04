package com.mtt.app.data.model

import androidx.room.TypeConverter

/**
 * Type converters for Room database.
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