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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

import org.junit.jupiter.api.Test;

import org.tinygears.uom.engine.Factor;
import org.tinygears.uom.engine.UnitAtom;
import org.tinygears.uom.quantity.Area;
import org.tinygears.uom.quantity.Energy;
import org.tinygears.uom.quantity.Force;
import org.tinygears.uom.quantity.Length;
import org.tinygears.uom.quantity.Mass;
import org.tinygears.uom.quantity.Power;
import org.tinygears.uom.quantity.Pressure;
import org.tinygears.uom.quantity.QuantityTypes;
import org.tinygears.uom.quantity.Speed;
import org.tinygears.uom.quantity.Temperature;
import org.tinygears.uom.quantity.Time;
import org.tinygears.uom.quantity.Volume;
import org.tinygears.uom.systems.CGS;
import org.tinygears.uom.systems.SI;
import org.tinygears.uom.systems.USCustomary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The native API over the generated types and catalogs: typing, products, offsets, decimals. */
class NativeQuantityTest {

    @Test
    void unitsAreTypedWithOurQuantityTypes() {
        // the declarations JSR-385's generics rule out: our Length as the unit's type parameter
        Unit<Length> km = SI.KILOMETRE;
        assertEquals(Length.class, km.type());
        assertEquals("km", km.symbol());
        assertEquals("kilometre", km.name());
        assertEquals(1000.0, Length.of(1, km).to(SI.METRE).value());
        assertEquals(SI.METRE, km.systemUnit());
        assertEquals(33, QuantityTypes.types().size());
    }

    @Test
    void productsAndQuotientsAreTyped() {
        Speed pace = Length.of(42.195, SI.KILOMETRE).divide(Time.of(3, SI.HOUR));
        assertEquals(Speed.SYSTEM_UNIT, pace.unit());
        assertEquals("m/s", pace.unit().symbol());
        assertEquals(42195.0 / 10800.0, pace.value());
        Length covered = pace.multiply(Time.of(30, SI.MINUTE));
        assertEquals(42195.0 / 10800.0 * 1800.0, covered.value());
        Unit<Speed> kmh = SI.KILOMETRE.divide(SI.HOUR).asType(Speed.class);
        Time needed = Length.of(10, SI.KILOMETRE).divide(Speed.of(20, kmh));
        assertEquals(1800.0, needed.value());
        Area a = Length.of(2, SI.METRE).multiply(Length.of(3, SI.METRE));
        Volume v = a.multiply(Length.of(4, SI.METRE));
        assertEquals(24.0, v.value());
        assertEquals("m³", v.unit().symbol());
        Energy e = Force.of(10, SI.NEWTON).multiply(Length.of(2, SI.METRE));
        Power p = e.divide(Time.of(4, SI.SECOND));
        assertEquals(5.0, p.value());
        assertEquals("W", p.unit().symbol());
        assertEquals(
                Mass.of(2, SI.KILOGRAM),
                Force.of(10, SI.NEWTON).divide(e.divide(Length.of(2, SI.METRE)).divide(Mass.of(2, SI.KILOGRAM))));
    }

    @Test
    void productsWithoutARelationAreUntypedAndNarrow() {
        Untyped u = Mass.of(2, SI.KILOGRAM).multiply(Speed.of(3, SI.METRE_PER_SECOND));
        assertEquals(6.0, u.value());
        assertEquals("kg·m/s", u.unit().toString());
        Untyped quotient = Length.of(10, SI.METRE).divide((Quantity<?>) Time.of(2, SI.SECOND));
        Speed narrowed = quotient.as(Speed.class);
        assertEquals(Speed.of(5, Speed.SYSTEM_UNIT), narrowed);
        Untyped inverse = Time.of(4, SI.SECOND).inverse();
        assertEquals(0.25, inverse.value());
        assertEquals(
                "s (T) is not a unit of Length",
                assertThrows(IllegalArgumentException.class, () -> Time.of(1, SI.SECOND).as(Length.class))
                        .getMessage());
    }

