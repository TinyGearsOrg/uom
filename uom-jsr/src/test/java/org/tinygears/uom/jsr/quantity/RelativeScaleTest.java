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
import javax.measure.Quantity.Scale;

import org.junit.jupiter.api.Test;

import org.tinygears.uom.jsr.systems.SI;
import org.tinygears.uom.jsr.systems.USCustomary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offset units (docs/NATIVE.md#offset-units): a RELATIVE quantity is a difference and takes no offset; ABSOLUTE operands
 * follow the literal rule of the Quantity Javadoc.
 */
class RelativeScaleTest {
    private static final double EPS = 1e-12;

    private static Temperature abs(double v) {
        return Temperature.of(v, SI.DEGREE_CELSIUS);
    }

    private static Temperature rel(double v) {
        return Temperature.of(v, SI.DEGREE_CELSIUS, Scale.RELATIVE);
    }

    private static Temperature relK(double v) {
        return Temperature.of(v, SI.KELVIN, Scale.RELATIVE);
    }

    private static void assertQuantity(double value, Scale scale, Quantity<?> q) {
        assertEquals(value, q.getValue().doubleValue(), EPS, q.toString());
        assertSame(scale, q.getScale(), q.toString());
    }

    @Test
    void absoluteOperandsFollowTheLiteralRule() {
        assertQuantity(276.15, Scale.ABSOLUTE, abs(2).add(abs(1)));
        assertQuantity(-272.15, Scale.ABSOLUTE, abs(2).subtract(abs(1)));
        assertQuantity(277.15, Scale.ABSOLUTE, abs(2).multiply(2));
        assertQuantity(-2, Scale.ABSOLUTE, abs(2).negate());
    }

    @Test
    void aRelativeOperandIsAPlainDelta() {
        assertQuantity(3, Scale.ABSOLUTE, abs(2).add(rel(1)));
        assertQuantity(1, Scale.ABSOLUTE, abs(2).subtract(rel(1)));
        assertQuantity(3, Scale.ABSOLUTE, rel(2).add(abs(1)));
        assertQuantity(1, Scale.ABSOLUTE, rel(2).subtract(abs(1)));
        assertQuantity(3, Scale.RELATIVE, rel(2).add(rel(1)));
        assertQuantity(1, Scale.RELATIVE, rel(2).subtract(rel(1)));
        // The delta is converted by factor only: 5 K is 5 degC, and 9 degF is 5 degC.
        assertQuantity(7, Scale.ABSOLUTE, abs(2).add(relK(5)));
        assertQuantity(-3, Scale.ABSOLUTE, abs(2).subtract(relK(5)));
        Temperature nineF = Temperature.of(9, USCustomary.DEGREE_FAHRENHEIT, Scale.RELATIVE);
        assertQuantity(7, Scale.ABSOLUTE, abs(2).add(nineF));
    }

    @Test
    void aRelativeQuantityScalesAndConvertsWithoutOffset() {
        assertQuantity(4, Scale.RELATIVE, rel(2).multiply(2));
        assertQuantity(1, Scale.RELATIVE, rel(2).divide(2));
        assertQuantity(2, Scale.RELATIVE, rel(2).to(SI.KELVIN));
        assertQuantity(2, Scale.RELATIVE, rel(2).toSystem(SI.SYSTEM));
        assertQuantity(5, Scale.RELATIVE, relK(5).to(SI.DEGREE_CELSIUS));
        assertQuantity(
                5,
                Scale.RELATIVE,
                Temperature.of(9, USCustomary.DEGREE_FAHRENHEIT, Scale.RELATIVE).to(SI.DEGREE_CELSIUS));
        assertQuantity(-2, Scale.RELATIVE, rel(2).negate());
        // An ABSOLUTE quantity still converts through the offset.
        assertQuantity(275.15, Scale.ABSOLUTE, abs(2).to(SI.KELVIN));
        assertQuantity(100, Scale.ABSOLUTE, Temperature.of(212, USCustomary.DEGREE_FAHRENHEIT).to(SI.DEGREE_CELSIUS));
    }

    @Test
    void relativeQuantitiesCompareAsDeltas() {
        assertTrue(rel(2).isEquivalentTo(relK(2)));
        assertEquals(0, rel(2).compareTo(relK(2)));
        assertFalse(rel(2).isEquivalentTo(relK(3)));
        assertTrue(rel(2).compareTo(relK(3)) < 0);
        // Otherwise the system values are compared: 2 degC is 275.15 K.
        assertFalse(abs(2).isEquivalentTo(Temperature.of(2, SI.KELVIN)));
        assertTrue(abs(2).compareTo(Temperature.of(2, SI.KELVIN)) > 0);
        assertTrue(abs(2).compareTo(relK(2)) > 0);
        assertFalse(abs(2).isEquivalentTo(relK(2)));
    }

    private static Temperature decAbs(String v) {
        return Temperature.of(new BigDecimal(v), SI.DEGREE_CELSIUS);
    }

    private static Temperature decRel(String v) {
        return Temperature.of(new BigDecimal(v), SI.DEGREE_CELSIUS, Scale.RELATIVE);
    }

    private static Temperature decRelK(String v) {
        return Temperature.of(new BigDecimal(v), SI.KELVIN, Scale.RELATIVE);
    }

    private static void assertDecimal(String value, Scale scale, Quantity<?> q) {
        assertEquals(0, new BigDecimal(value).compareTo((BigDecimal) q.getValue()), q.toString());
        assertSame(scale, q.getScale(), q.toString());
    }

    @Test
    void decimalQuantitiesFollowTheSameRules() {
        assertDecimal("276.15", Scale.ABSOLUTE, decAbs("2").add(decAbs("1")));
        assertDecimal("-272.15", Scale.ABSOLUTE, decAbs("2").subtract(decAbs("1")));
        assertDecimal("277.15", Scale.ABSOLUTE, decAbs("2").multiply(2));
        assertDecimal("3", Scale.ABSOLUTE, decAbs("2").add(decRel("1")));
        assertDecimal("1", Scale.ABSOLUTE, decAbs("2").subtract(decRel("1")));
        assertDecimal("3", Scale.ABSOLUTE, decRel("2").add(decAbs("1")));
        assertDecimal("1", Scale.ABSOLUTE, decRel("2").subtract(decAbs("1")));
        assertDecimal("3", Scale.RELATIVE, decRel("2").add(decRel("1")));
        assertDecimal("1", Scale.RELATIVE, decRel("2").subtract(decRel("1")));
        assertDecimal("7", Scale.ABSOLUTE, decAbs("2").add(decRelK("5")));
        assertDecimal("4", Scale.RELATIVE, decRel("2").multiply(2));
        assertDecimal("1", Scale.RELATIVE, decRel("2").divide(2));
        assertDecimal("2", Scale.RELATIVE, decRel("2").to(SI.KELVIN));
        assertDecimal("5", Scale.RELATIVE, decRelK("5").to(SI.DEGREE_CELSIUS));
        assertDecimal(
                "5",
                Scale.RELATIVE,
                Temperature.of(new BigDecimal("9"), USCustomary.DEGREE_FAHRENHEIT, Scale.RELATIVE)
                        .to(SI.DEGREE_CELSIUS));
        assertDecimal("275.15", Scale.ABSOLUTE, decAbs("2").to(SI.KELVIN));
    }

    @Test
    void decimalRelativeQuantitiesCompareAsDeltas() {
        assertTrue(decRel("2").isEquivalentTo(decRelK("2")));
        assertEquals(0, decRel("2").compareTo(decRelK("2")));
        assertTrue(decRel("2").compareTo(decRelK("3")) < 0);
        assertTrue(decAbs("2").compareTo(decRelK("2")) > 0);
        assertFalse(decAbs("2").isEquivalentTo(Temperature.of(new BigDecimal("2"), SI.KELVIN)));
        // Against a double operand the same rules apply, and the result is a double.
        assertQuantity(3, Scale.RELATIVE, decRel("2").add(rel(1)));
        assertQuantity(3, Scale.ABSOLUTE, decRel("2").add(abs(1)));
        assertEquals(0, decRel("2").compareTo(relK(2)));
    }
}
