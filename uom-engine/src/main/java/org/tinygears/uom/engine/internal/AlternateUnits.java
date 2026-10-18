/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.engine.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.tinygears.uom.engine.Dimension;
import org.tinygears.uom.engine.UnitAtom;
import org.tinygears.uom.engine.UnitException;

/** Registry of atom symbols, so each symbol names one definition. Not API. */
public final class AlternateUnits {

    private record Registered(UnitAtom atom, Map<UnitAtom, Integer> definition) {}

    // The only global state of the engine.
    private static final Map<String, Registered> REGISTRY = new ConcurrentHashMap<>();

    // The registrations of the load running on this thread, removed again if it fails.
    private static final ThreadLocal<List<Registered>> LOAD = new ThreadLocal<>();

    // The symbols of the built-in catalogs' alternate units (the ~ lines of data/*.units).
    static final Set<String> BUILT_IN = Set.of("rad", "sr", "Hz", "Bq", "Gy", "Sv", "bit");

    static {
        for (UnitAtom base : List.of(
                UnitAtom.METRE,
                UnitAtom.KILOGRAM,
                UnitAtom.SECOND,
                UnitAtom.AMPERE,
                UnitAtom.KELVIN,
                UnitAtom.MOLE,
                UnitAtom.CANDELA)) {
            REGISTRY.put(base.symbol(), new Registered(base, Map.of(base, 1)));
        }
        // Reserved before any other registration, so a conflicting one fails at its own call, not in a catalog.
        Map<UnitAtom, Integer> perSecond = Map.of(UnitAtom.SECOND, -1);
        Map<UnitAtom, Integer> joulePerKilogram = Map.of(UnitAtom.METRE, 2, UnitAtom.SECOND, -2);
        for (String symbol : List.of("rad", "sr", "bit")) {
            register(symbol, Map.of());
        }
        register("Hz", perSecond);
        register("Bq", perSecond);
        register("Gy", joulePerKilogram);
        register("Sv", joulePerKilogram);
    }

    private AlternateUnits() {
    }

    /**
     * The atom {@code symbol} defined as {@code definition}, registered unless the symbol already names the same
     * definition.
     *
     * @param symbol the symbol
     * @param definition the atoms of the defining unit
     * @return the atom
     * @throws UnitException if the symbol is invalid or names a different definition
     */
    public static UnitAtom register(String symbol, Map<UnitAtom, Integer> definition) {
        checkSymbol(symbol);
        // the normal form keeps the order, so a definition serializes the same each time
        Map<UnitAtom, Integer> atoms = Atoms.normalize(definition);
        Registered fresh = new Registered(new UnitAtom(symbol, Atoms.dimension(atoms)), atoms);
        Registered existing = REGISTRY.putIfAbsent(symbol, fresh);
        if (existing == null) {
            List<Registered> load = LOAD.get();
            if (load != null) {
                load.add(fresh);
            }
            return fresh.atom();
        }
        // An atom's alternate under its own symbol is the atom: rad.alternate("rad"), as m.alternate("m").
        return atoms.equals(Map.of(existing.atom(), 1))
                ? existing.atom()
                : matching(existing, fresh.atom(), fresh.definition());
    }

    /** A load of definitions that may register alternate units. */
    @FunctionalInterface
    public interface Load<T, E extends Exception> {
        /**
         * Runs the load.
         *
         * @return the result
         * @throws E if the load fails
         */
        T run() throws E;
    }

    /**
     * Runs {@code load} so that its registrations are all or nothing: if it throws, the symbols it registered are
     * removed again. A load inside another joins the outer one.
     *
     * @param <T> the result type
     * @param <E> the checked exception of the load
     * @param load the load
     * @return the result of the load
     * @throws E if the load throws it
     */
    public static <T, E extends Exception> T atomically(Load<T, E> load) throws E {
        if (LOAD.get() != null) {
            return load.run();
        }
        List<Registered> added = new ArrayList<>();
        LOAD.set(added);
        try {
            return load.run();
        } catch (Throwable t) {
            for (Registered r : added) {
                REGISTRY.remove(r.atom().symbol(), r);
            }
            throw t;
        } finally {
            LOAD.remove();
        }
    }

    /**
     * The registered definition of {@code atom}.
     *
     * @param atom the atom
     * @return the definition, or null if the atom is not registered
     */
    public static Map<UnitAtom, Integer> definition(UnitAtom atom) {
        Registered r = REGISTRY.get(atom.symbol());
        return r != null && r.atom().equals(atom) ? r.definition() : null;
    }

    /**
     * Resolves an atom read from a stream without registering it, so untrusted data cannot bind a symbol: a
     * registered symbol must match (same definition, or the same atom when none is given) and gives the
     * registered atom; an unregistered one gives an equal, unregistered atom. The built-in alternate units are
     * registered from the start, so a fresh JVM checks a catalog atom as well. Without a definition (a
     * base atom, or an unregistered atom written again) only symbol and dimension are compared: a weaker check, but
     * harmless since atoms are equal by symbol and dimension.
     *
     * @param symbol the symbol
     * @param dimension the dimension
     * @param definition the definition, or null
     * @return the atom
     * @throws UnitException if the symbol is invalid, the definition has another dimension, or the symbol names a
     *     different definition
     */
    public static UnitAtom restore(String symbol, Dimension dimension, Map<UnitAtom, Integer> definition) {
        checkSymbol(symbol);
        UnitAtom atom = new UnitAtom(symbol, dimension);
        Map<UnitAtom, Integer> atoms = null;
        if (definition != null) {
            atoms = Atoms.normalize(definition);
            Dimension d = Atoms.dimension(atoms);
            if (!d.equals(dimension)) {
                throw new UnitException(
                        "dimension " + dimension + " of " + symbol + " does not match its definition " + d);
            }
        }
        Registered existing = REGISTRY.get(symbol);
        return existing == null ? atom : matching(existing, atom, atoms);
    }

    /**
     * The number of registered symbols.
     *
     * @return the size
     */
    public static int size() {
        return REGISTRY.size();
    }

    private static UnitAtom matching(Registered existing, UnitAtom atom, Map<UnitAtom, Integer> definition) {
        if (definition == null ? existing.atom().equals(atom) : existing.definition().equals(definition)) {
            return existing.atom();
        }
        throw new UnitException("symbol " + atom.symbol() + " is already associated to a different unit");
    }

    private static void checkSymbol(String symbol) {
        if (symbol.isBlank() || symbol.chars().anyMatch(Character::isWhitespace)) {
            throw new UnitException("invalid unit symbol: '" + symbol + "'");
        }
    }
}
