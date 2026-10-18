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
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;

/**
 * The number of a quantity text in a locale, for the quantity formats of both APIs: written as the ASCII text of
 * {@code Double.toString} or {@code BigDecimal.toString} with each digit, {@code .}, {@code -} and {@code E} replaced
 * by the locale's symbols, and read back from the locale's or ASCII digits, the locale's decimal separator, minus
 * sign or {@code -} or {@code +}, and the locale's exponent separator or {@code E} or {@code e}; format characters
 * around the number and after a sign are skipped, a grouping separator only between integer digits when grouping
 * is allowed. Immutable.
 */
public final class NumberText {
    /** The ROOT symbols: the text of {@code Double.toString} and {@code BigDecimal.toString}. */
    public static final NumberText ROOT = new NumberText(Locale.ROOT, DecimalFormatSymbols.getInstance(Locale.ROOT));

    private static final long HUGE_EXPONENT = 1_000_000_000_000_000L;

    private final Locale locale;
    private final char zero;
    private final char decimal;
    private final char group;
    private final char minus;
    private final String exponent;

    /**
     * The number text of {@code locale} with {@code symbols}.
     *
     * @param locale the locale, for messages
     * @param symbols the number symbols
     * @throws IllegalArgumentException if the symbols collide so that a number could not round trip
     */
    public NumberText(Locale locale, DecimalFormatSymbols symbols) {
        this.locale = Objects.requireNonNull(locale, "locale");
        this.zero = symbols.getZeroDigit();
        this.decimal = symbols.getDecimalSeparator();
        this.group = symbols.getGroupingSeparator();
        this.minus = symbols.getMinusSign();
        this.exponent = symbols.getExponentSeparator();
        check();
    }

    /**
     * The locale.
     *
     * @return the locale
     */
    public Locale locale() {
        return locale;
    }

    private void check() {
        for (int i = 0; i < 10; i++) {
            if (Character.digit(zero + i, 10) != i) {
                throw unsafe("zero digit '" + zero + "' does not start a digit run");
            }
        }
        if (decimal == group) {
            throw unsafe("decimal separator '" + decimal + "' equals grouping separator");
        }
        checkSymbol("decimal separator", decimal);
        checkSymbol("grouping separator", group);
        checkSymbol("minus sign", minus);
        if (minus == decimal || minus == group || decimal == '-' || group == '-') {
            throw unsafe("minus sign '" + minus + "' collides with a separator");
        }
        if (exponent.isEmpty() || exponent.codePoints().anyMatch(Character::isWhitespace)) {
            throw unsafe("exponent separator '" + exponent + "' is empty or has a space");
        }
        char first = exponent.charAt(0);
        if (isDigit(first) || first == decimal || first == group || first == minus || first == '-' || first == '+') {
            throw unsafe("exponent separator '" + exponent + "' starts with a digit, sign or separator");
        }
    }

    private void checkSymbol(String what, char c) {
        if (Character.isLetterOrDigit(c) || Character.isWhitespace(c) || isFormatMark(c) || c == '+') {
            throw unsafe(what + " '" + c + "' is a letter, digit, space, format mark or plus");
        }
    }

    private IllegalArgumentException unsafe(String reason) {
        return new IllegalArgumentException(
                "locale " + locale.toLanguageTag() + " cannot round trip numbers: " + reason);
    }

