/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.tinygears.uom.engine.format.UnitSystem;
import org.tinygears.uom.engine.format.UnitSystemLoader;
import org.tinygears.uom.quantity.QuantityTypes;
import org.tinygears.uom.systems.SI;
import org.tinygears.uom.systems.UnitSystems;

/**
 * An immutable system of units indexed by symbol, with the preferred unit of each quantity type: the catalogs'
 * {@code SYSTEM} constants and systems loaded from {@code .units} files at run time, under the rules of the JSR-385
 * side (docs/CUSTOM-UNITS.md).
 */
public final class SystemOfUnits {
    private final Map<String, Unit<?>> bySymbol;
    private final Set<Unit<?>> units;
    private final Map<Class<?>, Unit<?>> preferred;
    private final UnitSystem engine;

    /**
     * A system; a unit without a symbol, a duplicate symbol or a prefixable symbol that is not a unit symbol throws
     * IllegalArgumentException.
     *
     * @param name the name
     * @param units the units, in lookup and listing order
     * @param preferred the unit of each quantity type
     * @param prefixable the symbols that take a metric prefix
     * @param binaryPrefixable the symbols that take a binary prefix ({@code Ki}, {@code Mi}, ...)
     */
    public SystemOfUnits(
            String name,
            List<Unit<?>> units,
            Map<Class<?>, Unit<?>> preferred,
            Set<String> prefixable,
            Set<String> binaryPrefixable
    ) {
        Map<String, Unit<?>> index = new LinkedHashMap<>();
        for (Unit<?> unit : Objects.requireNonNull(units, "units")) {
            String symbol = Objects.requireNonNull(unit, "unit").symbol();
            if (symbol == null || symbol.isEmpty()) {
                throw new IllegalArgumentException("unit without a symbol: " + unit);
            }
            if (index.put(symbol, unit) != null) {
                throw new IllegalArgumentException("duplicate unit symbol: " + symbol);
            }
        }
        this.bySymbol = Collections.unmodifiableMap(index);
        this.units = Collections.unmodifiableSet(new LinkedHashSet<>(index.values()));
        this.preferred = Map.copyOf(Objects.requireNonNull(preferred, "preferred"));
        Map<String, org.tinygears.uom.engine.Unit> byType = new HashMap<>();
        this.preferred.forEach((type, unit) -> byType.put(type.getSimpleName(), unit.engine()));
        this.engine = new UnitSystem(
                name,
                index.values().stream().<org.tinygears.uom.engine.Unit>map(Unit::engine).toList(),
                byType,
                prefixable,
                binaryPrefixable);
    }

    /**
     * Loads a system from a {@code .units} file in UTF-8; included and extended files are resolved in its directory
     * and must stay inside it. See {@link #load(String, Reader, Function)}.
     *
     * @param file the root file
     * @return the system
     * @throws IOException when the file itself can not be read
     * @throws DefinitionException for an error in a file, with its name and line
     */
    public static SystemOfUnits load(Path file) throws IOException {
        try {
            return wrap(UnitSystemLoader.load(file, builtins()));
        } catch (org.tinygears.uom.engine.parse.DefinitionException e) {
            throw definition(e);
        }
    }

    /**
     * Loads a system from {@code reader} (not closed), using {@code name} as file name in messages and as the system
     * name when there is no {@code @system} line; {@code includes} opens included and extended files by name, null
     * for a name it does not have (the function itself null: none).
     * Formulas may use the symbols of {@link UnitFormat#standard()}, which the file may not define; a type without a
     * {@code @preferred} unit takes the first {@code @extends}-ed system's, else SI's. A unit is typed with the
     * quantity type of its system unit, else {@link Untyped}. {@code ~} lines register their alternate-unit symbols
     * in the JVM-wide registry for the life of the JVM: loading the same definition again works, a different one for
     * the same symbol is an error until the JVM restarts. A load that fails registers nothing.
     *
     * @param name the file name in messages and the default system name
     * @param reader the content of the root file
     * @param includes opens included and extended files, or null
     * @return the system
     * @throws DefinitionException for an error in a file, with its name and line
     * @throws java.io.UncheckedIOException when a reader can not be read
     */
    public static SystemOfUnits load(String name, Reader reader, Function<String, Reader> includes) {
        try {
            return wrap(UnitSystemLoader.load(name, reader, includes == null ? null : includes::apply, builtins()));
        } catch (org.tinygears.uom.engine.parse.DefinitionException e) {
            throw definition(e);
        }
    }

