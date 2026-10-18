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

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.tinygears.uom.engine.Factor;
import org.tinygears.uom.quantity.Force;
import org.tinygears.uom.quantity.Length;
import org.tinygears.uom.quantity.QuantityTypes;
import org.tinygears.uom.quantity.Speed;
import org.tinygears.uom.quantity.Temperature;
import org.tinygears.uom.quantity.Time;
import org.tinygears.uom.systems.CGS;
import org.tinygears.uom.systems.Information;
import org.tinygears.uom.systems.SI;
import org.tinygears.uom.systems.USCustomary;
import org.tinygears.uom.systems.UnitSystems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The native unit and quantity formats, systems, {@code toSystem} and run-time {@code .units} files. */
class NativeFormatTest {
    private final UnitFormat units = UnitFormat.standard();
    private final QuantityFormat quantities = new QuantityFormat(units);

    @TempDir
    Path tmp;

    @Test
    void unitsFormatAndParse() {
        assertEquals("km", units.format(SI.KILOMETRE));
        assertEquals("km/h", units.format(SI.KILOMETRE.divide(SI.HOUR)));
        assertEquals("mHz", units.format(SI.HERTZ.divide(1000)));
        assertEquals("1000·min", units.format(SI.MINUTE.multiply(1000)));
        assertEquals("K + 5", units.format(SI.KELVIN.shift(5)));
        assertEquals(SI.DEGREE_CELSIUS, SI.KELVIN.shift(273.15));
        assertEquals(USCustomary.FOOT, SI.METRE.multiply(0.3048));
        assertEquals(SI.KILOMETRE, SI.METRE.multiply(new BigDecimal("1E3")));
        assertEquals(SI.METRE.scaledBy(Factor.PI), SI.METRE.scaledBy(Factor.PI).multiply(1));
        assertThrows(IllegalArgumentException.class, () -> SI.METRE.multiply(Double.NaN));
        assertEquals("m/s²", units.withSuperscripts(true).format(SI.METRE.divide(SI.SECOND.pow(2))));
        assertEquals("(m/s)/s", units.format(Speed.SYSTEM_UNIT.divide(SI.SECOND)));
        assertSame(SI.MINUTE, units.parse("min"));
        Unit<?> kmh = units.parse(" km/h ");
        assertEquals(Speed.class, kmh.type());
        assertEquals(SI.KILOMETRE.divide(SI.HOUR), kmh);
        assertEquals(Untyped.class, units.parse("kg·m/s").type());
        assertEquals("KiB", units.format(units.parse("KiB")));
        ParseException e = assertThrows(ParseException.class, () -> units.parse("km/foo"));
        assertEquals("unknown unit 'foo'", e.getMessage());
        assertEquals(3, e.position());
    }

    @Test
    void labelsBelongToTheFormat() {
        UnitFormat labelled = UnitFormat.standard();
        Unit<Speed> kmh = SI.KILOMETRE.divide(SI.HOUR).asType(Speed.class);
        labelled.label(kmh, "kph");
        assertEquals("kph", labelled.format(kmh));
        assertSame(kmh, labelled.parse("kph"));
        assertEquals("km/h", units.format(kmh));
        assertEquals(
                "label 'ft' is the symbol of foot",
                assertThrows(IllegalArgumentException.class, () -> labelled.label(SI.METRE, "ft")).getMessage());
    }

    @Test
    void quantitiesFormatAndParseTyped() {
        assertEquals("1.5 km", quantities.format(Length.of(1.5, SI.KILOMETRE)));
        assertEquals("1,5 km", quantities.withLocale(Locale.GERMANY).format(Length.of(1.5, SI.KILOMETRE)));
        Quantity<?> q = quantities.parse("90 km/h");
        Speed speed = q.as(Speed.class);
        assertEquals(25.0, speed.to(Speed.SYSTEM_UNIT).value());
        Quantity<?> d = quantities.withDecimals(MathContext.DECIMAL64).parse("1.50 km");
        assertEquals(new BigDecimal("1.50"), d.value());
        assertEquals(MathContext.DECIMAL64, d.mathContext());
        assertEquals(Length.class, d.unit().type());
        assertEquals(2.0, quantities.parse("2").value());
        assertEquals(1500.0, quantities.withLocale(Locale.GERMANY).withGrouping(true).parse("1.500 m").value());
        ParseException e = assertThrows(ParseException.class, () -> quantities.parse("1x km"));
        assertEquals("invalid number '1x'", e.getMessage());
        assertEquals(0, e.position());
    }

