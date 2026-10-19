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
import java.math.BigDecimal;
import java.math.MathContext;
import java.text.DecimalFormatSymbols;
import java.text.ParsePosition;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javax.measure.Quantity;
import javax.measure.format.MeasurementParseException;
import javax.measure.format.QuantityFormat;
import javax.measure.spi.QuantityFactory;

import org.tinygears.uom.engine.format.NumberText;
import org.tinygears.uom.engine.format.TextParseException;
import org.tinygears.uom.jsr.BaseUnits;
import org.tinygears.uom.jsr.UomUnit;
import org.tinygears.uom.jsr.quantity.AbstractDecimalQuantity;
import org.tinygears.uom.jsr.quantity.QuantityTypes;
import org.tinygears.uom.jsr.quantity.UntypedDecimalQuantity;
import org.tinygears.uom.jsr.quantity.UntypedDoubleQuantity;
import org.tinygears.uom.jsr.quantity.UomQuantity;

/**
 * Formats a quantity as its value ({@code BigDecimal.toString()} for a decimal quantity), a space and its unit,
 * and parses the same form; parsing gives a {@code double} quantity unless {@link #withDecimals} is set, which
 * keeps every digit and the scale of the text. Immutable and thread-safe. An offset unit without a symbol formats as a
 * shifted formula ({@code 1.0 K + 5}); catalog offset units print their symbols.
 * Formatting throws {@link IllegalArgumentException} for a unit the unit format refuses: a negative factor, a
 * factor with a root, an irrational shift or a formula too long to parse.
 *
 * <p>The default locale is {@link Locale#ROOT}: the value text is exactly {@code Double.toString} or
 * {@code BigDecimal.toString}, and parsing reads a decimal number: an optional sign, ASCII digits with an optional
 * decimal point, an optional exponent {@code E} or {@code e} with an optional sign, or {@code NaN},
 * {@code Infinity} with an optional sign; hexadecimal floats and a {@code d} or {@code f} suffix are invalid. The
 * number becomes a {@code double} ({@link Double#parseDouble}), or with decimals a {@code new BigDecimal}. With
 * {@link #withLocale} only the
 * number is localized, never the unit: the same text is written with each ASCII digit, {@code .}, {@code -} and
 * {@code E} replaced by the locale's digit, decimal separator, minus sign and exponent separator of
 * {@link DecimalFormatSymbols} ({@code -1,5E10 km} in {@code de-DE}, {@code \u22121,5×10^10 km} in {@code sv-SE});
 * an exponent {@code +} stays ASCII, {@code NaN}, {@code Infinity} and {@code -Infinity} keep their ROOT text, and
 * neither grouping separators nor the bidi marks that {@code DecimalFormat} puts around a minus sign are written.
 *
 * <p>Parsing in a locale accepts the locale's digits and ASCII digits, mixed freely in one number; only the
 * locale's decimal separator; the locale's minus sign, {@code -} or {@code +}; and the locale's exponent separator,
 * {@code E} or {@code e} (inside a multi-character separator such as {@code fa}'s {@code ×۱۰^} the characters must
 * match exactly, so {@code ۱×10^۱۰} is invalid while {@code ۱E۱۰} is accepted). Format characters (Unicode
 * {@code Cf}, such as bidi marks) are skipped at the start and end of the number and after a leading sign. Only
 * the exact grouping character of the locale is recognised ({@code fr-FR} groups with U+202F, so U+00A0 is
 * rejected). A grouping separator is an error unless {@link #withGrouping} is on; then it is skipped between two
 * digits of the integer part, without checking group sizes ({@code 1.5 km} in {@code de-DE} is 15 km), and is
 * an error after the decimal separator or in the exponent. The number ends at the first whitespace
 * ({@link Character#isWhitespace}, which excludes no-break spaces): {@code 1 234 km} with ASCII spaces is 1 of
 * the unit {@code 234 km} (the unit grammar's product), so space-grouping locales need a no-break grouping
 * character. {@code withGrouping(true)} on ROOT groups with {@code ,}. A locale whose symbols would make numbers
 * ambiguous is refused by {@code withLocale}.
 *
 * <p>A quantity of another JSR-385 implementation can not be formatted: its unit has no text that parses back to
 * an equal unit ({@link UomUnitFormat#format}).
 */
public final class UomQuantityFormat implements QuantityFormat {
    private final UomUnitFormat unitFormat;
    private final NumberText numbers;
    private final boolean grouping;
    private final MathContext decimals;

    /**
     * A ROOT format without grouping, parsing to {@code double}.
     *
     * @param unitFormat the format of the units
     */
    public UomQuantityFormat(UomUnitFormat unitFormat) {
        this(Objects.requireNonNull(unitFormat, "unitFormat"), NumberText.ROOT, false, null);
    }

