/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.jsr.quantity;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;
import javax.measure.Quantity;
import javax.measure.UnconvertibleException;
import javax.measure.Unit;
import javax.measure.spi.QuantityFactory;
import javax.measure.spi.SystemOfUnits;

import org.tinygears.uom.engine.arithmetic.DecimalArithmetic;
import org.tinygears.uom.engine.arithmetic.DoubleArithmetic;
import org.tinygears.uom.engine.arithmetic.Precision;
import org.tinygears.uom.jsr.UomUnit;
import org.tinygears.uom.jsr.spi.Providers;

/**
 * Base class of the generated {@link BigDecimal} quantities; {@code R} is the typed interface returned by
 * conversions. The {@link MathContext} rounds every computed result and is not part of equality; creation and
 * {@link #negate()} keep the value as given. {@link MathContext#UNLIMITED} keeps results exact where they terminate;
 * non-terminating quotients and irrational conversions fall back to {@code DECIMAL128} with {@code HALF_EVEN},
 * whatever the rounding mode. A {@link BigDecimal} value, operand or scalar whose scale exceeds ±100000 or that
 * has more than 50000 digits throws {@link ArithmeticException}, results of arithmetic included (an exact result in
 * {@code UNLIMITED} can grow past the bound), as does a {@link MathContext} precision over 50000 (at construction, so
 * in {@link #toDecimal(MathContext)} and every factory taking a context). {@link #toDecimal(MathContext)} rounds to a
 * new context; {@link #withMathContext(MathContext)}, used by the quantity format, keeps the value exactly. A {@code double}
 * operand (of {@code add}, {@code subtract}, {@code multiply}, {@code divide} and {@code compareTo}) is read as its
 * shortest decimal ({@link BigDecimal#valueOf(double)}) and adopts this context, so the result is decimal; a NaN or
 * infinite one throws {@link ArithmeticException}, except in {@code compareTo}. Immutable.
 * <p>
 * Serializable through a proxy (see {@link #writeReplace()}); the serialized form may change before 1.0. Reading
 * rebuilds the quantity through the factory of the provider current at read time, then restores the exact value,
 * scale and context: a user subclass, or a quantity read under another top-priority provider, comes back as that
 * provider's class (so it no longer equals the original), and a provider without factories for the standard types
 * makes typed reads fail.
 *
 * @param <Q> the quantity type
 * @param <R> the typed interface returned by conversions
 */
