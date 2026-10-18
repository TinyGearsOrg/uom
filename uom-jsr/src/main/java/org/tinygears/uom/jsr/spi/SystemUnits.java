/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.jsr.spi;

import javax.measure.Unit;

/**
 * Implemented by the service provider of this implementation, so that a named unit's system unit is the named system
 * unit of its quantity type ({@code Pa} for {@code kPa}) without the core knowing the types; exported to
 * {@code org.tinygears.uom.jsr} only, not API.
 */
public interface SystemUnits {

    /**
     * The system unit of the standard quantity type whose system unit equals {@code unit}, atoms included.
     *
     * @param unit an unscaled unit
     * @return the type's system unit, or null if no standard type has it
     */
    Unit<?> namedSystemUnit(Unit<?> unit);
}
