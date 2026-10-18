/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The native API of uom (docs/NATIVE.md): {@link org.tinygears.uom.Quantity} and
 * {@link org.tinygears.uom.Unit} typed with this library's quantity types, so a unit is a
 * {@code Unit<Length>} and the product of typed quantities is typed ({@code Length.divide(Time)} is a {@code Speed}).
 * The types are generated into {@code org.tinygears.uom.quantity}, the catalogs into
 * {@code org.tinygears.uom.systems}; the arithmetic is the engine's, shared with the JSR-385 quantities.
 */
package org.tinygears.uom;
