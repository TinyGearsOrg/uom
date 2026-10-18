/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.jsr.quantity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import javax.measure.Quantity;
import javax.measure.Unit;
import javax.measure.quantity.Length;
import javax.measure.quantity.Mass;
import javax.measure.quantity.Speed;
import javax.measure.spi.FormatService;
import javax.measure.spi.QuantityFactory;
import javax.measure.spi.ServiceProvider;
import javax.measure.spi.SystemOfUnitsService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.tinygears.uom.jsr.BaseUnits;
import org.tinygears.uom.jsr.TestProviderExtension;
import org.tinygears.uom.jsr.TestServiceProvider;
import org.tinygears.uom.jsr.UomUnit;
import org.tinygears.uom.jsr.spi.QuantityFactories;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(TestProviderExtension.class)
class QuantitySerializationTest {

    private static final UomUnit<Length> METRE = BaseUnits.METRE;
    private static final UomUnit<Length> KILOMETRE = METRE.multiply(1000).named("km", "kilometre");
    private static final UomUnit<Speed> KMH = UomUnit.unchecked(KILOMETRE.divide(BaseUnits.SECOND.multiply(3600)));
    private static final String LENGTH = Length.class.getName();
    private static final String UNTYPED = Untyped.class.getName();
    private static final Quantity.Scale[] SCALES = Quantity.Scale.values();

    /** Double values give the double fixture, decimal values the decimal one with DECIMAL128. */
    static final QuantityFactory<Length> LENGTH_FACTORY = new QuantityFactory<>() {
        @Override
        public Quantity<Length> create(Number value, Unit<Length> unit, Quantity.Scale scale) {
            UomUnit<Length> u = UomUnit.unchecked((UomUnit<?>) unit);
            return value instanceof BigDecimal d
                    ? new TestLengthDecimalImpl(d, u, scale, MathContext.DECIMAL128)
                    : new TestLengthImpl(value.doubleValue(), u, scale);
        }

        @Override
        public Quantity<Length> create(Number value, Unit<Length> unit) {
            return create(value, unit, Quantity.Scale.ABSOLUTE);
        }

        @Override
        public Unit<Length> getSystemUnit() {
            return METRE;
        }
    };

    @BeforeAll
    static void registerFactories() {
        TestServiceProvider.FACTORIES.put(Length.class, LENGTH_FACTORY);
        TestServiceProvider.FACTORIES.put(Speed.class, TestSpeedDecimalImpl.FACTORY);
    }

    @AfterAll
    static void removeFactories() {
        TestServiceProvider.FACTORIES.remove(Length.class);
        TestServiceProvider.FACTORIES.remove(Speed.class);
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

    private static void assertDoubleRoundTrip(AbstractDoubleQuantity<?, ?> q) throws Exception {
        AbstractDoubleQuantity<?, ?> copy = roundTrip(q);
        assertNotSame(q, copy);
        assertSame(q.getClass(), copy.getClass());
        assertEquals(q, copy);
        assertEquals(q.hashCode(), copy.hashCode());
        assertEquals(
                Double.doubleToRawLongBits(q.getValue()),
                Double.doubleToRawLongBits(copy.getValue()),
                q.toString());
        assertEquals(q.getUnit(), copy.getUnit());
        assertEquals(q.getUnit().getSymbol(), copy.getUnit().getSymbol());
        assertEquals(q.getUnit().getName(), copy.getUnit().getName());
        assertSame(q.getScale(), copy.getScale());
    }

    private static void assertDecimalRoundTrip(AbstractDecimalQuantity<?, ?> q) throws Exception {
        AbstractDecimalQuantity<?, ?> copy = roundTrip(q);
        assertNotSame(q, copy);
        assertSame(q.getClass(), copy.getClass());
        assertEquals(q, copy);
        assertEquals(q.hashCode(), copy.hashCode());
        // BigDecimal.equals compares the scale too, so 1.50 stays 1.50.
        assertEquals(q.getValue(), copy.getValue());
        assertEquals(q.getValue().scale(), copy.getValue().scale());
        assertEquals(q.mathContext(), copy.mathContext());
        assertEquals(q.getUnit(), copy.getUnit());
        assertEquals(q.getUnit().getSymbol(), copy.getUnit().getSymbol());
        assertSame(q.getScale(), copy.getScale());
    }

    @Test
    void doubleValuesKeepTheirBits() throws Exception {
        double[] values = {
            0.0,
            -0.0,
            1.5,
            -2.25e300,
            Double.NaN,
            Double.longBitsToDouble(0x7ff8_0000_0000_0123L),
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            Double.MIN_VALUE,
            -Double.MAX_VALUE };
        for (double v : values) {
            for (Quantity.Scale s : SCALES) {
                assertDoubleRoundTrip(new TestLengthImpl(v, METRE, s));
                assertDoubleRoundTrip(new TestLengthImpl(v, KILOMETRE, s));
                assertDoubleRoundTrip(new TestSpeedImpl(v, KMH, s));
                assertDoubleRoundTrip(new UntypedDoubleQuantity(v, UomUnit.unchecked(KMH.multiply(METRE)), s));
            }
        }
        TestLengthImpl negativeZero = roundTrip(new TestLengthImpl(-0.0, METRE));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(negativeZero.getValue()));
        assertFalse(negativeZero.equals(new TestLengthImpl(0.0, METRE)));
        TestLengthImpl relative = roundTrip(new TestLengthImpl(3.0, KILOMETRE, Quantity.Scale.RELATIVE));
        assertSame(Quantity.Scale.RELATIVE, relative.getScale());
    }

