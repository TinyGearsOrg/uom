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

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
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
import javax.measure.Dimension;
import javax.measure.Quantity;
import javax.measure.Unit;
import javax.measure.spi.SystemOfUnits;

import org.tinygears.uom.engine.format.UnitSystem;
import org.tinygears.uom.engine.format.UnitSystemLoader;
import org.tinygears.uom.engine.parse.DefinitionException;
import org.tinygears.uom.engine.parse.DefinitionParser;
import org.tinygears.uom.jsr.UomUnit;
import org.tinygears.uom.jsr.quantity.QuantityTypes;

/** An immutable system of units indexed by symbol. */
public final class UomSystemOfUnits implements SystemOfUnits {

    private final String name;
    private final Map<String, UomUnit<?>> bySymbol;
    private final Set<UomUnit<?>> units;
    private final Map<Class<?>, UomUnit<?>> preferred;
    private final Set<String> prefixable;
    private final Set<String> binaryPrefixable;
    private final UnitSystem engine;

    /**
     * A system whose units take no binary prefix; see {@link #UomSystemOfUnits(String, List, Map, Set, Set)}.
     *
     * @param name the name
     * @param units the units, in lookup and listing order
     * @param preferred the unit of each quantity type
     * @param prefixable the symbols that take a metric prefix
     */
    public UomSystemOfUnits(
            String name,
            List<UomUnit<?>> units,
            Map<Class<?>, UomUnit<?>> preferred,
            Set<String> prefixable
    ) {
        this(name, units, preferred, prefixable, Set.of());
    }

