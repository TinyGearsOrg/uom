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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tinygears.uom.engine.Converter;
import org.tinygears.uom.engine.Factor;
import org.tinygears.uom.engine.Unit;
import org.tinygears.uom.engine.internal.BoundedCache;
import org.tinygears.uom.engine.parse.FormulaEvaluator;
import org.tinygears.uom.engine.parse.FormulaException;

/**
 * Formats units by label or symbol and parses unit formulas such as {@code kg·m/s^2} over systems of units: the unit
 * format of both APIs. Symbols resolve as labels, then exact symbols, then a metric or binary prefix on a prefixable
 * unit ({@code km}, {@code MiB}), then, for parsing only, the Greek mu (U+03BC) for the micro sign and {@code l} for
 * the litre ({@code μm}, {@code ml}). A unit that takes binary prefixes takes only the metric prefixes from kilo up
 * ({@code kB}, not {@code dB}). Labels belong to the instance; {@link #with} and {@link #withSuperscripts} copy them.
 * Thread-safe.
 */
public final class UnitFormatter {
    private static final String EXCLUDED = "*/()^|=#[]!~@,·+-";
    private static final int MAX_POWER = 30;
    private static final int TEXT_CACHE_SIZE = 256;
    private static final List<Prefix> PREFIXES = Prefix.ALL.stream()
            .sorted(Comparator.comparingInt((Prefix p) -> p.symbol().length()).reversed()).toList();
    private static final Pattern EXPONENT = Pattern.compile("\\^(-?)(\\d+)");
    private static final String SUPERSCRIPT_DIGITS = "\u2070\u00B9\u00B2\u00B3\u2074\u2075\u2076\u2077\u2078\u2079";
    private static final char SUPERSCRIPT_MINUS = '\u207B';

    private final List<UnitSystem> systems;
    private final boolean superscripts;

    /** What each label parses to and the label each unit formats as; replaced whole, so a reader sees one state. */
    private record Labels(Map<String, Unit> aliases, Map<Unit, String> names) {
        static final Labels NONE = new Labels(Map.of(), Map.of());
    }

    private volatile Labels labels = Labels.NONE;
    // The text of units without a symbol or label in the systems, whose search parses several candidates.
    private final BoundedCache<Unit, String> texts = new BoundedCache<>(TEXT_CACHE_SIZE);
    // The units that take a metric or binary prefix, and all units, of the systems by unit, built when first needed.
    private volatile Map<Unit, String> prefixable;
    private volatile Map<Unit, String> symbols;

    /**
     * A formatter over {@code systems}, in lookup order.
     *
     * @param systems the systems
     */
    public UnitFormatter(List<UnitSystem> systems) {
        this(systems, false);
    }

    private UnitFormatter(List<UnitSystem> systems, boolean superscripts) {
        this.systems = List.copyOf(systems);
        this.superscripts = superscripts;
    }

    /**
     * The systems, in lookup order.
     *
     * @return the systems
     */
    public List<UnitSystem> systems() {
        return systems;
    }

    /**
     * A new formatter with a snapshot of the labels that writes exponents as superscript digits or as {@code ^n}.
     *
     * @param superscripts true to write superscripts
     * @return the new formatter
     */
    public UnitFormatter withSuperscripts(boolean superscripts) {
        UnitFormatter result = new UnitFormatter(systems, superscripts);
        result.labels = labels;
        return result;
    }

    /**
     * Whether exponents are written as superscript digits.
     *
     * @return true for superscripts
     */
    public boolean writesSuperscripts() {
        return superscripts;
    }

    /**
     * A new formatter over these systems followed by {@code added}, with a snapshot of the labels; a system already
     * in the formatter is skipped.
     *
     * @param added the systems to add
     * @return the new formatter
     * @throws IllegalArgumentException when {@code added} is empty, or a symbol of an added system resolves in the
     *     systems before it (exactly or as a prefixed form) or as a label to a different unit
     */
    public UnitFormatter with(List<UnitSystem> added) {
        if (Objects.requireNonNull(added, "systems").isEmpty()) {
            throw new IllegalArgumentException("no systems");
        }
        List<UnitSystem> all = new ArrayList<>(systems);
        Labels current = labels;
        for (UnitSystem system : added) {
            Objects.requireNonNull(system, "system");
            if (all.stream().anyMatch(s -> s == system)) {
                continue;
            }
            for (String symbol : system.symbols()) {
                Unit unit = system.lookup(symbol);
                Unit label = current.aliases().get(symbol);
                if (label != null && !label.equals(unit)) {
                    throw new IllegalArgumentException(
                            "symbol '" + symbol + "' of " + system.name() + " is the label of " + label);
                }
                Found other = resolve(all, symbol);
                if (other != null && !other.unit().equals(unit)) {
                    throw new IllegalArgumentException(
                            "symbol '" + symbol + "' of " + system.name() + " is a different unit in "
                                    + other.system().name());
                }
            }
            all.add(system);
        }
        UnitFormatter result = new UnitFormatter(all, superscripts);
        result.labels = current;
        return result;
    }