    @Test
    void decimalValuesKeepDigitsScaleAndContext() throws Exception {
        List<BigDecimal> values = List.of(
                new BigDecimal("1.50"),
                BigDecimal.ZERO,
                new BigDecimal("0E+5"),
                new BigDecimal("0.000"),
                new BigDecimal("-123456789012345678901234567890.123456789"),
                new BigDecimal(BigInteger.ONE, 100_000),
                new BigDecimal(BigInteger.ONE, -100_000),
                new BigDecimal(BigInteger.TEN.pow(5000).negate(), 7));
        List<MathContext> contexts = List.of(
                MathContext.UNLIMITED,
                MathContext.DECIMAL32,
                MathContext.DECIMAL128,
                new MathContext(5, RoundingMode.CEILING),
                new MathContext(0, RoundingMode.UNNECESSARY));
        for (BigDecimal v : values) {
            for (MathContext mc : contexts) {
                for (Quantity.Scale s : SCALES) {
                    assertDecimalRoundTrip(new TestLengthDecimalImpl(v, KILOMETRE, s, mc));
                    assertDecimalRoundTrip(new TestSpeedDecimalImpl(v, KMH, s, mc));
                    assertDecimalRoundTrip(
                            new UntypedDecimalQuantity(v, UomUnit.unchecked(KMH.multiply(METRE)), s, mc));
                }
            }
        }
    }

    @Test
    void digitsBeyondTheContextSurviveUnrounded() throws Exception {
        BigDecimal v = new BigDecimal("3.14159265358979323846264338327950288419716939937510");
        TestLengthDecimalImpl q = new TestLengthDecimalImpl(v, METRE, Quantity.Scale.ABSOLUTE, MathContext.DECIMAL32);
        TestLengthDecimalImpl copy = roundTrip(q);
        assertEquals(v, copy.getValue());
        assertEquals(51, copy.getValue().precision());
        assertEquals(MathContext.DECIMAL32, copy.mathContext());
        // The context still rounds what is computed from the copy.
        assertEquals(new BigDecimal("3.141593"), copy.to(METRE).getValue());
    }

    @Test
    void untypedQuantitiesStayUntyped() throws Exception {
        UomUnit<?> area = METRE.multiply(METRE);
        Quantity<?> product = new TestLengthImpl(2.0, METRE).multiply(new TestLengthImpl(3.0, METRE));
        assertSame(UntypedDoubleQuantity.class, product.getClass());
        assertEquals(product, roundTrip(product));
        Quantity<?> decimalProduct = new TestLengthDecimalImpl(new BigDecimal("2.0"), METRE)
                .multiply(new TestLengthDecimalImpl(new BigDecimal("3.00"), METRE));
        assertSame(UntypedDecimalQuantity.class, decimalProduct.getClass());
        assertDecimalRoundTrip((AbstractDecimalQuantity<?, ?>) decimalProduct);
        assertDoubleRoundTrip(UntypedDoubleQuantity.of(1.0, area));
        assertDecimalRoundTrip(UntypedDecimalQuantity.of(BigDecimal.TEN, BaseUnits.ONE, MathContext.UNLIMITED));
        // Untyped quantities never reach a provider.
        assertDoubleRoundTrip(UntypedDoubleQuantity.of(1.0, BaseUnits.KILOGRAM));
    }

    @Test
    void readingTwiceGivesEqualDistinctObjects() throws Exception {
        TestLengthDecimalImpl q = new TestLengthDecimalImpl(new BigDecimal("2.50"), KILOMETRE);
        byte[] bytes = serialize(q);
        Object a = deserialize(bytes);
        Object b = deserialize(bytes);
        assertEquals(a, b);
        assertNotSame(a, b);
        assertArrayEquals(bytes, serialize(a));
    }

