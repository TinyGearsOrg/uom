/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.engine;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.tinygears.uom.engine.internal.Decimals;
import org.tinygears.uom.engine.internal.Interval;
import org.tinygears.uom.engine.internal.Irrationals;
import org.tinygears.uom.engine.internal.SerialForm;

/**
 * An affine converter {@code x -> factor * x + offset} with exact coefficients, and the steps it was concatenated from.
 * Equal by factor and offset. Serializable through a proxy: one step is rebuilt with {@link #affine}; several are
 * validated and rebuilt keeping the stored factor, offset and step order.
 */
public final class Converter implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** The factor. */
    private final Factor factor;
    /** The offset. */
    private final Factor offset;
    /** The factor as a {@code double}. */
    private final double factorD;
    /** The offset as a {@code double}. */
    private final double offsetD;
    /** The steps; the class is written through its Proxy, so the field type need not be serializable. */
    @SuppressWarnings("serial")
    private final List<Converter> steps;
    /** The inverse, computed once: offset sums and scalars invert the converter to the system unit on every call. */
    private transient volatile Converter inverse;

    /** The converter {@code x -> x}. */
    public static final Converter IDENTITY = new Converter(Factor.ONE, Factor.ZERO);

    private Converter(Factor factor, Factor offset) {
        this(factor, offset, null);
    }

    private Converter(Factor factor, Factor offset, List<Converter> steps) {
        this.factor = factor;
        this.offset = offset;
        this.factorD = factor.doubleValue();
        this.offsetD = offset.doubleValue();
        this.steps = steps != null ? steps : List.of(this);
    }

    /**
     * The converter {@code x -> factor * x + offset}.
     *
     * @param factor the factor
     * @param offset the offset
     * @return the converter, {@link #IDENTITY} for factor 1 and offset 0
     */
    public static Converter affine(Factor factor, Factor offset) {
        return factor.isOne() && offset.isZero() ? IDENTITY : new Converter(factor, offset);
    }

    /**
     * The converter {@code x -> factor * x}.
     *
     * @param factor the factor
     * @return the converter
     */
    public static Converter scale(Factor factor) {
        return affine(factor, Factor.ZERO);
    }

    /**
     * The converter {@code x -> x + offset}.
     *
     * @param offset the offset
     * @return the converter
     */
    public static Converter shift(Factor offset) {
        return affine(Factor.ONE, offset);
    }

    /**
     * The factor.
     *
     * @return the factor
     */
    public Factor factor() {
        return factor;
    }

    /**
     * The offset.
     *
     * @return the offset
     */
    public Factor offset() {
        return offset;
    }

    /**
     * Whether this is {@code x -> x}.
     *
     * @return true for factor 1 and offset 0
     */
    public boolean isIdentity() {
        return factor.isOne() && offset.isZero();
    }

    /**
     * Whether the offset is zero.
     *
     * @return true if linear
     */
    public boolean isLinear() {
        return offset.isZero();
    }

    /**
     * The inverse; a zero factor throws {@link ArithmeticException}.
     *
     * @return {@code x -> (x - offset) / factor}
     */
    public Converter inverse() {
        Converter i = inverse;
        if (i == null) {
            Factor f = Factor.ONE.divide(factor);
            i = new Converter(f, offset.negate().multiply(f));
            inverse = i;
        }
        return i;
    }

    /**
     * Converts by {@code other} first, then by this converter; the steps of both are kept.
     *
     * @param other the converter applied first
     * @return the composition
     */
    public Converter concatenate(Converter other) {
        List<Converter> all = new ArrayList<>(steps);
        all.addAll(other.steps());
        return new Converter(
                factor.multiply(other.factor()),
                factor.multiply(other.offset()).add(offset),
                List.copyOf(all));
    }

    /**
     * The steps this converter was concatenated from, in order; a single converter is its own step.
     *
     * @return the steps
     */
    public List<Converter> steps() {
        return steps;
    }

    /**
     * Converts a {@code double}.
     *
     * @param value the value
     * @return the converted value
     */
    public double convert(double value) {
        // Adding a zero offset would turn -0.0 into 0.0.
        return offsetD == 0 ? factorD * value : factorD * value + offsetD;
    }

    /**
     * Converts a decimal value.
     *
     * @param value the value
     * @param mc the rounding; precision 0 is exact where the result terminates for this value (a factor such as
     *     {@code 5000/127} included), else {@code DECIMAL128}, as for an irrational factor
     * @return the converted value
     */
    public BigDecimal convert(BigDecimal value, MathContext mc) {
        if (mc.getPrecision() == 0) {
            if (factor.isExact() && offset.isExact()) {
                if (terminates(factor) && terminates(offset)) {
                    return value.multiply(factor.decimalValue(mc)).add(offset.decimalValue(mc));
                }
                // value * nf/df + no/do as one quotient: exact if it terminates for this value, as add() is.
                BigDecimal n = value.multiply(new BigDecimal(factor.numerator().multiply(offset.denominator())))
                        .add(new BigDecimal(offset.numerator().multiply(factor.denominator())));
                BigDecimal d = new BigDecimal(factor.denominator().multiply(offset.denominator()));
                if (Decimals.terminates(n, d)) {
                    return Decimals.divide(n, d, mc);
                }
            }
            // A non-terminating result or an irrational coefficient: DECIMAL128.
            return convert(value, MathContext.DECIMAL128);
        }
        if (factor.isExact() && offset.isExact()) {
            // value * nf/df + no/do as one exact quotient, rounded once.
            BigDecimal t = value.multiply(new BigDecimal(factor.numerator().multiply(offset.denominator())));
            BigDecimal b = new BigDecimal(offset.numerator().multiply(factor.denominator()));
            BigDecimal d = new BigDecimal(factor.denominator().multiply(offset.denominator()));
            int guard = mc.getPrecision() + d.precision() + 1;
            // Exact quotients take the scale of the sum aligned to scale max(value scale, 0); the zero-term and
            // negative-scale adjustments reproduce it without the alignment.
            BigDecimal n = b.signum() == 0
                    ? t
                    : t.signum() == 0 ? b.setScale(Math.max(0, Math.min(t.scale(), guard))) : addFar(t, b, guard);
            BigDecimal q = Decimals.divide(n, d, mc);
            return q.scale() >= 0
                    ? q
                    : q.signum() == 0
                            ? BigDecimal.ZERO
                            : q.setScale(Math.min(0, q.scale() + mc.getPrecision() - q.precision()));
        }
        // Irrational coefficients: enclosures narrow until both ends round alike. Only two irrational coefficients
        // that cancel exactly keep them apart; past the cap the lower end rounds.
        long cap = 2L * (mc.getPrecision() + value.precision()) + 1000;
        for (int digits = mc.getPrecision() + Irrationals.GUARD;; digits *= 2) {
            Interval x = Irrationals.enclose(factor, digits).multiply(value)
                    .add(Irrationals.enclose(offset, digits), digits);
            BigDecimal r = x.lo().round(mc);
            if (r.compareTo(x.hi().round(mc)) == 0 || digits > cap) {
                return r;
            }
        }
    }

    private static boolean terminates(Factor f) {
        return Decimals.terminates(BigDecimal.ONE, new BigDecimal(f.denominator()));
    }

    /**
     * {@code x + y} for the numerator of a quotient rounded to {@code guard - divisorDigits - 1} digits; a term
     * below 10^k, where k is under both the other term's last digit and the quotient's rounding grid (both
     * multiples of 10^k), becomes a same-signed 10^(k-1): it rounds identically and skips the scale alignment.
     */
    private static BigDecimal addFar(BigDecimal x, BigDecimal y, int guard) {
        long ex = (long) x.precision() - x.scale() - 1;
        long ey = (long) y.precision() - y.scale() - 1;
        BigDecimal big = ex >= ey ? x : y;
        BigDecimal small = ex >= ey ? y : x;
        long sticky = Math.min(Math.max(ex, ey) - guard - 1, -(long) big.scale()) - 1;
        if (Math.min(ex, ey) >= sticky) {
            return x.add(y);
        }
        return big.add(BigDecimal.valueOf(small.signum(), Math.toIntExact(-sticky)));
    }

    /**
     * The serialization proxy: factor, offset and the steps as parallel arrays (null for one step).
     *
     * @return the proxy
     */
    @Serial
    private Object writeReplace() {
        if (steps.size() == 1 && steps.get(0) == this) {
            return new Proxy(factor, offset, null, null);
        }
        Factor[] f = new Factor[steps.size()];
        Factor[] o = new Factor[steps.size()];
        for (int i = 0; i < f.length; i++) {
            Converter step = steps.get(i);
            f[i] = step.factor();
            o[i] = step.offset();
        }
        return new Proxy(factor, offset, f, o);
    }

    /**
     * Rejects a stream without the proxy.
     *
     * @param in the stream
     * @throws InvalidObjectException always
     */
    @Serial
    private void readObject(ObjectInputStream in) throws InvalidObjectException {
        throw new InvalidObjectException("proxy required");
    }

    /** Serialized form of a converter, steps as parallel arrays (null for one step); package-private for tests. */
    static final class Proxy implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private static final String TOO_MANY_STEPS = "invalid converter: too many conversion steps";
        private static final MathContext FOLD = new MathContext(60);
        private static final BigDecimal TOLERANCE = new BigDecimal("1e-40");
        private static final int MAX_DECIMAL_BITS = 4096;

        private transient Factor factor;
        private transient Factor offset;
        private transient Factor[] stepFactors;
        private transient Factor[] stepOffsets;

        Proxy(Factor factor, Factor offset, Factor[] stepFactors, Factor[] stepOffsets) {
            this.factor = factor;
            this.offset = offset;
            this.stepFactors = stepFactors;
            this.stepOffsets = stepOffsets;
        }

        @Serial
        private void writeObject(ObjectOutputStream out) throws IOException {
            out.defaultWriteObject();
            out.writeUnshared(factor);
            out.writeUnshared(offset);
            SerialForm.writeObjects(out, stepFactors, TOO_MANY_STEPS);
            SerialForm.writeObjects(out, stepOffsets, TOO_MANY_STEPS);
        }

        @Serial
        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            factor = SerialForm.read(in, Factor.class);
            offset = SerialForm.read(in, Factor.class);
            stepFactors = SerialForm.readObjects(in, Factor.class, Factor[]::new, TOO_MANY_STEPS);
            stepOffsets = SerialForm.readObjects(in, Factor.class, Factor[]::new, TOO_MANY_STEPS);
        }

        @Serial
        private Object readResolve() throws InvalidObjectException {
            try {
                Objects.requireNonNull(factor, "null factor");
                Objects.requireNonNull(offset, "null offset");
                if (stepFactors == null && stepOffsets == null) {
                    return affine(factor, offset);
                }
                if (stepFactors == null || stepOffsets == null || stepFactors.length != stepOffsets.length
                        || stepFactors.length == 0) {
                    throw new IllegalArgumentException("step factors and offsets do not match");
                }
                List<Converter> steps = new ArrayList<>(stepFactors.length);
                Factor f = null;
                Factor o = null;
                boolean folded = true;
                for (int i = 0; i < stepFactors.length; i++) {
                    Factor sf = Objects.requireNonNull(stepFactors[i], "null step");
                    Factor so = Objects.requireNonNull(stepOffsets[i], "null step");
                    steps.add(affine(sf, so));
                    if (!folded) {
                        continue;
                    }
                    try {
                        o = f == null ? so : f.multiply(so).add(o);
                        f = f == null ? sf : f.multiply(sf);
                    } catch (ArithmeticException inexact) {
                        folded = false;
                    }
                }
                // Steps grouped otherwise may not fold exactly left to right; a decimal fold checks them then.
                Boolean agrees = folded ? Boolean.valueOf(f.equals(factor) && o.equals(offset)) : decimalFoldAgrees();
                if (agrees == null) {
                    // Steps that cannot be checked are not trusted: the converter is read as one step.
                    return affine(factor, offset);
                }
                if (!agrees) {
                    throw new IllegalArgumentException("steps do not compose to x -> " + factor + " * x + " + offset);
                }
                // Validated above; the constructor keeps the stored values and the step list.
                return new Converter(factor, offset, List.copyOf(steps));
            } catch (RuntimeException e) {
                throw (InvalidObjectException) new InvalidObjectException("invalid converter: " + e.getMessage())
                        .initCause(e);
            }
        }

        /**
         * Left fold of the steps to 60 digits against the stored values, within 1e-40 relative to the largest
         * term (1e-40 absolute if all are zero). Null if the check cannot be made: a part over 4096 bits or a value
         * without a decimal.
         */
        private Boolean decimalFoldAgrees() {
            List<Factor> all = new ArrayList<>(List.of(factor, offset));
            all.addAll(List.of(stepFactors));
            all.addAll(List.of(stepOffsets));
            if (all.stream().anyMatch(
                    x -> x.numerator().bitLength() > MAX_DECIMAL_BITS
                            || x.denominator().bitLength() > MAX_DECIMAL_BITS)) {
                return null;
            }
            try {
                BigDecimal f = stepFactors[0].decimalValue(FOLD);
                BigDecimal o = stepOffsets[0].decimalValue(FOLD);
                BigDecimal terms = o.abs();
                for (int i = 1; i < stepFactors.length; i++) {
                    BigDecimal t = f.multiply(stepOffsets[i].decimalValue(FOLD), FOLD);
                    o = o.add(t, FOLD);
                    terms = terms.add(t.abs(), FOLD);
                    f = f.multiply(stepFactors[i].decimalValue(FOLD), FOLD);
                }
                return close(f, factor.decimalValue(FOLD), f.abs()) && close(o, offset.decimalValue(FOLD), terms);
            } catch (ArithmeticException e) {
                return null;
            }
        }

        private static boolean close(BigDecimal a, BigDecimal b, BigDecimal scale) {
            BigDecimal bound = scale.max(a.abs()).max(b.abs()).multiply(TOLERANCE);
            return a.subtract(b).abs().compareTo(bound.signum() == 0 ? TOLERANCE : bound) <= 0;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Converter c && factor.equals(c.factor) && offset.equals(c.offset);
    }

    @Override
    public int hashCode() {
        return Objects.hash(factor, offset);
    }

    @Override
    public String toString() {
        return "x -> " + factor + " * x + " + offset;
    }
}