    @Test
    void offsetUnitsFollowTheScale() {
        Temperature c = Temperature.of(20, SI.DEGREE_CELSIUS);
        assertEquals(293.15, c.to(SI.KELVIN).value());
        Temperature diff = Temperature.of(20, SI.DEGREE_CELSIUS, Scale.RELATIVE);
        assertEquals(20.0, diff.to(SI.KELVIN).value());
        assertEquals(Scale.RELATIVE, diff.to(SI.KELVIN).scale());
        Temperature warmer = c.add(Temperature.of(5, SI.KELVIN, Scale.RELATIVE));
        assertEquals(Temperature.of(25, SI.DEGREE_CELSIUS), warmer);
        assertEquals(Scale.ABSOLUTE, warmer.scale());
        assertEquals(
                new BigDecimal("212"),
                Temperature.of(new BigDecimal("100"), SI.DEGREE_CELSIUS).to(USCustomary.DEGREE_FAHRENHEIT).value());
        assertEquals(0, Temperature.of(0, SI.DEGREE_CELSIUS).compareTo(Temperature.of(273.15, SI.KELVIN)));
        assertEquals(293.15, Temperature.of(10, SI.DEGREE_CELSIUS).multiply(2).value());
        assertTrue(
                Temperature.of(20, SI.DEGREE_CELSIUS)
                        .isCloseTo(Temperature.of(293.65, SI.KELVIN), Temperature.of(1, SI.DEGREE_CELSIUS)));
    }

    @Test
    void decimalsAreExactAndRoundedOnce() {
        Length a = Length.of(new BigDecimal("0.1"), SI.KILOMETRE);
        Length sum = a.add(Length.of(new BigDecimal("0.2"), SI.METRE));
        assertEquals(new BigDecimal("0.1002"), sum.value());
        assertEquals(MathContext.DECIMAL128, sum.mathContext());
        Speed rounded = Length.of(new BigDecimal("42.195"), SI.KILOMETRE, Scale.ABSOLUTE, MathContext.DECIMAL32)
                .divide(Time.of(new BigDecimal("3"), SI.HOUR));
        assertEquals(new BigDecimal("3.906944"), rounded.value());
        assertEquals(MathContext.DECIMAL32, rounded.mathContext());
        assertEquals(new BigDecimal("0.03333333"), a.divide(3).toDecimal(MathContext.DECIMAL32).value());
        assertEquals(new BigDecimal("-0.1"), a.negate().value());
    }

    @Test
    void namedUnitsTakeTheSystemUnitOfTheirType() {
        assertSame(Pressure.SYSTEM_UNIT, SI.KILOPASCAL.systemUnit());
        assertEquals("Pa", SI.KILOPASCAL.systemUnit().symbol());
        assertEquals("pascal", SI.KILOPASCAL.systemUnit().name());
        assertSame(Speed.SYSTEM_UNIT, USCustomary.KNOT.systemUnit());
        assertEquals("N", SI.NEWTON.systemUnit().symbol());
        // a named unit of no standard type and an unnamed product are labelled by their atoms
        assertEquals("kg/(m·s)", CGS.POISE.systemUnit().symbol());
        assertNull(CGS.POISE.systemUnit().name());
        assertEquals("kg·m^2/s^2", SI.NEWTON.multiply(SI.METRE).systemUnit().symbol());
        assertEquals("kg/(m·s^2)", SI.KILOPASCAL.multiply(2).systemUnit().symbol());
    }

    @Test
    void decimalsAreBoundedInDigitsAndPrecision() {
        MathContext max = new MathContext(50_000);
        BigDecimal third = (BigDecimal) Length.of(new BigDecimal("0.1"), SI.METRE, Scale.ABSOLUTE, max).divide(3)
                .value();
        assertEquals(50_000, third.precision());
        assertEquals(50_001, third.scale());
        BigDecimal longest = new BigDecimal(BigInteger.TEN.pow(50_000).subtract(BigInteger.ONE));
        assertEquals(longest, Length.of(longest, SI.METRE).value());
        BigDecimal over = new BigDecimal(BigInteger.TEN.pow(50_000));
        assertEquals(
                "too many digits: 50001",
                assertThrows(ArithmeticException.class, () -> Length.of(over, SI.METRE)).getMessage());
        assertEquals(
                "too many digits: 50001",
                assertThrows(
                        ArithmeticException.class,
                        () -> Length.of(longest, SI.METRE, Scale.ABSOLUTE, MathContext.UNLIMITED)
                                .add(Length.of(BigDecimal.ONE, SI.METRE, Scale.ABSOLUTE, MathContext.UNLIMITED)))
                        .getMessage());
        assertEquals(
                "precision out of range: 50001",
                assertThrows(
                        ArithmeticException.class,
                        () -> Length.of(BigDecimal.ONE, SI.METRE, Scale.ABSOLUTE, new MathContext(50_001)))
                        .getMessage());
    }

