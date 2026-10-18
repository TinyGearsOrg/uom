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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidObjectException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.UnaryOperator;
import javax.measure.MeasurementException;

import org.junit.jupiter.api.Test;

import org.tinygears.uom.engine.Dimension;
import org.tinygears.uom.engine.Factor;
import org.tinygears.uom.engine.UnitAtom;
import org.tinygears.uom.engine.internal.AlternateUnits;
import org.tinygears.uom.jsr.serial.SerialCheck;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.tinygears.uom.jsr.BaseUnits.KELVIN;
import static org.tinygears.uom.jsr.BaseUnits.KILOGRAM;
import static org.tinygears.uom.jsr.BaseUnits.METRE;
import static org.tinygears.uom.jsr.BaseUnits.ONE;
import static org.tinygears.uom.jsr.BaseUnits.SECOND;

class UnitSerializationTest {

    private static final String GOLDEN = "/org/tinygears/uom/jsr/serial/";

    /** The twelve units pinned by golden files, by file name. */
    private static Map<String, UomUnit<?>> goldenUnits() {
        Map<String, UomUnit<?>> m = new LinkedHashMap<>();
        m.put("metre", METRE);
        m.put("kilogram", KILOGRAM);
        m.put("second", SECOND);
        m.put("ampere", BaseUnits.AMPERE);
        m.put("kelvin", KELVIN);
        m.put("mole", BaseUnits.MOLE);
        m.put("candela", BaseUnits.CANDELA);
        m.put("one", ONE);
        m.put("hertz", ONE.divide(SECOND).alternate("Hz").named("Hz", "hertz"));
        m.put(
                "celsius",
                KELVIN.transform(Converters.affine(Factor.ONE, Factor.of(5463L, 20L))).named("°C", "degree Celsius"));
        m.put("degree", ONE.alternate("rad").scaledBy(Factor.PI.divide(Factor.ofLong(180))).named("°", "degree"));
        m.put("root-speed", METRE.divide(SECOND).scaledBy(Factor.ofLong(2).root(2)));
        return m;
    }

