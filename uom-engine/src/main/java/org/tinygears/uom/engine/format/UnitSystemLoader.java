/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.engine.format;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.tinygears.uom.engine.Unit;
import org.tinygears.uom.engine.internal.AlternateUnits;
import org.tinygears.uom.engine.parse.Definition;
import org.tinygears.uom.engine.parse.DefinitionException;
import org.tinygears.uom.engine.parse.DefinitionParser;
import org.tinygears.uom.engine.parse.Definitions;
import org.tinygears.uom.engine.parse.Extends;
import org.tinygears.uom.engine.parse.FileDirectives;
import org.tinygears.uom.engine.parse.Member;
import org.tinygears.uom.engine.parse.Preferred;
import org.tinygears.uom.engine.parse.Prefixed;

/**
 * Loads systems of units from {@code .units} files at run time, for both APIs: the units a file defines, then those
 * of {@code @extends}-ed files, then the {@code @member}s, with the {@code @prefixed} and {@code @preferred} lines.
 * The rules are those of {@code UomSystemOfUnits.load} (docs/CUSTOM-UNITS.md).
 */
public final class UnitSystemLoader {

    /**
     * What a file is loaded against.
     *
     * @param known the built-in symbols formulas may use and files may not define, to their units
     * @param typeSystemUnits the system unit of each quantity type, by simple name
     * @param fallbackPreferred the preferred units of a system without {@code @extends} for the types it does not
     *     prefer itself (SI's)
     */
    public record Builtins(
            Function<String, Unit> known,
            Map<String, Unit> typeSystemUnits,
            Map<String, Unit> fallbackPreferred
    ) {}

    private UnitSystemLoader() {
    }

    /**
     * Loads a system from a {@code .units} file in UTF-8. Every {@code @include} and {@code @extends} name is resolved
     * against the directory of this file and must stay inside it, lexically and after resolving symbolic links. The
     * name is the {@code @system} name, else the file name without its extension.
     *
     * @param file the root file
     * @param builtins the built-in symbols and types
     * @return the system
     * @throws IOException when the file itself can not be read
     * @throws IllegalArgumentException when {@code file} has no file name
     */
    public static UnitSystem load(Path file, Builtins builtins) throws IOException {
        Path fileName = file.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("not a file: " + file);
        }
        Path dir = file.toAbsolutePath().normalize().getParent();
        String source = fileName.toString();
        DefinitionParser.Resolver resolver = name -> {
            try {
                Path p = dir.resolve(name).normalize();
                if (Path.of(name).isAbsolute() || !p.startsWith(dir)) {
                    throw new IOException("'" + name + "' is outside of " + dir);
                }
                Path real = p.toRealPath();
                if (!real.startsWith(dir.toRealPath())) {
                    throw new IOException("'" + name + "' is outside of " + dir + " (symbolic link)");
                }
                return new StringReader(Files.readString(real));
            } catch (InvalidPathException e) {
                throw new IOException(e.getMessage(), e);
            }
        };
        try (Reader reader = Files.newBufferedReader(file)) {
            return load(source, withoutExtension(source), reader, fully(resolver), builtins);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Loads a system from {@code reader} (not closed), using {@code name} as file name in messages and as the system
     * name when there is no {@code @system} line; {@code resolver} opens included and extended files (null: none).
     *
     * @param name the file name in messages and the default system name
     * @param reader the content of the root file
     * @param resolver opens included and extended files, or null
     * @param builtins the built-in symbols and types
     * @return the system
     * @throws DefinitionException for an error in a file, with its name and line
     * @throws UncheckedIOException when a reader can not be read
     */
    public static UnitSystem load(String name, Reader reader, DefinitionParser.Resolver resolver, Builtins builtins) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(reader, "reader");
        return load(name, name, reader, resolver == null ? null : fully(resolver), builtins);
    }

    /** Reads each file fully when it is opened, so a failing reader is not mistaken for a missing include. */
    private static DefinitionParser.Resolver fully(DefinitionParser.Resolver resolver) {
        return name -> {
            try (Reader r = resolver.open(name)) {
                if (r == null) {
                    return null;
                }
                StringWriter w = new StringWriter();
                try {
                    r.transferTo(w);
                } catch (IOException e) {
                    throw new UncheckedIOException(
                            "cannot read '" + name + "': the resolver returned a failing, closed or reused reader",
                            e);
                }
                return new StringReader(w.toString());
            }
        };
    }

