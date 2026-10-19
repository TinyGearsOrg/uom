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
import java.math.MathContext;
import javax.measure.Quantity;
import javax.measure.UnconvertibleException;
import javax.measure.Unit;

import org.junit.jupiter.api.Test;

import org.tinygears.uom.UnitFormat;
import org.tinygears.uom.jsr.BaseUnits;
import org.tinygears.uom.jsr.Jsr;
import org.tinygears.uom.jsr.UomUnit;
import org.tinygears.uom.jsr.systems.SI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The factories {@code Untyped.of}, which the typed interfaces have as {@code Length.of}. */
class UntypedFactoryTest {

    private static final Unit<javax.measure.quantity.Length> METRE = BaseUnits.METRE;
    private static final UomUnit<?> METRE_PER_SECOND = BaseUnits.METRE.divide(BaseUnits.SECOND);
    private static final UomUnit<?> CELSIUS = BaseUnits.KELVIN.shift(273.15);
    private static final MathContext DECIMAL32 = MathContext.DECIMAL32;
    private static final MathContext DECIMAL128 = MathContext.DECIMAL128;

    @Test
    void doubleQuantity() {
        UntypedDoubleQuantity q = assertInstanceOf(UntypedDoubleQuantity.class, Untyped.of(2.5, METRE_PER_SECOND));
        assertEquals(2.5, q.getValue());
        assertEquals(METRE_PER_SECOND, q.getUnit());
        assertSame(Quantity.Scale.ABSOLUTE, q.getScale());
    }

    @Test
    void anIntegerIsADouble() {
        assertEquals(5.0, assertInstanceOf(UntypedDoubleQuantity.class, Untyped.of(5, METRE)).getValue());
        assertEquals(5.0, assertInstanceOf(UntypedDoubleQuantity.class, Untyped.of(5L, METRE)).getValue());
    }

    @Test
    void doubleQuantityWithAScale() {
        Untyped q = Untyped.of(2.5, METRE_PER_SECOND, Quantity.Scale.RELATIVE);
        assertEquals(2.5, assertInstanceOf(UntypedDoubleQuantity.class, q).getValue());
        assertEquals(METRE_PER_SECOND, q.getUnit());
        assertSame(Quantity.Scale.RELATIVE, q.getScale());
    }

    @Test
    void decimalQuantityKeepsTheValueAndHasTheDefaultContext() {
        UntypedDecimalQuantity q = assertInstanceOf(
                UntypedDecimalQuantity.class,
                Untyped.of(new BigDecimal("1.50"), METRE));
        assertEquals(new BigDecimal("1.50"), q.getValue());
        assertEquals(METRE, q.getUnit());
        assertSame(Quantity.Scale.ABSOLUTE, q.getScale());
        assertEquals(DECIMAL128, q.mathContext());
    }

    @Test
    void decimalQuantityWithAScale() {
        UntypedDecimalQuantity q = assertInstanceOf(
                UntypedDecimalQuantity.class,
                Untyped.of(new BigDecimal("2.50"), METRE, Quantity.Scale.RELATIVE));
        assertEquals(new BigDecimal("2.50"), q.getValue());
        assertSame(Quantity.Scale.RELATIVE, q.getScale());
        assertEquals(DECIMAL128, q.mathContext());
    }

    @Test
    void decimalQuantityWithAContextIsNotRounded() {
        UntypedDecimalQuantity q = assertInstanceOf(
                UntypedDecimalQuantity.class,
                Untyped.of(new BigDecimal("1.23456789"), METRE, Quantity.Scale.ABSOLUTE, DECIMAL32));
        assertEquals(new BigDecimal("1.23456789"), q.getValue());
        assertEquals(DECIMAL32, q.mathContext());
        assertSame(Quantity.Scale.ABSOLUTE, q.getScale());
    }

    @Test
    void aUnitOfAnyDimension() {
        assertEquals(CELSIUS, Untyped.of(20, CELSIUS).getUnit());
        assertEquals(20.0, Untyped.of(20, CELSIUS).getValue());
        UomUnit<?> momentum = BaseUnits.KILOGRAM.multiply(BaseUnits.METRE).divide(BaseUnits.SECOND);
        assertEquals("6.0 kg·m/s", Untyped.of(6, momentum).toString());
        assertEquals(BaseUnits.SECOND.inverse(), Untyped.of(1.5, BaseUnits.SECOND.inverse()).getUnit());
    }

