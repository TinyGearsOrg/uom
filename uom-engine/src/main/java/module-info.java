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
 * The engine of uom, free of the JSR-385 API: exact factors with pi and roots, dimensions, unit atoms, the
 * alternate-unit registry, affine converters, the unit algebra and the {@code .units} parser, the decimal and interval
 * arithmetic behind them, the quantity arithmetic and the logic of the formats and systems. Both APIs build on it:
 * {@code org.tinygears.uom} and the JSR-385 implementation {@code org.tinygears.uom.jsr}.
 */
@SuppressWarnings("module") // the qualified export names modules that are compiled later
module org.tinygears.uom.engine {
    exports org.tinygears.uom.engine;
    exports org.tinygears.uom.engine.parse;
    // Helpers the other modules of uom share; not API.
    exports org.tinygears.uom.engine.internal to org.tinygears.uom.jsr, org.tinygears.uom;
    // The unit format, systems and number text of both APIs; not API.
    exports org.tinygears.uom.engine.format to org.tinygears.uom.jsr, org.tinygears.uom;
    // The quantity arithmetic of both APIs; not API.
    exports org.tinygears.uom.engine.arithmetic to org.tinygears.uom.jsr, org.tinygears.uom;
}
