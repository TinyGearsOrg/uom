/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.engine.arithmetic;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Operand-kind, conversion and precision rules shared by the quantities. */
public final class Precision {

    /** Largest absolute scale accepted for a {@link BigDecimal} quantity value. */
    public static final int MAX_SCALE = 100_000;

    /**
     * Largest context precision and largest number of digits of a {@link BigDecimal} quantity value: it caps the
     * digits, so the work, of every value and rounded result. Half of {@link #MAX_SCALE}, so that a result rounded to
     * any allowed precision keeps a valid scale down to a magnitude of {@code 1e-50000}.
     */
    public static final int MAX_PRECISION = 50_000;

    private static final BigDecimal DOUBLE_TOLERANCE = BigDecimal.valueOf(DoubleArithmetic.EQUIVALENCE_TOLERANCE);

    /** The kind of a number operand. */
    public enum Kind {
        /** An integer type: exact, adopts the other operand's number type. */
        EXACT,
        /** {@code double}, {@code float} and unknown {@link Number}s. */
        DOUBLE,
        /** {@link BigDecimal}. */
        DECIMAL
    }

    private Precision() {
    }

    /**
     * The kind of {@code n}.
     *
     * @param n the number
     * @return its kind
     */
    public static Kind kind(Number n) {
        Objects.requireNonNull(n, "number");
        if (n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long
                || n instanceof BigInteger || n instanceof AtomicInteger || n instanceof AtomicLong) {
            return Kind.EXACT;
        }
        return n instanceof BigDecimal ? Kind.DECIMAL : Kind.DOUBLE;
    }

    /**
     * {@code n} as a double; a BigDecimal or BigInteger beyond the double range throws, underflow gives 0.
     *
     * @param n the number
     * @return the double
     * @throws ArithmeticException if {@code n} is a BigDecimal or BigInteger beyond the double range
     */
    public static double toDouble(Number n) {
        double d = n.doubleValue();
        if (n instanceof BigDecimal b && Double.isInfinite(d)) {
            throw new ArithmeticException(
                    "value out of double range: precision " + b.precision() + ", scale " + b.scale());
        }
        if (n instanceof BigInteger i && Double.isInfinite(d)) {
            throw new ArithmeticException("value out of double range: bit length " + i.bitLength());
        }
        return d;
    }

    /**
     * {@code n} as a BigDecimal, a double as its shortest decimal; a BigDecimal or BigInteger beyond the scale or digit bound
     * throws, so it
     * never reaches the arithmetic.
     *
     * @param n the number
     * @return the decimal
     * @throws ArithmeticException if {@code n} is NaN, infinite or beyond the scale or digit bound
     */
    public static BigDecimal toDecimal(Number n) {
        if (n instanceof BigDecimal b) {
            return checked(b);
        }
        if (n instanceof BigInteger i) {
            return checked(new BigDecimal(i));
        }
        if (kind(n) == Kind.EXACT) {
            return BigDecimal.valueOf(n.longValue());
        }
        if (n instanceof Float f) {
            requireFinite(f, n);
            return new BigDecimal(Float.toString(f));
        }
        double d = n.doubleValue();
        requireFinite(d, n);
        return BigDecimal.valueOf(d);
    }

    private static void requireFinite(double d, Number n) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new ArithmeticException("not a finite number: " + n);
        }
    }

    /**
     * Rejects a relative tolerance that is negative, NaN or infinite.
     *
     * @param tolerance the tolerance
     * @throws IllegalArgumentException if it is negative, NaN or infinite
     */
    public static void checkRelativeTolerance(double tolerance) {
        if (!(tolerance >= 0) || Double.isInfinite(tolerance)) {
            throw new IllegalArgumentException("relative tolerance must be finite and not negative: " + tolerance);
        }
    }

    /**
     * Rejects the value of an absolute tolerance that is negative, NaN or infinite.
     *
     * @param value the tolerance's value
     * @throws IllegalArgumentException if it is negative, NaN or infinite
     */
    public static void checkAbsoluteTolerance(Number value) {
        boolean valid = kind(value) == Kind.DOUBLE
                ? value.doubleValue() >= 0 && !Double.isInfinite(value.doubleValue())
                : toDecimal(value).signum() >= 0;
        if (!valid) {
            throw new IllegalArgumentException("absolute tolerance must be finite and not negative: " + value);
        }
    }

    /**
     * {@code v} if its scale is within {@link #MAX_SCALE} and it has at most {@link #MAX_PRECISION} digits.
     *
     * @param v the value
     * @return {@code v}
     * @throws ArithmeticException if the scale or the number of digits is out of range
     */
    public static BigDecimal checked(BigDecimal v) {
        if (Math.abs((long) v.scale()) > MAX_SCALE) {
            throw new ArithmeticException("scale out of range: " + v.scale());
        }
        if (v.precision() > MAX_PRECISION) {
            throw new ArithmeticException("too many digits: " + v.precision());
        }
        return v;
    }

    /**
     * {@code mc} if its precision is within {@link #MAX_PRECISION}.
     *
     * @param mc the context
     * @return {@code mc}
     * @throws ArithmeticException if the precision is out of range
     */
    public static MathContext checked(MathContext mc) {
        if (mc.getPrecision() > MAX_PRECISION) {
            throw new ArithmeticException("precision out of range: " + mc.getPrecision());
        }
        return mc;
    }

    /**
     * Whether {@code a} and {@code b} agree within {@link DoubleArithmetic#EQUIVALENCE_TOLERANCE}, the tolerance of a
     * {@code double} operand, compared in decimal so that values outside the {@code double} range compare too.
     *
     * @param a one value
     * @param b the other value
     * @return true if they agree within the tolerance
     */
    public static boolean equivalentAsDoubles(BigDecimal a, BigDecimal b) {
        BigDecimal bound = a.abs().max(b.abs()).multiply(DOUBLE_TOLERANCE);
        return a.compareTo(b) == 0 || a.subtract(b).abs().compareTo(bound) <= 0;
    }

    /**
     * The lower precision; 0 is unlimited and highest; equal precision keeps {@code a}.
     *
     * @param a one context
     * @param b the other context
     * @return the context of lower precision
     */
    public static MathContext lower(MathContext a, MathContext b) {
        int pa = a.getPrecision();
        int pb = b.getPrecision();
        if (pa == 0) {
            return pb == 0 ? a : b;
        }
        return pb != 0 && pb < pa ? b : a;
    }

    /**
     * The context used for division; an unlimited context falls back to DECIMAL128.
     *
     * @param mc the context
     * @return {@code mc}, or {@code DECIMAL128} if it is unlimited
     */
    public static MathContext calculation(MathContext mc) {
        return mc.getPrecision() == 0 ? MathContext.DECIMAL128 : mc;
    }
}
