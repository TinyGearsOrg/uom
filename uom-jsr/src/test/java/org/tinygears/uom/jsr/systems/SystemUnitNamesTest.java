/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.jsr.systems;

import javax.measure.Unit;
import javax.measure.format.UnitFormat;
import javax.measure.spi.ServiceProvider;

import org.junit.jupiter.api.Test;

import org.tinygears.uom.jsr.BaseUnits;
import org.tinygears.uom.jsr.UomUnit;
import org.tinygears.uom.jsr.quantity.Acceleration;
import org.tinygears.uom.jsr.quantity.AccelerationFactory;
import org.tinygears.uom.jsr.quantity.Dimensionless;
import org.tinygears.uom.jsr.quantity.DimensionlessFactory;
import org.tinygears.uom.jsr.quantity.QuantityTypes;
import org.tinygears.uom.jsr.quantity.Speed;
import org.tinygears.uom.jsr.quantity.SpeedFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Every factory system unit has a symbol and a name; naming does not change formatting. */
class SystemUnitNamesTest {
    private static final UnitFormat FORMAT = ServiceProvider.current().getFormatService().getUnitFormat();

    @Test
    void aNamedSystemUnitIsItsOwnSystemUnit() {
        assertSame(SI.NEWTON, SI.NEWTON.getSystemUnit());
        assertSame(SI.METRE, SI.METRE.getSystemUnit());
        assertSame(SI.HERTZ, SI.HERTZ.getSystemUnit());
        assertEquals("N", SI.NEWTON.getSystemUnit().getSymbol());
        assertEquals("newton", SI.NEWTON.getSystemUnit().getName());
    }

    @Test
    void otherNamedUnitsTakeTheSystemUnitOfTheirType() {
        assertSame(SI.METRE, SI.KILOMETRE.getSystemUnit());
        assertSame(SI.PASCAL, SI.KILOPASCAL.getSystemUnit());
        assertEquals("pascal", SI.KILOPASCAL.getSystemUnit().getName());
        assertSame(SI.JOULE, SI.KILOJOULE.getSystemUnit());
        assertSame(SpeedFactory.SYSTEM_UNIT, USCustomary.KNOT.getSystemUnit());
        assertSame(SI.PASCAL, USCustomary.POUND_FORCE_PER_SQUARE_INCH.getSystemUnit());
        // a named unit of no standard type is labelled by its atoms
        assertEquals("kg/(m·s)", CGS.POISE.getSystemUnit().getSymbol());
        assertNull(CGS.POISE.getSystemUnit().getName());
    }

    @Test
    void unnamedUnitsHaveTheLabelOfTheirAtomsAsSystemUnit() {
        // A symbol built from other units is not a name: the product of two lengths is m^2, N·m is not J.
        assertEquals("m^2", SI.METRE.multiply(SI.METRE).getSystemUnit().getSymbol());
        assertEquals("kg·m/s^2", SI.NEWTON.multiply(2).getSystemUnit().getSymbol());
        assertEquals("kg·m^2/s^2", SI.NEWTON.multiply(SI.METRE).getSystemUnit().getSymbol());
        assertEquals("kg/(m·s^2)", SI.KILOPASCAL.multiply(2).getSystemUnit().getSymbol());
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void everySystemUnitIsNamedAndRoundTrips() {
        assertEquals(33, QuantityTypes.types().size());
        for (Class<?> type : QuantityTypes.types()) {
            Unit<?> unit = QuantityTypes.factory((Class) type).getSystemUnit();
            String name = type.getSimpleName();
            assertNotNull(unit.getSymbol(), name);
            assertNotNull(unit.getName(), name);
            assertEquals(unit, FORMAT.parse(FORMAT.format(unit)), name);
        }
    }

    @Test
    void explicitlyNamedUnits() {
        assertEquals("m/s", SpeedFactory.SYSTEM_UNIT.getSymbol());
        assertEquals("metre per second", SpeedFactory.SYSTEM_UNIT.getName());
        assertEquals("m/s^2", AccelerationFactory.SYSTEM_UNIT.getSymbol());
        assertEquals("metre per second squared", AccelerationFactory.SYSTEM_UNIT.getName());
        assertEquals("1", DimensionlessFactory.SYSTEM_UNIT.getSymbol());
        assertEquals("one", DimensionlessFactory.SYSTEM_UNIT.getName());
        assertSame(BaseUnits.ONE, DimensionlessFactory.SYSTEM_UNIT);
    }

    @Test
    void formatIsUnchanged() {
        assertEquals("m/s", FORMAT.format(SpeedFactory.SYSTEM_UNIT));
        assertEquals("m/s^2", FORMAT.format(AccelerationFactory.SYSTEM_UNIT));
        assertEquals("1", FORMAT.format(DimensionlessFactory.SYSTEM_UNIT));
        assertEquals(SpeedFactory.SYSTEM_UNIT, FORMAT.parse("m/s"));
        assertEquals(AccelerationFactory.SYSTEM_UNIT, FORMAT.parse("m/s^2"));
        assertEquals(DimensionlessFactory.SYSTEM_UNIT, FORMAT.parse("1"));
        assertEquals("10.0 m/s", Speed.of(10, SpeedFactory.SYSTEM_UNIT).toString());
        assertEquals("9.81 m/s^2", Acceleration.of(9.81, AccelerationFactory.SYSTEM_UNIT).toString());
        assertEquals("0.5", Dimensionless.of(0.5, DimensionlessFactory.SYSTEM_UNIT).toString());
        assertEquals(
                "10.0 m/s",
                ServiceProvider.current().getFormatService().getQuantityFormat()
                        .format(Speed.of(10, SpeedFactory.SYSTEM_UNIT)));
    }
}