    /**
     * A system; a unit without a symbol, a duplicate symbol or a prefixable symbol that is not a unit symbol throws IllegalArgumentException.
     *
     * @param name the name
     * @param units the units, in lookup and listing order
     * @param preferred the unit of each quantity type
     * @param prefixable the symbols that take a metric prefix
     * @param binaryPrefixable the symbols that take a binary prefix ({@code Ki}, {@code Mi}, ...)
     */
    public UomSystemOfUnits(
            String name,
            List<UomUnit<?>> units,
            Map<Class<?>, UomUnit<?>> preferred,
            Set<String> prefixable,
            Set<String> binaryPrefixable
    ) {
        this.name = Objects.requireNonNull(name, "name");
        Map<String, UomUnit<?>> index = new LinkedHashMap<>();
        for (UomUnit<?> unit : Objects.requireNonNull(units, "units")) {
            String symbol = Objects.requireNonNull(unit, "unit").getSymbol();
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
        this.prefixable = Set.copyOf(Objects.requireNonNull(prefixable, "prefixable"));
        this.binaryPrefixable = Set.copyOf(Objects.requireNonNull(binaryPrefixable, "binaryPrefixable"));
        for (Set<String> symbols : List.of(this.prefixable, this.binaryPrefixable)) {
            for (String symbol : symbols) {
                if (!index.containsKey(symbol)) {
                    throw new IllegalArgumentException("prefixable symbol is not a unit symbol: " + symbol);
                }
            }
        }
        Map<String, org.tinygears.uom.engine.Unit> byType = new HashMap<>();
        this.preferred.forEach((type, unit) -> byType.put(type.getSimpleName(), unit.engine()));
        this.engine = new UnitSystem(
                name,
                index.values().stream().map(UomUnit::engine).toList(),
                byType,
                this.prefixable,
                this.binaryPrefixable);
    }

    /** The engine system over the same units, for the unit format. */
    UnitSystem engine() {
        return engine;
    }

    /**
     * Loads a system from a {@code .units} file in UTF-8. Every {@code @include} and {@code @extends} name, in
     * nested files too, is resolved against the directory of this root file (one flat namespace, like the
     * generator's data directory). A name must be relative and stay inside that directory both lexically and after
     * resolving symbolic links (the real path of the file must lie in the real path of the directory), else it can
     * not be included. The name is the {@code @system} name, else the file name without its extension.
     *
     * @param file the root file
     * @return the system
     * @throws IOException when the file itself can not be read ({@link java.nio.file.NoSuchFileException} if it
     *     does not exist, {@link java.nio.charset.MalformedInputException} if it is not UTF-8); errors in the
     *     content, includes too, are {@link DefinitionException}s
     * @throws IllegalArgumentException when {@code file} has no file name, such as {@code /}
     * @see #load(String, Reader, DefinitionParser.Resolver)
     */
    public static UomSystemOfUnits load(Path file) throws IOException {
        return wrap(UnitSystemLoader.load(file, builtins()));
    }

    /**
     * Loads a system from {@code reader} (not closed), using {@code name} as file name in messages and as the
     * system name when there is no {@code @system} line. {@code resolver} opens included and extended user files
     * (null: none); it is called at most once per name and must return a fresh reader each time, which is read
     * fully and closed here. A file named by {@code @extends} is also loaded as a system of its own. No built-in
     * files exist: {@code @include si.units} fails unless the resolver provides it.
     *
     * <p>Formulas may use the symbols {@link UomUnitFormat#standard()} resolves (not labels), and the file may not
     * define any of them. The units are those the file defines, then those of {@code @extends}-ed files, then the
     * {@code @member}s; units of other included files are usable in formulas only. A file sees the units it defines,
     * those of the files it includes or extends (transitively) and the built-in symbols, not those of other files.
     * The root and its parents are built from one parse. {@code @preferred Type symbol}
     * names one of the 33 standard quantity types by simple name; a type without one takes the first
     * {@code @extends}-ed system's preferred unit, else {@link SI}'s. Units are {@code Unit<?>}: narrow them with
     * {@code asType}. Nothing is registered globally, except that {@code ~} lines register their alternate-unit
     * symbols in the JVM-wide registry for the life of the JVM: loading the same definition again works, a different
     * one for the same symbol is an error until the JVM restarts. A load that fails registers nothing. {@code @prefixed u k} checks only the generated
     * {@code ku} against the built-in symbols, though every prefix {@code u} takes (see {@link UomUnitFormat}) is
     * readable at run time; an exact built-in symbol wins over such a reading.
     *
     * @param name the file name in messages and the default system name
     * @param reader the content of the root file
     * @param resolver opens included and extended files, or null
     * @return the system
     * @throws DefinitionException for an error in a file, with its name and line
     * @throws UncheckedIOException when {@code reader} fails, or a reader from {@code resolver} can not be read
     */
    public static UomSystemOfUnits load(String name, Reader reader, DefinitionParser.Resolver resolver) {
        return wrap(UnitSystemLoader.load(name, reader, resolver, builtins()));
    }

    /** The built-in symbols of {@link UomUnitFormat#standard()}, the standard types and SI's preferred units. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static UnitSystemLoader.Builtins builtins() {
        UomUnitFormat builtIn = UomUnitFormat.standard();
        Map<String, org.tinygears.uom.engine.Unit> types = new HashMap<>();
        for (Class<?> type : QuantityTypes.types()) {
            types.put(
                    type.getSimpleName(),
                    ((UomUnit<?>) QuantityTypes.factory((Class) type).getSystemUnit()).engine());
        }
        return new UnitSystemLoader.Builtins(
                s -> builtIn.resolve(s).map(UomUnit::engine).orElse(null),
                types,
                SI.SYSTEM.engine().preferred());
    }

    /**
     * The system over an engine system loaded from a file; a unit that is a unit of the catalogs (SI's preferred
     * units) stays that instance, and one engine unit gets one wrapper.
     */
    private static UomSystemOfUnits wrap(UnitSystem system) {
        Map<org.tinygears.uom.engine.Unit, UomUnit<?>> wrappers = new IdentityHashMap<>();
        for (UomSystemOfUnits catalog : UnitSystems.all()) {
            catalog.bySymbol.values().forEach(u -> wrappers.putIfAbsent(u.engine(), u));
            catalog.preferred.values().forEach(u -> wrappers.putIfAbsent(u.engine(), u));
        }
        Function<org.tinygears.uom.engine.Unit, UomUnit<?>> wrap = e -> wrappers.computeIfAbsent(e, UomUnit::of);
        List<UomUnit<?>> units = new ArrayList<>();
        for (String symbol : system.symbols()) {
            units.add(wrap.apply(system.lookup(symbol)));
        }
        Map<String, Class<?>> types = new HashMap<>();
        QuantityTypes.types().forEach(t -> types.put(t.getSimpleName(), t));
        Map<Class<?>, UomUnit<?>> preferred = new HashMap<>();
        system.preferred().forEach((type, unit) -> preferred.put(types.get(type), wrap.apply(unit)));
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
        return new UomSystemOfUnits(system.name(), units, preferred, prefixable, binaryPrefixable);
    }

    /** Every symbol, in list order, aliases included. */
    Collection<String> symbols() {
        return bySymbol.keySet();
    }

    @Override
    public String getName() {
        return name;
    }

    /** Returns the preferred unit for the type, or null when none is defined. */
    @Override
    @SuppressWarnings("unchecked")
    public <Q extends Quantity<Q>> Unit<Q> getUnit(Class<Q> type) {
        // preferred maps each quantity interface to a unit of that type (the catalogs and load build it so); the
        // public constructor does not check it.
        return (Unit<Q>) preferred.get(type);
    }

    /** Returns the unit with exactly this symbol, or null when unknown. */
    @Override
    public Unit<?> getUnit(String symbol) {
        return bySymbol.get(symbol);
    }

    /** Units in list order; units equal to each other (aliases) collapse to the first one listed. */
    @Override
    public Set<? extends Unit<?>> getUnits() {
        return units;
    }

    @Override
    public Set<? extends Unit<?>> getUnits(Dimension dimension) {
        Set<UomUnit<?>> result = new LinkedHashSet<>();
        for (UomUnit<?> unit : units) {
            if (unit.getDimension().equals(dimension)) {
                result.add(unit);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * The unit with exactly this symbol; no prefixed forms.
     *
     * @param symbol the symbol
     * @return the unit, or empty
     */
    public Optional<UomUnit<?>> lookup(String symbol) {
        return Optional.ofNullable(bySymbol.get(symbol));
    }

    /**
     * Whether metric prefixes apply to the unit with this symbol.
     *
     * @param unitSymbol the unit symbol
     * @return true if prefixable
     */
    public boolean isPrefixable(String unitSymbol) {
        return prefixable.contains(unitSymbol);
    }

    /**
     * Whether binary prefixes ({@code Ki}, {@code Mi}, ...) apply to the unit with this symbol. Such a unit takes
     * only the metric prefixes from kilo up in a format, as IEC 80000-13 uses them ({@code kB}, not {@code dB}).
     *
     * @param unitSymbol the unit symbol
     * @return true if binary-prefixable
     */
    public boolean isBinaryPrefixable(String unitSymbol) {
        return binaryPrefixable.contains(unitSymbol);
    }

    @Override
    public String toString() {
        return name;
    }
}