    /**
     * The ASCII number text {@code value} in this locale, character by character; {@code NaN} and infinities keep
     * their text. The digits are never rounded.
     *
     * @param value the text of {@code Double.toString} or {@code BigDecimal.toString}
     * @return the localized text
     */
    public String write(String value) {
        if (value.endsWith("NaN") || value.endsWith("Infinity")) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append((char) (zero + c - '0'));
            } else if (c == '.') {
                sb.append(decimal);
            } else if (c == '-') {
                sb.append(minus);
            } else if (c == 'E') {
                sb.append(exponent);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * The number in {@code s[from, to)}: a {@code Double} ({@link Double#parseDouble}), or with {@code decimal} a
     * {@code new BigDecimal} with every digit and its scale.
     *
     * @param s the text
     * @param from the start of the number
     * @param to the end of the number
     * @param grouping whether a grouping separator is allowed between integer digits
     * @param decimal whether to read a decimal
     * @param maxScale the largest absolute scale of a decimal
     * @param maxDigits the largest number of significant digits of a decimal
     * @return the number
     * @throws TextParseException for an invalid or missing number, a misplaced grouping separator, and for a
     *     decimal NaN, infinity, a scale beyond {@code maxScale} or more than {@code maxDigits} significant digits,
     *     each checked in linear time before the decimal is built
     */
    public Number read(String s, int from, int to, boolean grouping, boolean decimal, int maxScale, int maxDigits) {
        String token = s.substring(from, to);
        try {
            String text = normalize(s, from, to, grouping);
            if (!decimal) {
                return Double.parseDouble(text);
            }
            String unsigned = text.startsWith("+") || text.startsWith("-") ? text.substring(1) : text;
            if (unsigned.equals("NaN") || unsigned.equals("Infinity")) {
                throw new TextParseException("decimal quantities have no NaN or infinity: '" + token + "'", from);
            }
            long scale = scaleOf(text);
            if (scale > maxScale || scale < -maxScale) {
                throw new TextParseException(
                        "invalid number '" + token + "': scale out of range"
                                + (scale == Long.MAX_VALUE || scale == Long.MIN_VALUE ? "" : ": " + scale),
                        from);
            }
            // new BigDecimal is quadratic in the digits: a million take seconds
            if (digitsOf(text) > maxDigits) {
                throw new TextParseException("invalid number: more than " + maxDigits + " digits", from);
            }
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new TextParseException(token.isEmpty() ? "missing number" : "invalid number '" + token + "'", from);
        }
    }

    /**
     * The scale {@code new BigDecimal(text)} would have, in linear time, so a huge text fails before the quadratic
     * parse; 0 if {@code text} is malformed (BigDecimal rejects it) and {@link Long#MAX_VALUE} or
     * {@link Long#MIN_VALUE} for an exponent of 10^15 or more.
     */
    static long scaleOf(String text) {
        int i = text.startsWith("+") || text.startsWith("-") ? 1 : 0;
        int digits = 0;
        long fraction = 0;
        boolean point = false;
        for (; i < text.length() && text.charAt(i) != 'E'; i++) {
            char c = text.charAt(i);
            if (c == '.' && !point) {
                point = true;
            } else if (c >= '0' && c <= '9') {
                digits++;
                fraction += point ? 1 : 0;
            } else {
                return 0;
            }
        }
        if (digits == 0) {
            return 0;
        }
        if (i == text.length()) {
            return fraction;
        }
        boolean negative = i + 1 < text.length() && text.charAt(i + 1) == '-';
        int start = i + 1 < text.length() && (negative || text.charAt(i + 1) == '+') ? i + 2 : i + 1;
        if (start == text.length()) {
            return 0;
        }
        long e = 0;
        for (int j = start; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c < '0' || c > '9') {
                return 0;
            }
            // saturates: an exponent of 10^15 or more is out of range whatever the fraction
            e = e >= HUGE_EXPONENT ? e : e * 10 + c - '0';
        }
        if (e >= HUGE_EXPONENT) {
            return negative ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return fraction - (negative ? -e : e);
    }

    /** The significant digits of the ASCII number {@code text}, as {@link BigDecimal#precision()} counts them. */
    static long digitsOf(String text) {
        long digits = 0;
        boolean leading = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 'E') {
                break;
            }
            if (c >= '0' && c <= '9') {
                leading &= c == '0';
                digits += leading ? 0 : 1;
            }
        }
        return Math.max(digits, 1);
    }

    /**
     * The number in {@code s[from, to)} as ASCII text for {@link Double#parseDouble} or {@code new BigDecimal};
     * throws {@link NumberFormatException} for a character outside the locale's number symbols.
     */
    private String normalize(String s, int from, int to, boolean grouping) {
        // Format marks (Cf) before or after the number and after a leading sign, as DecimalFormat writes them.
        while (from < to && isFormatMark(s.charAt(from))) {
            from++;
        }
        while (to > from && isFormatMark(s.charAt(to - 1))) {
            to--;
        }
        int i = from;
        StringBuilder sb = new StringBuilder(to - from);
        if (i < to && isSign(s.charAt(i))) {
            sb.append(s.charAt(i) == '+' ? '+' : '-');
            i++;
            while (i < to && isFormatMark(s.charAt(i))) {
                i++;
            }
        }
        String rest = s.substring(i, to);
        if (rest.equals("NaN") || rest.equals("Infinity")) {
            return sb + rest;
        }
        boolean fraction = false;
        boolean exponentPart = false;
        while (i < to) {
            char c = s.charAt(i);
            if (isDigit(c)) {
                sb.append((char) ('0' + (c >= '0' && c <= '9' ? c - '0' : c - zero)));
            } else if (c == decimal) {
                sb.append('.');
                fraction = true;
            } else if (c == group) {
                if (!grouping) {
                    throw new TextParseException("grouping separator not allowed: '" + c + "'", i);
                }
                if (exponentPart || fraction) {
                    throw new TextParseException(
                            "grouping separator not allowed "
                                    + (exponentPart ? "in the exponent" : "after the decimal separator"),
                            i);
                }
                if (i == from || !isDigit(s.charAt(i - 1)) || i + 1 >= to || !isDigit(s.charAt(i + 1))) {
                    throw new TextParseException("grouping separator not between digits", i);
                }
            } else if (isSign(c)) {
                sb.append(c == '+' ? '+' : '-');
            } else if (s.startsWith(exponent, i)) {
                sb.append('E');
                exponentPart = true;
                i += exponent.length();
                continue;
            } else if (c == 'E' || c == 'e') {
                sb.append('E');
                exponentPart = true;
            } else {
                throw new NumberFormatException();
            }
            i++;
        }
        return sb.toString();
    }

    private static boolean isFormatMark(char c) {
        return Character.getType(c) == Character.FORMAT;
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9' || c >= zero && c < zero + 10;
    }

    private boolean isSign(char c) {
        return c == minus || c == '-' || c == '+';
    }

    /** Equal number texts have the same locale and symbols. */
    @Override
    public boolean equals(Object o) {
        return o instanceof NumberText t && t.locale.equals(locale) && t.zero == zero && t.decimal == decimal
                && t.group == group && t.minus == minus && t.exponent.equals(exponent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(locale, zero, decimal, group, minus, exponent);
    }
}
