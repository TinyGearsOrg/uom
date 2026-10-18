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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;
import javax.measure.Unit;

import org.junit.jupiter.api.Test;

import org.tinygears.uom.jsr.quantity.AbstractDoubleQuantity;

import static org.junit.jupiter.api.Assertions.*;

// Fresh loaders, so the alternate-unit registry and factory initialisation start clean.
class QuantityTypesIsolationTest {
    private static final String PKG = "org.tinygears.uom.jsr.quantity.";

    @Test
    void conflictingAlternateFailsAtItsOwnCall() throws Exception {
        try (Loader l = new Loader()) {
            Class<?> baseUnits = Class.forName("org.tinygears.uom.jsr.BaseUnits", true, l);
            Object metre = baseUnits.getField("METRE").get(null);
            Method alternate = metre.getClass().getMethod("alternate", String.class);
            // The built-in alternate units are reserved before any catalog loads.
            var e = assertThrows(InvocationTargetException.class, () -> alternate.invoke(metre, "Hz"));
            assertEquals("symbol Hz is already associated to a different unit", e.getCause().getMessage());
            assertEquals("javax.measure.MeasurementException", e.getCause().getClass().getName());

            Class<?> types = Class.forName(PKG + "QuantityTypes", true, l);
            Method factory = types.getMethod("factory", Class.class);
            assertEquals(33, ((Set<?>) types.getMethod("types").invoke(null)).size());
            for (Object type : (Set<?>) types.getMethod("types").invoke(null)) {
                assertNotNull(factory.invoke(null, type), type.toString());
            }
        }
    }

    @Test
    void typesDoesNotInitialiseFactories() throws Exception {
        try (Loader l = new Loader()) {
            Class<?> types = Class.forName(PKG + "QuantityTypes", true, l);
            assertEquals(33, ((Set<?>) types.getMethod("types").invoke(null)).size());
            assertNull(l.loaded(PKG + "LengthFactory"));
            assertNull(l.loaded(PKG + "FrequencyFactory"));
        }
    }

    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static URL codeSource(Class<?> c) {
        return c.getProtectionDomain().getCodeSource().getLocation();
    }

    private static final class Loader extends URLClassLoader {
        Loader() {
            super(new URL[] {
                codeSource(Length.class),
                codeSource(AbstractDoubleQuantity.class),
                codeSource(org.tinygears.uom.engine.Factor.class),
                codeSource(Unit.class) }, ClassLoader.getPlatformClassLoader());
        }

        Class<?> loaded(String name) {
            return findLoadedClass(name);
        }

        Class<?> javax(String simple) throws ClassNotFoundException {
            return Class.forName("javax.measure.quantity." + simple, false, this);
        }
    }
}
