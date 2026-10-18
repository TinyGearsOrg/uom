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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.measure.BinaryPrefix;
import javax.measure.MeasurementException;
import javax.measure.MetricPrefix;
import javax.measure.Quantity;
import javax.measure.Unit;
import javax.measure.format.MeasurementParseException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.tinygears.uom.engine.UnitException;
import org.tinygears.uom.engine.parse.DefinitionException;
import org.tinygears.uom.engine.parse.DefinitionParser;
import org.tinygears.uom.jsr.BaseUnits;
import org.tinygears.uom.jsr.UomUnit;
import org.tinygears.uom.jsr.quantity.Length;
import org.tinygears.uom.jsr.quantity.QuantityTypes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class UserUnitsTest {
    private static final String MEDIEVAL = """
            league:league 3 mi
            furlong:furlong 220 yd
            @system Medieval
            @preferred Length furlong
            """;
    private static final UomUnit<?> THREE_MILES = USCustomary.MILE.multiply(3);

    private static DefinitionParser.Resolver files(Map<String, String> files) {
        return name -> {
            String content = files.get(name);
            if (content == null) {
                throw new FileNotFoundException(name);
            }
            return new StringReader(content);
        };
    }

    private static UomSystemOfUnits load(String text) {
        return load(text, Map.of());
    }

    private static UomSystemOfUnits load(String text, Map<String, String> files) {
        return UomSystemOfUnits.load("user.units", new StringReader(text), files(files));
    }

    private static DefinitionException fails(String text, String message) {
        return fails(text, Map.of(), message);
    }

    private static DefinitionException fails(String text, Map<String, String> files, String message) {
        DefinitionException e = assertThrows(DefinitionException.class, () -> load(text, files));
        assertEquals(message, e.getMessage());
        return e;
    }

    @SuppressWarnings("unchecked")
    private static BigDecimal metres(Unit<?> unit) {
        return ((UomUnit<javax.measure.quantity.Length>) unit.asType(javax.measure.quantity.Length.class))
                .getConverterTo(SI.METRE).convert(BigDecimal.ONE, MathContext.UNLIMITED);
    }

    @Test
    void loadsMedieval() throws Exception {
        UomSystemOfUnits m = load(MEDIEVAL);
        assertEquals("Medieval", m.getName());
        Unit<?> league = m.getUnit("league");
        assertEquals(THREE_MILES, league);
        assertEquals(0, new BigDecimal("4828.032").compareTo(metres(league)));
        assertEquals(0, new BigDecimal("201.168").compareTo(metres(m.getUnit("furlong"))));
        assertEquals("league", league.getSymbol());
        assertEquals("league", league.getName());
        Unit<javax.measure.quantity.Length> typed = league.asType(javax.measure.quantity.Length.class);
        assertSame(league, typed);
        assertThrows(ClassCastException.class, () -> league.asType(javax.measure.quantity.Time.class));
        assertEquals(List.of("league", "furlong"), m.getUnits().stream().map(Unit::getSymbol).toList());
        assertTrue(m.lookup("league").isPresent());
        assertTrue(m.lookup("mi").isEmpty());
        assertFalse(m.isPrefixable("league"));
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void preferredLayersOnSi() {
        UomSystemOfUnits m = load(MEDIEVAL);
        assertSame(m.getUnit("furlong"), m.getUnit(javax.measure.quantity.Length.class));
        assertEquals(SI.SECOND, m.getUnit(javax.measure.quantity.Time.class));
        assertEquals(33, QuantityTypes.types().size());
        for (Class<?> type : QuantityTypes.types()) {
            Unit<?> unit = m.getUnit((Class) type);
            assertNotNull(unit, type.getSimpleName());
            if (type != javax.measure.quantity.Length.class) {
                assertEquals(SI.PREFERRED.get(type), unit, type.getSimpleName());
            }
        }
        Length d = Length.of(BigDecimal.ONE, SI.KILOMETRE).toSystem(m);
        assertSame(m.getUnit("furlong"), d.getUnit());
        assertEquals(BigDecimal.valueOf(1000).divide(new BigDecimal("201.168"), MathContext.DECIMAL128), d.getValue());
        Length f = Length.of(1, SI.KILOMETRE).toSystem(m);
        assertSame(m.getUnit("furlong"), f.getUnit());
        assertEquals(1000 / 201.168, f.getValue().doubleValue(), 1e-12);
    }

    @Test
    void nameFromArgumentWithoutSystemLine() {
        assertEquals("fallback", UomSystemOfUnits.load("fallback", new StringReader("league 3 mi\n"), null).getName());
    }

    @Test
    void aFailedLoadLeavesNoAlternateUnits() {
        // the parse succeeds and the system fails to build: its alternate unit is removed again
        fails("tpx:pixel ~\n@preferred Time tpx\n", "user.units:2: unit 'tpx' is not a unit of Time");
        fails("tdot ~\ntbad tundefined\n", "user.units:2: unknown unit 'tundefined'");
        UomSystemOfUnits fixed = load("tpx:pixel ~ m\ntdot ~ s\n");
        assertEquals(SI.METRE.getDimension(), fixed.getUnit("tpx").getDimension());
        assertEquals(SI.SECOND.getDimension(), fixed.getUnit("tdot").getDimension());
        // a successful load keeps them for the life of the JVM
        fails("tpx ~ s\n", "user.units:1: symbol tpx is already associated to a different unit");
    }

    @Test
    void preferredErrors() {
        String base = "league:league 3 mi\nfurlong:furlong 220 yd\n";
        fails(base + "@preferred Foo league\n", "user.units:3: unknown quantity type 'Foo'");
        fails(base + "@preferred Length nothere\n", "user.units:3: unknown unit 'nothere'");
        fails(base + "@preferred Length m\n", "user.units:3: unknown unit 'm'");
        fails(base + "@preferred Time league\n", "user.units:3: unit 'league' is not a unit of Time");
        // structural, not by dimension (DR-20): the becquerel is a unit of Radioactivity, not of Frequency
        fails("bq 1 Bq\n@preferred Frequency bq\n", "user.units:2: unit 'bq' is not a unit of Frequency");
        assertEquals(
                SI.BECQUEREL,
                load("bq 1 Bq\n@preferred Radioactivity bq\n").getUnit(javax.measure.quantity.Radioactivity.class));
        fails("deg2 1 sr\n@preferred Angle deg2\n", "user.units:2: unit 'deg2' is not a unit of Angle");
        fails(
                base + "@preferred Length league\n@preferred Length furlong\n",
                "user.units:4: duplicate preferred unit for 'Length'");
        fails(
                "@include a.units\n@preferred Length rod\n",
                Map.of("a.units", "rod 5.5 yd\n"),
                "user.units:2: unit 'rod' is not a unit of user.units");
    }

    @Test
    void definitionErrorsCarryFileAndLine() {
        fails("league 3 mi\nx 2 nothere\n", "user.units:2: unknown unit 'nothere'");
        fails("league 3 mi\nleague 4 mi\n", "user.units:2: duplicate symbol 'league'");
        fails("league 3 mi^\n", "user.units:1: exponent must be an integer");
        fails("league 3 mi [length]\n", "user.units:1: malformed quantity type 'length'");
        fails(
                "\n@include a.units\n",
                Map.of("a.units", "@include b.units\n", "b.units", "\n@include a.units\n"),
                "b.units:2: include cycle: a.units -> b.units -> a.units");
        fails("@include missing.units\n", "user.units:1: cannot include 'missing.units'");
        DefinitionException e = assertThrows(
                DefinitionException.class,
                () -> UomSystemOfUnits.load("user.units", new StringReader("\n@include si.units\n"), null));
        assertEquals("user.units:2: cannot include 'si.units'", e.getMessage());
        assertEquals("user.units", e.source());
        assertEquals(2, e.line());
    }

    @Test
    void builtInSymbolsCannotBeRedefined() {
        for (String symbol : List.of("ft", "m", "kg", "km", "mm", "mi", "s")) {
            fails("league 3 mi\n" + symbol + " 2 league\n", "user.units:2: symbol '" + symbol + "' is already defined");
        }
        // 'mi' is the mile, so the milli form of a user unit 'i' clashes
        fails("i 2 m\n@prefixed i k m\n", "user.units:2: symbol 'mi' is already defined");
    }

    @Test
    void loadFromPath(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("old.units"), "@include common.units\nleague 3 mi\nperch 1 rod\n");
        Files.writeString(dir.resolve("common.units"), "rod 5.5 yd\n");
        UomSystemOfUnits old = UomSystemOfUnits.load(dir.resolve("old.units"));
        assertEquals("old", old.getName());
        assertEquals(List.of("league", "perch"), old.getUnits().stream().map(Unit::getSymbol).toList());
        assertEquals(0, new BigDecimal("5.0292").compareTo(metres(old.getUnit("perch"))));

        Files.createDirectory(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/nested.units"), "@include ../common.units\n");
        DefinitionException e = assertThrows(
                DefinitionException.class,
                () -> UomSystemOfUnits.load(dir.resolve("sub/nested.units")));
        assertEquals("nested.units:1: cannot include '../common.units'", e.getMessage());
        Files.writeString(dir.resolve("abs.units"), "@include " + dir.resolve("common.units") + "\n");
        e = assertThrows(DefinitionException.class, () -> UomSystemOfUnits.load(dir.resolve("abs.units")));
        assertEquals("abs.units:1: cannot include '" + dir.resolve("common.units") + "'", e.getMessage());
        Files.writeString(dir.resolve("down.units"), "@include sub/inner.units\nx 2 y\n");
        Files.writeString(dir.resolve("sub/inner.units"), "y 3 m\n");
        assertEquals(SI.METRE.multiply(6), UomSystemOfUnits.load(dir.resolve("down.units")).getUnit("x"));

        assertThrows(NoSuchFileException.class, () -> UomSystemOfUnits.load(dir.resolve("missing.units")));

        Files.writeString(
                dir.resolve("bom.units"),
                "﻿league 3 mi\r\n@system Bom\r\nfurlong 220 yd\r\n",
                StandardCharsets.UTF_8);
        UomSystemOfUnits bom = UomSystemOfUnits.load(dir.resolve("bom.units"));
        assertEquals("Bom", bom.getName());
        assertEquals(THREE_MILES, bom.getUnit("league"));
        assertEquals(USCustomary.YARD.multiply(220), bom.getUnit("furlong"));
    }

    @Test
    void extendsAnotherUserFile() {
        Map<String, String> files = Map.of(
                "base.units",
                "@system Base\nrod:rod 5.5 yd\n@prefixed rod k\n@preferred Length rod\n"
                        + "fortnight 14 d\n@preferred Time fortnight\n",
                "extra.units",
                "@include base.units\npole 2 rod\nhide 120 m^2\n");
        UomSystemOfUnits c = load(
                "@extends base.units\n@include extra.units\n@system Child\nleague 3 mi\n"
                        + "@member pole\nweek 7 d\n@preferred Time week\n",
                files);
        assertEquals("Child", c.getName());
        assertEquals(
                List.of("league", "week", "rod", "krod", "fortnight", "pole"),
                c.getUnits().stream().map(Unit::getSymbol).toList());
        assertTrue(c.isPrefixable("rod"));
        assertSame(c.getUnit("rod"), c.getUnit(javax.measure.quantity.Length.class));
        assertSame(c.getUnit("week"), c.getUnit(javax.measure.quantity.Time.class));
        assertEquals(SI.KILOGRAM, c.getUnit(javax.measure.quantity.Mass.class));
        assertEquals(null, c.getUnit("hide"));
        UomUnitFormat f = UomUnitFormat.standard().with(c);
        assertEquals(c.getUnit("rod").multiply(1e6), f.parse("Mrod"));
    }

    @Test
    void formatWithUserSystem() {
        UomSystemOfUnits m = load(MEDIEVAL);
        UomUnitFormat f = UomUnitFormat.standard().with(m);
        assertEquals(THREE_MILES, f.parse("league"));
        assertEquals(THREE_MILES.divide(SI.HOUR), f.parse("league/h"));
        assertEquals(SI.KILOMETRE.divide(THREE_MILES), f.parse("km/league"));
        assertEquals("league", f.format(THREE_MILES));
        assertEquals("league", f.format(m.getUnit("league")));
        assertThrows(MeasurementParseException.class, () -> f.parse("kleague"));
        UomQuantityFormat q = new UomQuantityFormat(f);
        Quantity<?> two = q.parse("2 league");
        assertEquals(2.0, two.getValue());
        assertEquals(THREE_MILES, two.getUnit());
        assertEquals("2.0 league", q.format(two));
        assertEquals(
                THREE_MILES,
                UomUnitFormat.standard().with(load(MEDIEVAL + "@prefixed league k\n")).parse("kleague").divide(1000));
        assertEquals(
                THREE_MILES.multiply(1e6),
                UomUnitFormat.standard().with(load(MEDIEVAL + "@prefixed league k\n")).parse("Mleague"));
    }

    @Test
    void originalFormatIsUnchanged() {
        UomUnitFormat base = UomUnitFormat.standard();
        UomUnitFormat f = base.with(load(MEDIEVAL));
        assertEquals(THREE_MILES, f.parse("league"));
        assertThrows(MeasurementParseException.class, () -> base.parse("league"));
        assertTrue(base.resolve("league").isEmpty());
        assertTrue(f.resolve("league").isPresent());
    }

    @Test
    void labelsAreCopied() {
        UomUnitFormat base = UomUnitFormat.standard();
        base.label(SI.METRE, "meter");
        UomUnitFormat f = base.with(load(MEDIEVAL));
        assertEquals(SI.METRE, f.parse("meter"));
        assertEquals("meter", f.format(SI.METRE));
        base.label(SI.SECOND, "sec");
        assertThrows(MeasurementParseException.class, () -> f.parse("sec"));
        f.label(SI.KILOGRAM, "kilo");
        assertThrows(MeasurementParseException.class, () -> base.parse("kilo"));
        assertTrue(base.resolve("meter").isEmpty());
    }

    @Test
    void resolveSeesSymbolsAndPrefixesButNotLabels() {
        UomUnitFormat f = UomUnitFormat.standard();
        assertSame(USCustomary.FOOT, f.resolve("ft").orElseThrow());
        assertEquals(SI.KILOMETRE, f.resolve("km").orElseThrow());
        assertEquals(SI.METRE.multiply(1e9), f.resolve("Gm").orElseThrow());
        assertTrue(f.resolve("").isEmpty());
        assertTrue(f.resolve("nope").isEmpty());
        f.label(SI.METRE, "meter");
        assertTrue(f.resolve("meter").isEmpty());
        assertThrows(NullPointerException.class, () -> f.resolve(null));
    }

    @Test
    void conflictingSymbolsAreRejected() {
        UomSystemOfUnits fake = new UomSystemOfUnits(
                "Fake",
                List.of(SI.METRE.named("ft", "fake foot")),
                Map.of(),
                Set.of());
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> UomUnitFormat.standard().with(fake));
        assertEquals("symbol 'ft' of Fake is a different unit in USCustomary", e.getMessage());
        UomSystemOfUnits km = new UomSystemOfUnits("Km", List.of(SI.METRE.named("km", "k")), Map.of(), Set.of());
        e = assertThrows(IllegalArgumentException.class, () -> UomUnitFormat.standard().with(km));
        assertEquals("symbol 'km' of Km is a different unit in SI", e.getMessage());

        UomSystemOfUnits same = new UomSystemOfUnits(
                "Same",
                List.of(USCustomary.FOOT, SI.KILOMETRE),
                Map.of(),
                Set.of());
        assertSame(USCustomary.FOOT, UomUnitFormat.standard().with(same).parse("ft"));

        UomSystemOfUnits a = load("xx 2 m\n@system A\n");
        UomSystemOfUnits b = load("xx 3 s\n@system B\n");
        e = assertThrows(IllegalArgumentException.class, () -> UomUnitFormat.standard().with(a, b));
        assertEquals("symbol 'xx' of B is a different unit in A", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> UomUnitFormat.standard().with(a).with(b));
        assertEquals("symbol 'xx' of B is a different unit in A", e.getMessage());

        UomUnitFormat labelled = UomUnitFormat.standard();
        labelled.label(SI.SECOND, "xx");
        e = assertThrows(IllegalArgumentException.class, () -> labelled.with(a));
        assertEquals("symbol 'xx' of A is the label of s", e.getMessage());
    }

    @Test
    void withArguments() {
        UomUnitFormat f = UomUnitFormat.standard();
        assertThrows(NullPointerException.class, () -> f.with((UomSystemOfUnits[]) null));
        assertThrows(NullPointerException.class, () -> f.with((UomSystemOfUnits) null));
        assertThrows(IllegalArgumentException.class, f::with);
        UomSystemOfUnits m = load(MEDIEVAL);
        UomUnitFormat twice = f.with(m, m).with(m).with(SI.SYSTEM);
        assertEquals(THREE_MILES, twice.parse("league"));
        assertEquals("league", twice.format(THREE_MILES));
    }

    @Test
    void noGlobalState() {
        UomUnitFormat fa = UomUnitFormat.standard().with(load("xx 2 m\n@system A\n"));
        UomUnitFormat fb = UomUnitFormat.standard().with(load("xx 3 s\n@system B\n"));
        assertEquals(SI.METRE.multiply(2), fa.parse("xx"));
        assertEquals(SI.SECOND.multiply(3), fb.parse("xx"));
        assertEquals(SI.METRE.multiply(2), fa.parse("xx"));
        assertThrows(MeasurementParseException.class, () -> UomUnitFormat.standard().parse("xx"));
        assertThrows(MeasurementParseException.class, () -> new UomUnitFormat(UnitSystems.all()).parse("xx"));
        // a later load does not see units of earlier ones
        assertEquals(SI.METRE.multiply(4), load("xx 4 m\n").getUnit("xx"));
    }

    @Test
    void alternateUnits() {
        String text = "uu7alt:usertestalt ~ m/s\nslow 2 uu7alt\n";
        UomSystemOfUnits one = load(text);
        UomSystemOfUnits two = load(text);
        assertEquals(one.getUnit("uu7alt"), two.getUnit("uu7alt"));
        assertEquals(SI.METRE.divide(SI.SECOND).getDimension(), one.getUnit("uu7alt").getDimension());
        assertFalse(one.getUnit("uu7alt").equals(SI.METRE.divide(SI.SECOND)));
        assertEquals(one.getUnit("slow"), UomUnitFormat.standard().with(two).parse("2 uu7alt"));
        DefinitionException e = fails(
                "\nuu7alt ~ kg\n",
                "user.units:2: symbol uu7alt is already associated to a different unit");
        assertInstanceOf(UnitException.class, e.getCause());
    }

    @Test
    void systemsAreImmutable() {
        UomSystemOfUnits m = load(MEDIEVAL);
        assertThrows(UnsupportedOperationException.class, () -> m.getUnits().clear());
        assertThrows(UnsupportedOperationException.class, () -> m.getUnits(SI.METRE.getDimension()).clear());
    }

    @Test
    void everyUnitRoundTrips() {
        UomSystemOfUnits m = load(
                MEDIEVAL + "@prefixed league k c\n@prefixed furlong M\nfortnight 14 d\n"
                        + "fps furlong/fortnight\nhot K = x + 7\n");
        UomUnitFormat f = UomUnitFormat.standard().with(m);
        int n = 0;
        for (Unit<?> u : m.getUnits()) {
            assertEquals(u, f.parse(f.format(u)), u.toString());
            n++;
            if (m.isPrefixable(u.getSymbol())) {
                for (MetricPrefix p : MetricPrefix.values()) {
                    Unit<?> prefixed = u.prefix(p);
                    assertEquals(prefixed, f.parse(f.format(prefixed)), p + " " + u);
                    assertEquals(prefixed, f.parse(p.getSymbol() + u.getSymbol()), p + " " + u);
                    n++;
                }
            }
        }
        assertEquals(8 + 2 * MetricPrefix.values().length, n);
    }

    @Test
    void threadSafety() throws Exception {
        UomSystemOfUnits shared = load(MEDIEVAL);
        UomUnitFormat format = UomUnitFormat.standard().with(shared);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                results.add(pool.submit(() -> {
                    for (int i = 0; i < 200; i++) {
                        UomSystemOfUnits own = load(MEDIEVAL);
                        UomUnitFormat f = UomUnitFormat.standard().with(own);
                        if (!format.parse("league/h").equals(f.parse("league/h"))
                                || !"league".equals(format.format(THREE_MILES))
                                || own.getUnit(javax.measure.quantity.Length.class) != own.getUnit("furlong")
                                || !shared.getUnit("league").equals(own.getUnit("league"))) {
                            return false;
                        }
                    }
                    return true;
                }));
            }
            for (Future<Boolean> r : results) {
                assertTrue(r.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void planExample() {
        UomSystemOfUnits medieval = UomSystemOfUnits.load("medieval", new StringReader(MEDIEVAL), null);
        UomQuantityFormat q = new UomQuantityFormat(UomUnitFormat.standard().with(medieval));
        Quantity<?> two = q.parse("2 league");
        Length l = Length.of(two.getValue().doubleValue(), two.getUnit().asType(javax.measure.quantity.Length.class));
        Length furlongs = l.toSystem(medieval);
        assertEquals("furlong", furlongs.getUnit().getSymbol());
        assertEquals(48.0, furlongs.getValue().doubleValue(), 1e-12);
        assertEquals(BaseUnits.METRE.getDimension(), medieval.getUnit("league").getDimension());
    }

    private static boolean link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }

    private static void includeFails(Path root, String name) {
        DefinitionException e = assertThrows(DefinitionException.class, () -> UomSystemOfUnits.load(root));
        assertEquals(root.getFileName() + ":1: cannot include '" + name + "'", e.getMessage());
    }

    @Test
    void symbolicLinksMustStayInside(@TempDir Path tmp) throws IOException {
        Path d = Files.createDirectory(tmp.resolve("d"));
        Files.writeString(tmp.resolve("secret.units"), "secret 2 m\n");
        Files.writeString(d.resolve("real.units"), "inside 3 m\n");
        assumeTrue(link(d.resolve("link.units"), Path.of("../secret.units")), "symbolic links unsupported");
        Path main = d.resolve("main.units");
        Files.writeString(main, "@include link.units\n");
        includeFails(main, "link.units");
        link(d.resolve("linkdir"), Path.of(".."));
        Files.writeString(main, "@include linkdir/secret.units\n");
        includeFails(main, "linkdir/secret.units");
        link(d.resolve("c1.units"), Path.of("c2.units"));
        link(d.resolve("c2.units"), Path.of("../secret.units"));
        Files.writeString(main, "@include c1.units\n");
        includeFails(main, "c1.units");
        Files.writeString(main, "@extends link.units\n");
        includeFails(main, "link.units");

        link(d.resolve("in.units"), Path.of("real.units"));
        link(d.resolve("c3.units"), Path.of("c4.units"));
        Files.writeString(d.resolve("real2.units"), "inside2 5 m\n");
        link(d.resolve("c4.units"), d.resolve("real2.units"));
        Files.writeString(main, "@include in.units\n@include c3.units\nx 2 inside\ny 2 inside2\n");
        UomSystemOfUnits ok = UomSystemOfUnits.load(main);
        assertEquals(SI.METRE.multiply(6), ok.getUnit("x"));
        assertEquals(SI.METRE.multiply(10), ok.getUnit("y"));
    }

    @Test
    void includesResolveAgainstTheRootDirectory(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("sub"));
        Files.writeString(dir.resolve("main.units"), "@include sub/inner.units\nx 2 y\n");
        Files.writeString(dir.resolve("sub/inner.units"), "@include deeper.units\ny 2 deep\n");
        Files.writeString(dir.resolve("deeper.units"), "deep 3 m\n");
        Files.writeString(dir.resolve("sub/deeper.units"), "deep 5 m\n");
        assertEquals(SI.METRE.multiply(12), UomSystemOfUnits.load(dir.resolve("main.units")).getUnit("x"));
    }

    @Test
    void pathWithoutFileNameOrMalformedText(@TempDir Path dir) throws IOException {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> UomSystemOfUnits.load(Path.of("/")));
        assertEquals("not a file: /", e.getMessage());
        Path bad = dir.resolve("bad.units");
        Files.write(bad, new byte[] { 'x', ' ', '2', ' ', 'm', '\n', (byte) 0xC3, (byte) 0x28, '\n' });
        assertThrows(MalformedInputException.class, () -> UomSystemOfUnits.load(bad));
        Files.writeString(dir.resolve("inc.units"), "@include bad.units\n");
        DefinitionException d = assertThrows(
                DefinitionException.class,
                () -> UomSystemOfUnits.load(dir.resolve("inc.units")));
        assertEquals("inc.units:1: cannot include 'bad.units'", d.getMessage());
        assertInstanceOf(MalformedInputException.class, d.getCause());
    }

    @Test
    void diamondExtendsOpensEachFileOnce() {
        Map<String, String> files = new HashMap<>();
        int depth = 25;
        for (int i = 1; i <= depth; i++) {
            String next = i == depth ? "" : "@extends a" + (i + 1) + ".units\n@extends b" + (i + 1) + ".units\n";
            files.put("x" + i + ".units", next + "ux" + i + " " + i + " m\n");
            files.put("a" + i + ".units", "@extends x" + i + ".units\nua" + i + " 1 m\n");
            files.put("b" + i + ".units", "@extends x" + i + ".units\nub" + i + " 1 m\n");
        }
        Map<String, Integer> opens = new HashMap<>();
        DefinitionParser.Resolver counting = name -> {
            opens.merge(name, 1, Integer::sum);
            return files(files).open(name);
        };
        UomSystemOfUnits s = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> UomSystemOfUnits.load(
                        "x0.units",
                        new StringReader("@extends a1.units\n@extends b1.units\nux0 1 m\n"),
                        counting));
        assertEquals(SI.METRE.multiply(depth), s.getUnit("ux" + depth));
        assertEquals(1 + 3 * depth, s.symbols().size());
        assertEquals(files.keySet(), opens.keySet());
        assertTrue(opens.values().stream().allMatch(n -> n == 1), opens.toString());
    }

    @Test
    void aFileSeesOnlyWhatItIncludes() {
        // extra.units uses rod of a sibling file without including it
        Map<String, String> files = Map.of("base.units", "rod 5.5 yd\n", "extra.units", "pole 2 rod\n");
        fails("@include base.units\n@include extra.units\n", files, "extra.units:1: unknown unit 'rod'");
        // a parent can not use units of the file that extends it
        fails(
                "ell 45 in\n@extends p.units\n",
                Map.of("p.units", "double_ell 2 ell\n"),
                "p.units:1: unknown unit 'ell'");
    }

    @Test
    void aChainOfParentsIsParsedOnce() {
        // Each parent was parsed again on its own, so a chain of n files took n^2 / 2 file parses.
        int depth = 500;
        int perFile = 40;
        Map<String, String> files = new HashMap<>();
        for (int i = 1; i <= depth; i++) {
            StringBuilder text = new StringBuilder(i == depth ? "" : "@extends c" + (i + 1) + ".units\n");
            for (int j = 0; j < perFile; j++) {
                text.append("u").append(i).append('_').append(j).append(' ').append(j + 1).append(" m\n");
            }
            files.put("c" + i + ".units", text.toString());
        }
        UomSystemOfUnits s = assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> load("@extends c1.units\n", files));
        assertEquals(depth * perFile, s.symbols().size());
        assertEquals(SI.METRE.multiply(40), s.getUnit("u500_39"));
    }

    @Test
    void manyMembersOfPrefixedUnitsLoadInLinearTime() {
        // Each member was checked against every definition, and its prefixed lines found by a scan of all of them.
        int n = 20_000;
        StringBuilder lib = new StringBuilder();
        StringBuilder root = new StringBuilder("@include lib.units\n");
        for (int i = 0; i < n; i++) {
            lib.append('u').append(i).append(" 2 m\n@prefixed u").append(i).append(" k\n");
            root.append("@member u").append(i).append('\n');
        }
        UomSystemOfUnits s = assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> load(root.toString(), Map.of("lib.units", lib.toString())));
        assertEquals(n, s.symbols().size());
        assertTrue(s.isPrefixable("u19999"));
        assertEquals(SI.METRE.multiply(2000), UomUnitFormat.standard().with(s).parse("ku19999"));
    }

    @Test
    void closedReaderFromResolver() {
        StringReader used = new StringReader("x 2 m\n");
        used.close();
        UncheckedIOException e = assertThrows(
                UncheckedIOException.class,
                () -> UomSystemOfUnits.load("u.units", new StringReader("@include a.units\n"), name -> used));
        assertEquals("cannot read 'a.units': the resolver returned a failing, closed or reused reader", e.getMessage());
    }

    @Test
    void memberOfAPrefixedIncludedUnitIsPrefixable() {
        UomSystemOfUnits s = load("@include e.units\n@member gs\n", Map.of("e.units", "gs 2 g\n@prefixed gs m\n"));
        assertTrue(s.isPrefixable("gs"));
        assertEquals(List.of("gs"), s.getUnits().stream().map(Unit::getSymbol).toList());
        assertEquals(SI.GRAM.multiply(2e-6), UomUnitFormat.standard().with(s).parse("µgs"));
    }

    @Test
    void binaryPrefixesInAUserFile() {
        UomSystemOfUnits binary = load("@system Imaging\npx:pixel ~\n@prefixed px Ki\n");
        assertFalse(binary.isPrefixable("px"));
        assertTrue(binary.isBinaryPrefixable("px"));
        UomUnitFormat f = UomUnitFormat.standard().with(binary);
        assertEquals(binary.getUnit("px").multiply(1048576), f.parse("Mipx"));
        assertEquals("mebipixel", f.parse("Mipx").getName());
        // Without a metric prefix in a line the unit takes none.
        assertThrows(MeasurementParseException.class, () -> f.parse("kpx"));

        UomSystemOfUnits both = load("@system Imaging\npx:pixel ~\n@prefixed px k Ki\n");
        assertTrue(both.isPrefixable("px"));
        UomUnitFormat g = UomUnitFormat.standard().with(both);
        assertEquals(both.getUnit("px").multiply(1000000), g.parse("Mpx"));
        assertThrows(MeasurementParseException.class, () -> g.parse("mpx"));
        assertEquals("Gipx", g.format(both.getUnit("px").prefix(BinaryPrefix.GIBI)));

        String base = "@system Base\npx:pixel ~\n@prefixed px Ki\n";
        assertTrue(load("@extends base.units\n@system Child\n", Map.of("base.units", base)).isBinaryPrefixable("px"));
        UomSystemOfUnits member = load("@include e.units\n@member px\n", Map.of("e.units", base));
        assertTrue(member.isBinaryPrefixable("px"));
        assertFalse(member.isPrefixable("px"));
    }

    @Test
    void prefixedIncludedUnitIsNotPrefixableButItsPrefixedFormIsAUnit() {
        UomSystemOfUnits s = load("@include rods.units\n@prefixed rod k\n", Map.of("rods.units", "rod 5.5 yd\n"));
        assertEquals(List.of("krod"), s.getUnits().stream().map(Unit::getSymbol).toList());
        assertFalse(s.isPrefixable("rod"));
        assertEquals(USCustomary.YARD.multiply(5500), s.getUnit("krod"));
    }

    @Test
    void conflictWithAPurePrefixReading() {
        UomSystemOfUnits gm = new UomSystemOfUnits("Gee", List.of(SI.GRAM.named("Gm", "gee")), Map.of(), Set.of());
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> UomUnitFormat.standard().with(gm));
        assertEquals("symbol 'Gm' of Gee is a different unit in SI", e.getMessage());
    }
}
