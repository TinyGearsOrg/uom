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

import java.math.BigDecimal;
import java.math.MathContext;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;

import org.tinygears.uom.engine.arithmetic.Precision;
import org.tinygears.uom.engine.format.NumberText;
import org.tinygears.uom.engine.format.TextParseException;
import org.tinygears.uom.quantity.Dimensionless;
import org.tinygears.uom.quantity.QuantityTypes;

/**
 * Formats a quantity as its value, a space and its unit, and parses the same form, with the rules of the JSR-385
 * side, whose engine it shares: the value is {@code Double.toString} or {@code BigDecimal.toString}, in a locale
 * with the locale's digits and separators ({@link #withLocale}); parsing gives a {@code double} quantity unless
 * {@link #withDecimals} is set, which keeps every digit and the scale of the text. A number alone is dimensionless.
 * The parsed quantity has the quantity type of its unit's system unit, else it is {@link Untyped}, and its scale is
 * {@code ABSOLUTE}. Immutable and thread-safe.
 */
public final class QuantityFormat {
    private final UnitFormat unitFormat;
    private final NumberText numbers;
    private final boolean grouping;
    private final MathContext decimals;

    /**
     * A ROOT format without grouping, parsing to {@code double}.
     *
     * @param unitFormat the format of the units
     */
    public QuantityFormat(UnitFormat unitFormat) {
        this(Objects.requireNonNull(unitFormat, "unitFormat"), NumberText.ROOT, false, null);
    }

    private QuantityFormat(UnitFormat unitFormat, NumberText numbers, boolean grouping, MathContext decimals) {
        this.unitFormat = unitFormat;
        this.numbers = numbers;
        this.grouping = grouping;
        this.decimals = decimals;
    }

    /**
     * A new ROOT format over {@link UnitFormat#standard()}. It writes a unit of a system it does not know, such as a
     * loaded one, as a formula; add the system with {@link #with} to write its symbols.
     *
     * @return the format
     */
    public static QuantityFormat standard() {
        return new QuantityFormat(UnitFormat.standard());
    }

    /**
     * This format with {@code added} systems in its unit format ({@link UnitFormat#with}), so that their symbols are
     * parsed and written; the locale, grouping and decimals are kept.
     *
     * @param added the systems to add
     * @return the new format
     * @throws IllegalArgumentException as {@link UnitFormat#with}
     */
    public QuantityFormat with(SystemOfUnits... added) {
        return new QuantityFormat(unitFormat.with(added), numbers, grouping, decimals);
    }

    /**
     * This format with numbers in {@code locale}.
     *
     * @param locale the locale of the numbers
     * @return the new format
     * @throws IllegalArgumentException if the locale's number symbols collide so that a number could not round trip
     */
    public QuantityFormat withLocale(Locale locale) {
        NumberText text = new NumberText(
                Objects.requireNonNull(locale, "locale"),
                DecimalFormatSymbols.getInstance(locale));
        return new QuantityFormat(unitFormat, text, grouping, decimals);
    }

    /**
     * This format accepting (never writing) the locale's grouping separator between integer digits when parsing.
     *
     * @param grouping whether to accept the separator
     * @return the new format
     */
    public QuantityFormat withGrouping(boolean grouping) {
        return new QuantityFormat(unitFormat, numbers, grouping, decimals);
    }

    /**
     * This format parsing to decimal quantities with context {@code mc}, every digit and the scale of the text kept,
     * or to {@code double} quantities again if {@code mc} is null. A number of more than 50000 digits or with a scale
     * beyond ±100000 is a {@link ParseException}, found before the number is built.
     *
     * @param mc the context of parsed quantities, or null
     * @return the new format
     * @throws ArithmeticException if the precision of {@code mc} exceeds 50000
     */
    public QuantityFormat withDecimals(MathContext mc) {
        if (mc != null) {
            Precision.checked(mc);
        }
        return new QuantityFormat(unitFormat, numbers, grouping, mc);
    }

    /**
     * The locale of the numbers.
     *
     * @return the locale
     */
    public Locale locale() {
        return numbers.locale();
    }

    /**
     * The context of parsed decimal quantities, or null if parsing gives {@code double} quantities.
     *
     * @return the context, or null
     */
    public MathContext decimalContext() {
        return decimals;
    }

    /**
     * The text of {@code quantity}: its value and its unit's text, or the value alone for a dimensionless one.
     *
     * @param quantity the quantity
     * @return the text
     * @throws IllegalArgumentException if the unit has no text that parses back (see {@link UnitFormat#format})
     */
    public String format(Quantity<?> quantity) {
        String value = String.valueOf(quantity.value());
        if (!Locale.ROOT.equals(numbers.locale())) {
            value = numbers.write(value);
        }
        String unit = unitFormat.format(quantity.unit());
        return unit.equals("1") ? value : value + " " + unit;
    }

    /**
     * Parses a quantity: a number, whitespace and a unit formula, or a number alone.
     *
     * @param text the text
     * @return the quantity, typed by its unit
     * @throws ParseException if the number or the unit does not parse
     */
    @SuppressWarnings({ "unchecked", "rawtypes" }) // the type is found at run time
    public Quantity<?> parse(CharSequence text) {
        String s = text.toString();
        int from = 0;
        while (from < s.length() && Character.isWhitespace(s.charAt(from))) {
            from++;
        }
        int to = from;
        while (to < s.length() && !Character.isWhitespace(s.charAt(to))) {
            to++;
        }
        Number number;
        try {
            number = numbers
                    .read(s, from, to, grouping, decimals != null, Precision.MAX_SCALE, Precision.MAX_PRECISION);
        } catch (TextParseException e) {
            throw new ParseException(e.getMessage(), text, e.position());
        }
        Unit<?> unit = s.substring(to).isBlank() ? Dimensionless.SYSTEM_UNIT : unitFormat.parseFrom(text, to);
        Class type = unit.type();
        AbstractQuantity.Factory factory = type == Untyped.class
                ? (v, d, mc, u, scale) -> d == null ? Untyped.of(v, u, scale) : Untyped.of(d, u, scale, mc)
                : QuantityTypes.factory(type);
        if (decimals == null) {
            return (Quantity<?>) factory.create(number.doubleValue(), null, null, unit, Scale.ABSOLUTE);
        }
        return (Quantity<?>) factory.create(0, (BigDecimal) number, decimals, unit, Scale.ABSOLUTE);
    }

    /** Equal formats have the same unit format instance, locale symbols, grouping and decimal context. */
    @Override
    public boolean equals(Object o) {
        return o instanceof QuantityFormat f && f.unitFormat == unitFormat && f.numbers.equals(numbers)
                && f.grouping == grouping && Objects.equals(f.decimals, decimals);
    }

    @Override
    public int hashCode() {
        return Objects.hash(System.identityHashCode(unitFormat), numbers, grouping, decimals);
    }
}
