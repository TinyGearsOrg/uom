/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.jsr.contract;

import java.math.BigDecimal;
import java.util.Map;
import javax.measure.BinaryPrefix;
import javax.measure.IncommensurableException;
import javax.measure.MeasurementException;
import javax.measure.MetricPrefix;
import javax.measure.UnconvertibleException;
import javax.measure.Unit;
import javax.measure.quantity.Length;
import javax.measure.quantity.Speed;
import javax.measure.quantity.Temperature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.tinygears.uom.engine.Factor;
import org.tinygears.uom.jsr.Converters;
import org.tinygears.uom.jsr.TestProviderExtension;
import org.tinygears.uom.jsr.TestServiceProvider;
import org.tinygears.uom.jsr.UomUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.tinygears.uom.jsr.BaseUnits.KELVIN;
import static org.tinygears.uom.jsr.BaseUnits.KILOGRAM;
import static org.tinygears.uom.jsr.BaseUnits.METRE;
import static org.tinygears.uom.jsr.BaseUnits.ONE;
import static org.tinygears.uom.jsr.BaseUnits.SECOND;

/** Each test states one clause of the unit-api 2.2 {@link Unit} Javadoc. */
@ExtendWith(TestProviderExtension.class)
class UnitContractTest {

    private static final UomUnit<Temperature> CELSIUS = KELVIN.shift(273.15);

    @Test
    void getConverterToThrowsUnconvertibleExceptionForIncompatibleUnits() {
        @SuppressWarnings({ "unchecked", "rawtypes" })
        Unit<Temperature> second = (Unit) SECOND;
        assertThrows(UnconvertibleException.class, () -> KELVIN.getConverterTo(second));
    }

    @Test
    void getConverterToAnyThrowsIncommensurableExceptionIfNotCompatible() {
        assertThrows(IncommensurableException.class, () -> METRE.getConverterToAny(SECOND));
    }

    @Test
    void getConverterToAnyConvertsCompatibleUnits() throws IncommensurableException {
        assertEquals(2000.0, METRE.multiply(1000).getConverterToAny(METRE).convert(2.0));
    }

    @Test
    void radianIsCompatibleWithOneButNotEqual() {
        UomUnit<?> radian = ONE.alternate("rad");
        assertNotEquals(ONE, radian);
        assertTrue(radian.isCompatible(ONE));
    }

    @Test
    void isCompatibleIsDimensionEquality() {
        assertTrue(METRE.isCompatible(METRE.multiply(1000)));
        assertEquals(METRE.getDimension(), METRE.multiply(1000).getDimension());
        assertFalse(METRE.isCompatible(SECOND));
        assertNotEquals(METRE.getDimension(), SECOND.getDimension());
    }

    @Test
    void isEquivalentToMeansIdentityConversion() {
        assertTrue(METRE.multiply(1000).isEquivalentTo(METRE.prefix(MetricPrefix.KILO)));
        assertFalse(METRE.isEquivalentTo(METRE.multiply(1000)));
    }

