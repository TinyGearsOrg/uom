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

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.ParsePosition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.measure.MetricPrefix;
import javax.measure.Quantity;
import javax.measure.Quantity.Scale;
import javax.measure.Unit;
import javax.measure.format.MeasurementParseException;
import javax.measure.spi.QuantityFactory;

import org.junit.jupiter.api.Test;

import org.tinygears.uom.jsr.BaseUnits;
import org.tinygears.uom.jsr.UomUnit;
import org.tinygears.uom.jsr.quantity.AbstractDecimalQuantity;
import org.tinygears.uom.jsr.quantity.Dimensionless;
import org.tinygears.uom.jsr.quantity.Length;
import org.tinygears.uom.jsr.quantity.QuantityTypes;
import org.tinygears.uom.jsr.quantity.Temperature;
import org.tinygears.uom.jsr.quantity.UntypedDecimalQuantity;
import org.tinygears.uom.jsr.quantity.UntypedDoubleQuantity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DecimalParseTest {
    private static final UomUnitFormat UNITS = UomUnitFormat.standard();
    private static final UomQuantityFormat ROOT = new UomQuantityFormat(UNITS);
    private static final UomQuantityFormat D64 = ROOT.withDecimals(MathContext.DECIMAL64);

    private static final String HUNDRED_DIGITS = "-" + "1234567890".repeat(9) + "." + "9876543210";

    /** 15 decimals: trailing zeros, signs, scale 0, negative scale, tiny and huge exponents, 100 digits. */
    static final String[] DECIMALS = {
        "1.50",
        "-1.50",
        "0",
        "0.000",
        "1E+3",
        "-2.5E-7",
        "123456789",
        "1E+99999",
        "1E-99999",
        "-9.99E+300",
        "3.14159265358979323846264338327950288",
        "0E+5",
        "-0.5",
        HUNDRED_DIGITS,
        "100000000000000000000000000000.000000000000000000000000000001" };

    static final String[] LOCALES = {
        "und",
        "de-DE",
        "fr-FR",
        "ar-EG",
        "fa",
        "sv-SE",
        "hi-IN-u-nu-deva",
        "de-CH",
        "en-IN",
        "th-TH-u-nu-thai",
        "he-IL",
        "ja-JP" };

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    /** Same class, unit, scale, unscaled value, value scale and context. */
    static void assertSameDecimal(Quantity<?> expected, Quantity<?> actual, String message) {
        AbstractDecimalQuantity<?, ?> e = (AbstractDecimalQuantity<?, ?>) expected;
        AbstractDecimalQuantity<?, ?> a = assertInstanceOf(AbstractDecimalQuantity.class, actual, message);
        assertEquals(e, a, message);
        assertSame(e.getClass(), a.getClass(), message);
        assertEquals(e.getUnit(), a.getUnit(), message);
        assertSame(e.getScale(), a.getScale(), message);
        assertEquals(e.getValue().unscaledValue(), a.getValue().unscaledValue(), message);
        assertEquals(e.getValue().scale(), a.getValue().scale(), message);
        assertEquals(e.mathContext(), a.mathContext(), message);
    }

    private static void assertFails(UomQuantityFormat format, String text, String message, int position) {
        MeasurementParseException e = assertThrows(MeasurementParseException.class, () -> format.parse(text), text);
        assertEquals(message, e.getMessage(), text);
        assertEquals(position, e.getPosition(), text);
        assertEquals(text, e.getParsedString(), text);
    }

    @Test
    void kilometresKeepTheirDigitsScaleAndContext() {
        Quantity<?> q = D64.parse("1.50 km");
        assertInstanceOf(Length.class, q);
        Length expected = Length.of(d("1.50"), SI.KILOMETRE, Scale.ABSOLUTE, MathContext.DECIMAL64);
        assertSameDecimal(expected, q, "1.50 km");
        BigDecimal v = (BigDecimal) q.getValue();
        assertEquals(BigInteger.valueOf(150), v.unscaledValue());
        assertEquals(2, v.scale());
        assertSame(MathContext.DECIMAL64, ((AbstractDecimalQuantity<?, ?>) q).mathContext());
        assertSame(Scale.ABSOLUTE, q.getScale());
    }

    @Test
    void manyDigitsSurviveExactly() {
        String thirty = "123456789012345678901234567.890";
        assertEquals(d(thirty), D64.parse(thirty + " m").getValue());
        // not rounded at parse, even beyond the context's precision
        String fifty = "1234567890".repeat(5);
        Quantity<?> q = ROOT.withDecimals(MathContext.DECIMAL32).parse(fifty + " m");
        assertEquals(d(fifty), q.getValue());
        assertEquals(50, ((BigDecimal) q.getValue()).precision());
        assertEquals(MathContext.DECIMAL32, ((AbstractDecimalQuantity<?, ?>) q).mathContext());
    }

    @Test
    void exponentFormsFollowBigDecimal() {
        BigDecimal a = (BigDecimal) D64.parse("1.5E3 m").getValue();
        assertEquals(BigInteger.valueOf(15), a.unscaledValue());
        assertEquals(-2, a.scale());
        BigDecimal b = (BigDecimal) D64.parse("1E+3 m").getValue();
        assertEquals(BigInteger.ONE, b.unscaledValue());
        assertEquals(-3, b.scale());
        BigDecimal z = (BigDecimal) D64.parse("-0.00 m").getValue();
        assertEquals(0, z.signum());
        assertEquals(2, z.scale());
        BigDecimal e = (BigDecimal) D64.parse("0E+5 m").getValue();
        assertEquals(0, e.signum());
        assertEquals(-5, e.scale());
        assertEquals(d("1.5E-3"), D64.parse("1.5e-3 m").getValue());
        assertEquals(d("5"), D64.parse("+5 m").getValue());
        assertEquals(d("0.5"), D64.parse(".5 m").getValue());
        assertEquals(d("5"), D64.parse("5. m").getValue());
    }

    @Test
    void defaultFormatStillParsesDouble() {
        Quantity<?> q = ROOT.parse("1.50 km");
        assertEquals(Double.class, q.getValue().getClass());
        assertInstanceOf(Length.class, q);
        assertNull(ROOT.getDecimalContext());
        assertEquals(1e10, ROOT.withLocale(Locale.GERMANY).parse("1,0E10 km").getValue());
        assertEquals(Double.NaN, ROOT.parse("NaN m").getValue());
    }

    @Test
    void unitsTypedAndUntyped() {
        Quantity<?> c = D64.parse("-12.50 °C");
        assertSameDecimal(
                Temperature.of(d("-12.50"), SI.DEGREE_CELSIUS, Scale.ABSOLUTE, MathContext.DECIMAL64),
                c,
                "°C");
        Quantity<?> shifted = D64.parse("2.5 K + 5");
        assertInstanceOf(Temperature.class, shifted);
        assertEquals(BaseUnits.KELVIN.shift(5), shifted.getUnit());
        assertEquals(d("2.5"), shifted.getValue());
        Quantity<?> micro = D64.parse("2.50 µm");
        assertSameDecimal(Length.of(d("2.50"), SI.MICROMETRE, Scale.ABSOLUTE, MathContext.DECIMAL64), micro, "µm");
        Quantity<?> untyped = D64.parse("1.20 kg·m^5/s");
        UntypedDecimalQuantity expected = UntypedDecimalQuantity
                .of(d("1.20"), (UomUnit<?>) UNITS.parse("kg·m^5/s"), MathContext.DECIMAL64);
        assertSameDecimal(expected, untyped, "untyped");
        Quantity<?> number = D64.parse("  7.0  ");
        assertSameDecimal(
                Dimensionless.of(d("7.0"), BaseUnits.ONE, Scale.ABSOLUTE, MathContext.DECIMAL64),
                number,
                "number");
    }

    @Test
    void nanAndInfinityAreErrors() {
        String message = "decimal quantities have no NaN or infinity: ";
        UomQuantityFormat de = D64.withLocale(Locale.GERMANY);
        for (UomQuantityFormat f : new UomQuantityFormat[] { D64, de, D64.withGrouping(true) }) {
            assertFails(f, "NaN m", message + "'NaN'", 0);
            assertFails(f, "Infinity m", message + "'Infinity'", 0);
            assertFails(f, " -Infinity m", message + "'-Infinity'", 1);
            assertFails(f, "+Infinity m", message + "'+Infinity'", 0);
        }
        assertFails(
                D64.withLocale(Locale.forLanguageTag("sv-SE")),
                "\u2212Infinity m",
                message + "'\u2212Infinity'",
                0);
        assertFails(
                D64.withLocale(Locale.forLanguageTag("he-IL")),
                "\u200E-Infinity m",
                message + "'\u200E-Infinity'",
                0);
    }

    @Test
    void scaleBeyondTheBoundIsAParseError() {
        assertFails(D64, "1E-100001 m", "invalid number '1E-100001': scale out of range: 100001", 0);
        assertFails(D64, "  1E+100001 m", "invalid number '1E+100001': scale out of range: -100001", 2);
        assertFails(
                D64.withLocale(Locale.GERMANY),
                "1,5E-100000 m",
                "invalid number '1,5E-100000': scale out of range: 100001",
                0);
        assertEquals(100000, ((BigDecimal) D64.parse("1E-100000 m").getValue()).scale());
        assertFails(D64, "1E+99999999999 m", "invalid number '1E+99999999999': scale out of range: -99999999999", 0);
        assertFails(
                D64,
                "1E-9999999999999999999999 m",
                "invalid number '1E-9999999999999999999999': scale out of range",
                0);
        assertFails(
                D64,
                "1E+9999999999999999999999 m",
                "invalid number '1E+9999999999999999999999': scale out of range",
                0);
        assertFails(D64, "1E-000100001 m", "invalid number '1E-000100001': scale out of range: 100001", 0);
        assertEquals(100000, ((BigDecimal) D64.parse("-.5E-99999 m").getValue()).scale());
        assertFails(D64, "-.50E-99999 m", "invalid number '-.50E-99999': scale out of range: 100001", 0);
        // number errors come before unit errors
        assertFails(D64, "1E-100001 foo", "invalid number '1E-100001': scale out of range: 100001", 0);
        assertEquals(-100000, ((BigDecimal) D64.parse("1E+100000 m").getValue()).scale());
        assertEquals(100000, ((BigDecimal) D64.parse("0.1E-99999 m").getValue()).scale());
        String fraction = "0." + "0".repeat(99_999) + "1";
        assertEquals(100000, ((BigDecimal) D64.parse(fraction + " m").getValue()).scale());
        assertFails(D64, "0" + fraction + "5 m", "invalid number '0" + fraction + "5': scale out of range: 100001", 0);
        ParsePosition pos = new ParsePosition(2);
        assertThrows(MeasurementParseException.class, () -> D64.parse("x 1E-100001 m", pos));
        assertEquals(2, pos.getErrorIndex());
    }

    @Test
    void invalidNumbers() {
        assertFails(D64, "1e m", "invalid number '1e'", 0);
        assertFails(D64, "1..5 m", "invalid number '1..5'", 0);
        assertFails(D64, "abc m", "invalid number 'abc'", 0);
        assertFails(D64, " 0x1p3 m", "invalid number '0x1p3'", 1);
        assertFails(D64, "1d m", "invalid number '1d'", 0);
        assertFails(D64, "1f m", "invalid number '1f'", 0);
        assertFails(D64, "", "missing number", 0);
        assertFails(D64, "   ", "missing number", 3);
        assertFails(D64, "1,5 m", "grouping separator not allowed: ','", 1);
        // only ASCII digits in ROOT, only ASCII and the locale's digits elsewhere
        assertFails(D64, "\u0661\u0662 m", "invalid number '\u0661\u0662'", 0);
        assertFails(D64, "\uFF11\uFF12 m", "invalid number '\uFF11\uFF12'", 0);
        assertFails(D64, "1\u0662 m", "invalid number '1\u0662'", 0);
        assertFails(D64, "1E\u0661 m", "invalid number '1E\u0661'", 0);
        assertFails(D64, "1.\u0965 m", "invalid number '1.\u0965'", 0);
        UomQuantityFormat de = D64.withLocale(Locale.GERMANY);
        assertFails(de, "\u0661\u0662 m", "invalid number '\u0661\u0662'", 0);
        assertFails(de, "\uFF11\uFF12 m", "invalid number '\uFF11\uFF12'", 0);
        UomQuantityFormat eg = D64.withLocale(Locale.forLanguageTag("ar-EG"));
        assertEquals(d("12"), eg.parse("\u0661\u0662 m").getValue());
        assertEquals(d("12"), eg.parse("1\u0662 m").getValue());
        assertEquals(d("1E+12"), eg.parse("1E\u0661\u0662 m").getValue());
        assertFails(eg, "\uFF11\uFF12 m", "invalid number '\uFF11\uFF12'", 0);
        assertFails(eg, "1E\uFF11 m", "invalid number '1E\uFF11'", 0);
        assertFails(eg, "\u0967 m", "invalid number '\u0967'", 0);
        assertFails(D64, "1.5 foo", "unknown unit 'foo'", 4);
        assertFails(D64.withLocale(Locale.GERMANY), "1.5 m", "grouping separator not allowed: '.'", 1);
        assertFails(D64.withLocale(Locale.GERMANY), "1d m", "invalid number '1d'", 0);
        assertFails(
                D64.withGrouping(true),
                "1,234.5,6 m",
                "grouping separator not allowed after the decimal separator",
                7);
    }

    @Test
    void whitespaceRulesAreUnchanged() {
        assertEquals(d("1.50"), D64.parse("\t 1.50 \n km ").getValue());
        assertEquals(SI.KILOMETRE, D64.parse("\t 1.50 \n km ").getUnit());
        Quantity<?> product = D64.withLocale(Locale.FRANCE).withGrouping(true).parse("1 234 km");
        assertEquals(d("1"), product.getValue());
        assertEquals(UNITS.parse("234 km"), product.getUnit());
        ParsePosition pos = new ParsePosition(3);
        Quantity<?> q = D64.parse("x: 2.50 m", pos);
        assertEquals(d("2.50"), q.getValue());
        assertEquals(9, pos.getIndex());
    }

    @Test
    void localeGroupingAndDecimalsCompose() {
        UomQuantityFormat de = ROOT.withLocale(Locale.GERMANY).withGrouping(true).withDecimals(MathContext.DECIMAL64);
        assertEquals(de, ROOT.withDecimals(MathContext.DECIMAL64).withGrouping(true).withLocale(Locale.GERMANY));
        BigDecimal v = (BigDecimal) de.parse("1.234,50 km").getValue();
        assertEquals(BigInteger.valueOf(123450), v.unscaledValue());
        assertEquals(2, v.scale());
        assertEquals(Locale.GERMANY, de.getLocale());
        assertEquals(MathContext.DECIMAL64, de.getDecimalContext());
        // the locale's digits and symbols
        assertEquals(d("-12.50"), D64.withLocale(Locale.forLanguageTag("ar-EG")).parse("-١٢٫٥٠ km").getValue());
        assertEquals(
                d("-1.50E+10"),
                D64.withLocale(Locale.forLanguageTag("sv-SE")).parse("\u22121,50×10^10 km").getValue());
        UomQuantityFormat fr = D64.withLocale(Locale.FRANCE).withGrouping(true);
        assertEquals(d("1234567.10"), fr.parse("1\u202F234\u202F567,10 km").getValue());
        assertFails(D64.withLocale(Locale.FRANCE), "1\u202F234 km", "grouping separator not allowed: '\u202F'", 1);
        // format is unchanged by withDecimals
        Length q = Length.of(d("-1.50"), SI.KILOMETRE, Scale.ABSOLUTE, MathContext.DECIMAL32);
        assertEquals(ROOT.withLocale(Locale.GERMANY).format(q), de.format(q));
        assertEquals("-1,50 km", de.format(q));
        assertEquals("2.5 km", D64.format(UntypedDoubleQuantity.of(2.5, SI.KILOMETRE)));
    }

    @Test
    void unlimitedContext() {
        UomQuantityFormat f = ROOT.withDecimals(MathContext.UNLIMITED);
        Length q = Length
                .of(d("0.1000000000000000000000000000000000000001"), SI.METRE, Scale.ABSOLUTE, MathContext.UNLIMITED);
        assertSameDecimal(q, f.parse(f.format(q)), "unlimited");
    }

    @Test
    void contextIsCheckedAtWithDecimals() {
        ArithmeticException e = assertThrows(
                ArithmeticException.class,
                () -> ROOT.withDecimals(new MathContext(50_001)));
        assertEquals("precision out of range: 50001", e.getMessage());
        MathContext max = new MathContext(50_000, RoundingMode.DOWN);
        assertEquals(max, ROOT.withDecimals(max).getDecimalContext());
    }

    @Test
    void equalsHashCodeAndToString() {
        UomQuantityFormat a = ROOT.withDecimals(MathContext.DECIMAL64);
        assertEquals(a, ROOT.withDecimals(new MathContext(16, RoundingMode.HALF_EVEN)));
        assertEquals(a.hashCode(), ROOT.withDecimals(new MathContext(16, RoundingMode.HALF_EVEN)).hashCode());
        assertNotEquals(a, ROOT);
        assertNotEquals(a, ROOT.withDecimals(MathContext.DECIMAL32));
        assertNotEquals(a, ROOT.withDecimals(new MathContext(16, RoundingMode.HALF_UP)));
        assertEquals("UomQuantityFormat[locale=und, grouping=false, decimals=off]", ROOT.toString());
        assertEquals(
                "UomQuantityFormat[locale=de-DE, grouping=true, decimals=16/HALF_EVEN]",
                a.withLocale(Locale.GERMANY).withGrouping(true).toString());
        assertEquals(
                "UomQuantityFormat[locale=und, grouping=false, decimals=unlimited]",
                ROOT.withDecimals(MathContext.UNLIMITED).toString());
        assertEquals(
                "UomQuantityFormat[locale=und, grouping=false, decimals=0/HALF_EVEN]",
                ROOT.withDecimals(new MathContext(0, RoundingMode.HALF_EVEN)).toString());
        assertEquals(MathContext.DECIMAL64, a.withLocale(Locale.GERMANY).withGrouping(true).getDecimalContext());
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Quantity<?> of(QuantityFactory<?> f, BigDecimal v, Unit<?> u, MathContext mc) throws Exception {
        Method m = f.getClass().getMethod("of", BigDecimal.class, Unit.class, Scale.class, MathContext.class);
        return (Quantity<?>) m.invoke(f, v, u, Scale.ABSOLUTE, mc);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void roundTripForEveryTypeInTwelveLocales() throws Exception {
        assertEquals(15, DECIMALS.length);
        assertEquals(12, LOCALES.length);
        assertEquals(33, QuantityTypes.types().size());
        MathContext[] contexts = {
            MathContext.DECIMAL32,
            MathContext.DECIMAL64,
            MathContext.DECIMAL128,
            MathContext.UNLIMITED,
            new MathContext(7, RoundingMode.HALF_UP) };
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (String tag : LOCALES) {
            UomQuantityFormat f = ROOT.withLocale(Locale.forLanguageTag(tag)).withGrouping(tag.equals("fr-FR"));
            int i = 0;
            for (Class<?> type : QuantityTypes.types()) {
                QuantityFactory<?> factory = QuantityTypes.factory((Class) type);
                UomUnit<?> system = (UomUnit<?>) factory.getSystemUnit();
                for (UomUnit<?> unit : List.of(system, system.prefix(MetricPrefix.KILO))) {
                    for (String s : DECIMALS) {
                        MathContext mc = contexts[i++ % contexts.length];
                        Quantity<?> q = of(factory, d(s), unit, mc);
                        String text = f.format(q);
                        checked++;
                        try {
                            assertSameDecimal(q, f.withDecimals(mc).parse(text), text);
                        } catch (AssertionError | RuntimeException e) {
                            failures.add(tag + " " + type.getSimpleName() + " " + text + ": " + e.getMessage());
                        }
                    }
                }
            }
        }
        assertEquals(12 * 33 * 2 * 15, checked);
        assertEquals(List.of(), failures.subList(0, Math.min(20, failures.size())), failures.size() + " failures");
    }

    @Test
    void roundTripOfOffsetAndUntypedUnits() {
        List<UomUnit<?>> units = List.of(
                SI.KILOMETRE,
                SI.DEGREE_CELSIUS,
                BaseUnits.KELVIN.shift(5),
                SI.MICROMETRE,
                (UomUnit<?>) UNITS.parse("kg·m^5/s"),
                (UomUnit<?>) UNITS.parse("km/h"));
        for (String tag : LOCALES) {
            UomQuantityFormat f = ROOT.withLocale(Locale.forLanguageTag(tag)).withDecimals(MathContext.DECIMAL128);
            for (UomUnit<?> unit : units) {
                for (String s : DECIMALS) {
                    Quantity<?> parsed = f
                            .parse(f.format(UntypedDecimalQuantity.of(d(s), unit, MathContext.DECIMAL128)));
                    assertEquals(unit, parsed.getUnit(), tag + " " + s);
                    assertEquals(d(s), parsed.getValue(), tag + " " + s);
                }
            }
            Quantity<?> untyped = f.parse(
                    f.format(
                            UntypedDecimalQuantity
                                    .of(d("-1.50"), (UomUnit<?>) UNITS.parse("kg·m^5/s"), MathContext.DECIMAL128)));
            assertInstanceOf(UntypedDecimalQuantity.class, untyped, tag);
        }
    }

    @Test
    void concurrentUseOfOneFormat() throws Exception {
        UomQuantityFormat fa = ROOT.withLocale(Locale.forLanguageTag("fa")).withGrouping(true)
                .withDecimals(MathContext.DECIMAL64);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                long seed = t;
                results.add(pool.submit(() -> {
                    java.util.Random random = new java.util.Random(seed);
                    int failures = 0;
                    for (int i = 0; i < 2000; i++) {
                        BigDecimal v = new BigDecimal(BigInteger.valueOf(random.nextLong()), random.nextInt(-30, 31));
                        Length q = Length.of(v, SI.KILOMETRE, Scale.ABSOLUTE, MathContext.DECIMAL64);
                        BigDecimal back = (BigDecimal) fa.parse(fa.format(q)).getValue();
                        if (!back.equals(v)) {
                            failures++;
                        }
                    }
                    return failures;
                }));
            }
            for (Future<Integer> result : results) {
                assertEquals(0, result.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void millionDigitFractionFailsBeforeTheParse() {
        String text = "0." + "3".repeat(1_000_000) + " m";
        assertTimeoutPreemptively(
                Duration.ofMillis(500),
                () -> assertFails(
                        D64.withLocale(Locale.GERMANY),
                        text.replace('.', ','),
                        "invalid number '" + text.substring(0, text.length() - 2).replace('.', ',')
                                + "': scale out of range: 1000000",
                        0));
        assertTimeoutPreemptively(
                Duration.ofMillis(500),
                () -> assertFails(
                        D64,
                        "1" + text,
                        "invalid number '1" + text.substring(0, text.length() - 2) + "': scale out of range: 1000000",
                        0));
    }

    @Test
    void fiftyThousandDigitsParseQuickly() {
        String digits = "7".repeat(50_000);
        String text = "-" + digits.substring(0, 25_000) + "." + digits.substring(25_000) + " m";
        UomQuantityFormat de = ROOT.withLocale(Locale.GERMANY).withDecimals(MathContext.DECIMAL64);
        String deText = text.replace('.', ',');
        // generous bound: the parse is well under a second, but CI machines vary
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            BigDecimal v = (BigDecimal) D64.parse(text).getValue();
            assertEquals(50_000, v.precision());
            assertEquals(25_000, v.scale());
            assertEquals(v, de.parse(deText).getValue());
        });
    }

    @Test
    void digitsBeyondTheBoundAreAParseError() {
        // Leading zeros do not count; trailing zeros do, as in BigDecimal.precision().
        assertEquals(50_000, ((BigDecimal) D64.parse("-00.000" + "1".repeat(50_000) + " m").getValue()).precision());
        assertEquals(50_000, ((BigDecimal) D64.parse("1" + "0".repeat(49_999) + "E-3 m").getValue()).precision());
        String message = "invalid number: more than 50000 digits";
        assertFails(D64, "  1" + "0".repeat(50_000) + " m", message, 2);
        assertFails(D64, "0.0" + "9".repeat(50_001) + "E+7 m", message, 0);
        assertFails(
                D64.withLocale(Locale.GERMANY),
                "1" + "2".repeat(25_000) + "," + "3".repeat(25_000) + " m",
                message,
                0);
        // Refused before the quadratic BigDecimal parse: ten million digits would take minutes.
        String huge = "9".repeat(10_000_000) + " m";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertFails(D64, huge, message, 0));
    }
}