    @Test
    void mixedOperandsAreDecimal() {
        Length d = Length.of(new BigDecimal("0.1"), SI.METRE, Scale.ABSOLUTE, MathContext.DECIMAL64);
        Length sum = Length.of(0.2, SI.METRE).add(d);
        assertEquals(new BigDecimal("0.3"), sum.value());
        assertEquals(MathContext.DECIMAL64, sum.mathContext());
        assertEquals(0.30000000000000004, Length.of(0.2, SI.METRE).add(Length.of(0.1, SI.METRE)).value());
        assertEquals(1, Length.of(Double.POSITIVE_INFINITY, SI.METRE).compareTo(d));
        assertEquals(Length.of(2.0, SI.METRE), Length.of(new BigDecimal("2.00"), SI.METRE).toDouble());
        assertEquals(Length.of(new BigDecimal("2"), SI.METRE), Length.of(new BigDecimal("2.00"), SI.METRE));
        assertNotEquals(Length.of(2.0, SI.METRE), Length.of(new BigDecimal("2"), SI.METRE));
        assertThrows(ArithmeticException.class, () -> Length.of(Double.NaN, SI.METRE).add(d));
    }

    @Test
    void equivalenceAndCloseness() {
        assertTrue(Length.of(0.1 + 0.2, SI.METRE).isEquivalentTo(Length.of(new BigDecimal("0.3"), SI.METRE)));
        assertFalse(
                Length.of(new BigDecimal("0.3000000001"), SI.METRE)
                        .isEquivalentTo(Length.of(new BigDecimal("0.3"), SI.METRE)));
        assertTrue(Length.of(1, SI.KILOMETRE).isEquivalentTo(Length.of(1000, SI.METRE)));
        Length a = Length.of(new BigDecimal("1000.0000000000001"), SI.METRE);
        assertEquals(1, a.compareTo(Length.of(1, SI.KILOMETRE)));
        assertTrue(a.isCloseTo(Length.of(1, SI.KILOMETRE), 1e-16));
        assertFalse(a.isCloseTo(Length.of(1, SI.KILOMETRE), 1e-17));
        assertThrows(IllegalArgumentException.class, () -> a.isCloseTo(a, -1));
    }

    @Test
    void untypedUnitsOfOtherDimensionsAreRefused() {
        Untyped m = Untyped.of(1, SI.METRE);
        Untyped s = Untyped.of(1, SI.SECOND);
        assertEquals(
                "incompatible dimensions: s and m",
                assertThrows(UnitException.class, () -> m.add(s)).getMessage());
        assertThrows(UnitException.class, () -> m.compareTo(s));
        assertFalse(m.isEquivalentTo(s));
    }

    @Test
    void unitsCheckTheirType() {
        assertEquals(
                "s (T) is not a unit of Length",
                assertThrows(
                        IllegalArgumentException.class,
                        () -> Unit.of(Length.class, org.tinygears.uom.engine.Unit.base(UnitAtom.SECOND, "second")))
                        .getMessage());
        Unit<Untyped> kmh = SI.KILOMETRE.divide(SI.HOUR);
        Unit<Speed> typed = kmh.asType(Speed.class);
        assertEquals(Speed.class, typed.type());
        assertEquals("km/h", typed.symbol());
        assertEquals(Speed.SYSTEM_UNIT.scaledBy(Factor.of(5, 18)), typed);
        assertSame(SI.METRE, SI.METRE.asType(Length.class));
        Unit<Length> yard = SI.METRE.scaledBy(Factor.of(1143, 1250)).named("yd", "yard");
        assertEquals(USCustomary.YARD, yard);
        assertNull(SI.METRE.scaledBy(Factor.ofLong(2)).symbol());
    }

    @Test
    void convertersAreExactAndNative() {
        Converter c = SI.DEGREE_CELSIUS.converterTo(USCustomary.DEGREE_FAHRENHEIT);
        assertEquals(212.0, c.convert(100));
        assertEquals(new BigDecimal("212.0"), c.convert(new BigDecimal("100"), MathContext.UNLIMITED));
        assertEquals(Factor.of(9, 5), c.factor());
        assertEquals(Factor.ofLong(32), c.offset());
        assertFalse(c.isLinear());
        assertEquals(100.0, c.inverse().convert(212));
        assertTrue(c.concatenate(c.inverse()).isIdentity());
        assertEquals(SI.METRE.converterTo(SI.KILOMETRE), SI.METRE.converterTo(SI.KILOMETRE));
        assertEquals(
                "incompatible dimensions: m and s",
                assertThrows(UnitException.class, () -> SI.METRE.converterTo(SI.SECOND)).getMessage());
    }

    @Test
    void toStringWritesValueAndSymbol() {
        assertEquals("1.5 km", Length.of(1.5, SI.KILOMETRE).toString());
        assertEquals("0.10 m", Length.of(new BigDecimal("0.10"), SI.METRE).toString());
    }

    @Test
    void noJsr385TypeIsReachable() {
        // the module depends on the engine only: unit-api is not even on the class path
        assertThrows(ClassNotFoundException.class, () -> Class.forName("javax.measure.Unit"));
    }
}
