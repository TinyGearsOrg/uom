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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.util.List;
import javax.measure.Quantity;
import javax.measure.UnconvertibleException;
import javax.measure.spi.ServiceProvider;

import org.junit.jupiter.api.Test;
import tech.units.indriya.quantity.Quantities;
import tech.units.indriya.unit.Units;

import org.tinygears.uom.jsr.quantity.Length;
import org.tinygears.uom.jsr.quantity.Time;
import org.tinygears.uom.jsr.systems.SI;
import org.tinygears.uom.jsr.systems.UomServiceProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * uom next to Indriya on one class path (docs/JSR.md#the-service-provider): the priority decides which provider is current, and
 * neither implementation depends on that choice.
 */
class ProviderCoexistenceTest {

    private static ServiceProvider indriya() {
        return ServiceProvider.of("Default");
    }

    private static Object roundTrip(Object o) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(o);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return in.readObject();
        }
    }

    @Test
    void theProvidersAreFoundByNameAndThePriorityDecides() {
        assertInstanceOf(UomServiceProvider.class, ServiceProvider.of("TinyGears"));
        assertEquals(100, ServiceProvider.of("TinyGears").getPriority());
        assertEquals("tech.units.indriya.spi.DefaultServiceProvider", indriya().getClass().getName());
        assertEquals(10, indriya().getPriority());
        assertInstanceOf(UomServiceProvider.class, ServiceProvider.current());
        // Other test providers may be on the class path; uom is listed before Indriya.
        assertEquals(
                List.of("TinyGears", "Default"),
                ServiceProvider.available().stream().map(Object::toString)
                        .filter(n -> n.equals("TinyGears") || n.equals("Default")).toList());
    }

    @Test
    void uomWorksWhileAnotherProviderIsCurrent() throws Exception {
        ServiceProvider old = ServiceProvider.current();
        ServiceProvider.setCurrent(indriya());
        try {
            assertEquals(SI.METRE, SI.METRE.asType(javax.measure.quantity.Length.class));
            Length six = Length.of(6, SI.METRE);
            Quantity<?> speed = six.divide(Time.of(2, SI.SECOND)).asType(javax.measure.quantity.Speed.class);
            assertEquals(3.0, speed.getValue().doubleValue(), 1e-12);
            assertEquals(six, roundTrip(six));
            Length decimal = Length.of(new BigDecimal("1.5"), SI.METRE);
            assertEquals(decimal, roundTrip(decimal));
        } finally {
            ServiceProvider.setCurrent(old);
        }
    }

    @Test
    void indriyaWorksWhileUomIsCurrent() throws Exception {
        assertInstanceOf(UomServiceProvider.class, ServiceProvider.current());
        Quantity<javax.measure.quantity.Length> five = Quantities.getQuantity(5, Units.METRE);
        assertEquals(five, five.asType(javax.measure.quantity.Length.class));
        assertEquals(Units.METRE, Units.METRE.asType(javax.measure.quantity.Length.class));
        assertEquals(five, roundTrip(five));
        assertEquals(10, five.add(five).getValue().intValue());
    }

    @Test
    void quantitiesOfTheTwoImplementationsDoNotMix() {
        Quantity<javax.measure.quantity.Length> indriyaLength = Quantities.getQuantity(1, Units.METRE);
        assertThrows(UnconvertibleException.class, () -> Length.of(1, SI.METRE).add(indriyaLength));
    }
}