public abstract class AbstractDecimalQuantity<Q extends Quantity<Q>, R extends UomQuantity<Q, R>>
        implements UomQuantity<Q, R>, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The largest absolute scale of a value or {@link BigDecimal} operand: 100000. */
    public static final int MAX_SCALE = Precision.MAX_SCALE;

    /** The largest precision of a context and the most digits of a value: 50000, half of {@link #MAX_SCALE}. */
    public static final int MAX_PRECISION = Precision.MAX_PRECISION;

    /** The value. */
    private final BigDecimal value;
    /** The unit. */
    private final UomUnit<Q> unit;
    /** The scale. */
    private final Quantity.Scale scale;
    /** The context that rounds results. */
    private final MathContext mathContext;

    /**
     * Throws {@link ArithmeticException} if the scale of {@code value} exceeds 100000 in magnitude, it has more than
     * 50000 digits or the precision of {@code mathContext} exceeds 50000.
     *
     * @param value the value
     * @param unit the unit
     * @param scale the scale
     * @param mathContext the context that rounds results
     */
    protected AbstractDecimalQuantity(
            BigDecimal value,
            UomUnit<Q> unit,
            Quantity.Scale scale,
            MathContext mathContext
    ) {
        this.value = Precision.checked(Objects.requireNonNull(value, "value"));
        this.unit = Objects.requireNonNull(unit, "unit");
        this.scale = Objects.requireNonNull(scale, "scale");
        this.mathContext = Precision.checked(Objects.requireNonNull(mathContext, "mathContext"));
    }

    /**
     * Creates a decimal quantity of the same typed interface.
     *
     * @param value the value
     * @param unit the unit
     * @param scale the scale
     * @param mc the context
     * @return the quantity
     */
    protected abstract R create(BigDecimal value, UomUnit<Q> unit, Quantity.Scale scale, MathContext mc);

    /**
     * Creates the {@code double} quantity of the same typed interface.
     *
     * @param value the value
     * @param unit the unit
     * @param scale the scale
     * @return the quantity
     */
    protected abstract R createDouble(double value, UomUnit<Q> unit, Quantity.Scale scale);

    /**
     * The standard quantity type of this class, looked up by {@link #toSystem(SystemOfUnits)}; {@link Untyped} if none.
     *
     * @return the quantity type
     */
    protected abstract Class<Q> quantityType();

    @Override
    public final BigDecimal getValue() {
        return value;
    }

    @Override
    public final UomUnit<Q> getUnit() {
        return unit;
    }

    @Override
    public final Quantity.Scale getScale() {
        return scale;
    }

    /**
     * The context that rounds results computed from this quantity.
     *
     * @return the context
     */
    public final MathContext mathContext() {
        return mathContext;
    }

    /**
     * Converts and rounds the result to {@link #mathContext()}, even for the identity conversion
     * ({@code 1.23456789 m} with {@code DECIMAL32} to metres gives {@code 1.234568 m}). An {@code ABSOLUTE} quantity
     * converts through the offsets, a {@code RELATIVE} one (a difference) by the factor only, and the scale is kept
     * (docs/OFFSET-UNITS.md). Throws
     * {@link ArithmeticException} if the result's scale exceeds the bound of ±100000 (conversion or rounding can
     * push a value past it).
     */
    @Override
    public R to(Unit<Q> unit) {
        UomUnit<Q> target = uomUnit(unit);
        return create(
                DecimalArithmetic.convert(value, Engine.converter(this.unit, target), relative(), mathContext),
                target,
                scale,
                mathContext);
    }

    /**
     * Converts to the unit {@code system} gives for {@link #quantityType()} as {@link #to(Unit)} does, so scale
     * and context are kept; a foreign unit or one of another dimension throws {@link UnconvertibleException}. A
     * system without a unit for the type ({@code getUnit} returns null) throws {@link UnsupportedOperationException}.
     * Untyped quantities look up {@link Untyped}, so they throw too unless the system maps it (no system of this
     * library does); those systems have a unit for each of the 33 standard types.
     *
     * @param system the system of units
     * @return the converted quantity
     */
    public R toSystem(SystemOfUnits system) {
        Objects.requireNonNull(system, "system");
        Unit<Q> target = system.getUnit(quantityType());
        if (target == null) {
            throw new UnsupportedOperationException(
                    "system " + system.getName() + " has no unit for type " + quantityType().getSimpleName());
        }
        return to(target);
    }

    /** Returns a quantity whose value is {@code -getValue()}, in this unit, scale and context. */
    @Override
    public R negate() {
        return create(value.negate(), unit, scale, mathContext);
    }

    /**
     * The same quantity as a {@code double}; a value outside the double range throws {@link ArithmeticException}.
     *
     * @return the double quantity
     */
    public R toDouble() {
        return createDouble(Precision.toDouble(value), unit, scale);
    }

    /**
     * The same quantity with its value rounded to {@code mc}, which becomes its context. Throws
     * {@link ArithmeticException} if the result's scale exceeds the bound of ±100000 (conversion or rounding can
     * push a value past it) or the precision of {@code mc} exceeds 50000.
     *
     * @param mc the context of the result
     * @return the rounded quantity
     */
    public R toDecimal(MathContext mc) {
        return create(value.round(mc), unit, scale, mc);
    }

    /**
     * The same quantity with context {@code mc}; unlike {@link #toDecimal(MathContext)} the value is kept exactly,
     * digits beyond the precision of {@code mc} included; used by the quantity format's decimal parse. Throws
     * {@link ArithmeticException} if the precision of {@code mc} exceeds 50000.
     *
     * @param mc the context of the result
     * @return the quantity with the new context
     */
    public R withMathContext(MathContext mc) {
        return create(value, unit, scale, mc);
    }

    /**
     * Adds as if both operands were in the system unit, with the result back in this unit; linear units skip the
     * system round trip. If either operand is {@code RELATIVE}, {@code addend} is a difference: it is converted by
     * the factor only and added to this value in this unit. The result is {@code RELATIVE} only if both operands
     * are, otherwise {@code ABSOLUTE} (docs/OFFSET-UNITS.md). Evaluated exactly and rounded once to the lower
     * context of the two (irrational unit factors through enclosures that narrow until the rounding is decided); a
     * {@code double} operand is read
     * as its shortest decimal and adopts this context. Throws {@link UnconvertibleException} for a foreign unit or incompatible dimensions.
     *
     * @throws NullPointerException if {@code addend} is null
     */
    @Override
    public R add(Quantity<Q> addend) {
        Objects.requireNonNull(addend, "addend");
        return combine(addend, false);
    }

    /**
     * Subtracts by the same rule as {@link #add}.
     *
     * @throws NullPointerException if {@code subtrahend} is null
     */
    @Override
    public R subtract(Quantity<Q> subtrahend) {
        Objects.requireNonNull(subtrahend, "subtrahend");
        return combine(subtrahend, true);
    }

    /**
     * Multiplies by {@code multiplicand} as a decimal ({@code double} and {@code Float} as written); an offset unit
     * goes through the system unit, unless this quantity is {@code RELATIVE}, which is scaled as a plain value.
     * Evaluated exactly and rounded once to this context.
     *
     * @throws NullPointerException if {@code multiplicand} is null
     */
    @Override
    public R multiply(Number multiplicand) {
        Objects.requireNonNull(multiplicand, "multiplicand");
        return scalar(Precision.toDecimal(multiplicand), false);
    }

    /**
     * Divides as {@link #multiply(Number)}, rounded once; an unlimited context rounds a non-terminating quotient
     * to {@code DECIMAL128}. Division by zero throws {@link ArithmeticException}.
     *
     * @throws NullPointerException if {@code divisor} is null
     */
    @Override
    public R divide(Number divisor) {
        Objects.requireNonNull(divisor, "divisor");
        return scalar(Precision.toDecimal(divisor), true);
    }

    /**
     * Compares the exact system-unit values (a consistent total preorder; two {@code RELATIVE} quantities as
     * differences, by the factor only); a {@code double} operand is read as its shortest decimal, and a NaN or
     * infinite one compares as {@link Double#compare} does. Not consistent with equals. Exact for units with exact
     * factors; irrational factors are enclosed until the sign is decided. A difference not separated from zero at
     * 1000 digits plus twice those of the context and both operands (irrational terms that cancel) compares as zero.
     *
     * @throws NullPointerException if {@code that} is null
     */
    @Override
    public int compareTo(Quantity<Q> that) {
        Objects.requireNonNull(that, "that");
        UomUnit<Q> thatUnit = uomUnit(that.getUnit());
        requireCompatible(thatUnit);
        Number n = that.getValue();
        boolean deltas = bothRelative(that);
        if (Precision.kind(n) == Precision.Kind.DOUBLE && !Double.isFinite(n.doubleValue())) {
            return DoubleArithmetic
                    .compare(Precision.toDouble(value), unit.engine(), n.doubleValue(), thatUnit.engine(), deltas);
        }
        return DecimalArithmetic
                .compare(value, unit.engine(), Precision.toDecimal(n), thatUnit.engine(), deltas, contextWith(that));
    }

    /**
     * Whether both measure exactly the same amount: {@code compareTo(that) == 0}, the exact comparison of the
     * system-unit values (two {@code RELATIVE} quantities as differences, by the factor only), whatever the contexts.
     * A value rounded on its way, such as one radian converted to degrees in a context, is therefore not equivalent to
     * its source. Against a {@code double} operand the {@code double} tolerance applies instead, compared in decimal, so
     * a value outside the {@code double} range is not equivalent to it, and a NaN or infinite operand is equivalent to
     * nothing. Incompatible dimensions give false (compareTo throws).
     *
     * @throws NullPointerException if {@code that} is null
     */
    @Override
    public boolean isEquivalentTo(Quantity<Q> that) {
        Objects.requireNonNull(that, "that");
        UomUnit<Q> thatUnit = uomUnit(that.getUnit());
        if (!unit.getDimension().equals(thatUnit.getDimension())) {
            return false;
        }
        Number n = that.getValue();
        if (Precision.kind(n) == Precision.Kind.DOUBLE) {
            if (!Double.isFinite(n.doubleValue())) {
                return false;
            }
            BigDecimal b = DecimalArithmetic.convert(
                    Precision.toDecimal(n),
                    Engine.converter(thatUnit, unit),
                    bothRelative(that),
                    MathContext.DECIMAL128);
            return Precision.equivalentAsDoubles(value, b);
        }
        return compareTo(that) == 0;
    }

    /** {@inheritDoc} Exact: decided on enclosures of the system values, through pi and roots too. */
    @Override
    public boolean isCloseTo(Quantity<Q> that, double relativeTolerance) {
        Precision.checkRelativeTolerance(relativeTolerance);
        return closeTo(that, BigDecimal.valueOf(relativeTolerance), null);
    }

    /** {@inheritDoc} Exact: decided on enclosures of the system values, through pi and roots too. */
    @Override
    public boolean isCloseTo(Quantity<Q> that, Quantity<Q> absoluteTolerance) {
        Objects.requireNonNull(that, "that");
        Objects.requireNonNull(absoluteTolerance, "absoluteTolerance");
        requireCompatible(uomUnit(absoluteTolerance.getUnit()));
        Precision.checkAbsoluteTolerance(absoluteTolerance.getValue());
        return closeTo(that, null, absoluteTolerance);
    }

    /**
     * Whether {@code |a - b| <= relative * max(|a|, |b|)}, or {@code <= |t|} for the absolute tolerance {@code t},
     * on the system values; enclosures of irrational factors narrow until the comparison is decided.
     */
    private boolean closeTo(Quantity<Q> that, BigDecimal relative, Quantity<Q> absolute) {
        Objects.requireNonNull(that, "that");
        UomUnit<Q> thatUnit = uomUnit(that.getUnit());
        requireCompatible(thatUnit);
        Number n = that.getValue();
        if (Precision.kind(n) == Precision.Kind.DOUBLE && !Double.isFinite(n.doubleValue())) {
            return false;
        }
        BigDecimal b = Precision.toDecimal(n);
        BigDecimal t = absolute == null ? null : Precision.toDecimal(absolute.getValue());
        return DecimalArithmetic.isCloseTo(
                value,
                unit.engine(),
                b,
                thatUnit.engine(),
                bothRelative(that),
                relative,
                t,
                absolute == null ? null : uomUnit(absolute.getUnit()).engine(),
                contextWith(that));
    }

    /**
     * Multiplies the system-unit values (operands are taken to the system unit, so offsets count, except for a
     * {@code RELATIVE} operand, which is a difference and counts by its factor only), evaluated exactly
     * and rounded once to the lower context of the two; the result is an {@link Untyped} quantity in the product of
     * the system units, scale {@code ABSOLUTE}. A {@code double} operand is read as its shortest decimal.
     * Throws {@link UnconvertibleException} for a foreign unit.
     *
     * @throws NullPointerException if {@code multiplicand} is null
     */
    @Override
    public UomQuantity<?, ?> multiply(Quantity<?> multiplicand) {
        Objects.requireNonNull(multiplicand, "multiplicand");
        return untyped(multiplicand, false);
    }

    /**
     * Divides by the same rule as {@link #multiply(Quantity)}; an unlimited context rounds a non-terminating quotient
     * to {@code DECIMAL128}. Division by zero throws {@link ArithmeticException}.
     *
     * @throws NullPointerException if {@code divisor} is null
     */
    @Override
    public UomQuantity<?, ?> divide(Quantity<?> divisor) {
        Objects.requireNonNull(divisor, "divisor");
        return untyped(divisor, true);
    }

    /**
     * Returns {@code 1 / systemValue} in the inverse system unit as an {@link Untyped} quantity, rounded to this
     * context ({@code DECIMAL128} if unlimited and non-terminating). Zero throws {@link ArithmeticException}.
     */
    @Override
    public UomQuantity<?, ?> inverse() {
        BigDecimal r = DecimalArithmetic.inverse(value, unit.engine(), relative(), mathContext);
        return UntypedDecimalQuantity.of(r, UomUnit.of(unit.engine().systemInverse()), mathContext);
    }

    /**
     * Returns this quantity if it already is a {@code type}, otherwise one created by the provider's factory; a
     * unit whose dimension is not that of {@code type} gives {@link ClassCastException} (alternate units count by dimension), a type the provider has no
     * factory for gives {@link UnsupportedOperationException}. The value is never changed: a decimal result
     * carries this value and context, a factory result of another kind is returned as is.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends Quantity<T>> Quantity<T> asType(Class<T> type) {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        QuantityFactory<T> factory;
        try {
            factory = Providers.quantityFactory(type);
        } catch (IllegalArgumentException e) {
            throw new UnsupportedOperationException("no quantity factory for " + type.getName(), e);
        }
        Unit<T> typed = unit.asType(type);
        Quantity<T> q = factory.create(value, typed, scale);
        // with returns the factory's typed interface, which is a Quantity<T>.
        return q instanceof AbstractDecimalQuantity<?, ?> d
                ? (Quantity<T>) d.with(value, uomUnit(typed), scale, mathContext)
                : q;
    }

    /** A quantity of this class with the given value, unit, scale and context, unrounded. */
    R with(BigDecimal v, UomUnit<?> u, Quantity.Scale s, MathContext mc) {
        return create(v, UomUnit.unchecked(u), s, mc);
    }

    /**
     * Writes the type, unit, scale, the value's unscaled bytes and scale, and the context; reading creates the
     * quantity through the factory of this implementation's provider and keeps value and context
     * unrounded. A type outside the 33 standard quantity interfaces and {@link Untyped} or a context precision over
     * 50000 throws {@link java.io.NotSerializableException}.
     *
     * @return the proxy
     * @throws ObjectStreamException if the quantity cannot be serialized
     */
    @Serial
    protected final Object writeReplace() throws ObjectStreamException {
        return QuantityProxy.ofDecimal(quantityType(), unit, scale, value, mathContext);
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

    /**
     * Called instead of readObject when a stream names a subclass without this class.
     *
     * @throws InvalidObjectException always
     */
    @Serial
    private void readObjectNoData() throws InvalidObjectException {
        throw new InvalidObjectException("proxy required");
    }

    /** {@code sys(a)·sys(b)} or {@code sys(a)/sys(b)} as one exact quotient, rounded once. */
    private UomQuantity<?, ?> untyped(Quantity<?> that, boolean divide) {
        UomUnit<?> thatUnit = uomUnit(that.getUnit());
        MathContext mc = contextWith(that);
        BigDecimal r = DecimalArithmetic.product(
                value,
                unit.engine(),
                relative(),
                Precision.toDecimal(that.getValue()),
                thatUnit.engine(),
                that.getScale() == Quantity.Scale.RELATIVE,
                divide,
                mc);
        org.tinygears.uom.engine.Unit product = divide
                ? unit.engine().systemQuotient(thatUnit.engine())
                : unit.engine().systemProduct(thatUnit.engine());
        return UntypedDecimalQuantity.of(r, UomUnit.of(product), mc);
    }

    /** The result context against {@code that}: the lower one; an exact or double value adopts this context. */
    private MathContext contextWith(Quantity<?> that) {
        if (Precision.kind(that.getValue()) != Precision.Kind.DECIMAL) {
            return mathContext;
        }
        // A foreign BigDecimal has no context and counts as unlimited.
        return Precision.lower(
                mathContext,
                that instanceof AbstractDecimalQuantity<?, ?> d ? d.mathContext : MathContext.UNLIMITED);
    }

    /** The literal rule {@code r = a ± (fb·b + ob)/fa} as one exact quotient, rounded once. */
    private R combine(Quantity<Q> that, boolean subtract) {
        UomUnit<Q> thatUnit = uomUnit(that.getUnit());
        requireCompatible(thatUnit);
        MathContext mc = contextWith(that);
        BigDecimal r = DecimalArithmetic.combine(
                value,
                unit.engine(),
                Precision.toDecimal(that.getValue()),
                thatUnit.engine(),
                relative() || that.getScale() == Quantity.Scale.RELATIVE,
                subtract,
                mc);
        return create(r, unit, Engine.sumScale(scale, that.getScale()), mc);
    }

    private R scalar(BigDecimal s, boolean divide) {
        return create(
                DecimalArithmetic.scalar(value, unit.engine(), relative(), s, divide, mathContext),
                unit,
                scale,
                mathContext);
    }

    private boolean relative() {
        return scale == Quantity.Scale.RELATIVE;
    }

    /** Whether both are differences, so they compare by the factor only. */
    private boolean bothRelative(Quantity<?> that) {
        return relative() && that.getScale() == Quantity.Scale.RELATIVE;
    }

    private void requireCompatible(UomUnit<Q> thatUnit) {
        if (!unit.getDimension().equals(thatUnit.getDimension())) {
            throw new UnconvertibleException("incompatible dimensions: " + unit + " and " + thatUnit);
        }
    }

    private static <Q extends Quantity<Q>> UomUnit<Q> uomUnit(Unit<Q> unit) {
        if (Objects.requireNonNull(unit, "unit") instanceof UomUnit<Q> u) {
            return u;
        }
        throw new UnconvertibleException("foreign unit implementation: " + unit.getClass().getName());
    }

    /** Same class, unit and scale and a numerically equal value ({@code 1.0 m} equals {@code 1.00 m}). */
    @Override
    public boolean equals(Object o) {
        return o instanceof AbstractDecimalQuantity<?, ?> q && getClass() == q.getClass()
                && value.compareTo(q.value) == 0 && unit.equals(q.unit) && scale == q.scale;
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(unit, scale) + value.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        String symbol = unit.getSymbol();
        String text = symbol != null ? symbol : unit.toString();
        // A dimensionless number is written alone, as UomQuantityFormat parses it.
        return text.equals("1") ? String.valueOf(value) : value + " " + text;
    }
}
