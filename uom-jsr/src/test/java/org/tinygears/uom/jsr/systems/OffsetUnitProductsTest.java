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

import java.math.BigDecimal;
import javax.measure.Quantity.Scale;
import javax.measure.Unit;

import org.junit.jupiter.api.Test;

import org.tinygears.uom.jsr.UomUnit;
import org.tinygears.uom.jsr.quantity.Length;
import org.tinygears.uom.jsr.quantity.Temperature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Units with an offset take part in products by their factor only (docs/NATIVE.md#offset-units). */
class OffsetUnitProductsTest {
    private static final UomUnitFormat FORMAT = UomUnitFormat.standard();
    private static final Unit<?> KELVIN_PER_METRE = SI.KELVIN.divide(SI.METRE);

    @Test
    void aProductOfAnOffsetUnitMeasuresDifferences() throws Exception {
        UomUnit<?> celsiusPerMetre = SI.DEGREE_CELSIUS.divide(SI.METRE);
        assertEquals(KELVIN_PER_METRE, celsiusPerMetre);
        assertEquals("°C/m", celsiusPerMetre.getSymbol());
        // °F is 5/9 K, so a gradient of 9 °F/m is 5 K/m.
        UomUnit<?> fahrenheitPerMetre = USCustomary.DEGREE_FAHRENHEIT.divide(SI.METRE);
        assertEquals(5.0, fahrenheitPerMetre.getConverterToAny(KELVIN_PER_METRE).convert(9.0), 1e-12);
        assertEquals(SI.KELVIN.pow(2), SI.DEGREE_CELSIUS.pow(2));
        assertEquals(SI.KELVIN.inverse(), SI.DEGREE_CELSIUS.inverse());
    }

    @Test
    void formulasWithAnOffsetUnitParseAndFormat() throws Exception {
        Unit<?> gradient = FORMAT.parse("°C/m");
        assertEquals(KELVIN_PER_METRE, gradient);
        assertEquals("°C/m", FORMAT.format(SI.DEGREE_CELSIUS.divide(SI.METRE)));
        assertEquals(gradient, FORMAT.parse(FORMAT.format(gradient)));
        // Specific heat in J/(kg·°C) is J/(kg·K); in J/(kg·°F) it is 9/5 of that.
        Unit<?> perCelsius = FORMAT.parse("J/(kg·°C)");
        assertEquals(FORMAT.parse("J/(kg·K)"), perCelsius);
        UomUnit<?> perFahrenheit = (UomUnit<?>) FORMAT.parse("J/(kg·°F)");
        assertEquals(1.8, perFahrenheit.getConverterToAny(perCelsius).convert(1.0), 1e-12);
    }

    @Test
    void aRelativeOperandOfAQuantityProductCountsByItsFactor() {
        Temperature rise = Temperature.of(20, SI.DEGREE_CELSIUS, Scale.RELATIVE);
        Length two = Length.of(2, SI.METRE);
        assertEquals(10.0, rise.divide(two).getValue().doubleValue(), 1e-12);
        assertEquals(KELVIN_PER_METRE, rise.divide(two).getUnit());
        assertEquals(40.0, two.multiply(rise).getValue().doubleValue(), 1e-12);
        assertEquals(SI.KELVIN.multiply(SI.METRE), two.multiply(rise).getUnit());
        assertEquals(
                0.5,
                Temperature.of(2, SI.DEGREE_CELSIUS, Scale.RELATIVE).inverse().getValue().doubleValue(),
                1e-12);
        // An ABSOLUTE quantity is a point: it goes through the offset first (293.15 K).
        Temperature point = Temperature.of(20, SI.DEGREE_CELSIUS);
        assertEquals(146.575, point.divide(two).getValue().doubleValue(), 1e-12);
        assertEquals(586.3, two.multiply(point).getValue().doubleValue(), 1e-12);
        assertEquals(1 / 293.15, point.inverse().getValue().doubleValue(), 1e-15);
    }

    @Test
    void decimalQuantitiesFollowTheSameRules() {
        Temperature rise = Temperature.of(new BigDecimal("20"), SI.DEGREE_CELSIUS, Scale.RELATIVE);
        Length two = Length.of(new BigDecimal("2"), SI.METRE);
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) rise.divide(two).getValue()));
        assertEquals(KELVIN_PER_METRE, rise.divide(two).getUnit());
        assertEquals(0, new BigDecimal("40").compareTo((BigDecimal) two.multiply(rise).getValue()));
        assertEquals(
                0,
                new BigDecimal("0.5").compareTo(
                        (BigDecimal) Temperature.of(new BigDecimal("2"), SI.DEGREE_CELSIUS, Scale.RELATIVE).inverse()
                                .getValue()));
        Temperature point = Temperature.of(new BigDecimal("20"), SI.DEGREE_CELSIUS);
        assertEquals(0, new BigDecimal("146.575").compareTo((BigDecimal) point.divide(two).getValue()));
        assertEquals(0, new BigDecimal("586.3").compareTo((BigDecimal) two.multiply(point).getValue()));
        assertTrue(rise.divide(two).getValue() instanceof BigDecimal);
    }
}