    private static DefinitionException definition(org.tinygears.uom.engine.parse.DefinitionException e) {
        return new DefinitionException(e.getMessage(), e.source(), e.line(), e);
    }

    private static UnitSystemLoader.Builtins builtins() {
        UnitFormat builtIn = UnitFormat.standard();
        Map<String, org.tinygears.uom.engine.Unit> types = new HashMap<>();
        for (Class<?> type : QuantityTypes.types()) {
            types.put(type.getSimpleName(), QuantityTypes.systemUnit(type).engine());
        }
        return new UnitSystemLoader.Builtins(
                s -> builtIn.resolve(s).map(Unit::engine).orElse(null),
                types,
                SI.SYSTEM.engine.preferred());
    }

    /** A loaded engine system, typed; a unit of the catalogs stays that instance, one engine unit gets one unit. */
    @SuppressWarnings({ "unchecked", "rawtypes" }) // the type is looked up by name, so it is raw
    private static SystemOfUnits wrap(UnitSystem system) {
        Map<org.tinygears.uom.engine.Unit, Unit<?>> instances = new IdentityHashMap<>();
        for (SystemOfUnits catalog : UnitSystems.all()) {
            catalog.bySymbol.values().forEach(u -> instances.putIfAbsent(u.engine(), u));
            catalog.preferred.values().forEach(u -> instances.putIfAbsent(u.engine(), u));
        }
        List<Unit<?>> units = system.symbols().stream()
                .<Unit<?>>map(s -> instances.computeIfAbsent(system.lookup(s), UnitFormat::typed)).toList();
        Map<String, Class<?>> types = new HashMap<>();
        QuantityTypes.types().forEach(t -> types.put(t.getSimpleName(), t));
        Map<Class<?>, Unit<?>> preferred = new HashMap<>();
        system.preferred().forEach((name, unit) -> {
            Class type = types.get(name);
            preferred.put(type, instances.computeIfAbsent(unit, UnitFormat::typed).asType(type));
        });
        Set<String> prefixable = new LinkedHashSet<>();
        Set<String> binaryPrefixable = new LinkedHashSet<>();
        for (String symbol : system.symbols()) {
            if (system.isPrefixable(symbol)) {
                prefixable.add(symbol);
            }
            if (system.isBinaryPrefixable(symbol)) {
                binaryPrefixable.add(symbol);
            }
        }
        return new SystemOfUnits(system.name(), units, preferred, prefixable, binaryPrefixable);
    }

    UnitSystem engine() {
        return engine;
    }

    /**
     * The name.
     *
     * @return the name
     */
    public String name() {
        return engine.name();
    }

    /**
     * The units in list order; units equal to each other (aliases) collapse to the first one listed.
     *
     * @return the units
     */
    public Set<Unit<?>> units() {
        return units;
    }

    /**
     * The unit with exactly this symbol; no prefixed forms.
     *
     * @param symbol the symbol
     * @return the unit, or empty
     */
    public Optional<Unit<?>> unit(String symbol) {
        return Optional.ofNullable(bySymbol.get(symbol));
    }

    /**
     * The preferred unit of {@code type}.
     *
     * @param <Q> the quantity type
     * @param type the quantity type
     * @return the unit, or null if the system has none for the type
     */
    @SuppressWarnings("unchecked") // the catalogs and load map each type to a unit of it
    public <Q extends Quantity<Q>> Unit<Q> unit(Class<Q> type) {
        return (Unit<Q>) preferred.get(type);
    }

    /**
     * Whether metric prefixes apply to the unit with this symbol.
     *
     * @param symbol the unit symbol
     * @return true if prefixable
     */
    public boolean isPrefixable(String symbol) {
        return engine.isPrefixable(symbol);
    }

    /**
     * Whether binary prefixes apply to the unit with this symbol; such a unit takes only the metric prefixes from
     * kilo up in a format ({@code kB}, not {@code dB}).
     *
     * @param symbol the unit symbol
     * @return true if binary-prefixable
     */
    public boolean isBinaryPrefixable(String symbol) {
        return engine.isBinaryPrefixable(symbol);
    }

    @Override
    public String toString() {
        return engine.name();
    }
}