    @Test
    void isEquivalentToNullThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> METRE.isEquivalentTo(null));
    }

    @Test
    void getBaseUnitsIsNullForABaseUnit() {
        assertNull(METRE.getBaseUnits());
        assertNull(KELVIN.getBaseUnits());
    }

    @Test
    void getBaseUnitsOfAProductMapsBaseUnitsToExponents() {
        assertEquals(Map.of(METRE, 1, SECOND, -2), METRE.divide(SECOND.pow(2)).getBaseUnits());
    }

    @Test
    void getSystemUnitIsUnscaled() {
        assertEquals(METRE, METRE.multiply(1000).getSystemUnit());
        assertEquals(KELVIN, CELSIUS.getSystemUnit());
    }

    @Test
    void getSystemUnitOfASystemUnitEqualsItself() {
        // Javadoc says "this"; we return an equal unit with a computed label (DESIGN section 3).
        UomUnit<?> perSecond = METRE.divide(SECOND);
        assertEquals(perSecond, perSecond.getSystemUnit());
    }

    @Test
    void getSystemUnitIdentifiesAngularSpeed() {
        UomUnit<?> radian = ONE.alternate("rad");
        UomUnit<?> revolution = radian.multiply(2 * Math.PI);
        UomUnit<?> minute = SECOND.multiply(60);
        assertEquals(radian.divide(SECOND), revolution.divide(minute).getSystemUnit());
    }

    @Test
    void asTypeOfSpeedOfLight() {
        UomUnit<?> c = METRE.multiply(299792458).divide(SECOND);
        assertSame(c, c.asType(Speed.class));
    }

    @Test
    void asTypeThrowsClassCastExceptionForADifferentDimension() {
        assertThrows(ClassCastException.class, () -> METRE.asType(Speed.class));
    }

    @Test
    void alternateExamples() {
        UomUnit<?> newton = METRE.multiply(KILOGRAM).divide(SECOND.pow(2)).alternate("N");
        UomUnit<?> pascal = newton.divide(METRE.pow(2)).alternate("Pa");
        assertEquals("N", newton.getSymbol());
        assertEquals("Pa", pascal.getSymbol());
        assertEquals(pascal, pascal.getSystemUnit());
        assertNotEquals(newton.divide(METRE.pow(2)), pascal);
        assertTrue(newton.divide(METRE.pow(2)).isCompatible(pascal));
    }

    @Test
    void alternateThrowsIllegalArgumentExceptionForAScaledUnit() {
        assertThrows(IllegalArgumentException.class, () -> METRE.multiply(1000).alternate("ctKm"));
    }

    @Test
    void alternateThrowsMeasurementExceptionForAnInvalidSymbol() {
        assertThrows(MeasurementException.class, () -> ONE.alternate(""));
        assertThrows(MeasurementException.class, () -> ONE.alternate("c t"));
    }

    @Test
    void alternateThrowsMeasurementExceptionForASymbolOfADifferentUnit() {
        ONE.divide(SECOND).alternate("ctA");
        assertThrows(MeasurementException.class, () -> METRE.alternate("ctA"));
        assertThrows(MeasurementException.class, () -> SECOND.alternate("m"));
    }

    @Test
    void shiftSetsTheOriginSoConvertingToItSubtractsTheOffset() {
        assertEquals(0.0, KELVIN.getConverterTo(CELSIUS).convert(273.15));
        assertEquals(273.15, CELSIUS.getConverterTo(KELVIN).convert(0.0));
        assertEquals(CELSIUS, KELVIN.shift(new BigDecimal("273.15")));
    }

    @Test
    void shiftedUnitIsConvertibleWithUnitsConvertibleWithTheOriginal() {
        UomUnit<Temperature> millikelvin = KELVIN.prefix(MetricPrefix.MILLI);
        assertEquals(273150.0, CELSIUS.getConverterTo(millikelvin).convert(0.0), 1e-9);
    }

    @Test
    void multiplyByAnIntegerIsExact() {
        UomUnit<Length> foot = METRE.multiply(3048).divide(10000);
        BigDecimal metres = (BigDecimal) foot.getConverterTo(METRE).convert((Number) BigDecimal.ONE);
        assertEquals(0, new BigDecimal("0.3048").compareTo(metres), metres::toPlainString);
    }

    @Test
    void multiplyByAnApproximateDouble() {
        assertEquals(9.10938188e-31, KILOGRAM.multiply(9.10938188e-31).getConverterTo(KILOGRAM).convert(1.0));
    }

    @Test
    void divideByANumber() {
        assertEquals(0.001, KILOGRAM.divide(1000).getConverterTo(KILOGRAM).convert(1.0));
        assertEquals(0.001, KILOGRAM.divide(1000d).getConverterTo(KILOGRAM).convert(1.0));
    }

    @Test
    void multiplyByAUnitIsTheProduct() {
        assertEquals(METRE.pow(2), METRE.multiply(METRE));
        assertEquals(METRE.multiply(SECOND).multiply(1000), METRE.multiply(1000).multiply(SECOND));
        assertEquals(METRE.getDimension().multiply(SECOND.getDimension()), METRE.multiply(SECOND).getDimension());
    }

    @Test
    void divideByAUnitIsTheQuotient() {
        assertEquals(METRE, METRE.divide(SECOND).multiply(SECOND));
        assertEquals(METRE.divide(SECOND).multiply(1000), METRE.multiply(1000).divide(SECOND));
        assertEquals(METRE.getDimension().divide(SECOND.getDimension()), METRE.divide(SECOND).getDimension());
    }

    @Test
    void inverseIsTheReciprocal() {
        UomUnit<?> km = METRE.multiply(1000);
        assertEquals(ONE.divide(SECOND), SECOND.inverse());
        assertEquals(ONE, km.inverse().multiply(km));
    }

    @Test
    void powRaisesFactorAndDimension() {
        assertEquals(METRE.pow(2).multiply(1_000_000), METRE.multiply(1000).pow(2));
        assertEquals(METRE.getDimension().pow(3), METRE.pow(3).getDimension());
    }

    @Test
    void rootOfHectareIsHectometre() {
        UomUnit<?> hectare = METRE.pow(2).multiply(10000);
        assertEquals(METRE.multiply(100), hectare.root(2));
    }

    @Test
    void rootOfLitreThrowsArithmeticException() {
        UomUnit<?> litre = METRE.pow(3).divide(1000);
        assertThrows(ArithmeticException.class, () -> litre.root(2));
    }

    @Test
    void rootZeroThrowsArithmeticException() {
        assertThrows(ArithmeticException.class, () -> METRE.pow(2).root(0));
    }

    @Test
    void transformTakesTheConverterFromTheNewUnitToThisOne() {
        assertEquals(METRE.multiply(1000), METRE.transform(Converters.scale(Factor.ofLong(1000))));
        assertEquals(CELSIUS, KELVIN.transform(Converters.shift(Factor.of(27315, 100))));
    }

    @Test
    void prefixScalesByThePrefixValue() {
        UomUnit<?> km = METRE.prefix(MetricPrefix.KILO);
        assertEquals(METRE.multiply(1000), km);
        assertNull(km.getSymbol());
        assertEquals(METRE.multiply(1024), METRE.prefix(BinaryPrefix.KIBI));
    }

    @Test
    void offsetUnitProductDropsTheOffset() {
        // The class Javadoc allows products of offset units; the product measures differences (docs/NATIVE.md#offset-units).
        assertEquals(KELVIN.divide(METRE), CELSIUS.divide(METRE));
        assertEquals(KELVIN.divide(METRE).getSystemUnit(), CELSIUS.divide(METRE).getSystemUnit());
        assertTrue(CELSIUS.divide(METRE).toSystemConverter().isLinear());
    }
}
