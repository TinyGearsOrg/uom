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
import java.math.BigInteger;
import java.text.ParsePosition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.measure.MetricPrefix;
import javax.measure.Unit;
import javax.measure.format.MeasurementParseException;

import org.junit.jupiter.api.Test;
import tech.units.indriya.unit.Units;

import org.tinygears.uom.engine.Factor;
import org.tinygears.uom.jsr.BaseUnits;
import org.tinygears.uom.jsr.UomUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnitFormatTest {
    private final UomUnitFormat format = UomUnitFormat.standard();

    private UomUnit<?> parse(String text) {
        return (UomUnit<?>) format.parse(text);
    }

    // Read, never written: the Greek mu (U+03BC) for the micro sign, l for the litre (DESIGN DR-26).
    @Test
    void greekMuAndLowercaseLitreParse() {
        assertSame(SI.MICROMETRE, parse("\u03BCm"));
        assertEquals("µm", format.format(parse("\u03BCm")));
        assertEquals(SI.METRE.prefix(MetricPrefix.MICRO).divide(SI.SECOND), parse("\u03BCm/s"));
        assertSame(SI.LITRE, parse("l"));
        assertSame(SI.MILLILITRE, parse("ml"));
        assertEquals("µL", format.format(parse("\u03BCl")));
        assertEquals(SI.LITRE.prefix(MetricPrefix.DECA), parse("dal"));
        assertEquals("daL", parse("dal").getSymbol());
        // exact symbols win: mol and Gal are not prefixed litres
        assertSame(SI.MOLE, parse("mol"));
        assertSame(CGS.GALILEO, parse("Gal"));
        for (String text : new String[] { "\u03BC", "Kil", "kl2", "\u03BCmol\u03BC" }) {
            assertThrows(MeasurementParseException.class, () -> format.parse(text), text);
        }
        assertEquals(SI.MOLE.prefix(MetricPrefix.MICRO), parse("\u03BCmol"));
    }

    @Test
    void everyCatalogUnitRoundTrips() {
        assertEquals(SystemsCatalogTest.members("si.units").size(), SI.UNITS.size());
        for (UomUnit<?> unit : SI.UNITS) {
            assertEquals(unit, format.parse(unit.toString()), unit.toString());
            assertEquals(unit, format.parse(format.format(unit)), unit.toString());
        }
        for (UomUnit<?> unit : List.of(
                SI.SQUARE_METRE,
                SI.CUBIC_METRE,
                SI.OHM,
                SI.DEGREE_CELSIUS,
                SI.MICROMETRE,
                SI.KILOOHM,
                SI.MEGAELECTRONVOLT)) {
            assertEquals(unit, format.parse(unit.getSymbol()), unit.getSymbol());
            assertEquals(unit.getSymbol(), format.format(unit));
        }
    }

    private void assertPrefixed(String text, UomUnit<?> unit, MetricPrefix prefix, String factor, String name) {
        UomUnit<?> parsed = parse(text);
        assertEquals(unit.prefix(prefix), parsed, text);
        assertEquals(unit.atoms(), parsed.atoms(), text);
        assertEquals(
                unit.toSystemConverter().factor().multiply(Factor.decimal(factor)),
                parsed.toSystemConverter().factor(),
                text);
        assertEquals(text, parsed.getSymbol());
        assertEquals(name, parsed.getName());
    }

    @Test
    void prefixResolution() {
        assertPrefixed("Gm", SI.METRE, MetricPrefix.GIGA, "1e9", "gigametre");
        assertPrefixed("fs", SI.SECOND, MetricPrefix.FEMTO, "1e-15", "femtosecond");
        assertPrefixed("dam", SI.METRE, MetricPrefix.DECA, "10", "decametre");
        assertPrefixed("ZHz", SI.HERTZ, MetricPrefix.ZETTA, "1e21", "zettahertz");
        assertPrefixed("µmol", SI.MOLE, MetricPrefix.MICRO, "1e-6", "micromole");
        assertPrefixed("Mg", SI.GRAM, MetricPrefix.MEGA, "1e6", "megagram");
        assertPrefixed("qΩ", SI.OHM, MetricPrefix.QUECTO, "1e-30", "quectoohm");
        assertSame(SI.HECTOPASCAL, parse("hPa"));
        assertSame(SI.MILLIMOLE, parse("mmol"));
        assertSame(SI.KILOOHM, parse("kΩ"));
        assertSame(SI.PICOMETRE, parse("pm"));
        assertEquals(SI.PASCAL.prefix(MetricPrefix.HECTO), parse("hPa"));
        assertEquals(SI.MOLE.prefix(MetricPrefix.MILLI), parse("mmol"));
        assertEquals(SI.OHM.prefix(MetricPrefix.KILO), parse("kΩ"));
    }

    @Test
    void prefixedConstantsOfAllSiUnitsParse() {
        assertSame(SI.MILLIKELVIN, parse("mK"));
        assertSame(SI.MILLICANDELA, parse("mcd"));
        assertSame(SI.KILODALTON, parse("kDa"));
        assertSame(SI.MEGATONNE, parse("Mt"));
        assertEquals(SI.KELVIN.prefix(MetricPrefix.MILLI), parse("mK"));
        assertEquals(SI.CANDELA.prefix(MetricPrefix.MILLI), parse("mcd"));
        assertEquals(SI.DALTON.prefix(MetricPrefix.KILO), parse("kDa"));
        assertEquals(SI.KILOGRAM.multiply(1_000_000_000), parse("Mt"));
        for (UomUnit<?> unit : List.of(
                SI.MILLIKELVIN,
                SI.MICROKELVIN,
                SI.NANOKELVIN,
                SI.MILLICANDELA,
                SI.KILOCANDELA,
                SI.MILLIKATAL,
                SI.MICROKATAL,
                SI.NANOKATAL,
                SI.KILOKATAL,
                SI.MILLILUMEN,
                SI.KILOLUMEN,
                SI.MILLISTERADIAN,
                SI.KILODALTON,
                SI.MEGADALTON,
                SI.KILOTONNE,
                SI.MEGATONNE,
                SI.GIGATONNE)) {
            assertEquals(unit.getSymbol(), format.format(unit));
            assertSame(unit, parse(format.format(unit)));
        }
        assertPrefixed("GK", SI.KELVIN, MetricPrefix.GIGA, "1e9", "gigakelvin");
        assertPrefixed("Tt", SI.TONNE, MetricPrefix.TERA, "1e12", "teratonne");
    }

    @Test
    void prefixedPowersMayUseCaretOrSuperscript() {
        assertEquals(SI.KILOMETRE.pow(2), parse("km^2"));
        assertEquals(SI.CENTIMETRE.pow(3), parse("cm^3"));
        // km² is (km)^2; m² is a catalog symbol of its own and stays the square metre.
        assertEquals(SI.KILOMETRE.pow(2), parse("km²"));
        assertSame(SI.SQUARE_METRE, parse("m²"));
    }

    @Test
    void exactMatchesWin() {
        assertSame(SI.MINUTE, parse("min"));
        assertSame(SI.CANDELA, parse("cd"));
        assertSame(SI.TESLA, parse("T"));
        assertSame(SI.TONNE, parse("t"));
        assertSame(SI.HOUR, parse("h"));
        assertSame(SI.HECTARE, parse("ha"));
        assertSame(SI.DAY, parse("d"));
        assertSame(SI.PASCAL, parse("Pa"));
        assertSame(SI.MILLIMETRE, parse("mm"));
    }

    @Test
    void expressions() {
        assertEquals(SI.METRE.divide(SI.SECOND), parse("m/s"));
        assertEquals(SI.NEWTON, parse("kg·m/s^2"));
        assertEquals(SI.NEWTON, parse("kg m / s^2"));
        assertEquals(SI.METRE.divide(SI.SECOND).pow(2), parse("(m/s)^2"));
        assertEquals(SI.KILOMETRE.divide(SI.HOUR), parse("km/h"));
        assertEquals(BaseUnits.ONE.divide(SI.SECOND), parse("1/s"));
        assertEquals(SI.METRE.pow(2).multiply(SI.KILOGRAM), parse("m^2·kg"));
        assertEquals(SI.NEWTON, parse("  N \t"));
    }

    @Test
    void derivedLabelsParseBack() {
        for (UomUnit<?> unit : List.of(
                BaseUnits.METRE.divide(BaseUnits.SECOND).pow(2),
                BaseUnits.KILOGRAM.divide(BaseUnits.METRE.multiply(BaseUnits.SECOND)),
                BaseUnits.METRE.pow(2).multiply(BaseUnits.KILOGRAM))) {
            assertEquals(unit, parse(unit.getSymbol()), unit.getSymbol());
        }
    }

    private void assertFails(String text, int position) {
        MeasurementParseException e = assertThrows(MeasurementParseException.class, () -> format.parse(text), text);
        assertEquals(position, e.getPosition(), text);
        assertEquals(text, e.getParsedString());
        assertTrue(e.getMessage() != null && !e.getMessage().isEmpty(), text);
    }

    @Test
    void errors() {
        assertFails("foo", 0);
        assertFails("m/", 2);
        assertFails("", 0);
        assertFails("   ", 3);
        assertFails("kk m", 0);
        assertFails("kmin", 0);
        assertFails("ºC", 0);
        assertFails("m/foo", 2);
        assertFails("  m*x", 4);
        assertFails("m)", 1);
        assertThrows(NullPointerException.class, () -> format.parse(null));
    }

    @Test
    void parsePosition() {
        ParsePosition pos = new ParsePosition(4);
        assertEquals(SI.METRE.divide(SI.SECOND), format.parse("abc m/s", pos));
        assertEquals(7, pos.getIndex());
        assertEquals(-1, pos.getErrorIndex());

        ParsePosition bad = new ParsePosition(2);
        MeasurementParseException e = assertThrows(MeasurementParseException.class, () -> format.parse("x m/q", bad));
        assertEquals(4, e.getPosition());
        assertEquals(4, bad.getErrorIndex());
        assertEquals(2, bad.getIndex());

        ParsePosition past = new ParsePosition(9);
        assertThrows(MeasurementParseException.class, () -> format.parse("m", past));
        assertEquals(1, past.getErrorIndex());
    }

    @Test
    void label() {
        UomUnitFormat f = UomUnitFormat.standard();
        UomUnit<?> furlong = SI.METRE.multiply(201.168);
        f.label(furlong, "fur");
        assertEquals(furlong, f.parse("fur"));
        assertEquals("fur", f.format(furlong));
        assertEquals(furlong.divide(SI.SECOND), f.parse("fur/s"));
        f.label(furlong, "furlong");
        assertEquals("furlong", f.format(furlong));
        f.label(SI.METRE, "m");
        assertEquals("m", f.format(SI.METRE));
        f.label(SI.KILOMETRE, "klick");
        assertEquals("klick", f.format(SI.KILOMETRE));
        assertEquals("km", format.format(SI.KILOMETRE));
        for (String bad : List.of(
                "",
                " ",
                "a b",
                "a*b",
                "a/b",
                "a(b",
                "a)",
                "a^2",
                "a|b",
                "a=b",
                "a#",
                "[a]",
                "a!",
                "a~",
                "a@",
                "a,b",
                "a·b",
                "\tx",
                "2x",
                "pi",
                "a-b",
                "a+",
                "-a")) {
            assertThrows(IllegalArgumentException.class, () -> f.label(furlong, bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> f.label(furlong, "s"));
        assertThrows(NullPointerException.class, () -> f.label(furlong, null));
        assertThrows(NullPointerException.class, () -> f.label(null, "x"));
    }

    @Test
    void relabellingAnAliasMovesIt() {
        UomUnitFormat f = UomUnitFormat.standard();
        UomUnit<?> a = SI.METRE.multiply(3);
        UomUnit<?> b = SI.METRE.multiply(5);
        f.label(a, "x");
        f.label(b, "x");
        assertEquals(b, f.parse("x"));
        assertEquals("x", f.format(b));
        assertFalse(f.format(a).equals("x"));
    }

    @Test
    void formatting() throws IOException {
        assertEquals("km", format.format(SI.KILOMETRE));
        StringBuilder sb = new StringBuilder("x=");
        assertSame(sb, format.format(SI.NEWTON, sb));
        assertEquals("x=N", sb.toString());
        assertThrows(NullPointerException.class, () -> format.format((Unit<?>) null));
        assertFalse(format.isLocaleSensitive());
    }

    private void assertFormats(String text, UomUnit<?> unit) {
        assertNull(unit.getSymbol());
        assertEquals(text, format.format(unit));
        assertEquals(unit, format.parse(text), text);
    }

    @Test
    void unlabelledUnitsFormatFaithfully() {
        assertFormats("km", SI.METRE.multiply(1000));
        assertFormats("2·m", SI.METRE.multiply(2));
        assertFormats("1|1000·m/s", SI.METRE.divide(SI.SECOND).divide(1000));
        assertFormats("3|2·kg/(m·s)", SI.KILOGRAM.divide(SI.METRE.multiply(SI.SECOND)).multiply(new BigDecimal("1.5")));
        assertFormats("2·1/s", BaseUnits.ONE.divide(SI.SECOND).multiply(2));
        assertFormats("3·1", BaseUnits.ONE.multiply(3));
        assertFormats("deg", SI.RADIAN.scaledBy(Factor.PI.divide(Factor.ofLong(180))));
        assertEquals(SI.DEGREE, SI.RADIAN.scaledBy(Factor.PI.divide(Factor.ofLong(180))));
        assertFormats("1|90·pi·rad", SI.RADIAN.scaledBy(Factor.PI.divide(Factor.ofLong(90))));
        assertFormats("pi^-1·rad", SI.RADIAN.scaledBy(Factor.ONE.divide(Factor.PI)));
        assertFormats("5·pi^2·m^2", SI.METRE.pow(2).scaledBy(Factor.PI.pow(2).multiply(Factor.ofLong(5))));
        assertFormats("m", SI.METRE.multiply(2).divide(2));
    }

    @Test
    void offsetUnitsFormatAsShiftedFormulas() {
        assertFormats("K + 5", BaseUnits.KELVIN.shift(5));
        assertFormats("2·K + 136.575", SI.DEGREE_CELSIUS.multiply(2));
    }

    @Test
    void unitsWithoutFaithfulFormAreRefused() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> format.format(SI.DEGREE_CELSIUS.multiply(-2)));
        assertEquals("unit can not be formatted: K (factor -2, offset 5463/20)", e.getMessage());
        e = assertThrows(
                IllegalArgumentException.class,
                () -> format.format(SI.METRE.multiply(BigInteger.TWO.pow(60_000))));
        assertTrue(e.getMessage().startsWith("unit can not be formatted: m (factor "), e.getMessage().substring(0, 40));
        assertThrows(IllegalArgumentException.class, () -> format.format(SI.METRE.multiply(-2)));
        assertThrows(IllegalArgumentException.class, () -> format.format(SI.METRE.pow(2).multiply(2).root(2)));
    }

    @Test
    void randomUnitsRoundTrip() {
        Random random = new Random(11);
        MetricPrefix[] prefixes = MetricPrefix.values();
        TreeMap<String, Integer> branches = new TreeMap<>();
        for (int i = 0; i < 300; i++) {
            UomUnit<?> u = SI.UNITS.get(random.nextInt(SI.UNITS.size()));
            for (int ops = 1 + random.nextInt(3); ops > 0; ops--) {
                UomUnit<?> current = u;
                try {
                    u = switch (random.nextInt(6)) {
                        case 0 -> current.multiply(
                                random.nextInt(12) == 0
                                        ? -random.nextInt(1, 50)
                                        : BigDecimal.valueOf(random.nextInt(1, 100_000), random.nextInt(6)));
                        case 1 -> current.divide(random.nextInt(1, 2000));
                        case 2 -> current.pow(random.nextInt(-3, 4));
                        case 3 -> current.multiply(SI.UNITS.get(random.nextInt(SI.UNITS.size())));
                        // Round factors often land on a catalog unit (km, min, ...).
                        case 4 -> current.multiply(List.of(10, 100, 1000, 60, 3600).get(random.nextInt(5)));
                        // A prefix on a non-prefixable symbol gives a label such as kmin, which is not parseable.
                        default -> current.getSymbol() == null || SI.PREFIXABLE.contains(current.getSymbol())
                                ? current.prefix(prefixes[random.nextInt(prefixes.length)])
                                : current;
                    };
                } catch (RuntimeException e) {
                    // a fractional root or an exponent overflow
                }
            }
            UomUnit<?> unit = u;
            String branch;
            if (unit.getSymbol() != null) {
                branch = "symbol";
            } else if (SI.UNITS.contains(unit)) {
                branch = "catalog";
            } else if (unit.toSystemConverter().factor().signum() < 0) {
                branch = "refused";
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> format.format(unit));
                assertTrue(
                        e.getMessage().startsWith("unit can not be formatted: " + unit.getSystemUnit() + " (factor "));
                branches.merge(branch, 1, Integer::sum);
                continue;
            } else {
                branch = "formula";
            }
            branches.merge(branch, 1, Integer::sum);
            String text = format.format(unit);
            assertEquals(unit, format.parse(text), branch + ": " + text);
        }
        assertTrue(branches.getOrDefault("symbol", 0) >= 60, branches::toString);
        assertTrue(branches.getOrDefault("catalog", 0) >= 3, branches::toString);
        assertTrue(branches.getOrDefault("formula", 0) >= 120, branches::toString);
        assertTrue(branches.getOrDefault("refused", 0) >= 5, branches::toString);
    }

    private void assertRoundTrips(UomUnit<?> unit, String notText) {
        String text = format.format(unit);
        assertFalse(text.equals(notText), text);
        assertEquals(unit, format.parse(text), text);
    }

    @Test
    void symbolsThatParseAsAnotherUnitAreNotUsed() {
        assertRoundTrips(SI.DAY.prefix(MetricPrefix.CENTI), "cd");
        assertRoundTrips(SI.METRE.prefix(MetricPrefix.ATTO).prefix(MetricPrefix.DECI), "dam");
        assertRoundTrips(SI.DAY.prefix(MetricPrefix.ATTO).prefix(MetricPrefix.RONTO), "rad");
        assertRoundTrips(SI.TONNE.prefix(MetricPrefix.ATTO).prefix(MetricPrefix.KILO), "kat");
        assertEquals("7·m", format.format(SI.METRE.multiply(7).named("km", null)));
        assertEquals("km", format.format(SI.KILOMETRE));
        assertEquals("d", format.format(SI.DAY));
    }

    @Test
    void aLabelThatReadsAsAnotherUnitIsRejected() {
        UomUnitFormat f = UomUnitFormat.standard();
        UomUnit<?> furlong = SI.METRE.multiply(201.168);
        // "Gm" is the gigametre as a metric prefix on a prefixable unit, "km" an exact symbol.
        IllegalArgumentException prefixed = assertThrows(IllegalArgumentException.class, () -> f.label(furlong, "Gm"));
        assertEquals("label 'Gm' is the symbol of gigametre", prefixed.getMessage());
        assertThrows(IllegalArgumentException.class, () -> f.label(furlong, "km"));
        assertEquals("25146|125·m", f.format(furlong), "an unlabelled unit is still formatted by its definition");
        // The unit that a label reads as may carry it.
        UomUnit<?> gigametre = (UomUnit<?>) f.parse("Gm");
        f.label(gigametre, "Gm");
        assertEquals("Gm", f.format(gigametre));
        assertEquals(gigametre, f.parse("Gm"));
    }

    @Test
    void everyPrefixedUnitRoundTripsOrIsRefused() {
        MetricPrefix[] prefixes = MetricPrefix.values();
        List<UomUnit<?>> units = new ArrayList<>();
        for (UomUnit<?> unit : SI.UNITS) {
            for (MetricPrefix p : prefixes) {
                units.add(unit.prefix(p));
            }
            for (int n : new int[] { -3, -2, 2, 3 }) {
                for (MetricPrefix p : List.of(
                        MetricPrefix.KILO,
                        MetricPrefix.MILLI,
                        MetricPrefix.DECA,
                        MetricPrefix.CENTI,
                        MetricPrefix.MICRO)) {
                    try {
                        units.add(unit.pow(n).prefix(p));
                    } catch (RuntimeException e) {
                        // offset units have no powers
                    }
                }
            }
        }
        Random random = new Random(3);
        for (int i = 0; i < 30; i++) {
            UomUnit<?> unit = SI.UNITS.get(random.nextInt(SI.UNITS.size()));
            for (MetricPrefix p : prefixes) {
                for (MetricPrefix q : prefixes) {
                    units.add(unit.prefix(p).prefix(q));
                }
            }
        }
        int roundTrips = 0;
        int refused = 0;
        List<String> bad = new ArrayList<>();
        for (UomUnit<?> unit : units) {
            String text;
            try {
                text = format.format(unit);
            } catch (IllegalArgumentException e) {
                refused++;
                continue;
            }
            try {
                if (unit.equals(format.parse(text))) {
                    roundTrips++;
                } else {
                    bad.add("mismatch " + text);
                }
            } catch (MeasurementParseException e) {
                bad.add("unparseable " + text);
            }
        }
        assertEquals(List.of(), bad);
        assertTrue(units.size() > 20_000, "units: " + units.size());
        // Prefixed offset units (m°C) format as shifted formulas, so nothing is refused.
        assertEquals(0, refused);
        assertEquals(units.size(), roundTrips);
    }

    @Test
    void concurrentParsing() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 20; i++) {
                        for (UomUnit<?> unit : SI.UNITS) {
                            assertEquals(unit, format.parse(unit.toString()));
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unitsOfAnotherImplementationAreRefused() {
        assertEquals(
                "foreign unit implementation tech.units.indriya.unit.TransformedUnit can not be formatted: m*1000",
                assertThrows(IllegalArgumentException.class, () -> format.format(Units.METRE.multiply(1000)))
                        .getMessage());
    }

    @Test
    void labelsBelongToTheirFormatAndNeverChangeToString() {
        Unit<?> kmh = SI.METRE.multiply(1000).divide(SI.SECOND.multiply(3600));
        UomUnitFormat labelled = UomUnitFormat.standard();
        UomUnitFormat before = labelled.withSuperscripts(false);
        labelled.label(kmh, "kmh");
        UomUnitFormat after = labelled.withSuperscripts(false);
        assertEquals("kmh", labelled.format(kmh));
        assertEquals(kmh, labelled.parse("kmh"));
        assertEquals("kmh", after.format(kmh));
        // copies taken before, other instances and toString do not see it
        assertEquals("5|18·m/s", before.format(kmh));
        assertEquals("5|18·m/s", format.format(kmh));
        assertEquals("5|18·m/s", UomUnitFormat.standard().format(kmh));
        assertEquals("5|18·m/s", kmh.toString());
        // the service's format is shared, still not toString
        javax.measure.format.UnitFormat service = new UomServiceProvider().getFormatService().getUnitFormat();
        assertSame(service, new UomServiceProvider().getFormatService().getUnitFormat());
        UomUnit<?> furlongPerFortnight = SI.METRE.multiply(201.168).divide(SI.SECOND.multiply(1_209_600));
        service.label(furlongPerFortnight, "fpf");
        assertEquals("fpf", new UomServiceProvider().getFormatService().getUnitFormat().format(furlongPerFortnight));
        assertFalse(furlongPerFortnight.toString().contains("fpf"));
    }

    @Test
    void derivedUnitTextsAreCached() {
        // a unit without a symbol or a short form was formatted by a search of about 100 µs each time
        Unit<?> kmh = SI.METRE.multiply(1000).divide(SI.SECOND.multiply(3600));
        UomUnitFormat f = UomUnitFormat.standard();
        assertEquals("5|18·m/s", f.format(kmh));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int i = 0; i < 50_000; i++) {
                assertEquals("5|18·m/s", f.format(kmh));
            }
        });
        // a label still wins over the cached text
        f.label(kmh, "kmh");
        assertEquals("kmh", f.format(kmh));
    }
}
