package com.bernaferrari.kmpwright.core

import java.util.logging.Level
import java.util.logging.Logger

private val kmpwrightLogger: Logger = Logger.getLogger("com.bernaferrari.kmpwright")

/**
 * Reports a failure that has no caller to propagate to (background protocol
 * work, detached callbacks). Uses java.util.logging so applications can route
 * or silence it without an extra dependency.
 */
internal fun kmpwrightLogWarning(message: String, error: Throwable?) {
    if (error == null) kmpwrightLogger.log(Level.WARNING, message)
    else kmpwrightLogger.log(Level.WARNING, message, error)
}
