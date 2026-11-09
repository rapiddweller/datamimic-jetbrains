// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Reads a time of the platform. The platform stores naive UTC timestamps, and depending on the serializer they arrive
 * with or without an offset.
 *
 * @throws java.time.format.DateTimeParseException when [value] is not a time.
 */
internal fun parsePlatformInstant(value: String): Instant =
    runCatching { OffsetDateTime.parse(value).toInstant() }.getOrElse { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }
