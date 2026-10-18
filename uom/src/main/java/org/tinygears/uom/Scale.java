/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom;

/**
 * Whether a quantity is a point on its scale or a difference, which matters for units with an offset: {@code 20 °C}
 * as a point is {@code 293.15 K}, as a difference {@code 20 K} (docs/NATIVE.md#offset-units).
 */
public enum Scale {
    /** A point: converts through the offset. */
    ABSOLUTE,
    /** A difference: converts by the factor only. */
    RELATIVE
}
