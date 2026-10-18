/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.jsr.spi;

import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.measure.Quantity;
import javax.measure.Unit;
import javax.measure.spi.QuantityFactory;
import javax.measure.spi.ServiceProvider;

/**
 * Finds what the provider of this implementation offers the core: its quantity factories ({@link QuantityFactories})
 * and the text of its units ({@link UnitTexts}), without depending on which provider is current. Not exported, not
 * API.
 */
public final class Providers {
    private static final Providers DEFAULT = new Providers(ServiceProvider::current, ServiceProvider::available);
    private static final Object NONE = new Object();

    private final Supplier<ServiceProvider> current;
    private final Supplier<List<ServiceProvider>> available;
    // available() builds new providers on every call, so the one found for a role is kept.
    private final Map<Class<?>, Object> found = new ConcurrentHashMap<>();

    Providers(Supplier<ServiceProvider> current, Supplier<List<ServiceProvider>> available) {
        this.current = current;
        this.available = available;
    }

    /**
     * The factory for {@code type}: of the current provider if it is ours, else of ours among the available
     * providers, else of the current provider.
     *
     * @param <Q> the quantity type
     * @param type the quantity type
     * @return the factory
     * @throws IllegalStateException if no provider is available
     * @throws IllegalArgumentException if the provider has no factory for {@code type} (throws or returns null)
     */
    public static <Q extends Quantity<Q>> QuantityFactory<Q> quantityFactory(Class<Q> type) {
        return DEFAULT.factory(type);
    }

    /**
     * The named system unit of the standard quantity type whose system unit equals {@code unit}, from our provider;
     * never throws.
     *
     * @param unit an unscaled unit
     * @return the type's system unit, or null if there is none or no provider of ours
     */
    public static Unit<?> namedSystemUnit(Unit<?> unit) {
        return DEFAULT.systemUnit(unit);
    }

    /**
     * The text of {@code unit} from our provider, if there is one and it has a text; never throws.
     *
     * @param unit the unit
     * @return the text, or null
     */
    public static String unitText(Unit<?> unit) {
        return DEFAULT.text(unit);
    }

    <Q extends Quantity<Q>> QuantityFactory<Q> factory(Class<Q> type) {
        ServiceProvider provider = current.get();
        QuantityFactories ours = provider instanceof QuantityFactories q ? q : scan(QuantityFactories.class, false);
        QuantityFactory<Q> factory = ours != null ? ours.getQuantityFactory(type) : provider.getQuantityFactory(type);
        // a provider may answer null for a type it does not support: the same as ours, which throws
        if (factory == null) {
            throw new IllegalArgumentException("no quantity factory for " + type.getName());
        }
        return factory;
    }

    Unit<?> systemUnit(Unit<?> unit) {
        try {
            SystemUnits units = scan(SystemUnits.class, true);
            return units == null ? null : units.namedSystemUnit(unit);
        } catch (RuntimeException | ServiceConfigurationError e) {
            // A unit has a system unit even if no provider is available or one fails.
            return null;
        }
    }

    String text(Unit<?> unit) {
        try {
            UnitTexts texts = scan(UnitTexts.class, true);
            return texts == null ? null : texts.unitText(unit);
        } catch (RuntimeException | ServiceConfigurationError e) {
            // A unit prints even if no provider is available or one fails.
            return null;
        }
    }

    /** Ours among the available providers for {@code role}; a miss is kept too if {@code keepMiss}. */
    private <T> T scan(Class<T> role, boolean keepMiss) {
        Object kept = found.get(role);
        if (kept != null) {
            return kept == NONE ? null : role.cast(kept);
        }
        T ours = null;
        for (ServiceProvider candidate : available.get()) {
            if (role.isInstance(candidate)) {
                ours = role.cast(candidate);
                break;
            }
        }
        if (ours != null || keepMiss) {
            found.put(role, ours != null ? ours : NONE);
        }
        return ours;
    }
}