    @Test
    void decimalDigitsAreBoundedBeforeTheParse() {
        QuantityFormat decimals = quantities.withDecimals(MathContext.DECIMAL64);
        assertEquals(50_000, ((BigDecimal) decimals.parse("0.0" + "4".repeat(50_000) + " m").value()).precision());
        ParseException e = assertThrows(ParseException.class, () -> decimals.parse(" 4" + "0".repeat(50_000) + " m"));
        assertEquals("invalid number: more than 50000 digits", e.getMessage());
        assertEquals(1, e.position());
        // ten million digits would take minutes in the BigDecimal parse
        String huge = "9".repeat(10_000_000) + " m";
        assertEquals(
                "invalid number: more than 50000 digits",
                assertTimeoutPreemptively(
                        Duration.ofSeconds(2),
                        () -> assertThrows(ParseException.class, () -> decimals.parse(huge))).getMessage());
    }

    @Test
    void toSystemUsesThePreferredUnit() {
        assertEquals(Force.of(100000, CGS.DYNE), Force.of(1, SI.NEWTON).toSystem(CGS.SYSTEM));
        assertEquals(
                new BigDecimal("212"),
                Temperature.of(new BigDecimal("100"), SI.DEGREE_CELSIUS).toSystem(USCustomary.SYSTEM).value());
        assertSame(SI.METRE, SI.SYSTEM.unit(Length.class));
        assertEquals(
                "system SI has no unit for type Untyped",
                assertThrows(UnsupportedOperationException.class, () -> Untyped.of(1, SI.METRE).toSystem(SI.SYSTEM))
                        .getMessage());
        assertTrue(Information.SYSTEM.isBinaryPrefixable("B"));
    }

    // DR-24: a preferred unit taken from SI is not a member of the system, as on the JSR-385 side
    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void fallbackPreferredUnitsAreNotMembers() {
        int outside = 0;
        for (SystemOfUnits system : UnitSystems.all()) {
            for (Class<?> type : QuantityTypes.types()) {
                Unit<?> preferred = system.unit((Class) type);
                if (!system.units().contains(preferred)) {
                    assertEquals(Optional.empty(), system.unit(preferred.symbol()), system.name() + " " + type);
                    outside++;
                }
            }
        }
        assertEquals(156, outside);
        assertSame(SI.SECOND, USCustomary.SYSTEM.unit(Time.class));
        assertEquals(Optional.empty(), USCustomary.SYSTEM.unit("s"));
        assertEquals(1800.0, Time.of(0.5, SI.HOUR).toSystem(USCustomary.SYSTEM).value());
    }

    @Test
    void systemsLoadAtRunTime() throws IOException {
        Files.writeString(tmp.resolve("game.units"), "@system Game\nleague:league 3 mi\n@preferred Length league\n");
        SystemOfUnits game = SystemOfUnits.load(tmp.resolve("game.units"));
        assertEquals("Game", game.name());
        Unit<Length> league = game.unit(Length.class);
        assertEquals("league", league.symbol());
        assertSame(SI.SECOND, game.unit(org.tinygears.uom.quantity.Time.class));
        Quantity<?> q = new QuantityFormat(UnitFormat.standard().with(game)).parse("2 league");
        assertEquals(9656.064, q.as(Length.class).to(SI.METRE).value());
        SystemOfUnits fromReader = SystemOfUnits.load("x.units", new StringReader("y:yy 2 m\n"), null);
        assertEquals("x.units", fromReader.name());
        assertEquals(Length.class, fromReader.unit("y").orElseThrow().type());
        assertSame(SI.METRE, fromReader.unit(Length.class));
        DefinitionException e = assertThrows(
                DefinitionException.class,
                () -> SystemOfUnits.load("x.units", new StringReader("y:yy 2 foo\n"), null));
        assertEquals("x.units:1: unknown unit 'foo'", e.getMessage());
        assertEquals("x.units", e.source());
        assertEquals(1, e.line());
        SystemOfUnits shop = SystemOfUnits.load(
                "shop.units",
                new StringReader("@include common.units\nleagues 2 league\n"),
                name -> name.equals("common.units") ? new StringReader("league:league 3 mi\n") : null);
        assertEquals("[leagues]", shop.units().toString());
    }
}
