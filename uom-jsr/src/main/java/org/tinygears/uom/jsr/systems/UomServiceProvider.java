/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.jsr.systems;

import javax.measure.Quantity;
import javax.measure.Unit;
import javax.measure.spi.FormatService;
import javax.measure.spi.QuantityFactory;
import javax.measure.spi.ServiceProvider;
import javax.measure.spi.SystemOfUnitsService;

import org.tinygears.uom.jsr.quantity.QuantityTypes;
import org.tinygears.uom.jsr.spi.QuantityFactories;
import org.tinygears.uom.jsr.spi.UnitTexts;

/** The JSR-385 service provider of this implementation. */
// QuantityFactories and UnitTexts are the core's hooks, not API
@SuppressWarnings("exports")
public final class UomServiceProvider extends ServiceProvider implements QuantityFactories, UnitTexts {
    private final SystemOfUnitsService systems = new UomSystemOfUnitsService();
    private final FormatService formats = new UomFormatService();

    /**
     * Called by the {@link java.util.ServiceLoader}.
     */
    public UomServiceProvider() {
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public SystemOfUnitsService getSystemOfUnitsService() {
        return systems;
    }

    @Override
    public FormatService getFormatService() {
        return formats;
    }

    @Override
    public <Q extends Quantity<Q>> QuantityFactory<Q> getQuantityFactory(Class<Q> quantity) {
        return QuantityTypes.factory(quantity);
    }

    /** Holds the standard format, which is created when the first unnamed unit prints. */
    private static final class Standard {
        static final UomUnitFormat FORMAT = UomUnitFormat.standard();
    }

    /**
     * The text of {@code unit} in {@link UomUnitFormat#standard()}, by which an unnamed unit prints.
     *
     * @param unit the unit
     * @return the text, or null if the format has none for the unit
     */
    @Override
    public String unitText(Unit<?> unit) {
        try {
            return Standard.FORMAT.format(unit);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The name {@code TinyGears}, by which {@code ServiceProvider.of("TinyGears")} finds this provider.
     *
     * @return the name
     */
    @Override
    public String toString() {
        return "TinyGears";
    }
}
