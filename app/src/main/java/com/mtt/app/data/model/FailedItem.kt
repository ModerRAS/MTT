package com.mtt.app.data.model

/**
 * Represents a translation item that failed verification or retry.
 *
 * @param globalIndex        Zero-based index of this item across all batches.
 * @param sourceText         Original source text that failed.
 * @param retryCount         Number of retry attempts made (default 0).
 * @param permanentlyFailed  True if all retry attempts exhausted (default false).
 */
data class FailedItem(
    val globalIndex: Int,
    val sourceText: String,
    val retryCount: Int = 0,
    val permanentlyFailed: Boolean = false
)
