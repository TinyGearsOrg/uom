/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.jsr;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.tinygears.uom.SystemOfUnits;
import org.tinygears.uom.Unit;
import org.tinygears.uom.Untyped;
import org.tinygears.uom.internal.Units;
import org.tinygears.uom.quantity.Dimensionless;
import org.tinygears.uom.quantity.Force;
import org.tinygears.uom.quantity.Length;
import org.tinygears.uom.quantity.Speed;
import org.tinygears.uom.quantity.Temperature;
import org.tinygears.uom.systems.CGS;
import org.tinygears.uom.systems.EMU;
import org.tinygears.uom.systems.ESU;
import org.tinygears.uom.systems.Gaussian;
import org.tinygears.uom.systems.Imperial;
import org.tinygears.uom.systems.Information;
import org.tinygears.uom.systems.SI;
import org.tinygears.uom.systems.USCustomary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Native and JSR-385 units convert into each other over the same engine unit. */
class JsrUnitBridgeTest {

    @Test
    void nativeToJsrKeepsTheEngineUnitTheSymbolAndTheName() {
        UomUnit<?> km = Jsr.toJsr(SI.KILOMETRE);
        assertSame(Units.engine(SI.KILOMETRE), km.engine());
        assertEquals("km", km.getSymbol());
        assertEquals("kilometre", km.getName());
        assertEquals(org.tinygears.uom.jsr.systems.SI.KILOMETRE, km);
    }

    @Test
    void jsrToNativeKeepsTheEngineUnitAndTakesTheNativeTypeOfItsDimension() {
        UomUnit<?> km = org.tinygears.uom.jsr.systems.SI.KILOMETRE;
        Unit<?> converted = Jsr.fromJsr(km);
        assertSame(km.engine(), Units.engine(converted));
        assertEquals(SI.KILOMETRE, converted);
        assertSame(Length.class, converted.type());
        assertEquals("km", converted.symbol());
        assertEquals("kilometre", converted.name());
    }

    @Test
    void theNativeTypeIsTheOneOfTheDimension() {
        assertSame(Force.class, Jsr.fromJsr(org.tinygears.uom.jsr.systems.SI.NEWTON).type());
        assertSame(Temperature.class, Jsr.fromJsr(org.tinygears.uom.jsr.systems.SI.DEGREE_CELSIUS).type());
        assertSame(
                Speed.class,
                Jsr.fromJsr(org.tinygears.uom.jsr.systems.SI.METRE.divide(org.tinygears.uom.jsr.systems.SI.SECOND))
                        .type());
        assertSame(Dimensionless.class, Jsr.fromJsr(BaseUnits.ONE).type());
        // no type has the dimension of a momentum
        UomUnit<?> momentum = org.tinygears.uom.jsr.systems.SI.KILOGRAM
                .multiply(org.tinygears.uom.jsr.systems.SI.METRE);
        assertSame(Untyped.class, Jsr.fromJsr(momentum).type());
        assertEquals("kg·m", org.tinygears.uom.UnitFormat.standard().format(Jsr.fromJsr(momentum)));
    }

    @Test
    void theConvertedUnitMakesNativeQuantities() {
        Unit<Length> km = Jsr.fromJsr(org.tinygears.uom.jsr.systems.SI.KILOMETRE).asType(Length.class);
        assertEquals(Length.of(5, SI.KILOMETRE), Length.of(5, km));
    }

    @Test
    void everyUnitOfEveryCatalogConvertsThereAndBack() {
        List<SystemOfUnits> systems = List.of(
                SI.SYSTEM,
                USCustomary.SYSTEM,
                Imperial.SYSTEM,
                CGS.SYSTEM,
                ESU.SYSTEM,
                EMU.SYSTEM,
                Gaussian.SYSTEM,
                Information.SYSTEM);
        int checked = 0;
        for (SystemOfUnits system : systems) {
            for (Unit<?> unit : system.units()) {
                UomUnit<?> jsr = Jsr.toJsr(unit);
                Unit<?> back = Jsr.fromJsr(jsr);
                String what = system.name() + " " + unit.symbol();
                assertSame(Units.engine(unit), jsr.engine(), what);
                assertEquals(unit.symbol(), jsr.getSymbol(), what);
                assertEquals(unit.name(), jsr.getName(), what);
                assertEquals(unit, back, what);
                assertEquals(jsr, Jsr.toJsr(back), what);
                checked++;
            }
        }
        assertTrue(checked > 250, "units checked: " + checked);
    }

    @Test
    void theJsrCatalogsConvertToTheNativeOnes() {
        for (Unit<?> unit : SI.SYSTEM.units()) {
            assertNotNull(org.tinygears.uom.jsr.systems.SI.SYSTEM.lookup(unit.symbol()).orElse(null), unit.symbol());
            UomUnit<?> jsr = org.tinygears.uom.jsr.systems.SI.SYSTEM.lookup(unit.symbol()).orElseThrow();
            assertEquals(unit, Jsr.fromJsr(jsr), unit.symbol());
        }
    }

    @Test
    void isTheUnitOfAQuantityOfOne() {
        UomUnit<?> unit = org.tinygears.uom.jsr.systems.SI.NEWTON;
        Unit<?> viaQuantity = Jsr.fromJsr(org.tinygears.uom.jsr.quantity.Untyped.of(1, unit), Untyped.class).unit();
        assertEquals(viaQuantity, Jsr.fromJsr(unit));
        assertEquals(unit, Jsr.toJsr(Untyped.of(1, SI.NEWTON)).getUnit());
        assertEquals(unit, Jsr.toJsr(SI.NEWTON));
    }

    @Test
    void aUnitOfAnotherImplementationIsRefused() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> Jsr.fromJsr(tech.units.indriya.unit.Units.METRE));
        assertEquals("unit of another implementation: tech.units.indriya.unit.BaseUnit", e.getMessage());
    }

    @Test
    void nullIsRefused() {
        assertEquals(
                "unit",
                assertThrows(NullPointerException.class, () -> Jsr.fromJsr((javax.measure.Unit<?>) null)).getMessage());
        assertEquals("unit", assertThrows(NullPointerException.class, () -> Jsr.toJsr((Unit<?>) null)).getMessage());
    }
}