    @Test
    void isTheQuantityOfTheImplementationFactories() {
        assertEquals(UntypedDoubleQuantity.of(2.5, METRE_PER_SECOND), Untyped.of(2.5, METRE_PER_SECOND));
        assertEquals(
                UntypedDecimalQuantity.of(new BigDecimal("2.5"), METRE_PER_SECOND, DECIMAL128),
                Untyped.of(new BigDecimal("2.5"), METRE_PER_SECOND));
    }

    @Test
    void narrowsLikeAnyOtherUntypedQuantity() {
        Length length = Length.from(Untyped.of(5, METRE));
        assertEquals(Length.of(5, METRE), length);
        assertEquals(5.0, length.getValue());
        assertThrows(ClassCastException.class, () -> Time.from(Untyped.of(5, METRE)));
    }

    @Test
    void aUnitOfAnotherImplementationIsRefused() {
        Unit<?> foreign = (Unit<?>) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { Unit.class },
                (proxy, method, args) -> "foreign");
        for (var create : new java.util.function.Supplier<?>[] {
            () -> Untyped.of(1.0, foreign),
            () -> Untyped.of(1.0, foreign, Quantity.Scale.ABSOLUTE),
            () -> Untyped.of(BigDecimal.ONE, foreign),
            () -> Untyped.of(BigDecimal.ONE, foreign, Quantity.Scale.ABSOLUTE),
            () -> Untyped.of(BigDecimal.ONE, foreign, Quantity.Scale.ABSOLUTE, DECIMAL32) }) {
            UnconvertibleException e = assertThrows(UnconvertibleException.class, create::get);
            assertTrue(e.getMessage().startsWith("foreign unit implementation: "), e.getMessage());
        }
    }

    @Test
    void nullArguments() {
        assertThrows(NullPointerException.class, () -> Untyped.of(1.0, null));
        assertThrows(NullPointerException.class, () -> Untyped.of(1.0, null, Quantity.Scale.ABSOLUTE));
        assertThrows(NullPointerException.class, () -> Untyped.of(BigDecimal.ONE, null));
        assertThrows(NullPointerException.class, () -> Untyped.of(BigDecimal.ONE, null, Quantity.Scale.ABSOLUTE));
        assertThrows(NullPointerException.class, () -> Untyped.of((BigDecimal) null, METRE));
        assertThrows(NullPointerException.class, () -> Untyped.of((BigDecimal) null, METRE, Quantity.Scale.ABSOLUTE));
        assertThrows(
                NullPointerException.class,
                () -> Untyped.of(BigDecimal.ONE, METRE, Quantity.Scale.ABSOLUTE, null));
        assertThrows(NullPointerException.class, () -> Untyped.of(1.0, METRE, null));
        assertThrows(NullPointerException.class, () -> Untyped.of(BigDecimal.ONE, METRE, null));
    }

    @Test
    void aValueOfTooManyDigitsIsRefused() {
        BigDecimal over = new BigDecimal("1".repeat(50001));
        assertThrows(ArithmeticException.class, () -> Untyped.of(over, METRE));
        assertThrows(
                ArithmeticException.class,
                () -> Untyped.of(BigDecimal.ONE, METRE, Quantity.Scale.ABSOLUTE, new MathContext(50001)));
    }

    /** A unit of uom-jsr becomes a unit of the native API through a quantity of one, whatever its dimension. */
    @Test
    void aUnitOfUomJsrBecomesANativeUnit() {
        UnitFormat format = UnitFormat.standard();
        assertEquals("km", format.format(nativeUnit(SI.KILOMETRE)));
        assertEquals("kg·m", format.format(nativeUnit(SI.KILOGRAM.multiply(SI.METRE))));
        assertEquals("°C", format.format(nativeUnit(SI.DEGREE_CELSIUS)));
        assertEquals("m/s^2", format.format(nativeUnit(SI.METRE.divide(SI.SECOND.pow(2)))));
        assertEquals(org.tinygears.uom.systems.SI.NEWTON, nativeUnit(SI.NEWTON));
    }

    private static org.tinygears.uom.Unit<?> nativeUnit(Unit<?> unit) {
        return Jsr.fromJsr(Untyped.of(1, unit), org.tinygears.uom.Untyped.class).unit();
    }
}
