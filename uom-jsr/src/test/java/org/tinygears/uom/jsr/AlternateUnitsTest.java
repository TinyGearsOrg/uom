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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import javax.measure.MeasurementException;
import javax.measure.Unit;

import org.junit.jupiter.api.Test;

import org.tinygears.uom.engine.Dimension;
import org.tinygears.uom.engine.UnitAtom;
import org.tinygears.uom.jsr.systems.SI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.tinygears.uom.jsr.BaseUnits.METRE;
import static org.tinygears.uom.jsr.BaseUnits.ONE;
import static org.tinygears.uom.jsr.BaseUnits.SECOND;

class AlternateUnitsTest {

    @Test
    void radianIsDistinct() throws Exception {
        Unit<?> rad = ONE.alternate("rad");
        assertNotEquals(ONE, rad);
        assertTrue(rad.isCompatible(ONE));
        assertTrue(rad.getConverterToAny(ONE).isIdentity());
    }

    @Test
    void alternateShape() {
        UomUnit<?> rad = (UomUnit<?>) ONE.alternate("rad");
        assertEquals(Map.of(new UnitAtom("rad", Dimension.NONE), 1), rad.atoms());
        assertEquals(Converters.IDENTITY, rad.toSystemConverter());
        assertEquals("rad", rad.getSymbol());
        assertNull(rad.getName());
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void equivalenceIgnoresAlternateAtoms() {
        UomUnit rad = ONE.alternate("rad");
        UomUnit perSecond = (UomUnit) ONE.divide(SECOND);
        UomUnit hz = perSecond.alternate("Hz");
        UomUnit bq = perSecond.alternate("Bq");
        assertTrue(rad.isEquivalentTo(ONE));
        assertTrue(ONE.isEquivalentTo(rad));
        assertTrue(hz.isEquivalentTo(perSecond));
        assertTrue(perSecond.isEquivalentTo(hz));
        assertTrue(hz.isEquivalentTo(bq));
        assertTrue(bq.isEquivalentTo(hz));
        assertFalse(hz.isEquivalentTo(rad));
        assertFalse(rad.isEquivalentTo(hz));
        assertFalse(hz.isEquivalentTo(perSecond.multiply(1000)));
    }

    @Test
    void sameDefinitionIsIdempotent() {
        assertEquals(ONE.alternate("rad"), ONE.alternate("rad"));
    }

    @Test
    void anAlternateUnderItsOwnSymbolIsTheUnit() {
        Unit<?> rad = ONE.alternate("rad");
        assertEquals(rad, rad.alternate("rad"));
        assertEquals(METRE, METRE.alternate("m"));
        assertEquals(SI.STERADIAN, SI.STERADIAN.alternate("sr"));
        assertEquals(
                "symbol rad is already associated to a different unit",
                assertThrows(MeasurementException.class, () -> SI.STERADIAN.alternate("rad")).getMessage());
    }

    @Test
    void conflictingDefinition() {
        ONE.alternate("rad");
        assertThrows(MeasurementException.class, () -> METRE.alternate("rad"));
    }

    @Test
    void baseSymbolIsTaken() {
        assertThrows(MeasurementException.class, () -> ONE.alternate("m"));
    }

    @Test
    void invalidSymbols() {
        assertThrows(NullPointerException.class, () -> ONE.alternate(null));
        assertThrows(MeasurementException.class, () -> ONE.alternate(""));
        assertThrows(MeasurementException.class, () -> ONE.alternate("  "));
        assertThrows(MeasurementException.class, () -> ONE.alternate("t8 space"));
    }

    @Test
    void scaledUnitRejected() {
        assertThrows(IllegalArgumentException.class, () -> METRE.multiply(1000).alternate("km2"));
    }

    @Test
    void hertzIsNotBecquerel() throws Exception {
        Unit<?> hz = ONE.divide(SECOND).alternate("Hz");
        Unit<?> bq = ONE.divide(SECOND).alternate("Bq");
        assertNotEquals(hz, bq);
        assertTrue(hz.isCompatible(bq));
        assertTrue(hz.getConverterToAny(ONE.divide(SECOND)).isIdentity());
        assertTrue(ONE.divide(SECOND).getConverterToAny(hz).isIdentity());
    }

    @Test
    void registrationRace() throws Exception {
        List<Object> same = race(i -> () -> ONE.alternate("t8-race-same"));
        same.forEach(r -> assertInstanceOf(Unit.class, r));
        same.forEach(r -> assertEquals(same.get(0), r));

        List<Object> mixed = race(i -> () -> (i % 2 == 0 ? METRE : SECOND).alternate("t8-race-x"));
        List<Object> units = mixed.stream().filter(r -> r instanceof Unit<?>).toList();
        List<Object> errors = mixed.stream().filter(r -> !(r instanceof Unit<?>)).toList();
        assertFalse(units.isEmpty());
        units.forEach(u -> assertEquals(units.get(0), u));
        errors.forEach(e -> assertInstanceOf(MeasurementException.class, e));
        assertEquals(16, units.size() + errors.size());
    }

    /** Runs 16 tasks released together; each result is the returned unit or the thrown exception. */
    private static List<Object> race(IntFunction<Supplier<Unit<?>>> task) throws Exception {
        int n = 16;
        CountDownLatch gate = new CountDownLatch(1);
        Object[] results = new Object[n];
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            Supplier<Unit<?>> s = task.apply(i);
            Thread t = new Thread(() -> {
                try {
                    gate.await();
                    results[k] = s.get();
                } catch (Throwable e) {
                    results[k] = e;
                }
            });
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        gate.countDown();
        for (Thread t : threads) {
            t.join(10_000);
            assertFalse(t.isAlive(), "registration thread hung");
        }
        return List.of(results);
    }
}
