package com.mtt.app

import org.robolectric.RobolectricTestRunner

/**
 * Custom Robolectric test runner enabling Hilt injection in unit tests.
 */
class HiltTestRunner(klass: Class<*>) : RobolectricTestRunner(klass)