    /**
     * The first of these texts that parses back to an equal unit: the label, the symbol, the symbol of an equal unit
     * of the systems, a prefix on a prefixable unit ({@code mHz}, {@code KiB}), a power of ten from 10 up times a
     * unit of the systems ({@code 1000·min}), an exact formula ({@code 1|1000·m/s}); an offset unit with factor
     * {@code f} and offset {@code o} as the formula of {@code f} shifted by {@code o/f} ({@code K + 5},
     * {@code 5|9·K + 459.67}).
     *
     * @param unit the unit
     * @return the text
     * @throws IllegalArgumentException when none does: a negative factor, a factor with a root, an irrational shift
     *     or a formula too long to parse
     */
    public String format(Unit unit) {
        String text = formatAscii(Objects.requireNonNull(unit, "unit"));
        return superscripts ? superscript(text) : text;
    }

    /**
     * {@code text} with each {@code ^n} written as superscript digits. One that directly follows a superscript
     * ({@code m²^2}, the square of the catalog unit {@code m²}) stays {@code ^2}: {@code m²²} would read as
     * {@code m^22}.
     */
    private static String superscript(String text) {
        Matcher m = EXPONENT.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            if (m.start() > 0 && isSuperscript(text.charAt(m.start() - 1))) {
                out.append(m.group());
            } else {
                if (!m.group(1).isEmpty()) {
                    out.append(SUPERSCRIPT_MINUS);
                }
                m.group(2).chars().forEach(c -> out.append(SUPERSCRIPT_DIGITS.charAt(c - '0')));
            }
            last = m.end();
        }
        return out.append(text, last, text.length()).toString();
    }

    private static boolean isSuperscript(char c) {
        return c == SUPERSCRIPT_MINUS || SUPERSCRIPT_DIGITS.indexOf(c) >= 0;
    }

    private String formatAscii(Unit u) {
        String label = labels.names().get(u);
        if (label != null && parsesTo(label, u)) {
            return label;
        }
        if (u.symbol() != null && parsesTo(u.symbol(), u)) {
            return u.symbol();
        }
        for (UnitSystem system : systems) {
            for (Unit known : system.units()) {
                if (known.equals(u) && parsesTo(known.symbol(), u)) {
                    return known.symbol();
                }
            }
        }
        // Equal units share this text: it depends on the factor, offset and atoms, never on a symbol.
        return texts.get(u, this::derived);
    }

    /** A prefixed symbol, a multiple or a formula of {@code u}, the first that parses back. */
    private String derived(Unit u) {
        String prefixed = prefixed(u);
        if (prefixed != null) {
            return prefixed;
        }
        String multiple = multiple(u);
        if (multiple != null) {
            return multiple;
        }
        Factor f = u.toSystem().factor();
        Factor offset = u.toSystem().offset();
        Factor shift = shiftOf(f, offset);
        if (f.signum() > 0 && f.rootIndex() == 1 && shift != null && shift.rootIndex() == 1
                && shift.piExponent() == 0) {
            String formula = formula(f, u.systemUnit().symbol()) + shift(shift);
            if (parsesTo(formula, u)) {
                return formula;
            }
        }
        throw new IllegalArgumentException(
                "unit can not be formatted: " + shown(u) + " (factor " + f + ", offset " + offset + ")");
    }

    private boolean parsesTo(String text, Unit unit) {
        try {
            return unit.equals(parse(text));
        } catch (FormulaException e) {
            return false;
        }
    }

    /** {@code mHz}, {@code pW} or {@code KiB}: a prefix on a prefixable unit of the systems that reads back as {@code u}. */
    private String prefixed(Unit u) {
        if (!u.toSystem().isLinear() || u.toSystem().factor().signum() <= 0) {
            return null;
        }
        Map<Unit, String> units = prefixable();
        for (Prefix prefix : PREFIXES) {
            String symbol = units.get(u.transform(Converter.scale(Factor.ONE.divide(prefix.factor()))));
            if (symbol != null && parsesTo(prefix.symbol() + symbol, u)) {
                return prefix.symbol() + symbol;
            }
        }
        return null;
    }

    /** {@code 1000·min}: a power of ten from 10 up times a unit of the systems that reads back as {@code u}. */
    private String multiple(Unit u) {
        if (!u.toSystem().isLinear() || u.toSystem().factor().signum() <= 0) {
            return null;
        }
        Map<Unit, String> units = symbols();
        Factor ten = Factor.ofLong(10);
        for (int n = 1; n <= MAX_POWER; n++) {
            Factor power = ten.pow(n);
            String symbol = units.get(u.transform(Converter.scale(Factor.ONE.divide(power))));
            if (symbol != null && parsesTo(formula(power, symbol), u)) {
                return formula(power, symbol);
            }
        }
        return null;
    }

    private Map<Unit, String> symbols() {
        Map<Unit, String> units = symbols;
        if (units == null) {
            units = new HashMap<>();
            for (UnitSystem system : systems) {
                for (Unit known : system.units()) {
                    if (known.symbol() != null) {
                        units.putIfAbsent(known, known.symbol());
                    }
                }
            }
            symbols = units;
        }
        return units;
    }

    private Map<Unit, String> prefixable() {
        Map<Unit, String> units = prefixable;
        if (units == null) {
            units = new HashMap<>();
            for (UnitSystem system : systems) {
                for (Unit known : system.units()) {
                    String symbol = known.symbol();
                    if (symbol != null && (system.isPrefixable(symbol) || system.isBinaryPrefixable(symbol))) {
                        units.putIfAbsent(known, symbol);
                    }
                }
            }
            prefixable = units;
        }
        return units;
    }

    /** The symbol of {@code unit}, or the label of its system unit if it has none (not its definition). */
    private static String shown(Unit unit) {
        return unit.symbol() != null ? unit.symbol() : unit.systemUnit().toString();
    }

    /** {@code n|d·pi^k·label}; juxtaposition binds tighter than the left-associative {@code ·} and {@code /}. */
    private static String formula(Factor f, String systemLabel) {
        StringBuilder sb = new StringBuilder();
        if (!f.numerator().equals(BigInteger.ONE) || !f.denominator().equals(BigInteger.ONE)) {
            sb.append(f.numerator());
            if (!f.denominator().equals(BigInteger.ONE)) {
                sb.append('|').append(f.denominator());
            }
            sb.append('·');
        }
        int k = f.piExponent();
        if (k != 0) {
            sb.append(k == 1 ? "pi" : "pi^" + k).append('·');
        }
        return sb.append(systemLabel).toString();
    }

    /** {@code o/f}, or null when it exceeds the factor size limit (its text would be too long anyway). */
    private static Factor shiftOf(Factor f, Factor offset) {
        try {
            return offset.divide(f);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /** {@code " + s"} or {@code " - s"}: a terminating plain decimal unless the exact {@code a|b} is shorter. */
    private static String shift(Factor s) {
        if (s.isZero()) {
            return "";
        }
        BigInteger n = s.numerator().abs();
        BigInteger d = s.denominator();
        String fraction = d.equals(BigInteger.ONE) ? n.toString() : n + "|" + d;
        String text;
        try {
            String decimal = new BigDecimal(n).divide(new BigDecimal(d)).toPlainString();
            text = decimal.length() <= fraction.length() ? decimal : fraction;
        } catch (ArithmeticException e) {
            text = fraction;
        }
        return (s.signum() < 0 ? " - " : " + ") + text;
    }

    /**
     * {@code formula} evaluated over {@link #lookup}; a formula that is one looked-up symbol gives that unit instance
     * itself.
     *
     * @param formula the formula, without surrounding whitespace
     * @return the unit
     * @throws FormulaException if the formula is malformed or names an unknown symbol, with the column
     */
    public Unit parse(String formula) throws FormulaException {
        return FormulaEvaluator.evaluate(formula, this::lookup);
    }

    /**
     * The unit of a label, else of an exact symbol of the systems, else of a prefix on a prefixable unit.
     *
     * @param symbol the symbol
     * @return the unit, or null
     */
    public Unit lookup(String symbol) {
        Unit alias = labels.aliases().get(symbol);
        return alias != null ? alias : resolve(symbol);
    }

    /**
     * The unit of an exact symbol of the systems, else of a prefix on a prefixable unit; never a label.
     *
     * @param symbol the symbol
     * @return the unit, or null
     */
    public Unit resolve(String symbol) {
        Found found = resolve(systems, Objects.requireNonNull(symbol, "symbol"));
        return found == null ? null : found.unit();
    }

    private record Found(UnitSystem system, Unit unit) {}

    private static Found resolve(List<UnitSystem> systems, String symbol) {
        Found found = resolveSpelled(systems, symbol);
        String other = otherSpelling(symbol);
        return found == null && !other.equals(symbol) ? resolveSpelled(systems, other) : found;
    }

    /**
     * The spelling of {@code symbol} that the systems use: the micro sign for the Greek mu, which Unicode prefers for
     * micro, and {@code L} for the litre's {@code l}, which the SI Brochure allows; read only, never written.
     */
    static String otherSpelling(String symbol) {
        String s = symbol.replace('\u03BC', '\u00B5');
        if (s.endsWith("l")) {
            String prefix = s.substring(0, s.length() - 1);
            Prefix p = Prefix.BY_SYMBOL.get(prefix);
            if (prefix.isEmpty() || p != null && !p.binary()) {
                return prefix + "L";
            }
        }
        return s;
    }

    private static Found resolveSpelled(List<UnitSystem> systems, String symbol) {
        for (UnitSystem system : systems) {
            Unit unit = system.lookup(symbol);
            if (unit != null) {
                return new Found(system, unit);
            }
        }
        for (Prefix prefix : PREFIXES) {
            String p = prefix.symbol();
            if (symbol.length() > p.length() && symbol.startsWith(p)) {
                String rest = symbol.substring(p.length());
                for (UnitSystem system : systems) {
                    Unit unit = system.lookup(rest);
                    if (takes(system, rest, prefix) && unit != null) {
                        return new Found(
                                system,
                                unit.scaledBy(prefix.factor()).named(symbol, prefix.name() + unit.name()));
                    }
                }
            }
        }
        return null;
    }

    /** Whether {@code prefix} applies to the unit {@code symbol} of {@code system}; see the class comment. */
    private static boolean takes(UnitSystem system, String symbol, Prefix prefix) {
        if (prefix.binary()) {
            return system.isBinaryPrefixable(symbol);
        }
        return system.isPrefixable(symbol) && (!system.isBinaryPrefixable(symbol) || prefix.exponent() >= 3);
    }

    /**
     * Adds {@code label} as an alias that parses to {@code unit} and formats it; a previous holder of the same label
     * loses it.
     *
     * @param unit the unit
     * @param label the label
     * @throws IllegalArgumentException if the label would not parse back or names a different unit of the systems,
     *     exactly or as a prefix on a prefixable unit
     */
    public synchronized void label(Unit unit, String label) {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(label, "label");
        if (label.isEmpty() || label.equals("pi") || "0123456789.".indexOf(label.charAt(0)) >= 0
                || label.chars().anyMatch(c -> Character.isWhitespace(c) || EXCLUDED.indexOf(c) >= 0)) {
            throw new IllegalArgumentException("invalid label: '" + label + "'");
        }
        // An exact symbol, or a prefix on a prefixable unit ("Gm" is the gigametre, not a label for seconds).
        Found existing = resolve(systems, label);
        if (existing != null && !existing.unit().equals(unit)) {
            throw new IllegalArgumentException("label '" + label + "' is the symbol of " + existing.unit().name());
        }
        Map<String, Unit> aliases = new HashMap<>(labels.aliases());
        Map<Unit, String> names = new HashMap<>(labels.names());
        Unit previous = aliases.put(label, unit);
        if (previous != null && !previous.equals(unit)) {
            names.remove(previous, label);
        }
        names.put(unit, label);
        labels = new Labels(Map.copyOf(aliases), Map.copyOf(names));
        texts.clear();
    }
}
