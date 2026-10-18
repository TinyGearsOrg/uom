/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.engine.parse;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.tinygears.uom.engine.Converter;
import org.tinygears.uom.engine.Unit;
import org.tinygears.uom.engine.UnitAtom;
import org.tinygears.uom.engine.UnitException;
import org.tinygears.uom.engine.format.Prefix;
import org.tinygears.uom.engine.internal.AlternateUnits;

/** Parses {@code .units} definition files: one {@code symbol[:name] body [Type] [# comment]} per line. */
public final class DefinitionParser {
    private static final Pattern TYPE = Pattern.compile("[A-Z][A-Za-z0-9]*");
    private static final String DELIMITERS = "*/·()^|=#[]!~@,+-";
    private static final String BASE_LETTERS = "LMTIΘNJK";
    // JLS 3.9 reserved keywords, '_', the literals and the restricted identifiers that are not type names;
    // avoids depending on the java.compiler module.
    private static final Set<String> RESERVED = Set.of(
            "abstract",
            "assert",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extends",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "native",
            "new",
            "package",
            "private",
            "protected",
            "public",
            "return",
            "short",
            "static",
            "strictfp",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "try",
            "void",
            "volatile",
            "while",
            "_",
            "true",
            "false",
            "null",
            "var",
            "yield",
            "record",
            "sealed",
            "permits");
    private static final Unit[] BASES = {
        Unit.base(UnitAtom.METRE, "metre"),
        Unit.base(UnitAtom.KILOGRAM, "kilogram"),
        Unit.base(UnitAtom.SECOND, "second"),
        Unit.base(UnitAtom.AMPERE, "ampere"),
        Unit.base(UnitAtom.KELVIN, "kelvin"),
        Unit.base(UnitAtom.MOLE, "mole"),
        Unit.base(UnitAtom.CANDELA, "candela"),
        Unit.base(UnitAtom.KELVIN, "kelvin") };

    /** Opens an included file by name. */
    public interface Resolver {
        /**
         * Opens a file.
         *
         * @param name the file name as written in the directive
         * @return a reader the parser closes
         * @throws IOException if the file cannot be read
         */
        Reader open(String name) throws IOException;
    }

    private final Resolver resolver;
    private final Function<String, Unit> known;
    private final Map<String, Unit> units = new HashMap<>();
    private final List<Definition> definitions = new ArrayList<>();
    // the definitions by symbol, which is unique, so that @prefixed finds its unit without a scan
    private final Map<String, Definition> bySymbol = new HashMap<>();
    private final List<Preferred> preferred = new ArrayList<>();
    private final List<Prefixed> prefixed = new ArrayList<>();
    private final Set<String> prefixedSymbols = new HashSet<>();
    private final Map<String, FileState> files = new LinkedHashMap<>();
    private final List<String> stack = new ArrayList<>();

    /** The directives of one file and the files whose symbols it sees: itself and what it includes, transitively. */
    private static final class FileState {
        String system;
        final List<Extends> extended = new ArrayList<>();
        final List<Member> members = new ArrayList<>();
        final Set<String> memberSymbols = new HashSet<>();
        final Set<String> visible = new HashSet<>();

        FileDirectives directives() {
            return new FileDirectives(system, extended, members);
        }
    }

    private DefinitionParser(Resolver resolver, Function<String, Unit> known) {
        this.resolver = resolver;
        this.known = known;
    }

    /**
     * Parses {@code reader} without closing it; included files are opened and closed here. Alternate-unit
     * registrations ({@code ~} lines) are JVM-global: those of a successful parse stay for the life of the JVM, so a
     * symbol cannot be defined differently until it restarts; a failed parse removes those it made.
     *
     * @param source the name of the file, used in messages and to detect include cycles
     * @param reader the content
     * @param resolver opens included files; with null, {@code @include} and {@code @extends} are errors
     * @return the definitions
     * @throws DefinitionException if a line is invalid
     */
    public static Definitions parse(String source, Reader reader, Resolver resolver) {
        return parse(source, reader, resolver, s -> null);
    }

