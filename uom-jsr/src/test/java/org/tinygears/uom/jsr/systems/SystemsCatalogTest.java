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
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.measure.BinaryPrefix;
import javax.measure.MetricPrefix;
import javax.measure.Prefix;
import javax.measure.Quantity;
import javax.measure.Unit;
import javax.measure.quantity.ElectricCharge;
import javax.measure.quantity.ElectricCurrent;
import javax.measure.quantity.Force;
import javax.measure.quantity.Length;
import javax.measure.quantity.MagneticFluxDensity;
import javax.measure.quantity.Temperature;
import javax.measure.quantity.Time;
import javax.measure.quantity.Volume;

import org.junit.jupiter.api.Test;

import org.tinygears.uom.engine.parse.Definition;
import org.tinygears.uom.engine.parse.DefinitionParser;
import org.tinygears.uom.engine.parse.Definitions;
import org.tinygears.uom.engine.parse.Extends;
import org.tinygears.uom.engine.parse.Member;
import org.tinygears.uom.engine.parse.Preferred;
import org.tinygears.uom.engine.parse.Prefixed;
import org.tinygears.uom.jsr.UomUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each system agrees with its data file, resolved here independently of the generator (D2 to D4). */
class SystemsCatalogTest {
    private static final Path DATA = Path.of("../data");
    private static final List<String> TYPES = List.of(
            "Acceleration",
            "AmountOfSubstance",
            "Angle",
            "Area",
            "CatalyticActivity",
            "Dimensionless",
            "ElectricCapacitance",
            "ElectricCharge",
            "ElectricConductance",
            "ElectricCurrent",
            "ElectricInductance",
            "ElectricPotential",
            "ElectricResistance",
            "Energy",
            "Force",
            "Frequency",
            "Illuminance",
            "Length",
            "LuminousFlux",
            "LuminousIntensity",
            "MagneticFlux",
            "MagneticFluxDensity",
            "Mass",
            "Power",
            "Pressure",
            "RadiationDoseAbsorbed",
            "RadiationDoseEffective",
            "Radioactivity",
            "SolidAngle",
            "Speed",
            "Temperature",
            "Time",
            "Volume");

    /** Data file, system and generated unit list, in {@link UnitSystems#all()} order. */
    record Catalog(String file, String name, UomSystemOfUnits system, List<UomUnit<?>> units, Set<String> prefixable) {}

    static final List<Catalog> CATALOGS = List.of(
            new Catalog("si.units", "SI", SI.SYSTEM, SI.UNITS, SI.PREFIXABLE),
            new Catalog(
                    "us-customary.units",
                    "USCustomary",
                    USCustomary.SYSTEM,
                    USCustomary.UNITS,
                    USCustomary.PREFIXABLE),
            new Catalog("imperial.units", "Imperial", Imperial.SYSTEM, Imperial.UNITS, Imperial.PREFIXABLE),
            new Catalog("cgs.units", "CGS", CGS.SYSTEM, CGS.UNITS, CGS.PREFIXABLE),
            new Catalog("cgs-esu.units", "ESU", ESU.SYSTEM, ESU.UNITS, ESU.PREFIXABLE),
            new Catalog("cgs-emu.units", "EMU", EMU.SYSTEM, EMU.UNITS, EMU.PREFIXABLE),
            new Catalog("cgs-gaussian.units", "Gaussian", Gaussian.SYSTEM, Gaussian.UNITS, Gaussian.PREFIXABLE),
            new Catalog(
                    "information.units",
                    "Information",
                    Information.SYSTEM,
                    Information.UNITS,
                    Information.PREFIXABLE));

    static final Map<String, Definitions> PARSED = load();