    private UomQuantityFormat(UomUnitFormat unitFormat, NumberText numbers, boolean grouping, MathContext decimals) {
        this.unitFormat = unitFormat;
        this.numbers = numbers;
        this.grouping = grouping;
        this.decimals = decimals;
    }

    /**
     * This format with {@code systems} added to its unit format ({@link UomUnitFormat#with}), so that their symbols
     * are parsed and written; the locale, grouping and decimals are kept. A format without a system writes its
     * units as formulas.
     *
     * @param systems the systems to add
     * @return the new format
     * @throws IllegalArgumentException as {@link UomUnitFormat#with}
     */
    public UomQuantityFormat with(UomSystemOfUnits... systems) {
        return new UomQuantityFormat(unitFormat.with(systems), numbers, grouping, decimals);
    }

    /**
     * This format with numbers in {@code locale}; throws {@link IllegalArgumentException} if the locale's number
     * symbols collide so that a number could not round trip (no available locale of JDK 25 does).
     *
     * @param locale the locale of the numbers
     * @return the new format
     */
    public UomQuantityFormat withLocale(Locale locale) {
        return withSymbols(locale, DecimalFormatSymbols.getInstance(Objects.requireNonNull(locale, "locale")));
    }

    UomQuantityFormat withSymbols(Locale locale, DecimalFormatSymbols symbols) {
        return new UomQuantityFormat(unitFormat, new NumberText(locale, symbols), grouping, decimals);
    }

    /**
     * This format accepting (never writing) the locale's grouping separator between digits when parsing.
     *
     * @param grouping whether to accept the separator
     * @return the new format
     */
    public UomQuantityFormat withGrouping(boolean grouping) {
        return new UomQuantityFormat(unitFormat, numbers, grouping, decimals);
    }

    /**
     * This format parsing to decimal quantities with context {@code mc}, or to {@code double} quantities again if
     * {@code mc} is null; formatting is unchanged. The number text,
     * read with the same locale and grouping rules (in ROOT too, so only ASCII digits there), becomes
     * {@code new BigDecimal(text)} with every digit and its scale kept, never rounded to {@code mc} ({@code 1.50 km}
     * has scale 2). The quantity has scale {@code ABSOLUTE} and the standard type of its unit, else it is an
     * {@link UntypedDecimalQuantity}. NaN, infinity, a value scale beyond ±100000
     * ({@link AbstractDecimalQuantity#MAX_SCALE}) and more than 50000 digits
     * ({@link AbstractDecimalQuantity#MAX_PRECISION}) are {@link MeasurementParseException}s, reported before any
     * unit error and before the number is built, so a long text costs linear time. Throws
     * {@link ArithmeticException}, as the quantities do, if the precision of {@code mc} exceeds 50000.
     *
     * @param mc the context of parsed quantities, or null for {@code double} quantities
     * @return the new format
     */
    public UomQuantityFormat withDecimals(MathContext mc) {
        if (mc != null && mc.getPrecision() > AbstractDecimalQuantity.MAX_PRECISION) {
            throw new ArithmeticException("precision out of range: " + mc.getPrecision());
        }
        return new UomQuantityFormat(unitFormat, numbers, grouping, mc);
    }

    /**
     * The locale of the numbers; {@link Locale#ROOT} unless set by {@link #withLocale}.
     *
     * @return the locale
     */
    public Locale getLocale() {
        return numbers.locale();
    }

    /**
     * The context of parsed decimal quantities, or null if parsing gives {@code double} quantities.
     *
     * @return the context, or null
     */
    public MathContext getDecimalContext() {
        return decimals;
    }

    /** True unless the locale is ROOT. */
    @Override
    public boolean isLocaleSensitive() {
        return !Locale.ROOT.equals(numbers.locale());
    }

    /** Equal formats have the same locale symbols, grouping and decimal context and the same unit format instance. */
    @Override
    public boolean equals(Object o) {
        return o instanceof UomQuantityFormat f && f.unitFormat == unitFormat && f.numbers.equals(numbers)
                && f.grouping == grouping && Objects.equals(f.decimals, decimals);
    }

    @Override
    public int hashCode() {
        return Objects.hash(System.identityHashCode(unitFormat), numbers.locale(), grouping, decimals);
    }

    @Override
    public String toString() {
        return "UomQuantityFormat[locale=" + numbers.locale().toLanguageTag() + ", grouping=" + grouping + ", decimals="
                + (decimals == null
                        ? "off"
                        : decimals.equals(MathContext.UNLIMITED)
                                ? "unlimited"
                                : decimals.getPrecision() + "/" + decimals.getRoundingMode())
                + "]";
    }

    @Override
    public String format(Quantity<?> quantity) {
        String value = String.valueOf(quantity.getValue());
        if (isLocaleSensitive()) {
            value = numbers.write(value);
        }
        String unit = unitFormat.format(quantity.getUnit());
        // A number alone is dimensionless when parsed (parse).
        return unit.equals("1") ? value : value + " " + unit;
    }