    @Test
    void bytesDependOnValuesNotIdentity() throws Exception {
        UomUnit<Length> km1 = METRE.multiply(1000).named("km", "kilometre");
        UomUnit<Length> km2 = METRE.multiply(1000).named(new String("km"), new String("kilometre"));
        assertArrayEquals(
                serialize(new TestLengthDecimalImpl(new BigDecimal("1.5"), km1)),
                serialize(new TestLengthDecimalImpl(new BigDecimal("1.5"), km2)));
        assertArrayEquals(serialize(new TestLengthImpl(1.5, km1)), serialize(new TestLengthImpl(1.5, km2)));
        // Two quantities in one stream share nothing, so the second is written in full.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TestLengthImpl q = new TestLengthImpl(1.5, km1);
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(q);
            out.writeObject(new TestLengthImpl(1.5, km2));
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals(q, in.readObject());
            assertEquals(q, in.readObject());
        }
    }

    private static QuantityProxy doubleProxy(String kind, String type, UomUnit<?> unit, String scale, double v) {
        return new QuantityProxy(kind, type, unit, scale, Double.doubleToRawLongBits(v), null, 0, 0, null);
    }

    private static QuantityProxy decimalProxy(
            String kind,
            String type,
            UomUnit<?> unit,
            byte[] unscaled,
            int scale,
            int precision,
            String rounding
    ) {
        return new QuantityProxy(kind, type, unit, "ABSOLUTE", 0, unscaled, scale, precision, rounding);
    }

    private static QuantityProxy decimalProxy(byte[] unscaled, int scale, int precision, String rounding) {
        return decimalProxy(QuantityProxy.DECIMAL, LENGTH, METRE, unscaled, scale, precision, rounding);
    }

    private static InvalidObjectException assertInvalid(String message, Object forged) {
        InvalidObjectException e = assertThrows(InvalidObjectException.class, () -> deserialize(serialize(forged)));
        assertEquals(message, e.getMessage());
        return e;
    }

    @Test
    void forgedProxiesThatAreValid() throws Exception {
        assertEquals(
                new TestLengthImpl(2.0, KILOMETRE, Quantity.Scale.RELATIVE),
                deserialize(serialize(doubleProxy("double", LENGTH, KILOMETRE, "RELATIVE", 2.0))));
        TestLengthDecimalImpl d = (TestLengthDecimalImpl) deserialize(
                serialize(decimalProxy(new byte[] { 1 }, 100_000, 7, "HALF_DOWN")));
        assertEquals(new BigDecimal(BigInteger.ONE, 100_000), d.getValue());
        assertEquals(new MathContext(7, RoundingMode.HALF_DOWN), d.mathContext());
        assertEquals(
                new BigDecimal(BigInteger.ONE, -100_000),
                ((TestLengthDecimalImpl) deserialize(serialize(decimalProxy(new byte[] { 1 }, -100_000, 0, "UP"))))
                        .getValue());
        // An Untyped quantity of any dimension is valid; the unit's own validation still applies.
        assertEquals(
                UntypedDoubleQuantity.of(4.0, BaseUnits.SECOND),
                deserialize(
                        serialize(
                                doubleProxy(
                                        QuantityProxy.UNTYPED_DOUBLE,
                                        UNTYPED,
                                        BaseUnits.SECOND,
                                        "ABSOLUTE",
                                        4.0))));
    }

    @Test
    void forgedFieldsAreRejected() {
        assertInvalid("invalid quantity: null kind", doubleProxy(null, LENGTH, METRE, "ABSOLUTE", 1));
        assertInvalid("invalid quantity: unknown kind float", doubleProxy("float", LENGTH, METRE, "ABSOLUTE", 1));
        assertInvalid("invalid quantity: null type", doubleProxy("double", null, METRE, "ABSOLUTE", 1));
        assertInvalid(
                "invalid quantity: unknown type org.tinygears.uom.jsr.quantity.TestLength",
                doubleProxy("double", TestLength.class.getName(), METRE, "ABSOLUTE", 1));
        assertInvalid("invalid quantity: unknown type Length", doubleProxy("double", "Length", METRE, "ABSOLUTE", 1));
        assertInvalid(
                "invalid quantity: kind untyped-double does not match type javax.measure.quantity.Length",
                doubleProxy(QuantityProxy.UNTYPED_DOUBLE, LENGTH, METRE, "ABSOLUTE", 1));
        assertInvalid(
                "invalid quantity: kind decimal does not match type org.tinygears.uom.jsr.quantity.Untyped",
                decimalProxy("decimal", UNTYPED, METRE, new byte[] { 1 }, 0, 0, "HALF_UP"));
        assertInvalid(
                "invalid quantity: kind double does not match type org.tinygears.uom.jsr.quantity.Untyped",
                doubleProxy("double", UNTYPED, METRE, "ABSOLUTE", 1));
        assertInvalid(
                "invalid quantity: kind untyped-decimal does not match type javax.measure.quantity.Length",
                decimalProxy(QuantityProxy.UNTYPED_DECIMAL, LENGTH, METRE, new byte[] { 1 }, 0, 0, "HALF_UP"));
        assertInvalid("invalid quantity: null unit", doubleProxy("double", LENGTH, null, "ABSOLUTE", 1));
        assertInvalid("invalid quantity: null scale", doubleProxy("double", LENGTH, METRE, null, 1));
        assertInvalid(
                "invalid quantity: unknown scale ABSOLUTELY",
                doubleProxy("double", LENGTH, METRE, "ABSOLUTELY", 1));
        assertInvalid("invalid quantity: unknown scale absolute", doubleProxy("double", LENGTH, METRE, "absolute", 1));
        assertInvalid(
                "invalid quantity: unit s of dimension T does not measure javax.measure.quantity.Length",
                doubleProxy("double", LENGTH, BaseUnits.SECOND, "ABSOLUTE", 1));
        assertInvalid(
                "invalid quantity: unit m of dimension L does not measure javax.measure.quantity.Speed",
                decimalProxy("decimal", Speed.class.getName(), METRE, new byte[] { 1 }, 0, 0, "HALF_UP"));
    }

    @Test
    void textsLongerThan10000CharactersAreRejected() {
        String tooLong = "x".repeat(10_001);
        assertInvalid(
                "invalid quantity: kind longer than 10000 characters",
                doubleProxy(tooLong, LENGTH, METRE, "ABSOLUTE", 1));
        assertInvalid(
                "invalid quantity: type longer than 10000 characters",
                doubleProxy("double", tooLong, METRE, "ABSOLUTE", 1));
        assertInvalid(
                "invalid quantity: scale longer than 10000 characters",
                doubleProxy("double", LENGTH, METRE, tooLong, 1));
        assertInvalid(
                "invalid quantity: rounding mode longer than 10000 characters",
                decimalProxy(new byte[] { 1 }, 0, 7, tooLong));
        // at the bound the text is read and fails as an unknown kind
        assertInvalid(
                "invalid quantity: unknown kind " + "x".repeat(10_000),
                doubleProxy("x".repeat(10_000), LENGTH, METRE, "ABSOLUTE", 1));
    }

    @Test
    void forgedDecimalsAreRejected() {
        assertInvalid("invalid quantity: null value", decimalProxy(null, 0, 0, "HALF_UP"));
        assertInvalid("invalid quantity: empty value", decimalProxy(new byte[0], 0, 0, "HALF_UP"));
        assertInvalid("invalid quantity: scale out of range: 100001", decimalProxy(new byte[] { 1 }, 100_001, 0, "UP"));
        assertInvalid(
                "invalid quantity: scale out of range: -100001",
                decimalProxy(new byte[] { 1 }, -100_001, 0, "UP"));
        assertInvalid(
                "invalid quantity: scale out of range: -2147483648",
                decimalProxy(new byte[] { 1 }, Integer.MIN_VALUE, 0, "UP"));
        assertInvalid("invalid quantity: negative precision -1", decimalProxy(new byte[] { 1 }, 0, -1, "UP"));
        assertInvalid("invalid quantity: null rounding mode", decimalProxy(new byte[] { 1 }, 0, 7, null));
        assertInvalid(
                "invalid quantity: unknown rounding mode HALF_SIDEWAYS",
                decimalProxy(new byte[] { 1 }, 0, 7, "HALF_SIDEWAYS"));
        InvalidObjectException e = assertInvalid(
                "invalid quantity: value too large",
                decimalProxy(new byte[QuantityProxy.MAX_VALUE_BYTES + 1], 0, 0, "UP"));
        assertNull(e.getCause());
    }

    @Test
    void valueByteCapIsExact() throws Exception {
        BigInteger largest = BigInteger.TEN.pow(AbstractDecimalQuantity.MAX_PRECISION).subtract(BigInteger.ONE);
        byte[] max = largest.toByteArray();
        assertEquals(QuantityProxy.MAX_VALUE_BYTES, max.length);
        assertEquals(QuantityProxy.MAX_VALUE_BYTES, largest.negate().toByteArray().length);
        TestLengthDecimalImpl q = (TestLengthDecimalImpl) deserialize(serialize(decimalProxy(max, 3, 0, "UP")));
        assertEquals(new BigDecimal(largest, 3), q.getValue());
        assertDecimalRoundTrip(q);
        // Within the byte cap, the digits are still bounded.
        assertInvalid(
                "invalid quantity: too many digits: 50001",
                decimalProxy(BigInteger.TEN.pow(AbstractDecimalQuantity.MAX_PRECISION).toByteArray(), 0, 0, "UP"));
        // The writer refuses what the reader would reject.
        BigDecimal over = new BigDecimal(BigInteger.ONE.shiftLeft(8 * QuantityProxy.MAX_VALUE_BYTES));
        NotSerializableException e = assertThrows(
                NotSerializableException.class,
                () -> QuantityProxy.ofDecimal(Length.class, METRE, Quantity.Scale.ABSOLUTE, over, MAX_CONTEXT));
        assertEquals("invalid quantity: value too large", e.getMessage());
    }

    @Test
    void forgedValueLengthIsRejectedBeforeAllocation() throws Exception {
        byte[] bytes = serialize(decimalProxy(new byte[] { 0x51, 0x52, 0x53, 0x54 }, 0, 0, "UP"));
        int at = indexOf(bytes, new byte[] { 0, 0, 0, 4, 0x51, 0x52, 0x53, 0x54 });
        assertTrue(at > 0);
        for (int length : new int[] { Integer.MAX_VALUE, QuantityProxy.MAX_VALUE_BYTES + 1 }) {
            byte[] forged = Arrays.copyOf(bytes, at + 4);
            forged[at] = (byte) (length >>> 24);
            forged[at + 1] = (byte) (length >>> 16);
            forged[at + 2] = (byte) (length >>> 8);
            forged[at + 3] = (byte) length;
            InvalidObjectException e = assertTimeoutPreemptively(
                    Duration.ofSeconds(1),
                    () -> assertThrows(InvalidObjectException.class, () -> deserialize(forged)));
            assertEquals("invalid quantity: value too large", e.getMessage());
        }
        byte[] negative = bytes.clone();
        negative[at] = (byte) 0xff;
        negative[at + 1] = (byte) 0xff;
        negative[at + 2] = (byte) 0xff;
        negative[at + 3] = (byte) 0xfe;
        InvalidObjectException e = assertThrows(InvalidObjectException.class, () -> deserialize(negative));
        assertEquals("negative length -2", e.getMessage());
    }

    private static int indexOf(byte[] bytes, byte[] part) {
        for (int i = 0; i + part.length <= bytes.length; i++) {
            if (Arrays.equals(bytes, i, i + part.length, part, 0, part.length)) {
                return i;
            }
        }
        return -1;
    }

    static volatile boolean tripped;

    /** Initialising it (as {@code Class.forName} would) sets {@link #tripped}. */
    static final class Tripwire {
        static {
            tripped = true;
        }
    }

    @Test
    void typeNamesAreNeverLoaded() {
        String name = Tripwire.class.getName();
        assertInvalid("invalid quantity: unknown type " + name, doubleProxy("double", name, METRE, "ABSOLUTE", 1));
        assertFalse(tripped);
        assertInvalid(
                "invalid quantity: unknown type java.lang.Runtime",
                doubleProxy("double", "java.lang.Runtime", METRE, "ABSOLUTE", 1));
        assertInvalid(
                "invalid quantity: unknown type javax.measure.Quantity",
                doubleProxy("double", "javax.measure.Quantity", METRE, "ABSOLUTE", 1));
    }

    @Test
    void wrongElementTypeIsRejected() {
        InvalidObjectException e = assertThrows(
                InvalidObjectException.class,
                () -> readReplacing(
                        new TestLengthImpl(1.0, METRE),
                        o -> o.getClass().getName().equals(UomUnit.class.getName() + "$Proxy") ? "m" : o));
        assertEquals("expected UomUnit, got java.lang.String", e.getMessage());
        e = assertThrows(
                InvalidObjectException.class,
                () -> readReplacing(
                        new TestLengthImpl(1.0, METRE),
                        o -> "ABSOLUTE".equals(o) ? Quantity.Scale.ABSOLUTE : o));
        assertEquals("expected String, got javax.measure.Quantity$Scale", e.getMessage());
    }

    @Test
    void providerWithoutAFactoryNamesTheType() throws Exception {
        byte[] mass = serialize(doubleProxy("double", Mass.class.getName(), BaseUnits.KILOGRAM, "ABSOLUTE", 1));
        InvalidObjectException e = assertThrows(InvalidObjectException.class, () -> deserialize(mass));
        assertEquals("invalid quantity: no quantity factory for javax.measure.quantity.Mass", e.getMessage());
        assertTrue(e.getCause() instanceof IllegalArgumentException, String.valueOf(e.getCause()));
        byte[] length = serialize(new TestLengthImpl(1.0, METRE));
        withProvider(new FixedProvider(null), () -> {
            InvalidObjectException n = assertThrows(InvalidObjectException.class, () -> deserialize(length));
            assertEquals("invalid quantity: no quantity factory for javax.measure.quantity.Length", n.getMessage());
            // a null factory is reported by Providers, as a missing one
            assertTrue(n.getCause() instanceof IllegalArgumentException, String.valueOf(n.getCause()));
        });
        withProvider(new FixedProvider(new UnsupportedOperationException("nope")), () -> {
            InvalidObjectException n = assertThrows(InvalidObjectException.class, () -> deserialize(length));
            assertEquals("invalid quantity: no quantity factory for javax.measure.quantity.Length", n.getMessage());
            assertTrue(n.getCause() instanceof UnsupportedOperationException);
        });
    }

    /** A provider whose factory lookup throws {@code failure}, as a broken or uninitialisable provider does. */
    static final class ThrowingProvider extends FixedProvider {
        private final Throwable failure;

        ThrowingProvider(Throwable failure) {
            super(null);
            this.failure = failure;
        }

        @Override
        public <Q extends Quantity<Q>> QuantityFactory<Q> getQuantityFactory(Class<Q> type) {
            if (failure instanceof Error e) {
                throw e;
            }
            throw (RuntimeException) failure;
        }
    }

    @Test
    void providerFailuresBecomeInvalidObjectException() throws Exception {
        byte[] length = serialize(new TestLengthImpl(1.0, METRE));
        for (Throwable failure : List.of(
                new IllegalStateException("No measurement ServiceProvider found"),
                new java.util.ServiceConfigurationError("broken provider"),
                new IllegalStateException())) {
            withProvider(new ThrowingProvider(failure), () -> {
                InvalidObjectException e = assertThrows(InvalidObjectException.class, () -> deserialize(length));
                String message = failure.getMessage() != null ? failure.getMessage() : failure.getClass().getName();
                assertEquals("invalid quantity: " + message, e.getMessage());
                assertSame(failure, e.getCause());
            });
        }
    }

    @Test
    void factoryOfTheWrongKindOrFailingIsRejected() throws Exception {
        TestServiceProvider.FACTORIES.put(Speed.class, TestSpeedImpl.FACTORY);
        try {
            InvalidObjectException e = assertThrows(
                    InvalidObjectException.class,
                    () -> roundTrip(
                            new TestSpeedDecimalImpl(
                                    BigDecimal.ONE,
                                    KMH,
                                    Quantity.Scale.ABSOLUTE,
                                    MathContext.DECIMAL64)));
            assertEquals(
                    "invalid quantity: the factory for javax.measure.quantity.Speed created "
                            + TestSpeedImpl.class.getName() + ", not a decimal quantity of this implementation",
                    e.getMessage());
            TestServiceProvider.FACTORIES.put(Speed.class, new FailingFactory());
            e = assertThrows(
                    InvalidObjectException.class,
                    () -> roundTrip(new TestSpeedImpl(1.0, KMH, Quantity.Scale.ABSOLUTE)));
            assertEquals("invalid quantity: factory failed", e.getMessage());
            assertTrue(e.getCause() instanceof IllegalStateException);
            TestServiceProvider.FACTORIES.put(Speed.class, new FailingFactory() {
                @Override
                public Quantity<Speed> create(Number value, Unit<Speed> unit, Quantity.Scale scale) {
                    return null;
                }
            });
            e = assertThrows(
                    InvalidObjectException.class,
                    () -> roundTrip(new TestSpeedImpl(1.0, KMH, Quantity.Scale.ABSOLUTE)));
            assertEquals(
                    "invalid quantity: the factory for javax.measure.quantity.Speed created null, not a double"
                            + " quantity of this implementation",
                    e.getMessage());
        } finally {
            TestServiceProvider.FACTORIES.put(Speed.class, TestSpeedDecimalImpl.FACTORY);
        }
    }

    /** Rounds values to float or DECIMAL32 and drops the scale, as a lossy provider might. */
    static final class LossyFactory extends FailingFactory {
        @Override
        public Quantity<Speed> create(Number value, Unit<Speed> unit, Quantity.Scale scale) {
            UomUnit<Speed> u = UomUnit.unchecked((UomUnit<?>) unit);
            return value instanceof BigDecimal d
                    ? new TestSpeedDecimalImpl(
                            d.round(MathContext.DECIMAL32),
                            u,
                            Quantity.Scale.ABSOLUTE,
                            MathContext.DECIMAL32)
                    : new TestSpeedImpl((float) value.doubleValue(), u, Quantity.Scale.ABSOLUTE);
        }
    }

    @Test
    void factoryResultIsRecreatedWithTheExactValueScaleAndContext() throws Exception {
        TestServiceProvider.FACTORIES.put(Speed.class, new LossyFactory());
        try {
            assertDoubleRoundTrip(new TestSpeedImpl(0.1, KMH, Quantity.Scale.RELATIVE));
            assertDecimalRoundTrip(
                    new TestSpeedDecimalImpl(
                            new BigDecimal("0.1234567890123"),
                            KMH,
                            Quantity.Scale.RELATIVE,
                            new MathContext(3, RoundingMode.FLOOR)));
        } finally {
            TestServiceProvider.FACTORIES.put(Speed.class, TestSpeedDecimalImpl.FACTORY);
        }
    }

    private static final MathContext MAX_CONTEXT = new MathContext(50_000, RoundingMode.HALF_UP);
    private static final MathContext OVER_CONTEXT = new MathContext(50_001, RoundingMode.HALF_UP);

    @Test
    void contextPrecisionIsBoundedOnEveryPath() throws Exception {
        assertDecimalRoundTrip(new TestLengthDecimalImpl(BigDecimal.ONE, METRE, Quantity.Scale.ABSOLUTE, MAX_CONTEXT));
        assertEquals(
                MAX_CONTEXT,
                ((TestLengthDecimalImpl) deserialize(serialize(decimalProxy(new byte[] { 1 }, 0, 50_000, "HALF_UP"))))
                        .mathContext());
        assertInvalid(
                "invalid quantity: precision out of range: 50001",
                decimalProxy(new byte[] { 1 }, 0, 50_001, "HALF_UP"));
        assertInvalid(
                "invalid quantity: precision out of range: 2147483647",
                decimalProxy(
                        QuantityProxy.UNTYPED_DECIMAL,
                        UNTYPED,
                        METRE,
                        new byte[] { 1 },
                        0,
                        Integer.MAX_VALUE,
                        "UP"));
        NotSerializableException w = assertThrows(
                NotSerializableException.class,
                () -> QuantityProxy
                        .ofDecimal(Length.class, METRE, Quantity.Scale.ABSOLUTE, BigDecimal.ONE, OVER_CONTEXT));
        assertEquals("invalid quantity: precision out of range: 50001", w.getMessage());
        ArithmeticException e = assertThrows(
                ArithmeticException.class,
                () -> new TestLengthDecimalImpl(BigDecimal.ONE, METRE, Quantity.Scale.ABSOLUTE, OVER_CONTEXT));
        assertEquals("precision out of range: 50001", e.getMessage());
        e = assertThrows(
                ArithmeticException.class,
                () -> UntypedDecimalQuantity.of(BigDecimal.ONE, METRE, new MathContext(Integer.MAX_VALUE)));
        assertEquals("precision out of range: 2147483647", e.getMessage());
        e = assertThrows(ArithmeticException.class, () -> new TestLengthImpl(1.5, METRE).toDecimal(OVER_CONTEXT));
        assertEquals("precision out of range: 50001", e.getMessage());
        e = assertThrows(
                ArithmeticException.class,
                () -> new TestLengthDecimalImpl(BigDecimal.ONE, METRE).toDecimal(OVER_CONTEXT));
        assertEquals("precision out of range: 50001", e.getMessage());
        assertEquals(
                MAX_CONTEXT,
                ((TestLengthDecimalImpl) new TestLengthImpl(1.5, METRE).toDecimal(MAX_CONTEXT)).mathContext());
    }

    @Test
    void boundsAreCheckedBeforeTheFactoryCreates() {
        int[] calls = { 0 };
        TestServiceProvider.FACTORIES.put(Speed.class, new FailingFactory() {
            @Override
            public Quantity<Speed> create(Number value, Unit<Speed> unit, Quantity.Scale scale) {
                calls[0]++;
                return TestSpeedDecimalImpl.FACTORY.create(value, unit, scale);
            }
        });
        try {
            String speed = Speed.class.getName();
            assertInvalid(
                    "invalid quantity: precision out of range: 50001",
                    decimalProxy("decimal", speed, KMH, new byte[] { 1 }, 0, 50_001, "UP"));
            assertInvalid(
                    "invalid quantity: scale out of range: 100001",
                    decimalProxy("decimal", speed, KMH, new byte[] { 1 }, 100_001, 7, "UP"));
            assertEquals(0, calls[0]);
        } finally {
            TestServiceProvider.FACTORIES.put(Speed.class, TestSpeedDecimalImpl.FACTORY);
        }
    }

    @Test
    void arithmeticAtTheMaximumPrecisionIsBounded() {
        TestLengthDecimalImpl q = new TestLengthDecimalImpl(
                new BigDecimal("1.5"),
                METRE,
                Quantity.Scale.ABSOLUTE,
                MAX_CONTEXT);
        UomUnit<Length> foot = METRE.multiply(new BigDecimal("0.3048"));
        // Mostly the first computation of pi; the bound only catches quadratic or worse.
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            assertEquals(new BigDecimal("2.25"), q.multiply(1.5).getValue());
            assertEquals(50_000, ((BigDecimal) q.divide(7).getValue()).precision());
            assertEquals(new BigDecimal("0.0015"), q.to(KILOMETRE).getValue());
            assertEquals(50_000, ((BigDecimal) q.to(foot).getValue()).precision());
            UomUnit<Length> piMetre = UomUnit.unchecked(METRE.scaledBy(org.tinygears.uom.engine.Factor.PI));
            assertEquals(50_000, ((BigDecimal) q.to(piMetre).getValue()).precision());
            assertEquals(
                    1,
                    q.compareTo(
                            new TestLengthDecimalImpl(
                                    BigDecimal.ONE,
                                    piMetre.multiply(new BigDecimal("0.4")),
                                    Quantity.Scale.ABSOLUTE,
                                    MAX_CONTEXT)));
        });
    }

    /** A factory whose system unit is null or foreign, or whose create throws without a message. */
    static final class OddFactory extends FailingFactory {
        private final Unit<Speed> system;

        OddFactory(Unit<Speed> system) {
            this.system = system;
        }

        @Override
        public Quantity<Speed> create(Number value, Unit<Speed> unit, Quantity.Scale scale) {
            throw new IllegalStateException();
        }

        @Override
        public Unit<Speed> getSystemUnit() {
            return system;
        }
    }

    @Test
    void unexpectedFactoriesGetPreciseMessages() {
        TestSpeedImpl q = new TestSpeedImpl(1.0, KMH, Quantity.Scale.ABSOLUTE);
        @SuppressWarnings("unchecked")
        Unit<Speed> foreign = (Unit<Speed>) java.lang.reflect.Proxy
                .newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Unit.class }, (p, m, a) -> null);
        try {
            TestServiceProvider.FACTORIES.put(Speed.class, new OddFactory(null));
            InvalidObjectException e = assertThrows(InvalidObjectException.class, () -> roundTrip(q));
            assertEquals(
                    "invalid quantity: factory for javax.measure.quantity.Speed has no system unit",
                    e.getMessage());
            TestServiceProvider.FACTORIES.put(Speed.class, new OddFactory(foreign));
            e = assertThrows(InvalidObjectException.class, () -> roundTrip(q));
            assertEquals(
                    "invalid quantity: factory for javax.measure.quantity.Speed has a foreign system unit",
                    e.getMessage());
            TestServiceProvider.FACTORIES.put(Speed.class, new OddFactory(TestSpeedImpl.FACTORY.getSystemUnit()));
            e = assertThrows(InvalidObjectException.class, () -> roundTrip(q));
            assertEquals("invalid quantity: java.lang.IllegalStateException", e.getMessage());
            assertTrue(e.getCause() instanceof IllegalStateException);
        } finally {
            TestServiceProvider.FACTORIES.put(Speed.class, TestSpeedDecimalImpl.FACTORY);
        }
    }

    /** Throws from create; its system unit is m/s. */
    static class FailingFactory implements QuantityFactory<Speed> {
        @Override
        public Quantity<Speed> create(Number value, Unit<Speed> unit, Quantity.Scale scale) {
            throw new IllegalStateException("factory failed");
        }

        @Override
        public Quantity<Speed> create(Number value, Unit<Speed> unit) {
            return create(value, unit, Quantity.Scale.ABSOLUTE);
        }

        @Override
        public Unit<Speed> getSystemUnit() {
            return TestSpeedImpl.FACTORY.getSystemUnit();
        }
    }

    /** The typed interface of {@link OtherLength}. */
    interface OtherLengthType extends Length, UomQuantity<Length, OtherLengthType> {
    }

    /** Another implementation of Length, created only by {@link OtherProvider}. */
    static final class OtherLength extends AbstractDoubleQuantity<Length, OtherLengthType> implements OtherLengthType {
        @Serial
        private static final long serialVersionUID = 1L;

        OtherLength(double value, UomUnit<Length> unit, Quantity.Scale scale) {
            super(value, unit, scale);
        }

        @Override
        protected OtherLengthType create(double value, UomUnit<Length> unit, Quantity.Scale scale) {
            return new OtherLength(value, unit, scale);
        }

        @Override
        protected OtherLengthType createDecimal(
                BigDecimal value,
                UomUnit<Length> unit,
                Quantity.Scale scale,
                MathContext mc
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected Class<Length> quantityType() {
            return Length.class;
        }
    }

    /** A provider of higher priority than the test provider; its Length factory creates {@link OtherLength}. */
    static final class OtherProvider extends FixedProvider {
        OtherProvider() {
            super(null);
        }

        @Override
        public int getPriority() {
            return 1000;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <Q extends Quantity<Q>> QuantityFactory<Q> getQuantityFactory(Class<Q> type) {
            if (type != Length.class) {
                return null;
            }
            return (QuantityFactory<Q>) new QuantityFactory<Length>() {
                @Override
                public Quantity<Length> create(Number value, Unit<Length> unit, Quantity.Scale scale) {
                    return new OtherLength(value.doubleValue(), UomUnit.unchecked((UomUnit<?>) unit), scale);
                }

                @Override
                public Quantity<Length> create(Number value, Unit<Length> unit) {
                    return create(value, unit, Quantity.Scale.ABSOLUTE);
                }

                @Override
                public Unit<Length> getSystemUnit() {
                    return METRE;
                }
            };
        }
    }

    @Test
    void readUsesTheCurrentProvider() throws Exception {
        TestLengthImpl q = new TestLengthImpl(2.5, KILOMETRE, Quantity.Scale.RELATIVE);
        byte[] bytes = serialize(q);
        withProvider(new OtherProvider(), () -> {
            Object read = deserialize(bytes);
            assertSame(OtherLength.class, read.getClass());
            OtherLength other = (OtherLength) read;
            assertEquals(2.5, other.getValue());
            assertEquals(KILOMETRE, other.getUnit());
            assertSame(Quantity.Scale.RELATIVE, other.getScale());
            // Written by the other implementation, read back by the test provider below.
            assertArrayEquals(bytes, serialize(other));
        });
        assertEquals(q, deserialize(bytes));
    }

    /** A provider whose factory lookup returns null or throws {@code failure}. */
    // Implements the hook so that, as the current provider, it is the one asked for factories.
    @SuppressWarnings("exports") // test code compiled into the module
    static class FixedProvider extends ServiceProvider implements QuantityFactories {
        private final RuntimeException failure;

        FixedProvider(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public SystemOfUnitsService getSystemOfUnitsService() {
            return null;
        }

        @Override
        public FormatService getFormatService() {
            return null;
        }

        @Override
        public <Q extends Quantity<Q>> QuantityFactory<Q> getQuantityFactory(Class<Q> type) {
            if (failure != null) {
                throw failure;
            }
            return null;
        }
    }

    interface Body {
        void run() throws Exception;
    }

    private static void withProvider(ServiceProvider provider, Body body) throws Exception {
        ServiceProvider before = ServiceProvider.current();
        ServiceProvider.setCurrent(provider);
        try {
            body.run();
        } finally {
            ServiceProvider.setCurrent(before);
        }
    }

    /** A user quantity type, outside the allow-list. */
    interface Custom extends UomQuantity<Custom, Custom> {
    }

    static final class CustomQuantity extends AbstractDoubleQuantity<Custom, Custom> implements Custom {
        @Serial
        private static final long serialVersionUID = 1L;

        CustomQuantity(double value) {
            super(value, UomUnit.unchecked(BaseUnits.METRE), Quantity.Scale.ABSOLUTE);
        }

        @Override
        protected Custom create(double value, UomUnit<Custom> unit, Quantity.Scale scale) {
            return new CustomQuantity(value);
        }

        @Override
        protected Custom createDecimal(BigDecimal value, UomUnit<Custom> unit, Quantity.Scale scale, MathContext mc) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected Class<Custom> quantityType() {
            return Custom.class;
        }
    }

    @Test
    void writerRefusesUnknownTypes() {
        NotSerializableException e = assertThrows(
                NotSerializableException.class,
                () -> serialize(new CustomQuantity(1)));
        assertEquals("invalid quantity: unknown type " + Custom.class.getName(), e.getMessage());
    }

    /** Twins of a quantity class and its base class, without the proxy and without fields. */
    static class BaseTwin implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    static final class Twin extends BaseTwin {
        private static final long serialVersionUID = 1L;
    }

    /**
     * Writes a {@link Twin} under the descriptors of {@code target} and, if {@code base} is not null, of
     * {@code base} in place of {@link BaseTwin}: what a proxy-less stream of {@code target} would be.
     */
    private static byte[] forgeAs(Class<?> target, Class<?> base) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes) {
            @Override
            protected void writeClassDescriptor(ObjectStreamClass desc) throws IOException {
                Class<?> c = desc.forClass();
                super.writeClassDescriptor(
                        c == Twin.class
                                ? ObjectStreamClass.lookup(target)
                                : c == BaseTwin.class && base != null ? ObjectStreamClass.lookup(base) : desc);
            }
        }) {
            out.writeObject(new Twin());
        }
        return bytes.toByteArray();
    }

    @Test
    void directDeserializationRequiresTheProxy() {
        for (Class<?> target : List.of(
                TestLengthImpl.class,
                TestLengthDecimalImpl.class,
                TestSpeedImpl.class,
                UntypedDoubleQuantity.class,
                UntypedDecimalQuantity.class)) {
            Class<?> base = AbstractDoubleQuantity.class.isAssignableFrom(target)
                    ? AbstractDoubleQuantity.class
                    : AbstractDecimalQuantity.class;
            // With the base class in the stream its readObject runs; without it, readObjectNoData.
            for (Class<?> b : Arrays.asList(base, null)) {
                InvalidObjectException e = assertThrows(
                        InvalidObjectException.class,
                        () -> deserialize(forgeAs(target, b)),
                        target.getName() + " " + b);
                assertEquals("proxy required", e.getMessage(), target.getName());
            }
        }
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
}
