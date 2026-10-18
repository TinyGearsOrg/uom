/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The units of quantity products and the inverse converter are computed once, and equal the uncached algebra. */
class SystemProductCacheTest {
    private static final Unit METRE = Unit.base(UnitAtom.METRE, "metre");
    private static final Unit SECOND = Unit.base(UnitAtom.SECOND, "second");
    private static final Unit KILOGRAM = Unit.base(UnitAtom.KILOGRAM, "kilogram");
    private static final Unit KM = METRE.scaledBy(Factor.ofLong(1000)).named("km", "kilometre");
    private static final Unit HOUR = SECOND.scaledBy(Factor.ofLong(3600)).named("h", "hour");

    @Test
    void productsQuotientsAndInversesAreCachedAndExact() {
        Unit product = KM.systemProduct(HOUR);
        assertSame(product, KM.systemProduct(HOUR));
        assertEquals(KM.systemUnit().multiply(HOUR.systemUnit()).systemUnit(), product);
        assertEquals("m·s", product.symbol());
        Unit quotient = KM.systemQuotient(HOUR);
        assertSame(quotient, KM.systemQuotient(HOUR));
        assertEquals("m/s", quotient.symbol());
        assertEquals(Converter.IDENTITY, quotient.toSystem());
        Unit inverse = HOUR.systemInverse();
        assertSame(inverse, HOUR.systemInverse());
        assertEquals("1/s", inverse.symbol());
    }

    @Test
    void theResultDependsOnTheAtomsOnly() {
        // a named system unit takes part by its atoms: newton times metre is labelled by the atoms, not N·m
        Unit newton = KILOGRAM.multiply(METRE).divide(SECOND.pow(2)).named("N", "newton");
        assertEquals("kg·m^2/s^2", newton.systemProduct(METRE).symbol());
        // an equal key with other labels gives the same, label-free result
        Unit hours = SECOND.scaledBy(Factor.ofLong(3600)).named("hr", "hours");
        assertSame(KM.systemQuotient(HOUR), KM.systemQuotient(hours));
        assertEquals(Unit.ONE, METRE.systemQuotient(KM));
    }

    @Test
    void theInverseConverterIsComputedOnce() {
        Converter celsius = Converter.affine(Factor.ONE, Factor.of(27315, 100));
        Converter inverse = celsius.inverse();
        assertSame(inverse, celsius.inverse());
        assertEquals(Factor.of(-27315, 100), inverse.offset());
        assertEquals(20.0, inverse.convert(293.15), 1e-12);
    }
}
