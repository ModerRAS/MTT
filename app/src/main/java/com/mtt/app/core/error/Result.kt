package com.mtt.app.core.error

/**
 * Sealed interface for operation results.
 * Provides functional utilities for chaining and handling.
 */
sealed interface Result<out T> {
    data class Success<T>(val data: T) : Result<T>
    data class Failure<T>(val exception: MttException) : Result<T>

    companion object {
        fun <T> success(data: T): Result<T> = Success(data)
        fun <T> failure(exception: MttException): Result<T> = Failure(exception)
    }
}

/**
 * Transforms the success value.
 */
@Suppress("UNCHECKED_CAST")
inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> {
    return when (this) {
        is Result.Success -> Result.Success(transform(data))
        is Result.Failure -> this as Result<R>
    }
}

/**
 * Chains to another Result.
 */
@Suppress("UNCHECKED_CAST")
inline fun <T, R> Result<T>.flatMap(transform: (T) -> Result<R>): Result<R> {
    return when (this) {
        is Result.Success -> transform(data)
        is Result.Failure -> this as Result<R>
    }
}

/**
 * Executes action on success.
 */
inline fun <T> Result<T>.onSuccess(action: (T) -> Unit): Result<T> {
    if (this is Result.Success) action(data)
    return this
}

/**
 * Executes action on failure.
 */
inline fun <T> Result<T>.onFailure(action: (MttException) -> Unit): Result<T> {
    if (this is Result.Failure) action(exception)
    return this
}

/**
 * Returns the value or null.
 */
fun <T> Result<T>.getOrNull(): T? {
    return when (this) {
        is Result.Success -> data
        is Result.Failure -> null
    }
}

/**
 * Returns the value or throws the exception.
 */
@Suppress("UNCHECKED_CAST")
fun <T> Result<T>.getOrThrow(): T {
    return when (this) {
        is Result.Success -> data
        is Result.Failure -> throw exception
    }
}

/**
 * Returns the value or a default.
 */
fun <T> Result<T>.getOrDefault(default: T): T {
    return when (this) {
        is Result.Success -> data
        is Result.Failure -> default
    }
}

/**
 * Returns true if this is a success.
 */
fun <T> Result<T>.isSuccess(): Boolean = this is Result.Success

/**
 * Returns true if this is a failure.
 */
fun <T> Result<T>.isFailure(): Boolean = this is Result.Failure