    private static Map<String, Definitions> load() {
        Map<String, Definitions> parsed = new HashMap<>();
        DefinitionParser.Resolver resolver = name -> Files
                .newBufferedReader(DATA.resolve(name), StandardCharsets.UTF_8);
        for (Catalog c : CATALOGS) {
            try (Reader in = resolver.open(c.file())) {
                parsed.put(c.file(), DefinitionParser.parse(c.file(), in, resolver));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return parsed;
    }

    /** Own definitions, then the members of the extended files, then the {@code @member} lines. */
    static Set<String> members(String file) {
        Definitions d = PARSED.get(file);
        Set<String> symbols = new LinkedHashSet<>();
        d.definitions().stream().filter(x -> x.source().equals(file)).forEach(x -> symbols.add(x.symbol()));
        for (Extends e : d.extended()) {
            symbols.addAll(members(e.file()));
        }
        d.members().stream().map(Member::symbol).forEach(symbols::add);
        return symbols;
    }

    private static UomUnit<?> unit(String file, String symbol) {
        return PARSED.get(file).definitions().stream().filter(d -> d.symbol().equals(symbol)).findFirst()
                .map(d -> (UomUnit<?>) UomUnit.of(d.unit()))
                .orElseThrow(() -> new AssertionError(file + ": " + symbol));
    }

    /** The {@code @prefixed} symbols of the parse that are members (an owner's status carries over). */
    static Set<String> prefixable(String file) {
        Set<String> members = members(file);
        return PARSED.get(file).prefixed().stream().filter(Prefixed::metric).map(Prefixed::unitSymbol)
                .filter(members::contains).collect(Collectors.toSet());
    }

    static Set<String> binaryPrefixable(String file) {
        Set<String> members = members(file);
        return PARSED.get(file).prefixed().stream().filter(Prefixed::binary).map(Prefixed::unitSymbol)
                .filter(members::contains).collect(Collectors.toSet());
    }

    /** The prefixes a format applies to the unit: metric (from kilo up on a binary-prefixable unit), then binary. */
    static List<Prefix> prefixes(UomSystemOfUnits system, String symbol) {
        List<Prefix> prefixes = new ArrayList<>();
        if (system.isPrefixable(symbol)) {
            Arrays.stream(MetricPrefix.values()).filter(p -> !system.isBinaryPrefixable(symbol) || p.getExponent() >= 3)
                    .forEach(prefixes::add);
        }
        if (system.isBinaryPrefixable(symbol)) {
            prefixes.addAll(List.of(BinaryPrefix.values()));
        }
        return prefixes;
    }

    /** Own {@code @preferred} line, else the first extended file's, else null (SI's rule applies). */
    private static String preferred(String file, String type) {
        Definitions d = PARSED.get(file);
        for (Preferred p : d.preferred()) {
            if (p.source().equals(file) && p.quantityType().equals(type)) {
                return p.symbol();
            }
        }
        for (Extends e : d.extended()) {
            String symbol = preferred(e.file(), type);
            if (symbol != null) {
                return symbol;
            }
        }
        return null;
    }

    @Test
    void membersAgreeWithTheData() {
        Map<String, List<Integer>> sizes = new LinkedHashMap<>();
        for (Catalog c : CATALOGS) {
            assertEquals(c.name(), c.system().getName());
            assertEquals(c.name(), PARSED.get(c.file()).system());
            Set<String> expected = members(c.file());
            Set<String> actual = c.units().stream().map(UomUnit::getSymbol).collect(Collectors.toSet());
            assertEquals(expected, actual, c.name());
            assertEquals(expected.size(), c.units().size(), c.name());
            Set<UomUnit<?>> distinct = new LinkedHashSet<>();
            for (String symbol : expected) {
                UomUnit<?> u = unit(c.file(), symbol);
                distinct.add(u);
                Unit<?> found = c.system().getUnit(symbol);
                assertEquals(u, found, c.name() + ": " + symbol);
                assertEquals(symbol, found.getSymbol());
                assertEquals(u.getName(), ((UomUnit<?>) found).getName(), symbol);
            }
            assertEquals(distinct, c.system().getUnits(), c.name());
            sizes.put(c.name(), List.of(distinct.size(), expected.size()));
        }
        // Deliberate tripwires: a data change that alters a system's size must update these on purpose.
        Map<String, List<Integer>> pinned = new LinkedHashMap<>();
        pinned.put("SI", List.of(128, 128));
        pinned.put("USCustomary", List.of(35, 35));
        pinned.put("Imperial", List.of(42, 42));
        pinned.put("CGS", List.of(16, 16));
        pinned.put("ESU", List.of(22, 22));
        pinned.put("EMU", List.of(29, 29));
        pinned.put("Gaussian", List.of(26, 27));
        pinned.put("Information", List.of(22, 22));
        assertEquals(pinned, sizes);
    }

    @Test
    void noLeaks() {
        assertEquals(
                Set.of("cm", "g", "s"),
                CGS.UNITS.stream().map(UomUnit::getSymbol).filter(s -> SI.SYSTEM.getUnit(s) != null)
                        .collect(Collectors.toSet()));
        assertEquals(
                Set.of(),
                Gaussian.UNITS.stream().map(UomUnit::getSymbol).filter(s -> s.startsWith("ab"))
                        .collect(Collectors.toSet()));
        assertEquals(Set.of(), Imperial.UNITS.stream().filter(u -> SI.UNITS.contains(u)).collect(Collectors.toSet()));
        assertSame(SI.CENTIMETRE, Gaussian.SYSTEM.getUnit("cm"));
        assertSame(Gaussian.FRANKLIN, Gaussian.SYSTEM.getUnit("Fr"));
        assertSame(ESU.STATCOULOMB, Gaussian.SYSTEM.getUnit("statC"));
        assertEquals(Gaussian.FRANKLIN, ESU.STATCOULOMB);
    }

    @Test
    void prefixableAgreesWithTheData() {
        for (Catalog c : CATALOGS) {
            Set<String> expected = prefixable(c.file());
            Set<String> binary = binaryPrefixable(c.file());
            assertEquals(expected, c.prefixable(), c.name());
            for (String symbol : members(c.file())) {
                assertEquals(expected.contains(symbol), c.system().isPrefixable(symbol), c.name() + ": " + symbol);
                assertEquals(binary.contains(symbol), c.system().isBinaryPrefixable(symbol), c.name() + ": " + symbol);
            }
        }
        assertEquals(Set.of("B", "bit"), Information.PREFIXABLE);
        assertEquals(Set.of("B", "bit"), Information.BINARY_PREFIXABLE);
        assertEquals(Set.of(), USCustomary.PREFIXABLE);
        assertEquals(Set.of(), Imperial.PREFIXABLE);
        assertEquals(Set.of("Gal", "P", "St", "g", "s"), CGS.PREFIXABLE);
        assertEquals(CGS.PREFIXABLE, ESU.PREFIXABLE);
        assertEquals(Set.of("G", "Gal", "P", "St", "g", "s"), EMU.PREFIXABLE);
        assertEquals(EMU.PREFIXABLE, Gaussian.PREFIXABLE);
    }

    @Test
    void prefixedConstantsEqualTheirPrefixedBase() {
        int expected = 0;
        int checked = 0;
        for (Catalog c : CATALOGS) {
            for (Prefixed p : PARSED.get(c.file()).prefixed()) {
                if (!p.source().equals(c.file())) {
                    continue;
                }
                UomUnit<?> base = (UomUnit<?>) c.system().getUnit(p.unitSymbol());
                for (String symbol : p.prefixSymbols()) {
                    expected++;
                    Prefix prefix = Stream
                            .<Prefix>concat(Arrays.stream(MetricPrefix.values()), Arrays.stream(BinaryPrefix.values()))
                            .filter(m -> m.getSymbol().equals(symbol)).findFirst().orElseThrow();
                    String unitSymbol = symbol + p.unitSymbol();
                    UomUnit<?> found = (UomUnit<?>) c.system().getUnit(unitSymbol);
                    String name = prefix.getName().toLowerCase(java.util.Locale.ROOT) + base.getName();
                    assertEquals(base.prefix(prefix).named(unitSymbol, name), found, c.name() + ": " + unitSymbol);
                    assertEquals(unitSymbol, found.getSymbol());
                    assertEquals(name, found.getName());
                    checked++;
                }
            }
        }
        assertEquals(expected, checked);
        assertEquals(112, checked);
    }

    // Lets the loop below name a type variable for getUnit(Class<Q>).
    private interface Q extends Quantity<Q> {
    }

    @Test
    void preferredUnitsFollowTheLayering() throws Exception {
        assertEquals(33, TYPES.size());
        for (Catalog c : CATALOGS) {
            for (String name : TYPES) {
                @SuppressWarnings({ "unchecked", "rawtypes" })
                Class<Q> type = (Class) Class.forName("javax.measure.quantity." + name);
                Unit<?> actual = c.system().getUnit(type);
                assertNotNull(actual, c.name() + " " + name);
                String symbol = preferred(c.file(), name);
                Unit<?> expected = symbol == null ? SI.SYSTEM.getUnit(type) : unit(c.file(), symbol);
                assertEquals(expected, actual, c.name() + " " + name);
                assertEquals(expected.getSymbol(), actual.getSymbol(), c.name() + " " + name);
            }
        }
        assertSame(Imperial.GALLON_UK, Imperial.SYSTEM.getUnit(Volume.class));
        assertSame(USCustomary.FOOT, Imperial.SYSTEM.getUnit(Length.class));
        assertSame(USCustomary.GALLON_US, USCustomary.SYSTEM.getUnit(Volume.class));
        assertSame(USCustomary.DEGREE_FAHRENHEIT, USCustomary.SYSTEM.getUnit(Temperature.class));
        assertSame(CGS.DYNE, CGS.SYSTEM.getUnit(Force.class));
        assertEquals(SI.SECOND, CGS.SYSTEM.getUnit(Time.class));
        assertSame(SI.CENTIMETRE, CGS.SYSTEM.getUnit(Length.class));
        assertSame(Gaussian.FRANKLIN, Gaussian.SYSTEM.getUnit(ElectricCharge.class));
        assertSame(ESU.STATAMPERE, Gaussian.SYSTEM.getUnit(ElectricCurrent.class));
        assertSame(EMU.GAUSS, Gaussian.SYSTEM.getUnit(MagneticFluxDensity.class));
        assertEquals(SI.TESLA, ESU.SYSTEM.getUnit(MagneticFluxDensity.class));
        assertEquals("T", ESU.SYSTEM.getUnit(MagneticFluxDensity.class).getSymbol());
        assertSame(EMU.ABAMPERE, EMU.SYSTEM.getUnit(ElectricCurrent.class));
    }

    // DR-24: a preferred unit taken from SI is not a member of the system; units of an @extends-ed parent are.
    @Test
    void fallbackPreferredUnitsAreNotMembers() throws Exception {
        List<String> outside = new ArrayList<>();
        for (Catalog c : CATALOGS) {
            for (String name : TYPES) {
                @SuppressWarnings({ "unchecked", "rawtypes" })
                Class<Q> type = (Class) Class.forName("javax.measure.quantity." + name);
                Unit<?> preferred = c.system().getUnit(type);
                if (!c.system().getUnits().contains(preferred)) {
                    assertNull(c.system().getUnit(preferred.getSymbol()), c.name() + " " + name);
                    outside.add(c.name() + " " + name);
                }
            }
        }
        assertEquals(156, outside.size());
        assertEquals(
                List.of("SI Acceleration", "SI Dimensionless", "SI Speed"),
                outside.stream().filter(p -> p.startsWith("SI ")).toList());
        assertEquals(SI.SECOND, USCustomary.SYSTEM.getUnit(Time.class));
        assertNull(USCustomary.SYSTEM.getUnit("s"));
        assertTrue(outside.contains("USCustomary Time"));
        assertEquals(SI.METRE, Information.SYSTEM.getUnit(Length.class));
        assertNull(Information.SYSTEM.getUnit("m"));
        // ESU extends CGS, so CGS's preferred units are members of ESU
        assertSame(SI.CENTIMETRE, ESU.SYSTEM.getUnit(Length.class));
        assertSame(SI.CENTIMETRE, ESU.SYSTEM.getUnit("cm"));
    }
}
