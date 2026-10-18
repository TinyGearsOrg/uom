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
import java.time.Duration;
import javax.measure.Quantity;
import javax.measure.UnconvertibleException;
import javax.measure.Unit;
import javax.measure.quantity.Length;

import org.junit.jupiter.api.Test;

import org.tinygears.uom.jsr.BaseUnits;
import org.tinygears.uom.jsr.UomUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class AbstractDecimalQuantityTest {

    private static final UomUnit<Length> METRE = BaseUnits.METRE;
    private static final UomUnit<Length> KILOMETRE = BaseUnits.METRE.multiply(1000);
    private static final UomUnit<Length> INCH = BaseUnits.METRE.multiply(new BigDecimal("0.0254"));
    private static final UomUnit<Length> CELSIUS = UomUnit.unchecked(BaseUnits.KELVIN.shift(new BigDecimal("273.15")));
    private static final UomUnit<Length> FAHRENHEIT = UomUnit
            .unchecked(BaseUnits.KELVIN.multiply(5).divide(9).shift(new BigDecimal("459.67")));

    private static TestLengthDecimalImpl q(String value, UomUnit<Length> unit) {
        return new TestLengthDecimalImpl(new BigDecimal(value), unit);
    }

    private static TestLengthDecimalImpl q(String value, UomUnit<Length> unit, MathContext mc) {
        return new TestLengthDecimalImpl(new BigDecimal(value), unit, Quantity.Scale.ABSOLUTE, mc);
    }

    @SuppressWarnings("unchecked")
    private static Unit<Length> foreignUnit() {
        return (Unit<Length>) Proxy.newProxyInstance(
                AbstractDecimalQuantityTest.class.getClassLoader(),
                new Class<?>[] { Unit.class },
                (p, m, a) -> null);
    }

    @Test
    void accessors() {
        TestLengthDecimalImpl q = new TestLengthDecimalImpl(
                new BigDecimal("1.50"),
                KILOMETRE,
                Quantity.Scale.RELATIVE,
                MathContext.DECIMAL32);
        assertEquals(new BigDecimal("1.50"), q.getValue());
        assertSame(KILOMETRE, q.getUnit());
        assertSame(Quantity.Scale.RELATIVE, q.getScale());
        assertSame(MathContext.DECIMAL32, q.mathContext());
        assertSame(MathContext.DECIMAL128, q("1", METRE).mathContext());
    }

    @Test
    void nullArgumentsFail() {
        BigDecimal one = BigDecimal.ONE;
        Quantity.Scale abs = Quantity.Scale.ABSOLUTE;
        MathContext mc = MathContext.DECIMAL128;
        assertThrows(NullPointerException.class, () -> new TestLengthDecimalImpl(null, METRE, abs, mc));
        assertThrows(NullPointerException.class, () -> new TestLengthDecimalImpl(one, null, abs, mc));
        assertThrows(NullPointerException.class, () -> new TestLengthDecimalImpl(one, METRE, null, mc));
        assertThrows(NullPointerException.class, () -> new TestLengthDecimalImpl(one, METRE, abs, null));
    }

    @Test
    void scaleIsBounded() {
        assertEquals(100_000, q("1e-100000", METRE).getValue().scale());
        assertEquals(-100_000, q("1e+100000", METRE).getValue().scale());
        assertThrows(ArithmeticException.class, () -> q("1e-100001", METRE));
        assertThrows(ArithmeticException.class, () -> q("1e+100001", METRE));
    }

    @Test
    void digitsAndPrecisionAreBounded() {
        MathContext max = new MathContext(AbstractDecimalQuantity.MAX_PRECISION);
        BigDecimal third = (BigDecimal) new TestLengthDecimalImpl(
                new BigDecimal("0.1"),
                METRE,
                Quantity.Scale.ABSOLUTE,
                max).divide(3).getValue();
        assertEquals(50_000, third.precision());
        assertEquals(50_001, third.scale());
        BigDecimal longest = new BigDecimal(BigInteger.TEN.pow(50_000).subtract(BigInteger.ONE), 7);
        assertEquals(longest, new TestLengthDecimalImpl(longest, METRE).getValue());
        BigDecimal over = new BigDecimal(BigInteger.TEN.pow(50_000));
        assertEquals(
                "too many digits: 50001",
                assertThrows(ArithmeticException.class, () -> new TestLengthDecimalImpl(over, METRE)).getMessage());
        assertEquals(
                "too many digits: 50001",
                assertThrows(
                        ArithmeticException.class,
                        () -> UntypedDecimalQuantity.of(over, METRE, MathContext.UNLIMITED)).getMessage());
    }

    @Test
    void convertsToAnotherUnit() {
        TestLength r = q("1.5", KILOMETRE).to(METRE);
        assertInstanceOf(TestLengthDecimalImpl.class, r);
        assertEquals(0, new BigDecimal("1500").compareTo((BigDecimal) r.getValue()));
        assertSame(METRE, r.getUnit());
        assertEquals(new BigDecimal("0.0254"), q("1", INCH).to(METRE).getValue());
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) q("212", FAHRENHEIT).to(CELSIUS).getValue()));
        assertEquals(0, new BigDecimal("212").compareTo((BigDecimal) q("100", CELSIUS).to(FAHRENHEIT).getValue()));
    }

    @Test
    void toKeepsScaleAndContext() {
        TestLengthDecimalImpl q = new TestLengthDecimalImpl(
                new BigDecimal("1"),
                KILOMETRE,
                Quantity.Scale.RELATIVE,
                MathContext.DECIMAL32);
        TestLengthDecimalImpl r = (TestLengthDecimalImpl) q.to(METRE);
        assertSame(Quantity.Scale.RELATIVE, r.getScale());
        assertSame(MathContext.DECIMAL32, r.mathContext());
    }

    @Test
    void toRoundsToTheContext() {
        UomUnit<Length> third = METRE.divide(3);
        assertEquals(new BigDecimal("0.333"), q("1", third, new MathContext(3)).to(METRE).getValue());
        TestLengthDecimalImpl unrounded = q("1.23456789", METRE, MathContext.DECIMAL32);
        assertEquals(new BigDecimal("1.23456789"), unrounded.getValue());
        assertEquals(new BigDecimal("1.234568"), unrounded.to(METRE).getValue());
    }

    @Test
    void toPastTheScaleBoundFails() {
        ArithmeticException e = assertThrows(ArithmeticException.class, () -> q("1e-100000", METRE).to(KILOMETRE));
        assertEquals("scale out of range: 100003", e.getMessage());
    }

    @Test
    void toForeignOrIncompatibleUnitFails() {
        TestLengthDecimalImpl one = q("1", METRE);
        UnconvertibleException e = assertThrows(UnconvertibleException.class, () -> one.to(foreignUnit()));
        assertEquals("foreign unit implementation: ", e.getMessage().substring(0, 29));
        assertThrows(NullPointerException.class, () -> one.to((Unit<Length>) null));
        UomUnit<Length> second = UomUnit.unchecked(BaseUnits.SECOND);
        assertThrows(UnconvertibleException.class, () -> one.to(second));
    }

    @Test
    void equalsComparesValuesNumerically() {
        assertEquals(q("1.0", METRE), q("1.00", METRE));
        assertEquals(q("0", METRE), q("0.000", METRE));
        assertEquals(q("100", METRE), q("1E+2", METRE));
        assertEquals(q("1.5", METRE, MathContext.DECIMAL32), q("1.5", METRE, MathContext.DECIMAL128));
        assertNotEquals(q("1", KILOMETRE), q("1", METRE));
        assertNotEquals(q("1000", METRE), q("1", KILOMETRE));
        assertNotEquals(q("1", METRE), q("2", METRE));
        assertNotEquals(
                q("1", METRE),
                new TestLengthDecimalImpl(BigDecimal.ONE, METRE, Quantity.Scale.RELATIVE, MathContext.DECIMAL128));
        assertNotEquals(q("1.5", METRE), new TestLengthImpl(1.5, METRE));
        assertNotEquals(new TestLengthImpl(1.5, METRE), q("1.5", METRE));
        assertNotEquals(q("1", METRE), null);
    }

    @Test
    void hashCodeIgnoresTrailingZeros() {
        assertEquals(q("1.0", METRE).hashCode(), q("1.00", METRE).hashCode());
        assertEquals(q("100", METRE).hashCode(), q("1E+2", METRE).hashCode());
        assertEquals(q("0", METRE).hashCode(), q("0.000", METRE).hashCode());
        assertEquals(q("0", METRE).hashCode(), q("0E+5", METRE).hashCode());
    }

    @Test
    void hugeScalesHashAndCompareQuickly() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            assertEquals(q("1e+99999", METRE), q("10e+99998", METRE));
            assertEquals(q("1e+99999", METRE).hashCode(), q("10e+99998", METRE).hashCode());
            assertEquals(q("1e-99999", METRE), q("0.10e-99998", METRE));
            assertEquals(q("1e-99999", METRE).hashCode(), q("0.10e-99998", METRE).hashCode());
            assertNotEquals(q("1e+99999", METRE), q("1e-99999", METRE));
        });
    }

    @Test
    void negate() {
        TestLengthDecimalImpl q = new TestLengthDecimalImpl(
                new BigDecimal("1.5"),
                KILOMETRE,
                Quantity.Scale.RELATIVE,
                MathContext.DECIMAL32);
        TestLengthDecimalImpl n = (TestLengthDecimalImpl) q.negate();
        assertEquals(new BigDecimal("-1.5"), n.getValue());
        assertSame(KILOMETRE, n.getUnit());
        assertSame(Quantity.Scale.RELATIVE, n.getScale());
        assertSame(MathContext.DECIMAL32, n.mathContext());
        assertEquals(q("0", METRE), q("0", METRE).negate());
    }

    @Test
    void negateIsExact() {
        TestLength n = q("1.234567891", METRE, MathContext.DECIMAL32).negate();
        assertEquals(new BigDecimal("-1.234567891"), n.getValue());
    }

    @Test
    void toDouble() {
        TestLength d = q("1.5", KILOMETRE).toDouble();
        assertInstanceOf(TestLengthImpl.class, d);
        assertEquals(1.5, d.getValue());
        assertSame(KILOMETRE, d.getUnit());
        assertThrows(ArithmeticException.class, () -> q("1e400", METRE).toDouble());
    }

    @Test
    void toDecimalRoundsToTheContext() {
        MathContext three = new MathContext(3);
        TestLengthDecimalImpl r = (TestLengthDecimalImpl) q("1.2345", METRE).toDecimal(three);
        assertEquals(new BigDecimal("1.23"), r.getValue());
        assertSame(METRE, r.getUnit());
        assertEquals(3, r.mathContext().getPrecision());
    }

    @Test
    void withMathContextKeepsTheValueExactly() {
        TestLengthDecimalImpl q = new TestLengthDecimalImpl(
                new BigDecimal("1.23450"),
                KILOMETRE,
                Quantity.Scale.RELATIVE,
                MathContext.DECIMAL128);
        MathContext three = new MathContext(3);
        TestLengthDecimalImpl r = (TestLengthDecimalImpl) q.withMathContext(three);
        assertEquals(new BigDecimal("1.23450"), r.getValue());
        assertEquals(5, r.getValue().scale());
        assertSame(KILOMETRE, r.getUnit());
        assertSame(Quantity.Scale.RELATIVE, r.getScale());
        assertSame(three, r.mathContext());
        assertThrows(NullPointerException.class, () -> q.withMathContext(null));
        assertEquals(
                "precision out of range: 50001",
                assertThrows(ArithmeticException.class, () -> q.withMathContext(new MathContext(50_001))).getMessage());
    }

    @Test
    void toStringUsesTheSymbol() {
        assertEquals("1.50 m", q("1.50", METRE).toString());
        assertEquals("1E+3 m", q("1E+3", METRE).toString());
    }
}
