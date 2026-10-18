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
 * uom-jsr, the JSR-385 implementation of uom: the JSR-385 units, dimensions and converters over the engine
 * ({@code org.tinygears.uom.engine}), the typed quantities ({@code org.tinygears.uom.jsr.quantity}), the unit
 * catalogs, formats and the service provider ({@code org.tinygears.uom.jsr.systems}), and the conversion to and from
 * the native API of uom ({@code org.tinygears.uom}, {@link org.tinygears.uom.jsr.Jsr}).
 */
module org.tinygears.uom.jsr {
    requires transitive java.measure;
    requires transitive org.tinygears.uom.engine;
    requires transitive org.tinygears.uom;

    exports org.tinygears.uom.jsr;
    exports org.tinygears.uom.jsr.quantity;
    exports org.tinygears.uom.jsr.systems;

    provides javax.measure.spi.ServiceProvider with org.tinygears.uom.jsr.systems.UomServiceProvider;
}