    private static String withoutExtension(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    private static UnitSystem load(
            String source,
            String defaultName,
            Reader reader,
            DefinitionParser.Resolver resolver,
            Builtins builtins
    ) {
        // a system that fails to build after its parse removes its alternate units as well
        return AlternateUnits.atomically(() -> {
            Definitions d = DefinitionParser.parse(source, reader, resolver, builtins.known());
            return build(new Parsed(d), source, defaultName, builtins, new HashMap<>());
        });
    }

    /** One parse indexed for building its systems: definitions by symbol, by-file lists, prefixed lines by unit. */
    private static final class Parsed {
        final Definitions definitions;
        final Map<String, Definition> bySymbol = new HashMap<>();
        final Map<String, List<Definition>> bySource = new HashMap<>();
        final Map<String, List<Prefixed>> prefixedByUnit = new HashMap<>();

        Parsed(Definitions d) {
            definitions = d;
            for (Definition def : d.definitions()) {
                bySymbol.putIfAbsent(def.symbol(), def);
                bySource.computeIfAbsent(def.source(), k -> new ArrayList<>()).add(def);
            }
            for (Prefixed p : d.prefixed()) {
                prefixedByUnit.computeIfAbsent(p.unitSymbol(), k -> new ArrayList<>()).add(p);
            }
        }
    }

    /** The system of {@code source}, a file of the parse; its parents are built from the same parse, once each. */
    private static UnitSystem build(
            Parsed parsed,
            String source,
            String defaultName,
            Builtins builtins,
            Map<String, UnitSystem> loaded
    ) {
        Definitions d = parsed.definitions;
        FileDirectives file = d.files().get(source);
        String name = file.system() != null ? file.system() : defaultName;
        Map<String, Unit> units = new LinkedHashMap<>();
        for (Definition def : parsed.bySource.getOrDefault(source, List.of())) {
            units.put(def.symbol(), def.unit());
        }
        Set<String> prefixable = new LinkedHashSet<>();
        Set<String> binaryPrefixable = new LinkedHashSet<>();
        for (Prefixed p : d.prefixed()) {
            if (p.source().equals(source)) {
                addPrefixable(p, prefixable, binaryPrefixable);
            }
        }
        List<UnitSystem> parents = new ArrayList<>();
        for (Extends x : file.extended()) {
            UnitSystem system = loaded.get(x.file());
            if (system == null) {
                system = build(parsed, x.file(), withoutExtension(x.file()), builtins, loaded);
                loaded.put(x.file(), system);
            }
            parents.add(system);
            for (String symbol : system.symbols()) {
                units.putIfAbsent(symbol, system.lookup(symbol));
                if (system.isPrefixable(symbol)) {
                    prefixable.add(symbol);
                }
                if (system.isBinaryPrefixable(symbol)) {
                    binaryPrefixable.add(symbol);
                }
            }
        }
        for (Member m : file.members()) {
            Definition def = parsed.bySymbol.get(m.symbol());
            units.putIfAbsent(m.symbol(), def.unit());
            for (Prefixed p : parsed.prefixedByUnit.getOrDefault(m.symbol(), List.of())) {
                if (p.source().equals(def.source())) {
                    addPrefixable(p, prefixable, binaryPrefixable);
                }
            }
        }
        prefixable.retainAll(units.keySet());
        binaryPrefixable.retainAll(units.keySet());

        Map<String, Unit> preferred = new HashMap<>();
        for (Preferred p : d.preferred()) {
            if (!p.source().equals(source)) {
                continue;
            }
            Unit typeUnit = builtins.typeSystemUnits().get(p.quantityType());
            if (typeUnit == null) {
                throw new DefinitionException(
                        p.source(),
                        p.line(),
                        "unknown quantity type '" + p.quantityType() + "'",
                        null);
            }
            Unit unit = units.get(p.symbol());
            if (unit == null) {
                throw new DefinitionException(
                        p.source(),
                        p.line(),
                        "unit '" + p.symbol() + "' is not a unit of " + name,
                        null);
            }
            if (!typeUnit.equals(unit.systemUnit())) {
                throw new DefinitionException(
                        p.source(),
                        p.line(),
                        "unit '" + p.symbol() + "' is not a unit of " + p.quantityType(),
                        null);
            }
            if (preferred.putIfAbsent(p.quantityType(), unit) != null) {
                throw new DefinitionException(
                        p.source(),
                        p.line(),
                        "duplicate preferred unit for '" + p.quantityType() + "'",
                        null);
            }
        }
        Map<String, Unit> fallback = parents.isEmpty() ? builtins.fallbackPreferred() : parents.get(0).preferred();
        for (String type : builtins.typeSystemUnits().keySet()) {
            preferred.computeIfAbsent(type, fallback::get);
        }
        return new UnitSystem(name, List.copyOf(units.values()), preferred, prefixable, binaryPrefixable);
    }

    /** A line with a metric prefix makes its unit metric-prefixable, one with a binary prefix binary-prefixable. */
    private static void addPrefixable(Prefixed p, Set<String> prefixable, Set<String> binaryPrefixable) {
        if (p.metric()) {
            prefixable.add(p.unitSymbol());
        }
        if (p.binary()) {
            binaryPrefixable.add(p.unitSymbol());
        }
    }
}