    @Override
    public Appendable format(Quantity<?> quantity, Appendable destination) throws IOException {
        return destination.append(format(quantity));
    }

    /**
     * A number alone is dimensionless; the quantity has the standard type of its unit, if any. The result is a
     * {@link UomQuantity}, so {@code parse("90 km/h").as(Speed.class)} narrows it without a cast.
     */
    @Override
    public UomQuantity<?, ?> parse(CharSequence csq) throws MeasurementParseException {
        return parseFrom(csq, 0);
    }

    @Override
    public UomQuantity<?, ?> parse(CharSequence csq, ParsePosition pos) throws MeasurementParseException {
        try {
            UomQuantity<?, ?> quantity = parseFrom(csq, pos.getIndex());
            pos.setIndex(csq.length());
            return quantity;
        } catch (MeasurementParseException e) {
            pos.setErrorIndex(e.getPosition());
            throw e;
        }
    }

    private UomQuantity<?, ?> parseFrom(CharSequence csq, int start) {
        String s = csq.toString();
        if (start < 0 || start > s.length()) {
            throw new MeasurementParseException("index out of range: " + start, csq, start < 0 ? 0 : s.length());
        }
        int from = start;
        while (from < s.length() && Character.isWhitespace(s.charAt(from))) {
            from++;
        }
        int to = from;
        while (to < s.length() && !Character.isWhitespace(s.charAt(to))) {
            to++;
        }
        String token = s.substring(from, to);
        Number number;
        try {
            number = numbers.read(
                    s,
                    from,
                    to,
                    grouping,
                    decimals != null,
                    AbstractDecimalQuantity.MAX_SCALE,
                    AbstractDecimalQuantity.MAX_PRECISION);
        } catch (TextParseException e) {
            throw new MeasurementParseException(e.getMessage(), csq, e.position());
        }
        UomUnit<?> unit = s.substring(to).isBlank() ? BaseUnits.ONE : unitFormat.parseFrom(csq, to);
        if (decimals == null) {
            return create(number.doubleValue(), unit);
        }
        try {
            return create((BigDecimal) number, unit, decimals);
        } catch (ArithmeticException e) {
            throw new MeasurementParseException("invalid number '" + token + "': " + e.getMessage(), csq, from);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static UomQuantity<?, ?> create(double value, UomUnit<?> unit) {
        QuantityFactory factory = Factories.BY_SYSTEM_UNIT.get(unit.getSystemUnit());
        // the factories of this library create its quantities
        return factory != null
                ? (UomQuantity<?, ?>) factory.create(value, unit)
                : UntypedDoubleQuantity.of(value, unit);
    }

    /** Keeps every digit of {@code value}: the factory's decimal quantity then takes {@code mc} without rounding. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static UomQuantity<?, ?> create(BigDecimal value, UomUnit<?> unit, MathContext mc) {
        QuantityFactory factory = Factories.BY_SYSTEM_UNIT.get(unit.getSystemUnit());
        return factory != null
                ? (UomQuantity<?, ?>) ((AbstractDecimalQuantity<?, ?>) factory.create(value, unit)).withMathContext(mc)
                : UntypedDecimalQuantity.of(value, unit, mc);
    }

    /** The factory of each standard type by its system unit, built on first use. */
    static final class Factories {
        static final Map<UomUnit<?>, QuantityFactory<?>> BY_SYSTEM_UNIT = build(
                QuantityTypes.types(),
                Factories::factory,
                System.getLogger(UomQuantityFormat.class.getName()));

        private Factories() {
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private static QuantityFactory<?> factory(Class<?> type) {
            return QuantityTypes.factory((Class) type);
        }

        /** The factories of {@code types}; one that fails to initialise is logged and its type parses as untyped (D8). */
        static Map<UomUnit<?>, QuantityFactory<?>> build(
                Collection<Class<?>> types,
                Function<Class<?>, QuantityFactory<?>> factories,
                System.Logger logger
        ) {
            Map<UomUnit<?>, QuantityFactory<?>> map = new HashMap<>();
            for (Class<?> type : types) {
                QuantityFactory<?> factory;
                try {
                    factory = factories.apply(type);
                } catch (RuntimeException | LinkageError e) {
                    logger.log(
                            System.Logger.Level.WARNING,
                            "no quantity factory for " + type.getName() + "; its quantities parse as untyped",
                            e);
                    continue;
                }
                UomUnit<?> systemUnit = (UomUnit<?>) factory.getSystemUnit();
                if (map.put(systemUnit, factory) != null) {
                    throw new IllegalStateException("two quantity types with the system unit " + systemUnit);
                }
            }
            return Map.copyOf(map);
        }
    }
}
