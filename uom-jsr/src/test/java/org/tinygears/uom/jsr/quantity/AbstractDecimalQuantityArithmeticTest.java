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

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import javax.measure.Quantity;
import javax.measure.UnconvertibleException;
import javax.measure.Unit;
import javax.measure.quantity.Length;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import org.tinygears.uom.engine.Factor;
import org.tinygears.uom.engine.arithmetic.Precision;
import org.tinygears.uom.jsr.BaseUnits;
import org.tinygears.uom.jsr.UomConverter;
import org.tinygears.uom.jsr.UomUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractDecimalQuantityArithmeticTest {

    private static final UomUnit<Length> METRE = BaseUnits.METRE;
    private static final UomUnit<Length> KILOMETRE = BaseUnits.METRE.multiply(1000);
    private static final UomUnit<Length> CELSIUS = UomUnit.unchecked(BaseUnits.KELVIN.shift(new BigDecimal("273.15")));
    private static final UomUnit<Length> KELVIN = UomUnit.unchecked(BaseUnits.KELVIN);
    private static final UomUnit<Length> INCH = BaseUnits.METRE.multiply(new BigDecimal("0.0254"));
    private static final UomUnit<Length> FAHRENHEIT = UomUnit
            .unchecked(BaseUnits.KELVIN.multiply(5).divide(9).shift(new BigDecimal("459.67")));
    private static final UomUnit<Length> MINUS_2_5_M = BaseUnits.METRE.multiply(new BigDecimal("-2.5"));
    private static final UomUnit<Length> MINUS_2_K_SHIFTED = UomUnit
            .unchecked(BaseUnits.KELVIN.multiply(-2).shift(new BigDecimal("7.5")));
    private static final UomUnit<Length> PI_M = UomUnit.unchecked(METRE.scaledBy(Factor.PI));
    private static final MathContext UNLIMITED = MathContext.UNLIMITED;

    @Test
    void unlimitedConversionAgreesWithAddition() {
        TestLengthDecimalImpl x = q("254.000000000000000000000000000000000000254", METRE, UNLIMITED);
        BigDecimal converted = (BigDecimal) x.to(INCH).getValue();
        assertEquals(new BigDecimal("10000.000000000000000000000000000000000010000"), converted);
        assertEquals(converted, q("0", INCH, UNLIMITED).add(x).getValue());
    }

    private static TestLengthDecimalImpl q(String value, UomUnit<Length> unit) {
        return new TestLengthDecimalImpl(new BigDecimal(value), unit);
    }

    private static TestLengthDecimalImpl q(String value, UomUnit<Length> unit, MathContext mc) {
        return new TestLengthDecimalImpl(new BigDecimal(value), unit, Quantity.Scale.ABSOLUTE, mc);
    }

    private static TestLengthImpl d(double value, UomUnit<Length> unit) {
        return new TestLengthImpl(value, unit);
    }

    /** Asserts a decimal result: numerically equal value, unit and context. */
    private static void assertDecimal(String value, UomUnit<Length> unit, MathContext mc, Quantity<Length> actual) {
        TestLengthDecimalImpl r = assertInstanceOf(TestLengthDecimalImpl.class, actual);
        assertEquals(0, new BigDecimal(value).compareTo(r.getValue()), () -> value + " vs " + r.getValue());
        assertEquals(unit, r.getUnit());
        assertEquals(mc, r.mathContext());
    }

    private static void assertDecimal(String value, UomUnit<Length> unit, Quantity<Length> actual) {
        assertDecimal(value, unit, MathContext.DECIMAL128, actual);
    }

    @Test
    void add() {
        assertDecimal("3.75", METRE, q("1.5", METRE).add(q("2.25", METRE)));
        assertDecimal("1.001", KILOMETRE, q("1", KILOMETRE).add(q("1", METRE)));
        assertDecimal("1001", METRE, q("1", METRE).add(q("1", KILOMETRE)));
    }

    @Test
    void addIsExactInDecimal() {
        assertEquals(new BigDecimal("0.3"), q("0.1", METRE).add(q("0.2", METRE)).getValue());
    }

    @Test
    void subtract() {
        assertDecimal("9.75", METRE, q("10", METRE).subtract(q("0.25", METRE)));
        assertDecimal("0.999", KILOMETRE, q("1", KILOMETRE).subtract(q("1", METRE)));
    }

    @Test
    void offsetUnitsGoThroughTheSystemUnitExactly() {
        assertEquals(new BigDecimal("276.15"), q("2", CELSIUS).add(q("1", CELSIUS)).getValue());
        assertEquals(new BigDecimal("-272.15"), q("2", CELSIUS).subtract(q("1", CELSIUS)).getValue());
        assertEquals(new BigDecimal("277.15"), q("2", CELSIUS).multiply(2).getValue());
        assertDecimal("277.15", KELVIN, q("2", KELVIN).add(q("2", CELSIUS)));
        assertDecimal("4", CELSIUS, q("2", CELSIUS).add(q("2", KELVIN)));
    }

    @Test
    void scalarMultiply() {
        assertDecimal("4", METRE, q("2", METRE).multiply(2));
        assertEquals(new BigDecimal("1.5"), q("3", METRE).multiply(0.5).getValue());
        assertEquals(new BigDecimal("0.75"), q("3", METRE).multiply(0.25).getValue());
        assertEquals(new BigDecimal("0.3"), q("3", METRE).multiply(0.1f).getValue());
        assertEquals(new BigDecimal("2.2"), q("2", METRE).multiply(new BigDecimal("1.1")).getValue());
        assertDecimal(
                "2.2",
                METRE,
                MathContext.DECIMAL32,
                q("2", METRE, MathContext.DECIMAL32).multiply(new BigDecimal("1.1")));
        assertThrows(ArithmeticException.class, () -> q("2", METRE).multiply(Double.NaN));
        assertThrows(ArithmeticException.class, () -> q("2", METRE).multiply(Double.POSITIVE_INFINITY));
    }

    @Test
    void scalarAdoptsTheReceiverContext() {
        assertDecimal("2.469", METRE, new MathContext(4), q("1.2345", METRE, new MathContext(4)).multiply(2));
        assertDecimal(
                "1.235",
                METRE,
                new MathContext(4),
                q("1", METRE, new MathContext(4)).multiply(new BigDecimal("1.2345")));
    }

    @Test
    void exactOperandsStayExact() {
        BigInteger huge = BigInteger.TEN.pow(400);
        assertDecimal("2e400", METRE, UNLIMITED, q("2", METRE, UNLIMITED).multiply(huge));
        assertDecimal(
                "1",
                METRE,
                UNLIMITED,
                q("2e400", METRE, UNLIMITED).divide(new BigDecimal(huge).multiply(BigDecimal.valueOf(2))));
        assertDecimal("9007199254740993", METRE, q("0", METRE).add(new Foreign<>(9007199254740993L, METRE)));
        assertDecimal("1e400", METRE, UNLIMITED, q("0", METRE, UNLIMITED).add(new Foreign<>(huge, METRE)));
    }

    @Test
    void scalarDivide() {
        assertDecimal("0.5", METRE, q("1", METRE).divide(2));
        assertEquals(new BigDecimal("0.3333333333333333333333333333333333"), q("1", METRE).divide(3).getValue());
        TestLength unlimited = q("1", METRE, UNLIMITED).divide(3);
        assertDecimal("0.3333333333333333333333333333333333", METRE, UNLIMITED, unlimited);
        assertEquals(new BigDecimal("0.33333"), q("1", METRE, new MathContext(5)).divide(3).getValue());
        assertEquals(new BigDecimal("-135.575"), q("2", CELSIUS).divide(2).getValue());
    }

    @Test
    void divisionByZeroThrows() {
        assertThrows(ArithmeticException.class, () -> q("1", METRE).divide(0));
        assertThrows(ArithmeticException.class, () -> q("0", METRE).divide(0));
        assertThrows(ArithmeticException.class, () -> q("1", METRE, UNLIMITED).divide(BigDecimal.ZERO));
        assertThrows(ArithmeticException.class, () -> q("1", CELSIUS).divide(0.0));
    }

    // F23: the decimal wins; a double operand is read as its shortest decimal and adopts this context.
    @Test
    void doubleOperandIsReadAsDecimal() {
        assertDecimal("4", METRE, q("1.5", METRE).add(d(2.5, METRE)));
        assertDecimal("0.3", METRE, q("0.1", METRE).add(d(0.2, METRE)));
        assertDecimal("0.5", KILOMETRE, q("1", KILOMETRE).subtract(d(500, METRE)));
        assertDecimal("276.15", CELSIUS, q("2", CELSIUS).add(d(1, CELSIUS)));
        assertDecimal("3.5", METRE, q("1", METRE).add(new Foreign<>(2.5f, METRE)));
        assertDecimal("1.3333", METRE, new MathContext(5), q("1", METRE, new MathContext(5)).add(d(1.0 / 3, METRE)));
        assertThrows(ArithmeticException.class, () -> q("1", METRE).add(d(Double.NaN, METRE)));
        assertThrows(ArithmeticException.class, () -> q("1", METRE).subtract(d(Double.POSITIVE_INFINITY, METRE)));
    }

    @Test
    void decimalOutsideDoubleRangeWorksAgainstDouble() {
        assertDecimal("1e400", METRE, q("1e400", METRE).add(d(1, METRE)));
        assertDecimal("1e400", METRE, q("1e400", METRE).subtract(d(1, METRE)));
    }

    @Test
    void foreignExactValueStaysDecimal() {
        assertDecimal("3.5", METRE, q("1.5", METRE).add(new Foreign<>(2, METRE)));
        assertDecimal("-0.5", METRE, q("1.5", METRE).subtract(new Foreign<>(2, METRE)));
    }

    @Test
    void foreignDecimalCountsAsUnlimited() {
        assertDecimal(
                "3.5",
                METRE,
                MathContext.DECIMAL64,
                q("1.5", METRE, MathContext.DECIMAL64).add(new Foreign<>(new BigDecimal("2"), METRE)));
        assertDecimal(
                "3.5",
                METRE,
                UNLIMITED,
                q("1.5", METRE, UNLIMITED).add(new Foreign<>(new BigDecimal("2"), METRE)));
    }

    @Test
    void lowerPrecisionWins() {
        TestLengthDecimalImpl d32 = q("1", METRE, MathContext.DECIMAL32);
        TestLengthDecimalImpl d128 = q("1", METRE, MathContext.DECIMAL128);
        assertEquals(7, ((TestLengthDecimalImpl) d32.add(d128)).mathContext().getPrecision());
        assertEquals(7, ((TestLengthDecimalImpl) d128.add(d32)).mathContext().getPrecision());
        assertEquals(7, ((TestLengthDecimalImpl) d128.subtract(d32)).mathContext().getPrecision());
        TestLengthDecimalImpl unlimited = q("1", METRE, UNLIMITED);
        TestLengthDecimalImpl d64 = q("1", METRE, MathContext.DECIMAL64);
        assertEquals(16, ((TestLengthDecimalImpl) unlimited.add(d64)).mathContext().getPrecision());
        assertEquals(16, ((TestLengthDecimalImpl) d64.add(unlimited)).mathContext().getPrecision());
        TestLengthDecimalImpl halfDown = q("1", METRE, new MathContext(7, java.math.RoundingMode.HALF_DOWN));
        assertSame(halfDown.mathContext(), ((TestLengthDecimalImpl) halfDown.add(d32)).mathContext());
    }

    @Test
    void resultIsRoundedToTheResultContext() {
        Quantity<Length> r = q("1.2345678", METRE, MathContext.DECIMAL32).add(q("0", METRE));
        assertEquals(new BigDecimal("1.234568"), r.getValue());
        assertEquals(7, ((BigDecimal) r.getValue()).precision());
        assertEquals(
                new BigDecimal("1.234568"),
                q("0", METRE).add(q("1.2345678", METRE, MathContext.DECIMAL32)).getValue());
    }

    @Test
    void scaleIsKept() {
        TestLengthDecimalImpl relative = new TestLengthDecimalImpl(
                BigDecimal.ONE,
                KILOMETRE,
                Quantity.Scale.RELATIVE,
                MathContext.DECIMAL128);
        TestLengthDecimalImpl delta = new TestLengthDecimalImpl(
                BigDecimal.ONE,
                METRE,
                Quantity.Scale.RELATIVE,
                MathContext.DECIMAL128);
        assertSame(Quantity.Scale.RELATIVE, relative.add(delta).getScale());
        assertSame(Quantity.Scale.RELATIVE, relative.subtract(delta).getScale());
        assertSame(Quantity.Scale.RELATIVE, relative.multiply(2).getScale());
        assertSame(Quantity.Scale.RELATIVE, relative.divide(2).getScale());
        // A sum or difference is a difference only if both operands are, also against a double operand.
        assertSame(Quantity.Scale.ABSOLUTE, relative.add(q("1", METRE)).getScale());
        assertSame(Quantity.Scale.ABSOLUTE, relative.subtract(q("1", METRE)).getScale());
        assertSame(Quantity.Scale.ABSOLUTE, relative.add(d(1, METRE)).getScale());
        assertSame(Quantity.Scale.ABSOLUTE, q("1", METRE).add(relative).getScale());
        assertSame(Quantity.Scale.ABSOLUTE, q("1", METRE).subtract(delta).getScale());
    }

    @Test
    void foreignUnitFails() {
        Quantity<Length> foreign = new Foreign<>(BigDecimal.ONE, foreignUnit());
        UnconvertibleException e = assertThrows(UnconvertibleException.class, () -> q("1", METRE).add(foreign));
        assertTrue(e.getMessage().contains("foreign unit implementation"), e.getMessage());
        assertThrows(UnconvertibleException.class, () -> q("1", METRE).subtract(foreign));
        assertThrows(UnconvertibleException.class, () -> q("1", METRE).compareTo(foreign));
        assertThrows(UnconvertibleException.class, () -> q("1", METRE).isEquivalentTo(foreign));
    }

    @Test
    void incompatibleDimensionFails() {
        assertThrows(UnconvertibleException.class, () -> q("1", METRE).add(q("1", KELVIN)));
        assertThrows(UnconvertibleException.class, () -> q("1", METRE).subtract(q("1", KELVIN)));
        assertThrows(UnconvertibleException.class, () -> q("1", METRE).add(d(1, KELVIN)));
    }

    @Test
    void nullFails() {
        TestLengthDecimalImpl one = q("1", METRE);
        assertThrows(NullPointerException.class, () -> one.add(null));
        assertThrows(NullPointerException.class, () -> one.subtract(null));
        assertThrows(NullPointerException.class, () -> one.multiply((Number) null));
        assertThrows(NullPointerException.class, () -> one.divide((Number) null));
        assertThrows(NullPointerException.class, () -> one.compareTo(null));
        assertThrows(NullPointerException.class, () -> one.isEquivalentTo(null));
    }

    @Test
    void compareTo() {
        assertEquals(0, q("1", KILOMETRE).compareTo(q("1000", METRE)));
        assertEquals(1, q("1", KILOMETRE).compareTo(q("999.999", METRE)));
        assertEquals(-1, q("999.999", METRE).compareTo(q("1", KILOMETRE)));
        assertEquals(0, q("2", CELSIUS).compareTo(q("275.15", KELVIN)));
        assertEquals(0, q("1.0", METRE).compareTo(q("1.00", METRE)));
        assertEquals(
                1,
                q("1.000000000000000000000000000000000000001", METRE, UNLIMITED).compareTo(q("1", METRE, UNLIMITED)));
        assertEquals(1, q("2", METRE).compareTo(new Foreign<>(1, METRE)));
    }

    @Test
    void compareToDoubleComparesAsDecimals() {
        // The double 0.1 reads as the decimal 0.1.
        assertEquals(0, q("0.1", METRE).compareTo(d(0.1, METRE)));
        assertEquals(1, q("0.10000000000000000001", METRE).compareTo(d(0.1, METRE)));
        assertEquals(-1, q("1", METRE).compareTo(d(1.5, METRE)));
        assertEquals(1, q("1", KILOMETRE).compareTo(d(999.5, METRE)));
        assertEquals(1, q("1e400", METRE).compareTo(d(1, METRE)));
        // NaN and infinities keep the Double.compare order.
        assertEquals(-1, q("1", METRE).compareTo(d(Double.NaN, METRE)));
        assertEquals(-1, q("1", METRE).compareTo(d(Double.POSITIVE_INFINITY, METRE)));
        assertEquals(1, q("1", METRE).compareTo(d(Double.NEGATIVE_INFINITY, METRE)));
    }

    @Test
    void compareToIncompatibleDimensionThrows() {
        assertThrows(UnconvertibleException.class, () -> q("1", METRE).compareTo(q("1", KELVIN)));
        assertThrows(UnconvertibleException.class, () -> q("1", METRE).compareTo(d(1, KELVIN)));
    }

    @Test
    void equivalentAcrossUnits() {
        assertTrue(q("2", KILOMETRE).isEquivalentTo(q("2000", METRE)));
        assertTrue(q("2", CELSIUS).isEquivalentTo(q("275.15", KELVIN)));
        assertFalse(q("2", CELSIUS).isEquivalentTo(q("2", KELVIN)));
        assertFalse(q("1", METRE).isEquivalentTo(q("1", KELVIN)));
        assertFalse(q("1", METRE).isEquivalentTo(d(1, KELVIN)));
        assertTrue(q("0", METRE).isEquivalentTo(q("0.000", METRE)));
        assertTrue(q("1", METRE).isEquivalentTo(new Foreign<>(1, METRE)));
    }

    @Test
    void decimalEquivalenceIsExactWhateverTheContext() {
        MathContext p1 = new MathContext(1);
        // The former tolerance max(|a|, |b|) * 10^(1 - p) made every two same-sign values equivalent at precision 1.
        assertFalse(q("1", METRE, p1).isEquivalentTo(q("1.9", METRE, p1)));
        assertFalse(q("1", METRE, p1).isEquivalentTo(q("100", METRE, p1)));
        assertTrue(q("1", METRE, p1).isEquivalentTo(q("1.000", METRE, p1)));
        assertFalse(q("1", METRE).isEquivalentTo(q("1.0000000000000000000000000000000001", METRE)));
        assertFalse(q("1", METRE).isEquivalentTo(q("1.000001", METRE, MathContext.DECIMAL32)));
        assertFalse(q("1000000", METRE, MathContext.DECIMAL32).isEquivalentTo(q("999999", METRE)));
        // exact across units, and alike when both sides rounded the same value in the same context
        assertTrue(q("1", INCH, p1).isEquivalentTo(q("0.0254", METRE)));
        assertTrue(q("1", KILOMETRE).divide(3).isEquivalentTo(q("1000", METRE).divide(3)));
        // a value rounded from an irrational one is not its source
        String pi34 = "3.141592653589793238462643383279503";
        assertFalse(q(pi34, METRE).isEquivalentTo(q("1", PI_M)));
        assertFalse(q("1", PI_M).isEquivalentTo(q(pi34, METRE)));
        assertTrue(q("2", PI_M).isEquivalentTo(q("1", PI_M).add(q("1", PI_M))));
    }

    @Test
    void closeWithinARelativeTolerance() {
        // |a - b| <= tolerance * max(|a|, |b|), inclusive and exact
        assertTrue(q("100", METRE).isCloseTo(q("101", METRE), 0.01));
        assertFalse(q("100", METRE).isCloseTo(q("101.02", METRE), 0.01));
        assertTrue(q("101", METRE).isCloseTo(q("100", METRE), 0.01));
        // b - 1 <= 1e-9 * b exactly when b <= 1 / (1 - 1e-9) = 1.000000001000000001000...
        assertTrue(q("1", METRE).isCloseTo(q("1.000000001000000001", METRE), 1e-9));
        assertFalse(q("1", METRE).isCloseTo(q("1.000000001000000002", METRE), 1e-9));
        // in the system unit, offsets included: 20 °C is 293.15 K
        assertTrue(q("1", KILOMETRE).isCloseTo(q("1000.5", METRE), 5e-4));
        assertFalse(q("1", KILOMETRE).isCloseTo(q("1000.5", METRE), 4e-4));
        assertTrue(q("20", CELSIUS).isCloseTo(q("293.2", KELVIN), 2e-4));
        assertFalse(q("20", CELSIUS).isCloseTo(q("293.2", KELVIN), 1.7e-4));
        // two RELATIVE quantities are differences: 2 °C and 2 K
        TestLengthDecimalImpl twoCelsius = new TestLengthDecimalImpl(
                new BigDecimal("2"),
                CELSIUS,
                Quantity.Scale.RELATIVE,
                MathContext.DECIMAL128);
        TestLengthDecimalImpl twoKelvin = new TestLengthDecimalImpl(
                new BigDecimal("2"),
                KELVIN,
                Quantity.Scale.RELATIVE,
                MathContext.DECIMAL128);
        assertTrue(twoCelsius.isCloseTo(twoKelvin, 0));
        // zero is close only to zero
        assertTrue(q("0", METRE).isCloseTo(q("0.000", METRE), 0));
        assertFalse(q("0", METRE).isCloseTo(q("1e-30", METRE), 0.5));
        // the context does not matter, nor does a precision of 1
        assertFalse(q("1", METRE, new MathContext(1)).isCloseTo(q("1.9", METRE, new MathContext(1)), 0.4));
        assertTrue(q("1", METRE, new MathContext(1)).isCloseTo(q("1.9", METRE, new MathContext(1)), 0.5));
        // exact through pi: pi34 - pi is -1.158e-34, relative 3.69e-35
        String pi34 = "3.141592653589793238462643383279503";
        assertTrue(q(pi34, METRE).isCloseTo(q("1", PI_M), 3.7e-35));
        assertFalse(q(pi34, METRE).isCloseTo(q("1", PI_M), 3.6e-35));
        // a double operand is read as its shortest decimal
        assertTrue(q("0.1", METRE).isCloseTo(d(0.1, METRE), 0));
    }

    @Test
    void closeWithinAnAbsoluteTolerance() {
        TestLengthDecimalImpl mm = q("1", METRE.divide(1000));
        assertTrue(q("1", METRE).isCloseTo(q("1.001", METRE), mm));
        assertFalse(q("1", METRE).isCloseTo(q("1.0010000000000000000001", METRE), mm));
        assertTrue(q("1", KILOMETRE).isCloseTo(q("1000.001", METRE), mm));
        // works near zero, where a relative tolerance accepts nothing
        assertTrue(q("0", METRE).isCloseTo(q("1e-30", METRE), q("1e-20", METRE)));
        // the tolerance is a difference: 1 °C of tolerance is 1 K, not 274.15 K
        assertTrue(q("20", CELSIUS).isCloseTo(q("294.15", KELVIN), q("1", CELSIUS)));
        assertFalse(q("20", CELSIUS).isCloseTo(q("294.16", KELVIN), q("1", CELSIUS)));
        // a negative factor turns the tolerance around; its magnitude counts
        assertTrue(q("0", METRE).isCloseTo(q("2.5", METRE), q("1", MINUS_2_5_M)));
        String pi34 = "3.141592653589793238462643383279503";
        assertTrue(q(pi34, METRE).isCloseTo(q("1", PI_M), q("1.16e-34", METRE)));
        assertFalse(q(pi34, METRE).isCloseTo(q("1", PI_M), q("1.15e-34", METRE)));
    }

    @Test
    void closenessRejectsBadTolerancesAndOtherDimensions() {
        assertEquals(
                "relative tolerance must be finite and not negative: -1.0",
                assertThrows(IllegalArgumentException.class, () -> q("1", METRE).isCloseTo(q("1", METRE), -1))
                        .getMessage());
        assertThrows(IllegalArgumentException.class, () -> q("1", METRE).isCloseTo(q("1", METRE), Double.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> q("1", METRE).isCloseTo(q("1", METRE), Double.POSITIVE_INFINITY));
        assertEquals(
                "absolute tolerance must be finite and not negative: -0.1",
                assertThrows(
                        IllegalArgumentException.class,
                        () -> q("1", METRE).isCloseTo(q("1", METRE), q("-0.1", METRE))).getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> q("1", METRE).isCloseTo(q("1", METRE), d(Double.NaN, METRE)));
        assertThrows(UnconvertibleException.class, () -> q("1", METRE).isCloseTo(q("1", KELVIN), 0.1));
        assertThrows(NullPointerException.class, () -> q("1", METRE).isCloseTo(null, 0.1));
        assertThrows(NullPointerException.class, () -> q("1", METRE).isCloseTo(q("1", METRE), null));
        assertFalse(q("1", METRE).isCloseTo(d(Double.NaN, METRE), 1));
        assertFalse(q("1", METRE).isCloseTo(d(Double.POSITIVE_INFINITY, METRE), 1));
    }

    @Test
    void equivalenceAgainstDoubleUsesTheDoubleTolerance() {
        assertTrue(q("1", METRE).isEquivalentTo(d(1 + 1e-13, METRE)));
        assertFalse(q("1", METRE).isEquivalentTo(d(1 + 1e-11, METRE)));
        assertTrue(q("0.1", METRE).isEquivalentTo(d(0.1, METRE)));
        assertTrue(q("2", KILOMETRE).isEquivalentTo(d(2000, METRE)));
    }

    @Test
    void addAndSubtractRoundOnce() {
        // Exact: 1 - 39.37008 * 0.0254 = -3.2E-8.
        assertDecimal(
                "-3.2E-8",
                METRE,
                MathContext.DECIMAL32,
                q("1", METRE, MathContext.DECIMAL32).subtract(q("39.37008", INCH)));
        TestLengthDecimalImpl p7 = q("1.2345678", METRE, new MathContext(7));
        TestLengthDecimalImpl p34 = q("1.2345674", METRE, MathContext.DECIMAL128);
        assertDecimal("4E-7", METRE, new MathContext(7), p7.subtract(p34));
        assertDecimal("-4E-7", METRE, new MathContext(7), p34.subtract(p7));
        assertDecimal(
                "7.2E+14",
                METRE,
                new MathContext(7),
                q("-987666759108827382456942", METRE, new MathContext(7))
                        .subtract(q("-987666759828827382456942", METRE, MathContext.DECIMAL64)));
    }

    @Test
    void valuesNearTheScaleBoundStayFast() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            assertDecimal("1E+99999", METRE, q("1e99999", METRE).add(q("1", METRE)));
            assertDecimal("1E+99999", KILOMETRE, q("1e99999", KILOMETRE).add(q("1e-99999", METRE)));
            // The exact sum has 100000 digits, over the digit bound.
            assertEquals(
                    "too many digits: 100000",
                    assertThrows(
                            ArithmeticException.class,
                            () -> q("1e99999", METRE, UNLIMITED).add(q("1", METRE, UNLIMITED))).getMessage());
            assertEquals(1, q("1e99999", CELSIUS).compareTo(q("1e-99999", KELVIN)));
            assertDecimal("2E+99999", CELSIUS, q("1e99999", CELSIUS).multiply(2));
        });
    }

    @Test
    void zeroOffsetDoesNotAlignScales() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            TestLengthDecimalImpl big = q("1e99999", METRE, UNLIMITED);
            assertEquals("1E+99999 m", big.add(q("0", METRE, UNLIMITED)).toString());
            assertEquals("1E+99999 m", big.subtract(q("0", METRE, UNLIMITED)).toString());
            assertEquals("2E+99999 m", big.multiply(2).toString());
            assertEquals("5E+99998 m", big.divide(2).toString());
            assertEquals("2E+99999 m", big.add(big).toString());
            assertEquals(0, ((BigDecimal) big.subtract(big).getValue()).signum());
        });
    }

    @Test
    void irrationalFactorsAreApproximatedBeyondTheContext() {
        UomUnit<Length> piOver180 = UomUnit.unchecked(METRE.scaledBy(Factor.PI.divide(Factor.ofLong(180))));
        assertDecimal("4.141592653589793238462643383279503", METRE, q("1", METRE).add(q("180", piOver180)));
        assertDecimal(
                "4.141592653589793238462643383279503",
                METRE,
                UNLIMITED,
                q("1", METRE, UNLIMITED).add(q("180", piOver180, UNLIMITED)));
        assertDecimal("360", piOver180, q("180", piOver180).add(q("180", piOver180)));
        // pi = 3.14159265358979323846264338327950288419716...
        assertEquals(
                1,
                q("3.1415926535897932384626433832795028842", METRE, UNLIMITED)
                        .compareTo(q("180", piOver180, UNLIMITED)));
        assertEquals(
                -1,
                q("3.1415926535897932384626433832795028841", METRE, UNLIMITED)
                        .compareTo(q("180", piOver180, UNLIMITED)));
    }

    @Test
    void irrationalFactorsResolveCancellationAgainstTheOperandDigits() {
        // pi = 3.14159265358979323846264338327950288419716939937510582097494459230781640628620899862803...
        String pi34 = "3.141592653589793238462643383279503";
        String pi70 = "3.141592653589793238462643383279502884197169399375105820974944592307816";
        assertDecimal("-1.158028306006248941790250554076922E-34", METRE, q("-" + pi34, METRE).add(q("1", PI_M)));
        assertDecimal(
                "-1.158028306006248941790250554076922E-34",
                METRE,
                UNLIMITED,
                q("-" + pi34, METRE, UNLIMITED).add(q("1", PI_M, UNLIMITED)));
        assertDecimal("4.062862089986280348253421170679821E-70", METRE, q("-" + pi70, METRE).add(q("1", PI_M)));
        assertEquals(-1, q(pi70, METRE).compareTo(q("1", PI_M)));
        assertEquals(1, q("1", PI_M).compareTo(q(pi70, METRE)));
    }

    @Test
    void irrationalFactorsResolveCancellationBeyondTheOperandDigits() {
        // sqrt(1 + 10^-200) = 1 + 5e-201 - ...: one metre and one of this unit differ 200 digits beyond their own.
        UomUnit<Length> nearlyMetre = UomUnit.unchecked(
                METRE.scaledBy(
                        Factor.of(BigInteger.TEN.pow(200).add(BigInteger.ONE), BigInteger.TEN.pow(200)).root(2)));
        assertEquals(-1, q("1", METRE).compareTo(q("1", nearlyMetre)));
        assertEquals(1, q("1", nearlyMetre).compareTo(q("1", METRE)));
        assertDecimal("-5.000000000000000000000000000000000E-201", METRE, q("1", METRE).subtract(q("1", nearlyMetre)));
        assertDecimal(
                "5.000000000000000000000000000000000E-201",
                nearlyMetre,
                q("1", nearlyMetre).subtract(q("1", METRE)));
    }

    @Test
    void irrationalFactorsCostDigitsOfTheContextNotOfTheOperand() {
        // Comparing with an operand of 49999 digits needs pi to a few dozen digits, not to the operand's length.
        BigDecimal value = new BigDecimal(BigInteger.TEN.pow(49_999).divide(BigInteger.valueOf(3)));
        TestLengthDecimalImpl big = new TestLengthDecimalImpl(value, METRE, Quantity.Scale.ABSOLUTE, UNLIMITED);
        int sign = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> big.compareTo(q("1", PI_M)));
        assertEquals(1, sign);
    }

    @Test
    void negativeFactorUnits() {
        // 1 (-2.5 m) is -2.5 m.
        assertEquals(-1, q("1", MINUS_2_5_M).compareTo(q("0", METRE)));
        assertEquals(1, q("0", METRE).compareTo(q("1", MINUS_2_5_M)));
        assertEquals(1, q("-1", MINUS_2_5_M).compareTo(q("2", METRE)));
        assertDecimal("0", MINUS_2_5_M, q("1", MINUS_2_5_M).add(q("2.5", METRE)));
        assertDecimal("-2", METRE, q("0.5", METRE).add(q("1", MINUS_2_5_M)));
        UomConverter toKelvin = MINUS_2_K_SHIFTED.getConverterTo(KELVIN);
        BigDecimal one = toKelvin.convert(BigDecimal.ONE, MathContext.DECIMAL128);
        assertEquals(0, q("1", MINUS_2_K_SHIFTED).compareTo(q(one.toPlainString(), KELVIN)));
        assertEquals(
                -Integer.signum(one.compareTo(BigDecimal.ZERO)),
                q("0", KELVIN).compareTo(q("1", MINUS_2_K_SHIFTED)));
    }

    @Test
    void offsetScalarRoundsOnce() {
        MathContext p3 = new MathContext(3);
        // Exact: (274.38 * 3 - 273.15) = 549.99 -> 550; rounding 274.38 K first would give 549.
        assertDecimal("550", CELSIUS, p3, q("1.23", CELSIUS, p3).multiply(3));
        // Exact: (274.38 / 0.5 - 273.15) = 275.61 -> 276; rounding first would give 275.
        assertDecimal("276", CELSIUS, p3, q("1.23", CELSIUS, p3).divide(new BigDecimal("0.5")));
    }

    @Test
    void compareToIsAConsistentPreorder() {
        TestLengthDecimalImpl p1 = q("1.4", METRE, new MathContext(1));
        TestLengthDecimalImpl one = q("1.0", METRE);
        TestLengthDecimalImpl oneTwo = q("1.2", METRE);
        assertEquals(1, p1.compareTo(one));
        assertEquals(1, p1.compareTo(oneTwo));
        assertEquals(-1, oneTwo.compareTo(p1));
        assertEquals(-1, one.compareTo(oneTwo));
        assertEquals(1, q("1.4", KILOMETRE, new MathContext(1)).compareTo(q("1000", METRE)));
        assertEquals(1, q("0.0000001", CELSIUS, MathContext.DECIMAL32).compareTo(q("273.15", KELVIN)));
        assertEquals(-1, q("-459.67", FAHRENHEIT, new MathContext(1)).compareTo(q("1e-30", KELVIN)));
        assertEquals(0, q("-459.67", FAHRENHEIT).compareTo(q("0", KELVIN)));
    }

    private static final List<BigDecimal> OUT_OF_SCALE = List
            .of(new BigDecimal("1e-20000000"), new BigDecimal("1e+20000000"));

    /** {@code e} throws the scale-bound ArithmeticException for {@code v} within a second. */
    private static void assertScaleOutOfRange(BigDecimal v, Executable e) {
        ArithmeticException x = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> assertThrows(ArithmeticException.class, e));
        assertEquals("scale out of range: " + v.scale(), x.getMessage());
    }

    @Test
    void scalarMultiplyOutsideTheScaleBoundThrowsPromptly() {
        for (BigDecimal v : OUT_OF_SCALE) {
            assertScaleOutOfRange(v, () -> q("1", CELSIUS).multiply(v));
            assertScaleOutOfRange(v, () -> q("1", METRE).multiply(v));
        }
    }

    @Test
    void scalarDivideOutsideTheScaleBoundThrowsPromptly() {
        for (BigDecimal v : OUT_OF_SCALE) {
            assertScaleOutOfRange(v, () -> q("1", CELSIUS).divide(v));
            assertScaleOutOfRange(v, () -> q("1", METRE).divide(v));
        }
    }

    @Test
    void foreignAddendOutsideTheScaleBoundThrowsPromptly() {
        for (BigDecimal v : OUT_OF_SCALE) {
            assertScaleOutOfRange(v, () -> q("1", METRE).add(new Foreign<>(v, METRE)));
            assertScaleOutOfRange(v, () -> q("1", CELSIUS).subtract(new Foreign<>(v, KELVIN)));
        }
    }

    @Test
    void foreignComparandOutsideTheScaleBoundThrowsPromptly() {
        for (BigDecimal v : OUT_OF_SCALE) {
            assertScaleOutOfRange(v, () -> q("1", CELSIUS).compareTo(new Foreign<>(v, KELVIN)));
            assertScaleOutOfRange(v, () -> q("1", METRE).isEquivalentTo(new Foreign<>(v, METRE)));
        }
    }

    @Test
    void foreignFactorOutsideTheScaleBoundThrowsPromptly() {
        for (BigDecimal v : OUT_OF_SCALE) {
            assertScaleOutOfRange(v, () -> q("1", CELSIUS).multiply(new Foreign<>(v, KELVIN)));
            assertScaleOutOfRange(v, () -> q("1", CELSIUS).divide(new Foreign<>(v, KELVIN)));
        }
    }

    @Test
    void operandsAtTheScaleBoundStillWork() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            assertDecimal("2E-100000", METRE, q("2", METRE).multiply(new BigDecimal("1e-100000")));
            assertDecimal("2E+100000", METRE, q("2", METRE).multiply(new BigDecimal("1e+100000")));
            assertDecimal("2E-100000", METRE, q("2", METRE).divide(new BigDecimal("1e+100000")));
            assertDecimal("2E+99999", METRE, q("2", METRE).divide(new BigDecimal("1e-99999")));
            assertEquals(1, q("1", METRE).compareTo(new Foreign<>(new BigDecimal("1e-100000"), METRE)));
            assertEquals(-1, q("1", METRE).compareTo(new Foreign<>(new BigDecimal("1e+100000"), METRE)));
        });
    }

    @Test
    void doubleQuantityTakesOperandsOutsideTheScaleBoundPromptly() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            BigDecimal tiny = OUT_OF_SCALE.get(0);
            BigDecimal huge = OUT_OF_SCALE.get(1);
            assertEquals(0.0, d(1, METRE).multiply(tiny).getValue());
            // A decimal operand promotes the double quantity, so the decimal scale bound applies.
            assertThrows(ArithmeticException.class, () -> d(1, METRE).add(new Foreign<>(tiny, METRE)));
            assertThrows(ArithmeticException.class, () -> d(1, METRE).compareTo(new Foreign<>(tiny, METRE)));
            assertThrows(ArithmeticException.class, () -> d(1, METRE).multiply(huge));
            assertThrows(ArithmeticException.class, () -> d(1, METRE).divide(huge));
            assertThrows(ArithmeticException.class, () -> d(1, METRE).add(new Foreign<>(huge, METRE)));
            assertThrows(ArithmeticException.class, () -> d(1, METRE).compareTo(new Foreign<>(huge, METRE)));
            assertThrows(ArithmeticException.class, () -> d(1, METRE).isEquivalentTo(new Foreign<>(huge, METRE)));
        });
    }

    /** Add, subtract, compareTo and scalars against an exact rational reference of the literal rule, correctly rounded. */
    @Test
    void correctlyRoundedAgainstExactReference() {
        List<List<UomUnit<Length>>> groups = List.of(
                List.of(KILOMETRE, METRE, INCH, MINUS_2_5_M),
                List.of(KELVIN, CELSIUS, FAHRENHEIT, MINUS_2_K_SHIFTED));
        RoundingMode[] modes = {
            RoundingMode.HALF_UP,
            RoundingMode.HALF_EVEN,
            RoundingMode.HALF_DOWN,
            RoundingMode.UP,
            RoundingMode.DOWN,
            RoundingMode.CEILING,
            RoundingMode.FLOOR };
        Random random = new Random(20261012);
        for (int i = 0; i < 20_000; i++) {
            List<UomUnit<Length>> group = groups.get(random.nextInt(2));
            UomUnit<Length> ua = group.get(random.nextInt(group.size()));
            UomUnit<Length> ub = group.get(random.nextInt(group.size()));
            TestLengthDecimalImpl a = new TestLengthDecimalImpl(
                    randomDecimal(random),
                    ua,
                    Quantity.Scale.ABSOLUTE,
                    randomContext(random, modes));
            TestLengthDecimalImpl b = new TestLengthDecimalImpl(
                    randomDecimal(random),
                    ub,
                    Quantity.Scale.ABSOLUTE,
                    randomContext(random, modes));
            BigDecimal s = randomDecimal(random);
            MathContext mc = Precision.lower(a.mathContext(), b.mathContext());
            Rat sa = Rat.sys(a.getValue(), ua);
            Rat sb = Rat.sys(b.getValue(), ub);
            String what = a + " " + a.mathContext() + " / " + b + " " + b.mathContext() + " / " + s;
            assertRounded(Rat.fromSys(sa.add(sb), ua), mc, a.add(b), what);
            assertRounded(Rat.fromSys(sa.add(sb.negate()), ua), mc, a.subtract(b), what);
            assertEquals(sa.add(sb.negate()).n.signum(), a.compareTo(b), what);
            assertRounded(Rat.fromSys(sa.multiply(Rat.of(s)), ua), a.mathContext(), a.multiply(s), what);
            if (s.signum() != 0) {
                assertRounded(Rat.fromSys(sa.divide(Rat.of(s)), ua), a.mathContext(), a.divide(s), what);
            }
        }
    }

    private static BigDecimal randomDecimal(Random random) {
        BigInteger unscaled = new BigInteger(random.nextInt(1, 90), random);
        return new BigDecimal(random.nextBoolean() ? unscaled : unscaled.negate(), random.nextInt(-10, 30));
    }

    private static MathContext randomContext(Random random, RoundingMode[] modes) {
        int p = random.nextInt(10) == 0 ? 0 : random.nextInt(1, 40);
        return new MathContext(p, modes[random.nextInt(modes.length)]);
    }

    /** Exact or correctly rounded to {@code mc}; a non-terminating unlimited result within 1 ulp of DECIMAL128. */
    private static void assertRounded(Rat exact, MathContext mc, Quantity<Length> actual, String what) {
        BigDecimal n = new BigDecimal(exact.n);
        BigDecimal d = new BigDecimal(exact.d);
        BigDecimal r = (BigDecimal) actual.getValue();
        if (mc.getPrecision() != 0) {
            BigDecimal expected = n.divide(d, mc);
            assertEquals(0, expected.compareTo(r), () -> what + ": expected " + expected + " got " + r);
            return;
        }
        try {
            BigDecimal expected = n.divide(d);
            assertEquals(0, expected.compareTo(r), () -> what + ": expected " + expected + " got " + r);
        } catch (ArithmeticException nonTerminating) {
            BigDecimal expected = n.divide(d, MathContext.DECIMAL128);
            assertTrue(
                    expected.subtract(r).abs().compareTo(expected.ulp()) <= 0,
                    () -> what + ": expected " + expected + " got " + r);
        }
    }

    /** An exact rational n/d, d > 0. */
    private record Rat(BigInteger n, BigInteger d) {
        static Rat of(BigDecimal x) {
            return x.scale() >= 0
                    ? new Rat(x.unscaledValue(), BigInteger.TEN.pow(x.scale()))
                    : new Rat(x.unscaledValue().multiply(BigInteger.TEN.pow(-x.scale())), BigInteger.ONE);
        }

        static Rat of(org.tinygears.uom.engine.Factor f) {
            return new Rat(f.numerator(), f.denominator());
        }

        static Rat sys(BigDecimal x, UomUnit<?> unit) {
            return of(x).multiply(of(unit.toSystemConverter().factor())).add(of(unit.toSystemConverter().offset()));
        }

        static Rat fromSys(Rat x, UomUnit<?> unit) {
            return x.add(of(unit.toSystemConverter().offset()).negate()).divide(of(unit.toSystemConverter().factor()));
        }

        Rat add(Rat o) {
            return new Rat(n.multiply(o.d).add(o.n.multiply(d)), d.multiply(o.d));
        }

        Rat negate() {
            return new Rat(n.negate(), d);
        }

        Rat multiply(Rat o) {
            return new Rat(n.multiply(o.n), d.multiply(o.d));
        }

        Rat divide(Rat o) {
            return o.n.signum() < 0
                    ? new Rat(n.multiply(o.d).negate(), d.multiply(o.n).negate())
                    : new Rat(n.multiply(o.d), d.multiply(o.n));
        }
    }

    @SuppressWarnings("unchecked")
    private static Unit<Length> foreignUnit() {
        return (Unit<Length>) Proxy.newProxyInstance(
                AbstractDecimalQuantityArithmeticTest.class.getClassLoader(),
                new Class<?>[] { Unit.class },
                (p, m, a) -> null);
    }

    /** A Quantity from another implementation carrying any Number. */
    private record Foreign<Q extends Quantity<Q>>(Number value, Unit<Q> unit) implements Quantity<Q> {
        @Override
        public Number getValue() {
            return value;
        }
        @Override
        public Unit<Q> getUnit() {
            return unit;
        }
        @Override
        public Scale getScale() {
            return Scale.ABSOLUTE;
        }
        @Override
        public Quantity<Q> add(Quantity<Q> addend) {
            throw new UnsupportedOperationException();
        }
        @Override
        public Quantity<Q> subtract(Quantity<Q> s) {
            throw new UnsupportedOperationException();
        }
        @Override
        public Quantity<?> divide(Quantity<?> divisor) {
            throw new UnsupportedOperationException();
        }
        @Override
        public Quantity<Q> divide(Number divisor) {
            throw new UnsupportedOperationException();
        }
        @Override
        public Quantity<?> multiply(Quantity<?> m) {
            throw new UnsupportedOperationException();
        }
        @Override
        public Quantity<Q> multiply(Number m) {
            throw new UnsupportedOperationException();
        }
        @Override
        public Quantity<?> inverse() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Quantity<Q> negate() {
            throw new UnsupportedOperationException();
        }
        @Override
        public Quantity<Q> to(Unit<Q> u) {
            throw new UnsupportedOperationException();
        }
        @Override
        public boolean isEquivalentTo(Quantity<Q> that) {
            throw new UnsupportedOperationException();
        }
        @Override
        public <T extends Quantity<T>> Quantity<T> asType(Class<T> type) {
            throw new UnsupportedOperationException();
        }
    }
}
