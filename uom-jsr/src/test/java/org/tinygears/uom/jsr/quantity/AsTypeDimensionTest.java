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

import java.math.BigDecimal;
import javax.measure.Quantity;

import org.junit.jupiter.api.Test;

import org.tinygears.uom.jsr.systems.SI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** asType goes by dimension, as the Unit and Quantity Javadoc say; alternate units (Hz, Bq, rad, sr) are no obstacle. */
class AsTypeDimensionTest {
    private static final double EPS = 1e-12;

    @Test
    void aReciprocalTimeIsAFrequencyOrARadioactivity() {
        Quantity<?> perSecond = Time.of(2, SI.SECOND).inverse();
        Quantity<javax.measure.quantity.Frequency> f = perSecond.asType(javax.measure.quantity.Frequency.class);
        assertInstanceOf(Frequency.class, f);
        assertEquals(0.5, f.getValue().doubleValue(), EPS);
        assertEquals(0.5, f.to(SI.HERTZ).getValue().doubleValue(), EPS);
        assertInstanceOf(Radioactivity.class, perSecond.asType(javax.measure.quantity.Radioactivity.class));
    }

    @Test
    void aFrequencyTimesATimeIsDimensionless() {
        Quantity<?> product = Frequency.of(2, SI.HERTZ).multiply(Time.of(3, SI.SECOND));
        Quantity<javax.measure.quantity.Dimensionless> d = product.asType(javax.measure.quantity.Dimensionless.class);
        assertInstanceOf(Dimensionless.class, d);
        assertEquals(6.0, d.getValue().doubleValue(), EPS);
    }

    @Test
    void anAngleTimesALengthIsALength() {
        Quantity<?> arc = Angle.of(2, SI.RADIAN).multiply(Length.of(3, SI.METRE));
        Quantity<javax.measure.quantity.Length> length = arc.asType(javax.measure.quantity.Length.class);
        assertInstanceOf(Length.class, length);
        assertEquals(6.0, length.to(SI.METRE).getValue().doubleValue(), EPS);
    }

    @Test
    void alternateUnitsOfTheSameDimensionConvert() {
        assertInstanceOf(
                Radioactivity.class,
                Frequency.of(2, SI.HERTZ).asType(javax.measure.quantity.Radioactivity.class));
        assertInstanceOf(
                Dimensionless.class,
                Angle.of(2, SI.RADIAN).asType(javax.measure.quantity.Dimensionless.class));
        assertInstanceOf(SolidAngle.class, Angle.of(2, SI.RADIAN).asType(javax.measure.quantity.SolidAngle.class));
        Quantity<?> ratio = Length.of(2, SI.METRE).divide(Length.of(1, SI.METRE));
        assertInstanceOf(Angle.class, ratio.asType(javax.measure.quantity.Angle.class));
        // Equality of units stays structural: Hz is not Bq and not 1/s, but they are equivalent.
        assertNotEquals(SI.HERTZ, SI.BECQUEREL);
        assertNotEquals(SI.HERTZ, SI.SECOND.inverse());
    }

    @Test
    void aDifferentDimensionIsStillRejected() {
        assertThrows(ClassCastException.class, () -> Length.of(1, SI.METRE).asType(javax.measure.quantity.Time.class));
        assertThrows(
                ClassCastException.class,
                () -> Frequency.of(1, SI.HERTZ).asType(javax.measure.quantity.Angle.class));
        assertThrows(ClassCastException.class, () -> SI.HERTZ.asType(javax.measure.quantity.Length.class));
        assertThrows(ClassCastException.class, () -> SI.RADIAN.asType(javax.measure.quantity.Frequency.class));
    }

    @Test
    void decimalQuantitiesFollowTheSameRule() {
        Quantity<?> perSecond = Time.of(new BigDecimal("2"), SI.SECOND).inverse();
        Quantity<javax.measure.quantity.Frequency> f = perSecond.asType(javax.measure.quantity.Frequency.class);
        assertEquals(0, new BigDecimal("0.5").compareTo((BigDecimal) f.getValue()));
        Quantity<?> product = Frequency.of(new BigDecimal("2"), SI.HERTZ)
                .multiply(Time.of(new BigDecimal("3"), SI.SECOND));
        Quantity<javax.measure.quantity.Dimensionless> d = product.asType(javax.measure.quantity.Dimensionless.class);
        assertEquals(0, new BigDecimal("6").compareTo((BigDecimal) d.getValue()));
        Quantity<?> arc = Angle.of(new BigDecimal("2"), SI.RADIAN).multiply(Length.of(new BigDecimal("3"), SI.METRE));
        assertEquals(
                0,
                new BigDecimal("6").compareTo(
                        (BigDecimal) arc.asType(javax.measure.quantity.Length.class).to(SI.METRE).getValue()));
    }

    @Test
    void asTakesTheSystemUnitOfTheTypeWhereTheUnitIsEqual() {
        Quantity<?> product = Untyped.of(3, SI.KILONEWTON).multiply(Untyped.of(2, SI.METRE));
        assertEquals("6000.0 kg·m^2/s^2", product.toString());
        Energy energy = ((UomQuantity<?, ?>) product).as(Energy.class);
        assertEquals("6000.0 J", energy.toString());
        assertEquals(
                "6000.0 kg·m^2/s^2",
                ((UomQuantity<?, ?>) product).asType(javax.measure.quantity.Energy.class).toString());
        UomQuantity<?, ?> perSecond = (UomQuantity<?, ?>) Untyped.of(2, SI.SECOND).inverse();
        assertEquals("0.5 1/s", perSecond.as(Frequency.class).toString());
        assertEquals("3.0 km", Untyped.of(3, SI.KILOMETRE).as(Length.class).toString());
    }

    @Test
    void asKeepsTheDecimalContext() {
        UomQuantity<?, ?> product = (UomQuantity<?, ?>) Untyped.of(new BigDecimal("1.50"), SI.NEWTON)
                .multiply(Untyped.of(new BigDecimal("2"), SI.METRE));
        Energy energy = product.as(Energy.class);
        assertEquals("3.00 J", energy.toString());
        assertEquals(
                ((AbstractDecimalQuantity<?, ?>) product).mathContext(),
                ((AbstractDecimalQuantity<?, ?>) energy).mathContext());
    }
}