    /**
     * As {@link #parse(String, Reader, Resolver)}; formula symbols the files do not define resolve through
     * {@code known} (null when unknown), and defining a symbol that {@code known} resolves is an error.
     *
     * @param source the name of the file, used in messages and to detect include cycles
     * @param reader the content
     * @param resolver opens included files; with null, {@code @include} and {@code @extends} are errors
     * @param known resolves symbols defined elsewhere
     * @return the definitions
     * @throws DefinitionException if a line is invalid
     */
    public static Definitions parse(String source, Reader reader, Resolver resolver, Function<String, Unit> known) {
        DefinitionParser p = new DefinitionParser(resolver, Objects.requireNonNull(known, "known"));
        try {
            AlternateUnits.atomically(() -> {
                p.file(source, reader);
                return null;
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        FileState root = p.files.get(source);
        Map<String, FileDirectives> directives = new LinkedHashMap<>();
        p.files.forEach((name, f) -> directives.put(name, f.directives()));
        return new Definitions(
                p.definitions,
                p.preferred,
                p.prefixed,
                root.system,
                root.extended,
                root.members,
                directives);
    }

    private void file(String source, Reader reader) throws IOException {
        stack.add(source);
        FileState state = new FileState();
        state.visible.add(source);
        files.put(source, state);
        BufferedReader in = new BufferedReader(reader);
        int n = 0;
        for (String text = in.readLine(); text != null; text = in.readLine()) {
            n++;
            line(source, n, n == 1 && text.startsWith("\uFEFF") ? text.substring(1) : text);
        }
        stack.remove(stack.size() - 1);
    }

    private void line(String src, int ln, String text) throws IOException {
        String t = text.strip();
        if (t.isEmpty() || t.startsWith("#")) {
            return;
        }
        try {
            if (t.startsWith("@")) {
                directive(src, ln, t);
            } else {
                definition(src, ln, t);
            }
        } catch (FormulaException | ArithmeticException | IllegalArgumentException | UnitException e) {
            throw new DefinitionException(src, ln, e.getMessage(), e);
        }
    }

    private void directive(String src, int ln, String t) throws IOException {
        int hash = t.indexOf('#');
        String[] w = (hash < 0 ? t : t.substring(0, hash)).strip().split("\\s+");
        switch (w[0]) {
            case "@include" -> {
                if (w.length != 2) {
                    throw new DefinitionException(src, ln, "expected '@include name'", null);
                }
                include(src, ln, w[1]);
            }
            case "@preferred" -> {
                if (w.length != 3) {
                    throw new DefinitionException(src, ln, "expected '@preferred Type symbol'", null);
                }
                checkType(src, ln, w[1]);
                if (visible(src, w[2]) == null) {
                    throw new DefinitionException(src, ln, "unknown unit '" + w[2] + "'", null);
                }
                preferred.add(new Preferred(w[1], w[2], src, ln));
            }
            case "@prefixed" -> prefixed(src, ln, w);
            case "@system" -> system(src, ln, w);
            case "@extends" -> {
                if (w.length != 2) {
                    throw new DefinitionException(src, ln, "expected '@extends name'", null);
                }
                List<Extends> extended = files.get(src).extended;
                if (extended.stream().anyMatch(e -> e.file().equals(w[1]))) {
                    throw new DefinitionException(src, ln, "duplicate '@extends' of '" + w[1] + "'", null);
                }
                include(src, ln, w[1]);
                extended.add(new Extends(w[1], src, ln));
            }
            case "@member" -> member(src, ln, w);
            default -> throw new DefinitionException(src, ln, "unknown directive '" + w[0] + "'", null);
        }
    }

    /** The definition of {@code symbol} if {@code src} sees it, else null. */
    private Definition visible(String src, String symbol) {
        Definition d = bySymbol.get(symbol);
        return d != null && files.get(src).visible.contains(d.source()) ? d : null;
    }

    private void system(String src, int ln, String[] w) {
        if (w.length != 2) {
            throw new DefinitionException(src, ln, "expected '@system Name'", null);
        }
        if (!isJavaName(w[1])) {
            throw new DefinitionException(src, ln, "invalid system name '" + w[1] + "'", null);
        }
        FileState state = files.get(src);
        if (state.system != null) {
            throw new DefinitionException(src, ln, "duplicate '@system'", null);
        }
        state.system = w[1];
    }

    private static boolean isJavaName(String s) {
        return !RESERVED.contains(s) && Character.isJavaIdentifierStart(s.codePointAt(0))
                && s.codePoints().allMatch(Character::isJavaIdentifierPart);
    }

    private void member(String src, int ln, String[] w) {
        if (w.length < 2) {
            throw new DefinitionException(src, ln, "expected '@member symbol...'", null);
        }
        FileState state = files.get(src);
        for (String symbol : List.of(w).subList(1, w.length)) {
            Definition d = visible(src, symbol);
            if (d == null) {
                throw new DefinitionException(src, ln, "unknown unit '" + symbol + "'", null);
            }
            if (d.source().equals(src)) {
                throw new DefinitionException(src, ln, "unit '" + symbol + "' is defined in this file", null);
            }
            if (!state.memberSymbols.add(symbol)) {
                throw new DefinitionException(src, ln, "duplicate member '" + symbol + "'", null);
            }
            state.members.add(new Member(symbol, src, ln));
        }
    }

    private void prefixed(String src, int ln, String[] w) {
        if (w.length < 3) {
            throw new DefinitionException(src, ln, "expected '@prefixed unit prefix...'", null);
        }
        Definition base = visible(src, w[1]);
        if (base == null) {
            throw new DefinitionException(src, ln, "unknown unit '" + w[1] + "'", null);
        }
        if (prefixedSymbols.contains(w[1])) {
            throw new DefinitionException(src, ln, "unit '" + w[1] + "' is already prefixed", null);
        }
        if (!base.unit().toSystem().isLinear()) {
            throw new DefinitionException(src, ln, "unit '" + w[1] + "' can not be prefixed", null);
        }
        List<String> symbols = List.of(w).subList(2, w.length);
        for (String p : symbols) {
            Prefix prefix = Prefix.BY_SYMBOL.get(p);
            if (prefix == null) {
                throw new DefinitionException(src, ln, "unknown prefix '" + p + "'", null);
            }
            String symbol = p + base.symbol();
            checkSymbol(src, ln, symbol);
            String name = prefix.name() + base.name();
            Unit unit = base.unit().scaledBy(prefix.factor()).named(symbol, name);
            units.put(symbol, unit);
            prefixedSymbols.add(symbol);
            define(new Definition(symbol, name, unit, null, base.explicitType(), src, ln));
        }
        prefixed.add(new Prefixed(base.symbol(), symbols, src, ln));
    }

    private void include(String src, int ln, String name) {
        int i = stack.indexOf(name);
        if (i >= 0) {
            String chain = String.join(" -> ", stack.subList(i, stack.size())) + " -> " + name;
            throw new DefinitionException(src, ln, "include cycle: " + chain, null);
        }
        if (!files.containsKey(name)) {
            try (Reader r = resolver == null ? null : resolver.open(name)) {
                if (r == null) {
                    throw new FileNotFoundException(name);
                }
                file(name, r);
            } catch (IOException e) {
                throw new DefinitionException(src, ln, "cannot include '" + name + "'", e);
            }
        }
        files.get(src).visible.addAll(files.get(name).visible);
    }

    private void definition(String src, int ln, String t) throws FormulaException {
        // '#' and '[' can not occur in symbols or formulas, so the first of each delimits.
        int hash = t.indexOf('#');
        int open = t.indexOf('[');
        String type = null;
        if (open >= 0 && (hash < 0 || open < hash)) {
            int close = t.indexOf(']', open);
            String rest = close < 0 ? "" : t.substring(close + 1).strip();
            if (close < 0) {
                throw new DefinitionException(src, ln, "missing ']' after the quantity type", null);
            }
            type = t.substring(open + 1, close);
            if (!rest.isEmpty() && !rest.startsWith("#")) {
                throw new DefinitionException(
                        src,
                        ln,
                        "the quantity type [" + type + "] must end the definition",
                        null);
            }
            checkType(src, ln, type);
            t = t.substring(0, open).strip();
        } else if (hash >= 0) {
            t = t.substring(0, hash).strip();
        }
        int sp = 0;
        while (sp < t.length() && !Character.isWhitespace(t.charAt(sp))) {
            sp++;
        }
        String head = t.substring(0, sp);
        String body = t.substring(sp).strip();
        int colon = head.indexOf(':');
        String symbol = colon < 0 ? head : head.substring(0, colon);
        String name = colon < 0 ? symbol : head.substring(colon + 1).replace('_', ' ');
        checkSymbol(src, ln, symbol);
        if (name.isEmpty()) {
            throw new DefinitionException(src, ln, "empty name for '" + symbol + "'", null);
        }
        if (body.isEmpty()) {
            // "x#y m" reads as x and a comment
            boolean comment = hash == sp && (open < 0 || hash < open);
            throw new DefinitionException(
                    src,
                    ln,
                    "missing formula for '" + symbol + "'" + (comment ? " ('#' starts a comment)" : ""),
                    null);
        }

        Unit unit;
        Unit alternateOf = null;
        int eq = body.indexOf('=');
        if (body.startsWith("!")) {
            unit = base(src, ln, symbol, body.substring(1));
        } else if (body.startsWith("~")) {
            String formula = body.substring(1);
            alternateOf = formula.isBlank() ? Unit.ONE : evaluate(formula);
            try {
                unit = alternateOf.alternate(symbol);
            } catch (IllegalArgumentException e) {
                throw new DefinitionException(src, ln, "alternate unit must be an unscaled unit", e);
            }
        } else if (eq >= 0) {
            Unit ref = evaluate(body.substring(0, eq));
            AffineFunction f = AffineFunction.parse(body.substring(eq + 1));
            unit = ref.transform(Converter.affine(f.a(), f.b()));
        } else {
            unit = evaluate(body);
        }
        Unit named = unit.named(symbol, name);
        units.put(symbol, named);
        define(new Definition(symbol, name, named, alternateOf, type, src, ln));
    }

    private void define(Definition d) {
        definitions.add(d);
        bySymbol.put(d.symbol(), d);
    }

    private Unit evaluate(String formula) throws FormulaException {
        return FormulaEvaluator.evaluate(formula, this::find);
    }

    /** A symbol of the user files that the current file sees, else a built-in one. */
    private Unit find(String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            return null;
        }
        if (units.containsKey(symbol)) {
            return visible(stack.get(stack.size() - 1), symbol) != null ? units.get(symbol) : null;
        }
        return known.apply(symbol);
    }

    private static Unit base(String src, int ln, String symbol, String letter) {
        if (!letter.isEmpty() && Character.isWhitespace(letter.charAt(0))) {
            throw new DefinitionException(src, ln, "the dimension letter must follow '!' directly", null);
        }
        int i = letter.length() == 1 ? BASE_LETTERS.indexOf(letter.charAt(0)) : -1;
        if (i < 0) {
            throw new DefinitionException(src, ln, "unknown base dimension '" + letter + "'", null);
        }
        if (!BASES[i].symbol().equals(symbol)) {
            throw new DefinitionException(src, ln, "base unit symbol must be '" + BASES[i].symbol() + "'", null);
        }
        return BASES[i];
    }

    private void checkSymbol(String src, int ln, String symbol) {
        boolean valid = !symbol.isEmpty() && symbol.charAt(0) != '.'
                && !(symbol.charAt(0) >= '0' && symbol.charAt(0) <= '9')
                && symbol.chars().noneMatch(c -> DELIMITERS.indexOf(c) >= 0);
        if (!valid) {
            throw new DefinitionException(src, ln, "invalid symbol '" + symbol + "'", null);
        }
        if (symbol.equals("pi")) {
            throw new DefinitionException(src, ln, "symbol 'pi' is reserved", null);
        }
        if (units.containsKey(symbol)) {
            throw new DefinitionException(src, ln, "duplicate symbol '" + symbol + "'", null);
        }
        if (known.apply(symbol) != null) {
            throw new DefinitionException(src, ln, "symbol '" + symbol + "' is already defined", null);
        }
    }

    private static void checkType(String src, int ln, String type) {
        if (!TYPE.matcher(type).matches()) {
            throw malformedType(src, ln, type);
        }
    }

    private static DefinitionException malformedType(String src, int ln, String type) {
        return new DefinitionException(src, ln, "malformed quantity type '" + type + "'", null);
    }
}
