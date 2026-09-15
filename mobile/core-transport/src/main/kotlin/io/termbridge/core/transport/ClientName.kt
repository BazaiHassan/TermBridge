package io.termbridge.core.transport

import javax.inject.Qualifier

/** The `client` string sent in HELLO, e.g. `android/0.1.0`; bound by the app module. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ClientName
