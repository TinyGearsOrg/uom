/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.jsr.serial;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.measure.MeasurementException;
import javax.measure.UnitConverter;

import org.tinygears.uom.engine.Factor;
import org.tinygears.uom.engine.UnitAtom;
import org.tinygears.uom.jsr.BaseUnits;
import org.tinygears.uom.jsr.Converters;
import org.tinygears.uom.jsr.UomConverter;
import org.tinygears.uom.jsr.UomUnit;

/** Subprocess check: reads golden units in a fresh JVM, then round trips in that runtime. Arg: golden dir. */
public final class SerialCheck {

    private SerialCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        UomUnit<?> hz = (UomUnit<?>) read(Files.readAllBytes(dir.resolve("hertz.ser")));
        UomUnit<?> degree = (UomUnit<?>) read(Files.readAllBytes(dir.resolve("degree.ser")));
        check("Hz".equals(hz.getSymbol()) && "hertz".equals(hz.getName()), "hertz labels: " + hz);
        check("°".equals(degree.getSymbol()) && "degree".equals(degree.getName()), "degree labels: " + degree);
        // rad is registered from the start as SI defines it, though no catalog is loaded: it cannot be rebound.
        // (That reading itself registers nothing is PoisonCheck's subject, with a symbol of no catalog.)
        try {
            BaseUnits.METRE.alternate("rad");
            throw new AssertionError("rad bound to metre although it is reserved");
        } catch (MeasurementException expected) {
            check(
                    expected.getMessage().equals("symbol rad is already associated to a different unit"),
                    expected.getMessage());
        }
        // A later registration with the same definition gives an equal unit, and reading then resolves to it.
        UomUnit<?> registered = BaseUnits.ONE.divide(BaseUnits.SECOND).alternate("Hz");
        check(hz.equals(registered), "hertz differs from Hz");
        check(
                registered.atoms().keySet().iterator()
                        .next() == ((UomUnit<?>) read(Files.readAllBytes(dir.resolve("hertz.ser")))).atoms().keySet()
                                .iterator().next(),
                "hertz does not resolve to the registered atom");

        UomUnit<?> celsius = BaseUnits.KELVIN.shift(273.15);
        check(celsius.equals(read(write(celsius))), "offset unit round trip");
        UomConverter a = Converters.scale(Factor.ofLong(3));
        UomConverter b = Converters.shift(Factor.ofLong(5));
        List<? extends UnitConverter> steps = a.concatenate(b).getConversionSteps();
        check(steps.equals(((UomConverter) read(write(a.concatenate(b)))).getConversionSteps()), "step order");
        List<String> symbols = hz.atoms().keySet().stream().map(UnitAtom::symbol).toList();
        System.out.println("ok named=" + UomUnit.class.getModule().isNamed() + " atoms=" + symbols);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static byte[] write(Object o) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(o);
        }
        return bytes.toByteArray();
    }

    private static Object read(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return in.readObject();
        }
    }
}