    static byte[] serialize(Object o) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(o);
        }
        return bytes.toByteArray();
    }

    static Object deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return in.readObject();
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T roundTrip(T o) throws Exception {
        return (T) deserialize(serialize(o));
    }

    private static void assertUnitRoundTrip(UomUnit<?> unit) throws Exception {
        UomUnit<?> copy = roundTrip(unit);
        assertEquals(unit, copy);
        assertEquals(unit.hashCode(), copy.hashCode());
        assertEquals(unit.getSymbol(), copy.getSymbol());
        assertEquals(unit.getName(), copy.getName());
        assertEquals(unit.atoms(), copy.atoms());
        assertEquals(unit.toSystemConverter(), copy.toSystemConverter());
        assertEquals(unit.getDimension(), copy.getDimension());
    }

    @Test
    void baseUnitsAndOne() throws Exception {
        for (UomUnit<?> u : List
                .of(METRE, KILOGRAM, SECOND, BaseUnits.AMPERE, KELVIN, BaseUnits.MOLE, BaseUnits.CANDELA, ONE)) {
            assertUnitRoundTrip(u);
        }
        UnitAtom metre = METRE.atoms().keySet().iterator().next();
        assertSame(metre, roundTrip(METRE).atoms().keySet().iterator().next());
        assertEquals("one", roundTrip(ONE).getName());
    }

    @Test
    void alternateUnits() throws Exception {
        UomUnit<?> hz = ONE.divide(SECOND).alternate("Hz").named("Hz", "hertz");
        assertUnitRoundTrip(hz);
        assertSame(hz.atoms().keySet().iterator().next(), roundTrip(hz).atoms().keySet().iterator().next());
        UomUnit<?> fresh = METRE.divide(SECOND).alternate("t1-alt-speed");
        assertUnitRoundTrip(fresh);
        assertEquals(
                Map.of(new UnitAtom("t1-alt-speed", new Dimension(1, 0, -1, 0, 0, 0, 0)), 1),
                roundTrip(fresh).atoms());
        // An alternate atom defined through another alternate atom.
        assertUnitRoundTrip(hz.multiply(METRE).alternate("t1-alt-nested").named("t1-alt-nested", "nested"));
    }

    @Test
    void derivedOffsetPiAndRootUnits() throws Exception {
        UomUnit<?> newton = KILOGRAM.multiply(METRE).divide(SECOND.pow(2));
        assertEquals("kg·m/s^2", newton.getSymbol());
        assertNull(newton.getName());
        assertUnitRoundTrip(newton);
        assertUnitRoundTrip(newton.named("N", "newton"));
        assertUnitRoundTrip(KELVIN.shift(273.15));
        assertUnitRoundTrip(KELVIN.shift(273.15).named("°C", "degree Celsius"));
        assertUnitRoundTrip(METRE.scaledBy(Factor.PI.pow(2)));
        assertUnitRoundTrip(METRE.scaledBy(Factor.of(3, 7).root(3)));
        assertUnitRoundTrip(METRE.multiply(1000).named("km", null));
        for (UomUnit<?> u : goldenUnits().values()) {
            assertUnitRoundTrip(u);
        }
    }

    @Test
    void factors() throws Exception {
        for (Factor f : List.of(
                Factor.ZERO,
                Factor.ONE,
                Factor.PI,
                Factor.of(-1, 3),
                Factor.PI.pow(-2),
                Factor.ofLong(2).root(2),
                Factor.of(5, 3).multiply(Factor.PI).root(5),
                Factor.of(BigInteger.TWO.pow(60_000).add(BigInteger.ONE), BigInteger.valueOf(3)))) {
            Factor copy = roundTrip(f);
            assertEquals(f, copy);
            assertEquals(f.hashCode(), copy.hashCode());
            assertEquals(f.toString(), copy.toString());
        }
        assertSame(Factor.ZERO, roundTrip(Factor.ZERO));
    }

    @Test
    void converters() throws Exception {
        assertSame(Converters.IDENTITY, roundTrip(Converters.IDENTITY));
        UomConverter a = Converters.scale(Factor.ofLong(3));
        UomConverter b = Converters.affine(Factor.of(1, 2), Factor.ofLong(-7));
        UomConverter c = Converters.shift(Factor.of(1, 4));
        for (UomConverter conv : List.of(a, b, c, b.inverse())) {
            UomConverter copy = roundTrip(conv);
            assertEquals(conv, copy);
            assertEquals(List.of(copy), copy.getConversionSteps());
        }
        UomConverter chain = a.concatenate(b).concatenate(Converters.IDENTITY).concatenate(c);
        UomConverter copy = roundTrip(chain);
        assertEquals(chain, copy);
        assertEquals(List.of(a, b, Converters.IDENTITY, c), copy.getConversionSteps());
        assertEquals(chain.getConversionSteps(), copy.getConversionSteps());
        assertEquals(chain.convert(2.0), copy.convert(2.0));
        // A unit's converter keeps its steps too.
        UomUnit<?> celsius = KELVIN.shift(273.15);
        assertEquals(
                celsius.toSystemConverter().getConversionSteps(),
                roundTrip(celsius).toSystemConverter().getConversionSteps());
    }

    @Test
    void converterCacheIsNotSerialized() throws Exception {
        UomUnit<?> km = METRE.multiply(1000);
        km.getConverterToAny(METRE);
        assertEquals(1, EngineProxies.cache(km).size());
        UomUnit<?> copy = roundTrip(km);
        assertEquals(0, EngineProxies.cache(copy).size());
        assertEquals(Factor.ofLong(1000), copy.getConverterToAny(METRE).factor());
    }

    @Test
    void goldenFiles() throws Exception {
        Map<String, UomUnit<?>> units = goldenUnits();
        assertEquals(12, units.size());
        boolean write = System.getProperty("uom.writeGolden") != null;
        Path dir = Path.of("src/test/resources" + GOLDEN);
        if (write) {
            Files.createDirectories(dir);
            for (Map.Entry<String, UomUnit<?>> e : units.entrySet()) {
                Files.write(dir.resolve(e.getKey() + ".ser"), serialize(e.getValue()));
            }
        }
        for (Map.Entry<String, UomUnit<?>> e : units.entrySet()) {
            // In write mode the class path still holds the previous copies.
            byte[] golden = write ? Files.readAllBytes(dir.resolve(e.getKey() + ".ser")) : golden(e.getKey());
            UomUnit<?> read = (UomUnit<?>) deserialize(golden);
            assertEquals(e.getValue(), read, e.getKey());
            assertEquals(e.getValue().getSymbol(), read.getSymbol(), e.getKey());
            assertEquals(e.getValue().getName(), read.getName(), e.getKey());
            assertArrayEquals(golden, serialize(e.getValue()), "serialized form of " + e.getKey() + " changed");
        }
    }

    private static byte[] golden(String name) throws IOException {
        try (InputStream in = UnitSerializationTest.class.getResourceAsStream(GOLDEN + name + ".ser")) {
            assertTrue(in != null, "missing golden file " + name);
            return in.readAllBytes();
        }
    }

    @Test
    void bytesDependOnValuesNotIdentity() throws Exception {
        UomUnit<?> hz = ONE.divide(SECOND).alternate(new String("Hz")).named(new String("Hz"), new String("hertz"));
        assertArrayEquals(golden("hertz"), serialize(hz));
        UomUnit<?> celsius = KELVIN.transform(Converters.affine(Factor.of(7, 7), Factor.of(10926L, 40L)))
                .named(new String("°C"), new String("degree Celsius"));
        assertArrayEquals(golden("celsius"), serialize(celsius));
        // The same object written twice must not differ from equal objects written once each.
        assertArrayEquals(
                serialize(METRE.named("t1-x", "t1-x")),
                serialize(METRE.named(new String("t1-x"), new String("t1-x"))));
        Factor third = Factor.of(1, 3);
        assertArrayEquals(
                serialize(Converters.affine(third, third)),
                serialize(Converters.affine(Factor.of(1, 3), Factor.of(1, 3))));
        Dimension d = new Dimension(2, 0, 0, 0, 0, 0, 0);
        assertArrayEquals(
                serialize(
                        UomUnit.of(
                                Map.of(new UnitAtom("t1-a", d), 1, new UnitAtom("t1-b", d), 1),
                                Converters.IDENTITY,
                                null,
                                null)),
                serialize(
                        UomUnit.of(
                                Map.of(
                                        new UnitAtom("t1-a", d),
                                        1,
                                        new UnitAtom("t1-b", new Dimension(2, 0, 0, 0, 0, 0, 0)),
                                        1),
                                Converters.IDENTITY,
                                null,
                                null)));
        // An atom written twice (in the unit and in an alternate's definition) with one symbol object or two.
        UnitAtom q = new UnitAtom("t1-q", Dimension.NONE);
        UnitAtom alt = atom(UomUnit.of(Map.of(q, 1), Converters.IDENTITY, null, null).alternate("t1-alt-q"));
        assertArrayEquals(
                serialize(UomUnit.of(Map.of(alt, 1, q, 1), Converters.IDENTITY, null, null)),
                serialize(
                        UomUnit.of(
                                Map.of(alt, 1, new UnitAtom(new String("t1-q"), Dimension.NONE), 1),
                                Converters.IDENTITY,
                                null,
                                null)));
    }

    private static Path location(Class<?> c) throws Exception {
        return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static String goldenDir() throws Exception {
        return Path.of(UnitSerializationTest.class.getResource(GOLDEN + "hertz.ser").toURI()).getParent().toString();
    }

    @Test
    void freshJvmReadsGoldenFilesOnTheClassPath() throws Exception {
        String out = ModulePathTest.runBounded(
                List.of(
                        java(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        "org.tinygears.uom.jsr.serial.SerialCheck",
                        goldenDir()),
                Duration.ofSeconds(60));
        assertTrue(out.contains("ok named=false atoms=[Hz]"), out);
    }

    @Test
    void roundTripOnTheModulePath() throws Exception {
        String modules = location(UomUnit.class) + File.pathSeparator + location(Factor.class) + File.pathSeparator
                + location(org.tinygears.uom.Quantity.class) + File.pathSeparator + location(javax.measure.Unit.class);
        String out = ModulePathTest.runBounded(
                List.of(
                        java(),
                        "--module-path",
                        modules,
                        "--add-modules",
                        "org.tinygears.uom.jsr",
                        "-cp",
                        location(SerialCheck.class).toString(),
                        "org.tinygears.uom.jsr.serial.SerialCheck",
                        goldenDir()),
                Duration.ofSeconds(60));
        assertTrue(out.contains("ok named=true atoms=[Hz]"), out);
    }

    @Test
    void readingNeverRegisters() throws Exception {
        String out = ModulePathTest.runBounded(
                List.of(java(), "-cp", System.getProperty("java.class.path"), PoisonCheck.class.getName()),
                Duration.ofSeconds(60));
        assertTrue(out.contains("ok poison"), out);
    }

    /**
     * Fresh JVM: a forged atom read before its registration must not break the real one, nor grow the registry. {@code Hq}
     * is in no catalog: the built-in alternate units are registered from the start.
     */
    static final class PoisonCheck {
        public static void main(String[] args) throws Exception {
            UnitAtom m = atom(METRE);
            UnitAtom forged = (UnitAtom) deserialize(
                    serialize(EngineProxies.atom("Hq", Dimension.LENGTH, new UnitAtom[] { m }, new int[] { 1 })));
            int size = AlternateUnits.size();
            for (int i = 0; i < 10_000; i++) {
                deserialize(
                        serialize(
                                EngineProxies.atom(
                                        "t1-unique-" + i,
                                        Dimension.LENGTH,
                                        new UnitAtom[] { m },
                                        new int[] { 1 })));
            }
            check(AlternateUnits.size() == size, "registry grew to " + AlternateUnits.size());
            UomUnit<?> hz = ONE.divide(SECOND).alternate("Hq").named("Hq", "test hertz");
            check(!forged.equals(atom(hz)), "forged atom equals the real one");
            check(AlternateUnits.size() == size + 1, "Hq not registered");
            try {
                deserialize(
                        serialize(EngineProxies.atom("Hq", Dimension.LENGTH, new UnitAtom[] { m }, new int[] { 1 })));
                throw new AssertionError("forged Hq read after registration");
            } catch (InvalidObjectException expected) {
                check(
                        expected.getMessage()
                                .equals("invalid unit atom: symbol Hq is already associated to a different unit"),
                        expected.getMessage());
            }
            System.out.println("ok poison");
        }

        private static void check(boolean condition, String message) {
            if (!condition) {
                throw new AssertionError(message);
            }
        }
    }

    private static InvalidObjectException assertInvalid(String message, Object forged) {
        InvalidObjectException e = assertThrows(InvalidObjectException.class, () -> deserialize(serialize(forged)));
        assertEquals(message, e.getMessage());
        return e;
    }

    private static UnitAtom atom(UomUnit<?> base) {
        return base.atoms().keySet().iterator().next();
    }

    @Test
    void forgedDimensions() throws Exception {
        assertInvalid("invalid dimension: expected 7 exponents, got 6", new UomDimension.Proxy(new int[6]));
        assertInvalid("invalid dimension: expected 7 exponents, got 8", new UomDimension.Proxy(new int[8]));
        assertInvalid("invalid dimension: null exponents", new UomDimension.Proxy(null));
        assertEquals(
                new UomDimension(1, 2, 3, 4, 5, 6, 7),
                roundTrip(new UomDimension.Proxy(new int[] { 1, 2, 3, 4, 5, 6, 7 })));
    }

    @Test
    void forgedAtoms() throws Exception {
        UnitAtom s = atom(SECOND);
        UnitAtom m = atom(METRE);
        Dimension perSecond = new Dimension(0, 0, -1, 0, 0, 0, 0);
        METRE.alternate("t1-forge-bound");
        assertInvalid(
                "invalid unit atom: symbol t1-forge-bound is already associated to a different unit",
                EngineProxies.atom("t1-forge-bound", Dimension.TIME, new UnitAtom[] { s }, new int[] { 1 }));
        ONE.divide(SECOND).alternate("Hz");
        assertInvalid(
                "invalid unit atom: symbol Hz is already associated to a different unit",
                EngineProxies.atom("Hz", Dimension.LENGTH, new UnitAtom[] { m }, new int[] { 1 }));
        assertInvalid(
                "invalid unit atom: symbol m is already associated to a different unit",
                EngineProxies.atom("m", Dimension.TIME, null, null));
        assertInvalid(
                "invalid unit atom: symbol m is already associated to a different unit",
                EngineProxies.atom("m", Dimension.TIME, new UnitAtom[] { s }, new int[] { 1 }));
        assertInvalid(
                "invalid unit atom: dimension L of t1-forge-dim does not match its definition T",
                EngineProxies.atom("t1-forge-dim", Dimension.LENGTH, new UnitAtom[] { s }, new int[] { 1 }));
        // The rejected atom was not registered.
        assertEquals("t1-forge-dim", METRE.alternate("t1-forge-dim").getSymbol());
        assertInvalid("invalid unit atom: null symbol", EngineProxies.atom(null, Dimension.TIME, null, null));
        assertInvalid("invalid unit atom: null dimension", EngineProxies.atom("t1-forge-x", null, null, null));
        assertInvalid(
                "invalid unit atom: invalid unit symbol: ' '",
                EngineProxies.atom(" ", Dimension.TIME, null, null));
        assertInvalid(
                "invalid unit atom: invalid unit symbol: 'a b'",
                EngineProxies.atom("a b", Dimension.TIME, null, null));
        assertInvalid(
                "invalid unit atom: atoms and exponents do not match",
                EngineProxies.atom("t1-forge-x", perSecond, new UnitAtom[] { s, m }, new int[] { -1 }));
        assertInvalid(
                "invalid unit atom: atoms and exponents do not match",
                EngineProxies.atom("t1-forge-x", perSecond, new UnitAtom[] { s }, null));
        assertInvalid(
                "invalid unit atom: null atom",
                EngineProxies.atom("t1-forge-x", perSecond, new UnitAtom[] { null }, new int[] { -1 }));
        assertInvalid(
                "invalid unit atom: duplicate atom s",
                EngineProxies.atom("t1-forge-x", perSecond, new UnitAtom[] { s, s }, new int[] { -1, 0 }));
        // An invalid nested dimension fails the whole read.
        InvalidObjectException e = assertThrows(
                InvalidObjectException.class,
                () -> readReplacing(
                        ONE.divide(SECOND).alternate("Hz"),
                        o -> EngineProxies.isDimension(o) ? EngineProxies.dimension(new int[3]) : o));
        assertEquals("invalid dimension: expected 7 exponents, got 3", e.getMessage());
        // A valid definition read for an unknown symbol does not register it; a later registration equals it.
        int size = AlternateUnits.size();
        UnitAtom fresh = (UnitAtom) deserialize(
                serialize(EngineProxies.atom("t1-forge-new", perSecond, new UnitAtom[] { s }, new int[] { -1 })));
        assertEquals(new UnitAtom("t1-forge-new", perSecond), fresh);
        assertEquals(size, AlternateUnits.size());
        assertEquals(fresh, atom(ONE.divide(SECOND).alternate("t1-forge-new")));
        assertThrows(MeasurementException.class, () -> METRE.alternate("t1-forge-new"));
        assertInvalid(
                "invalid unit atom: symbol t1-forge-new is already associated to a different unit",
                EngineProxies.atom("t1-forge-new", Dimension.LENGTH, new UnitAtom[] { m }, new int[] { 1 }));
    }

    @Test
    void forgedConverters() throws Exception {
        Factor two = Factor.ofLong(2);
        assertInvalid("invalid converter: null factor", forgedConverter(null, Factor.ZERO, null, null));
        assertInvalid("invalid converter: null offset", forgedConverter(Factor.ONE, null, null, null));
        assertInvalid(
                "invalid converter: step factors and offsets do not match",
                forgedConverter(two, Factor.ZERO, new Factor[] { two }, new Factor[] { Factor.ZERO, Factor.ZERO }));
        assertInvalid(
                "invalid converter: step factors and offsets do not match",
                forgedConverter(two, Factor.ZERO, new Factor[] { two }, null));
        assertInvalid(
                "invalid converter: step factors and offsets do not match",
                forgedConverter(two, Factor.ZERO, new Factor[0], new Factor[0]));
        assertInvalid(
                "invalid converter: null step",
                forgedConverter(
                        two,
                        Factor.ZERO,
                        new Factor[] { two, null },
                        new Factor[] { Factor.ZERO, Factor.ZERO }));
        assertInvalid(
                "invalid converter: steps do not compose to x -> 1 * x + 0",
                forgedConverter(
                        Factor.ONE,
                        Factor.ZERO,
                        new Factor[] { two, Factor.ofLong(3) },
                        new Factor[] { Factor.ZERO, Factor.ZERO }));
        // a factor 1/3 whose denominator is patched to 0 in the stream: numerator bytes [1], denominator bytes [3]
        byte[] third = serialize(Converters.scale(Factor.of(1, 3)));
        int at = indexOf(third, new byte[] { 0, 0, 0, 1, 1, 0, 0, 0, 1, 3 });
        assertTrue(at > 0);
        third[at + 9] = 0;
        InvalidObjectException e = assertThrows(InvalidObjectException.class, () -> deserialize(third));
        assertEquals("invalid factor: division by zero", e.getMessage());
        UomConverter copy = (UomConverter) deserialize(
                serialize(
                        forgedConverter(
                                Factor.ofLong(6),
                                Factor.ZERO,
                                new Factor[] { two, Factor.ofLong(3) },
                                new Factor[] { Factor.ZERO, Factor.ZERO })));
        assertEquals(List.of(Converters.scale(two), Converters.scale(Factor.ofLong(3))), copy.getConversionSteps());
    }

    @Test
    void stepsThatCannotBeCheckedAreNotTrusted() throws Exception {
        // an identity whose steps claim shifts by two roots: they fold neither exactly nor, at over 4096 bits, in decimal
        Factor huge = Factor.of(BigInteger.TWO.pow(5000).add(BigInteger.ONE), BigInteger.ONE, 0, 2);
        Factor r2 = Factor.ofLong(2).root(2);
        UomConverter copy = (UomConverter) deserialize(
                serialize(
                        forgedConverter(
                                Factor.ONE,
                                Factor.ZERO,
                                new Factor[] { Factor.ONE, Factor.ONE },
                                new Factor[] { huge, r2 })));
        // read as one step, so the steps agree with what it converts
        assertTrue(copy.isIdentity());
        assertEquals(List.of(copy), copy.getConversionSteps());
        assertEquals(5.0, copy.convert(5.0));
    }

    @Test
    void stepsGroupedOtherwiseStillRoundTrip() throws Exception {
        Factor r2 = Factor.ofLong(2).root(2);
        UomUnit<?> u = KELVIN.shift(1).transform(Converters.shift(r2).concatenate(Converters.shift(r2.negate())));
        assertUnitRoundTrip(u);
        assertEquals(u.toSystemConverter().getConversionSteps(), roundTrip(u).toSystemConverter().getConversionSteps());
        // When the steps do fold, they must agree with the stored values.
        assertInvalid(
                "invalid converter: steps do not compose to x -> 1 * x + 2",
                forgedConverter(
                        Factor.ONE,
                        Factor.ofLong(2),
                        new Factor[] { Factor.ONE, Factor.ONE },
                        new Factor[] { Factor.ONE, Factor.ofLong(3) }));
    }

    @Test
    void conversionStepsAreBounded() throws Exception {
        Factor[] ones = new Factor[1000];
        Factor[] zeros = new Factor[1000];
        Arrays.fill(ones, Factor.ONE);
        Arrays.fill(zeros, Factor.ZERO);
        UomConverter max = (UomConverter) deserialize(serialize(forgedConverter(Factor.ONE, Factor.ZERO, ones, zeros)));
        assertEquals(1000, max.getConversionSteps().size());
        // The writer refuses more than 1000 steps, so the forged count of 100_000 is patched into the stream.
        Factor[] few = Arrays.copyOf(ones, 999);
        byte[] bytes = serialize(forgedConverter(Factor.ONE, Factor.ZERO, few, Arrays.copyOf(zeros, 999)));
        int at = indexOf(bytes, new byte[] { 0, 0, 0x03, (byte) 0xe7 });
        assertTrue(at > 0);
        byte[] forged = patchLength(bytes, at, 100_000, 0);
        InvalidObjectException e = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> assertThrows(InvalidObjectException.class, () -> deserialize(forged)));
        assertEquals("invalid converter: too many conversion steps", e.getMessage());
    }

    @Test
    void forgedLengthIsRejectedBeforeAllocation() throws Exception {
        byte[] bytes = serialize(Factor.of(new BigInteger(1, new byte[] { 0x51, 0x52, 0x53, 0x54 }), BigInteger.ONE));
        byte[] marker = { 0, 0, 0, 4, 0x51, 0x52, 0x53, 0x54 };
        int at = indexOf(bytes, marker);
        assertTrue(at > 0);
        bytes[at] = 0x7f;
        bytes[at + 1] = (byte) 0xff;
        bytes[at + 2] = (byte) 0xff;
        bytes[at + 3] = (byte) 0xff;
        byte[] forged = Arrays.copyOf(bytes, at + 4);
        InvalidObjectException e = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> assertThrows(InvalidObjectException.class, () -> deserialize(forged)));
        assertEquals("invalid factor: factor too large", e.getMessage());
    }

    /** {@code bytes} with the four bytes at {@code at} replaced by {@code length}, the {@code drop} after them removed. */
    private static byte[] patchLength(byte[] bytes, int at, int length, int drop) {
        byte[] out = new byte[bytes.length - drop];
        System.arraycopy(bytes, 0, out, 0, at);
        out[at] = (byte) (length >>> 24);
        out[at + 1] = (byte) (length >>> 16);
        out[at + 2] = (byte) (length >>> 8);
        out[at + 3] = (byte) length;
        System.arraycopy(bytes, at + 4 + drop, out, at + 4, bytes.length - at - 4 - drop);
        return out;
    }

    @Test
    void forgedNegativeLengthAndEmptyNumber() throws Exception {
        byte[] dim = serialize(new UomDimension.Proxy(new int[] { 0x5a5a5a5a }));
        int at = indexOf(dim, new byte[] { 0, 0, 0, 1, 0x5a, 0x5a, 0x5a, 0x5a });
        assertTrue(at > 0);
        InvalidObjectException e = assertThrows(
                InvalidObjectException.class,
                () -> deserialize(patchLength(dim, at, -2, 0)));
        assertEquals("negative length -2", e.getMessage());

        byte[] factor = serialize(Factor.of(new BigInteger(1, new byte[] { 0x51, 0x52, 0x53, 0x54 }), BigInteger.ONE));
        int n = indexOf(factor, new byte[] { 0, 0, 0, 4, 0x51, 0x52, 0x53, 0x54 });
        assertTrue(n > 0);
        e = assertThrows(InvalidObjectException.class, () -> deserialize(patchLength(factor, n, 0, 4)));
        assertEquals("invalid factor: empty number", e.getMessage());
    }

    @Test
    void wrongElementTypes() throws Exception {
        int[] seen = { 0 };
        UomConverter chain = Converters.scale(Factor.ofLong(2)).concatenate(Converters.shift(Factor.ONE));
        // The third factor written is the first step factor.
        InvalidObjectException e = assertThrows(
                InvalidObjectException.class,
                () -> readReplacing(chain, o -> isFactorProxy(o) && ++seen[0] == 3 ? "x" : o));
        assertEquals(6, seen[0]);
        assertEquals("expected Factor, got java.lang.String", e.getMessage());
        e = assertThrows(
                InvalidObjectException.class,
                () -> readReplacing(
                        METRE.divide(SECOND),
                        o -> o.getClass().getName().equals("org.tinygears.uom.engine.UnitAtom$Proxy") ? "x" : o));
        assertEquals("expected UnitAtom, got java.lang.String", e.getMessage());
        e = assertThrows(
                InvalidObjectException.class,
                () -> readReplacing(
                        METRE,
                        o -> o.getClass().getName().equals("org.tinygears.uom.engine.Converter$Proxy") ? "x" : o));
        assertEquals("expected Converter, got java.lang.String", e.getMessage());
    }

    @Test
    void writeRefusesWhatReadWouldReject() throws Exception {
        UomConverter c = Converters.IDENTITY;
        for (int i = 1; i < 1000; i++) {
            c = c.concatenate(Converters.IDENTITY);
        }
        assertEquals(1000, roundTrip(c).getConversionSteps().size());
        UomConverter over = c.concatenate(Converters.IDENTITY);
        NotSerializableException e = assertThrows(NotSerializableException.class, () -> serialize(over));
        assertEquals("invalid converter: too many conversion steps", e.getMessage());

        Map<UnitAtom, Integer> atoms = new LinkedHashMap<>();
        for (int i = 0; i < 1000; i++) {
            atoms.put(new UnitAtom("t1-many-" + i, Dimension.NONE), 1);
        }
        UomUnit<?> wide = UomUnit.of(atoms, Converters.IDENTITY, null, null);
        assertEquals(wide, roundTrip(wide));
        atoms.put(new UnitAtom("t1-many-1000", Dimension.NONE), 1);
        UomUnit<?> wider = UomUnit.of(atoms, Converters.IDENTITY, null, null);
        e = assertThrows(NotSerializableException.class, () -> serialize(wider));
        assertEquals("invalid unit: too many atoms", e.getMessage());
    }

    @Test
    void inexactFoldIsCheckedInDecimal() throws Exception {
        Factor r2 = Factor.ofLong(2).root(2);
        Factor r3 = Factor.ofLong(3).root(2);
        assertInvalid(
                "invalid converter: steps do not compose to x -> 1000 * x + 7",
                forgedConverter(
                        Factor.ofLong(1000),
                        Factor.ofLong(7),
                        new Factor[] { Factor.ONE, Factor.ONE },
                        new Factor[] { r2, r3 }));
        // Off by one part in 10^30: still rejected.
        assertInvalid(
                "invalid converter: steps do not compose to x -> 1 * x + 0",
                forgedConverter(
                        Factor.ONE,
                        Factor.ZERO,
                        new Factor[] { Factor.ONE, Factor.ONE },
                        new Factor[] {
                            r2,
                            r2.multiply(Factor.of(BigInteger.TEN.pow(30).add(BigInteger.ONE), BigInteger.TEN.pow(30)))
                                    .negate() }));
        UomConverter ok = (UomConverter) deserialize(
                serialize(
                        forgedConverter(
                                Factor.ONE,
                                Factor.ZERO,
                                new Factor[] { Factor.ONE, Factor.ONE },
                                new Factor[] { r2, r2.negate() })));
        assertEquals(Converters.IDENTITY, ok);
    }

    /** A converter's stream as the facade writes it, around a forged engine proxy. */
    private static Serializable forgedConverter(
            Factor factor,
            Factor offset,
            Factor[] stepFactors,
            Factor[] stepOffsets
    ) {
        return new AffineConverter.Proxy(EngineProxies.converter(factor, offset, stepFactors, stepOffsets));
    }

    /** A unit's stream as the facade writes it, around a forged engine proxy. */
    private static Serializable forgedUnit(
            UnitAtom[] atoms,
            int[] exponents,
            UomConverter toSystem,
            String symbol,
            String name
    ) {
        org.tinygears.uom.engine.Converter c = toSystem == null ? null : AffineConverter.engine(toSystem);
        return new UomUnit.Proxy(EngineProxies.unit(atoms, exponents, c, symbol, name));
    }

    /** Whether {@code o} is the serialization proxy of a factor, which the engine does not expose. */
    private static boolean isFactorProxy(Object o) {
        return o.getClass().getName().equals("org.tinygears.uom.engine.Factor$Proxy");
    }

    private static int indexOf(byte[] bytes, byte[] part) {
        for (int i = 0; i + part.length <= bytes.length; i++) {
            if (Arrays.equals(bytes, i, i + part.length, part, 0, part.length)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void forgedUnits() throws Exception {
        UnitAtom m = atom(METRE);
        UnitAtom s = atom(SECOND);
        UomConverter id = Converters.IDENTITY;
        assertInvalid(
                "invalid unit: null converter",
                forgedUnit(new UnitAtom[] { m }, new int[] { 1 }, null, "m", null));
        assertInvalid(
                "invalid unit: zero scale factor",
                forgedUnit(new UnitAtom[] { m }, new int[] { 1 }, Converters.scale(Factor.ZERO), null, null));
        assertInvalid("invalid unit: atoms and exponents do not match", forgedUnit(null, null, id, null, null));
        assertInvalid(
                "invalid unit: atoms and exponents do not match",
                forgedUnit(new UnitAtom[] { m, s }, new int[] { 1 }, id, null, null));
        assertInvalid("invalid unit: null atom", forgedUnit(new UnitAtom[] { null }, new int[] { 1 }, id, null, null));
        assertInvalid(
                "invalid unit: duplicate atom m",
                forgedUnit(new UnitAtom[] { m, m }, new int[] { 1, 2 }, id, null, null));
        // Unsorted atoms and zero exponents are normalised on read.
        UomUnit<?> speed = (UomUnit<?>) deserialize(
                serialize(
                        forgedUnit(new UnitAtom[] { s, atom(KILOGRAM), m }, new int[] { -1, 0, 1 }, id, "v", "speed")));
        assertEquals(METRE.divide(SECOND), speed);
        assertEquals(List.of(m, s), List.copyOf(speed.atoms().keySet()));
        assertEquals("v", speed.getSymbol());
        assertEquals("speed", speed.getName());
    }

    @Test
    void textsLongerThan10000CharactersAreNeitherWrittenNorRead() throws Exception {
        String atTheBound = "n".repeat(10_000);
        String tooLong = atTheBound + "n";
        UomUnit<?> named = METRE.multiply(3).named("tri", atTheBound);
        UomUnit<?> copy = (UomUnit<?>) deserialize(serialize(named));
        assertEquals(atTheBound, copy.getName());
        assertEquals(
                "invalid unit: name longer than 10000 characters",
                assertThrows(NotSerializableException.class, () -> serialize(METRE.multiply(3).named("tri", tooLong)))
                        .getMessage());
        // The writer refuses them, so the stream is forged by replacing the strings as they are written.
        assertEquals(
                "invalid unit: name longer than 10000 characters",
                assertThrows(
                        InvalidObjectException.class,
                        () -> readReplacing(named, o -> o == atTheBound ? tooLong : o)).getMessage());
        assertEquals(
                "invalid unit: symbol longer than 10000 characters",
                assertThrows(
                        InvalidObjectException.class,
                        () -> readReplacing(named, o -> "tri".equals(o) ? "t".repeat(10_001) : o)).getMessage());
        UomUnit<?> alternate = METRE.divide(SECOND).alternate("vel");
        assertEquals(
                "invalid unit atom: symbol longer than 10000 characters",
                assertThrows(
                        InvalidObjectException.class,
                        () -> readReplacing(alternate, o -> "vel".equals(o) ? "v".repeat(10_001) : o)).getMessage());
    }

    /** Twin of a class: same field names and types, no proxy. */
    static final class Empty implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    static final class DimensionTwin implements Serializable {
        private static final long serialVersionUID = 1L;
        int length = 1;
        int mass;
        int time;
        int current;
        int temperature;
        int amount;
        int luminosity;
    }

    static final class AtomTwin implements Serializable {
        private static final long serialVersionUID = 1L;
        String symbol = "m";
        Dimension dimension = Dimension.LENGTH;
    }

    /**
     * Writes {@code twin}'s fields under {@code target}'s class descriptor: the stream a direct (proxy-less)
     * serialization of {@code target} would produce. Classes reject it in readObject before reading fields.
     */
    private static byte[] forgeAs(Class<?> target, Serializable twin) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes) {
            @Override
            protected void writeClassDescriptor(ObjectStreamClass desc) throws IOException {
                super.writeClassDescriptor(
                        desc.forClass() == twin.getClass() ? ObjectStreamClass.lookup(target) : desc);
            }
        }) {
            out.writeObject(twin);
        }
        return bytes.toByteArray();
    }

    @Test
    void directDeserializationRequiresTheProxy() {
        Map<Class<?>, Serializable> twins = new LinkedHashMap<>();
        twins.put(Factor.class, new Empty());
        twins.put(AffineConverter.class, new Empty());
        twins.put(UomUnit.class, new Empty());
        twins.put(UomDimension.class, new DimensionTwin());
        twins.put(UnitAtom.class, new AtomTwin());
        twins.forEach((target, twin) -> {
            InvalidObjectException e = assertThrows(
                    InvalidObjectException.class,
                    () -> deserialize(forgeAs(target, twin)),
                    target.getName());
            assertEquals("proxy required", e.getMessage(), target.getName());
        });
    }

    private static Object readReplacing(Object root, UnaryOperator<Object> replace) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes) {
            {
                enableReplaceObject(true);
            }

            @Override
            protected Object replaceObject(Object obj) {
                return replace.apply(obj);
            }
        }) {
            out.writeObject(root);
        }
        return deserialize(bytes.toByteArray());
    }

    private static String java() {
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }
}
