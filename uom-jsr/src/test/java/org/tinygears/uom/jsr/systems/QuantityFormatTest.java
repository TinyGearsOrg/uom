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

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.ResourceBundle;
import java.util.Set;
import javax.measure.Quantity;
import javax.measure.format.MeasurementParseException;
import javax.measure.quantity.Dimensionless;
import javax.measure.quantity.Frequency;
import javax.measure.quantity.Length;
import javax.measure.quantity.Speed;
import javax.measure.spi.QuantityFactory;

import org.junit.jupiter.api.Test;
import tech.units.indriya.quantity.Quantities;
import tech.units.indriya.unit.Units;

import org.tinygears.uom.jsr.BaseUnits;
import org.tinygears.uom.jsr.UomUnit;
import org.tinygears.uom.jsr.quantity.QuantityTypes;
import org.tinygears.uom.jsr.quantity.UntypedDoubleQuantity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuantityFormatTest {
    private final UomUnitFormat units = UomUnitFormat.standard();
    private final UomQuantityFormat format = new UomQuantityFormat(units);

    @Test
    void typedQuantities() {
        Quantity<?> metre = format.parse("1 m");
        assertInstanceOf(Length.class, metre);
        assertEquals(1.0, metre.getValue());
        assertEquals(SI.METRE, metre.getUnit());
        assertSame(Quantity.Scale.ABSOLUTE, metre.getScale());

        Quantity<?> speed = format.parse("2.5 km/h");
        assertInstanceOf(Speed.class, speed);
        assertEquals(2.5, speed.getValue());
        assertEquals(units.parse("km/h"), speed.getUnit());

        Quantity<?> frequency = format.parse("1e3 Hz");
        assertInstanceOf(Frequency.class, frequency);
        assertEquals(1000.0, frequency.getValue());
        assertEquals(SI.HERTZ, frequency.getUnit());

        assertEquals(SI.METRE, format.parse("  -3   m  ").getUnit());
        assertEquals(-3.0, format.parse("  -3   m  ").getValue());
    }

    @Test
    void untypedQuantity() {
        Quantity<?> q = format.parse("1 rad/s");
        assertInstanceOf(UntypedDoubleQuantity.class, q);
        assertEquals(SI.RADIAN.divide(SI.SECOND), q.getUnit());
    }

    @Test
    void numberOnlyIsDimensionless() {
        Quantity<?> q = format.parse("5");
        assertInstanceOf(Dimensionless.class, q);
        assertEquals(5.0, q.getValue());
        assertEquals(BaseUnits.ONE, q.getUnit());
    }

    private void assertFails(String text, int position) {
        MeasurementParseException e = assertThrows(MeasurementParseException.class, () -> format.parse(text), text);
        assertEquals(position, e.getPosition(), text);
        assertEquals(text, e.getParsedString());
        assertTrue(e.getMessage() != null && !e.getMessage().isEmpty(), text);
    }

    @Test
    void errors() {
        assertFails("abc m", 0);
        assertFails("", 0);
        assertFails("  ", 2);
        assertFails("1 foo", 2);
        assertFails("1 m extra junk", 4);
        assertFails(" 1 m/", 5);
        assertThrows(NullPointerException.class, () -> format.parse(null));
    }

    @Test
    void parsePosition() {
        ParsePosition pos = new ParsePosition(3);
        Quantity<?> q = format.parse("x: 2 kg", pos);
        assertEquals(2.0, q.getValue());
        assertEquals(SI.KILOGRAM, q.getUnit());
        assertEquals(7, pos.getIndex());

        ParsePosition bad = new ParsePosition(3);
        MeasurementParseException e = assertThrows(
                MeasurementParseException.class,
                () -> format.parse("x: 2 kgg", bad));
        assertEquals(5, e.getPosition());
        assertEquals(5, bad.getErrorIndex());
        assertEquals(3, bad.getIndex());
    }

    @Test
    void formatRoundTrip() throws IOException {
        Random random = new Random(7);
        for (int i = 0; i < 30; i++) {
            UomUnit<?> unit = SI.UNITS.get(random.nextInt(SI.UNITS.size()));
            double value = random.nextDouble(-1e6, 1e6);
            Quantity<?> q = format.parse(value + " " + unit);
            String text = format.format(q);
            assertEquals(value + " " + unit.getSymbol(), text);
            Quantity<?> back = format.parse(text);
            assertEquals(q.getValue(), back.getValue(), text);
            assertEquals(q.getUnit(), back.getUnit(), text);
            assertEquals(q.getClass(), back.getClass(), text);
        }
        StringBuilder sb = new StringBuilder();
        assertSame(sb, format.format(format.parse("3 N"), sb));
        assertEquals("3.0 N", sb.toString());
        assertFalse(format.isLocaleSensitive());
    }

    @Test
    void scaledUnitRoundTrip() {
        Quantity<?> q = format.parse("1 2 m");
        assertEquals(SI.METRE.multiply(2), q.getUnit());
        assertEquals("1.0 2·m", format.format(q));
        assertEquals(q, format.parse(format.format(q)));
        Quantity<?> shifted = UntypedDoubleQuantity.of(1, BaseUnits.KELVIN.shift(5));
        assertEquals("1.0 K + 5", format.format(shifted));
        assertEquals(BaseUnits.KELVIN.shift(5), format.parse("1.0 K + 5").getUnit());
        assertThrows(
                IllegalArgumentException.class,
                () -> format.format(UntypedDoubleQuantity.of(1, BaseUnits.KELVIN.multiply(-1))));
    }

    @Test
    void everySymbolParsesAsOneOfIt() {
        for (UomUnit<?> unit : SI.UNITS) {
            Quantity<?> q = format.parse("1 " + unit);
            assertEquals(unit, q.getUnit(), unit.toString());
            assertEquals(1.0, q.getValue().doubleValue(), unit.toString());
        }
    }

    @Test
    void decimalFormatsAsBigDecimalToStringAndParsesAsDouble() {
        Quantity<?> decimal = org.tinygears.uom.jsr.quantity.Length.of(new BigDecimal("1.50"), SI.KILOMETRE);
        assertEquals("1.50 km", format.format(decimal));
        // D12: the default format parses to double; withDecimals keeps the digits (DecimalParseTest).
        Quantity<?> parsed = format.parse("1.50 km");
        assertEquals(1.5, parsed.getValue());
        assertEquals(SI.KILOMETRE, parsed.getUnit());
    }

    @Test
    void numbersAreDecimalOnly() {
        for (String text : List.of("1f m", "1d m", "1.5F m", "0x1p3 m", "1e m", "1,5 m")) {
            MeasurementParseException e = assertThrows(MeasurementParseException.class, () -> format.parse(text), text);
            String number = text.substring(0, text.indexOf(' '));
            assertEquals(
                    number.contains(",") ? "grouping separator not allowed: ','" : "invalid number '" + number + "'",
                    e.getMessage());
            assertEquals(number.contains(",") ? 1 : 0, e.getPosition(), text);
        }
        assertEquals(1000.0, format.parse("+1e3 m").getValue());
        assertEquals(0.5, format.parse(".5 m").getValue());
        assertEquals(5.0, format.parse("5. m").getValue());
        assertEquals(Double.NaN, format.parse("NaN m").getValue());
        assertEquals(Double.NEGATIVE_INFINITY, format.parse("-Infinity m").getValue());
    }

    @Test
    void decimalsSwitchOffAndCheckThePrecision() {
        UomQuantityFormat decimals = format.withDecimals(MathContext.DECIMAL64);
        assertEquals(new BigDecimal("1.50"), decimals.parse("1.50 m").getValue());
        UomQuantityFormat doubles = decimals.withDecimals(null);
        assertNull(doubles.getDecimalContext());
        assertEquals(1.5, doubles.parse("1.50 m").getValue());
        assertEquals(format, doubles);
        assertEquals(
                "precision out of range: 50001",
                assertThrows(ArithmeticException.class, () -> format.withDecimals(new MathContext(50_001)))
                        .getMessage());
        assertEquals(50_000, format.withDecimals(new MathContext(50_000)).getDecimalContext().getPrecision());
    }

    @Test
    void quantitiesOfAnotherImplementationAreRefused() {
        Quantity<?> foreign = Quantities.getQuantity(5, Units.METRE);
        assertEquals(
                "foreign unit implementation tech.units.indriya.unit.BaseUnit can not be formatted: m",
                assertThrows(IllegalArgumentException.class, () -> format.format(foreign)).getMessage());
    }

    @Test
    void aFailingFactoryIsLoggedAndItsTypeParsesUntyped() {
        List<String> logged = new ArrayList<>();
        System.Logger logger = new System.Logger() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            public boolean isLoggable(Level level) {
                return true;
            }

            @Override
            public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
                logged.add(level + " " + msg + " (" + thrown.getMessage() + ")");
            }

            @Override
            public void log(Level level, ResourceBundle bundle, String format, Object... params) {
                logged.add(level + " " + format);
            }
        };
        Map<UomUnit<?>, QuantityFactory<?>> factories = UomQuantityFormat.Factories
                .build(List.of(javax.measure.quantity.Length.class, javax.measure.quantity.Time.class), type -> {
                    if (type == javax.measure.quantity.Time.class) {
                        throw new IllegalStateException("broken");
                    }
                    return QuantityTypes.factory(javax.measure.quantity.Length.class);
                }, logger);
        assertEquals(Set.of(SI.METRE), factories.keySet());
        assertEquals(
                List.of(
                        "WARNING no quantity factory for javax.measure.quantity.Time; its quantities parse as untyped (broken)"),
                logged);
    }
}
