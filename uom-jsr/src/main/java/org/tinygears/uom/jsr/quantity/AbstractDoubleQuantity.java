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
 * Base class of the generated {@code double} quantities; {@code R} is the typed interface returned by
 * conversions. A quantity operand holding a {@link BigDecimal} (of {@code add}, {@code subtract},
 * {@code multiply}, {@code divide} and {@code compareTo}) makes the operation decimal: this value is read as its
 * shortest decimal ({@link BigDecimal#valueOf(double)}) with the operand's context, and the result is a decimal
 * quantity; a NaN or infinite value then throws {@link ArithmeticException}, except in {@code compareTo}.
 * {@code isEquivalentTo} and scalar arguments stay {@code double}: a {@link BigDecimal} or
 * {@link java.math.BigInteger} outside the {@code double} range throws {@link ArithmeticException} there. Immutable.
 * <p>
 * Serializable through a proxy (see {@link #writeReplace()}); the serialized form may change before 1.0. Reading
 * rebuilds the quantity through the factory of the provider current at read time, then restores the exact value and
 * scale: a user subclass, or a quantity read under another top-priority provider, comes back as that provider's
 * class (so it no longer equals the original), and a provider without factories for the standard types makes typed
 * reads fail.
 *
 * @param <Q> the quantity type
 * @param <R> the typed interface returned by conversions
 */
public abstract class AbstractDoubleQuantity<Q extends Quantity<Q>, R extends UomQuantity<Q, R>>
        implements UomQuantity<Q, R>, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Relative tolerance of {@link #isEquivalentTo}. */
    public static final double EQUIVALENCE_TOLERANCE = DoubleArithmetic.EQUIVALENCE_TOLERANCE;

    /** The value. */
    private final double value;
    /** The unit. */
    private final UomUnit<Q> unit;
    /** The scale. */
    private final Quantity.Scale scale;

    /**
     * A quantity.
     *
     * @param value the value
     * @param unit the unit
     * @param scale the scale
     */
    protected AbstractDoubleQuantity(double value, UomUnit<Q> unit, Quantity.Scale scale) {
        this.value = value;
        this.unit = Objects.requireNonNull(unit, "unit");
        this.scale = Objects.requireNonNull(scale, "scale");
    }

    /**
     * Creates a {@code double} quantity of the same typed interface.
     *
     * @param value the value
     * @param unit the unit
     * @param scale the scale
     * @return the quantity
     */
    protected abstract R create(double value, UomUnit<Q> unit, Quantity.Scale scale);

    /**
     * Creates the {@link BigDecimal} quantity of the same typed interface.
     *
     * @param value the value
     * @param unit the unit
     * @param scale the scale
     * @param mc the context
     * @return the quantity
     */
    protected abstract R createDecimal(BigDecimal value, UomUnit<Q> unit, Quantity.Scale scale, MathContext mc);

    /**
     * The standard quantity type of this class, looked up by {@link #toSystem(SystemOfUnits)}; {@link Untyped} if none.
     *
     * @return the quantity type
     */
    protected abstract Class<Q> quantityType();

    @Override
    public final Double getValue() {
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
     * Converts to {@code unit}; an {@code ABSOLUTE} quantity converts through the offsets, a {@code RELATIVE} one (a
     * difference) by the factor only, and the scale is kept (docs/OFFSET-UNITS.md).
     */
    @Override
    public R to(Unit<Q> unit) {
        UomUnit<Q> target = uomUnit(unit);
        return create(DoubleArithmetic.convert(value, Engine.converter(this.unit, target), relative()), target, scale);
    }

    /**
     * Converts to the unit {@code system} gives for {@link #quantityType()} as {@link #to(Unit)} does, so
     * the scale is kept; a foreign unit or one of another dimension throws {@link UnconvertibleException}. A system
     * without a unit for the type ({@code getUnit} returns null) throws {@link UnsupportedOperationException}.
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

    /**
     * The same quantity: an equal {@code double} quantity.
     *
     * @return the double quantity
     */
    public R toDouble() {
        return create(value, unit, scale);
    }

    /**
     * The same quantity as a decimal: the value as written ({@link BigDecimal#valueOf(double)}), rounded to
     * {@code mc}, which becomes its context. NaN, infinity or a precision of {@code mc} over 50000 throws
     * {@link ArithmeticException}.
     *
     * @param mc the context of the result
     * @return the decimal quantity
     */
    public R toDecimal(MathContext mc) {
        return createDecimal(Precision.toDecimal(value).round(mc), unit, scale, mc);
    }

    /**
     * Converts {@code that} into this unit and compares within the relative {@link #EQUIVALENCE_TOLERANCE}, which
     * is asymmetric near zero for offset units. Two {@code RELATIVE} quantities are compared as differences (factor
     * only, no offset), otherwise as points. NaN is equivalent to nothing; incompatible dimensions give false
     * (compareTo throws). A decimal operand is compared in decimal with the same tolerance, so a value outside the
     * {@code double} range is not equivalent to this one; a NaN or infinite value is equivalent to no decimal.
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
        org.tinygears.uom.engine.Converter c = Engine.converter(thatUnit, unit);
        boolean deltas = bothRelative(that);
        if (Precision.kind(that.getValue()) == Precision.Kind.DECIMAL) {
            if (!Double.isFinite(value)) {
                return false;
            }
            BigDecimal b = DecimalArithmetic
                    .convert(Precision.toDecimal(that.getValue()), c, deltas, MathContext.DECIMAL128);
            return Precision.equivalentAsDoubles(Precision.toDecimal(value), b);
        }
        return DoubleArithmetic
                .equivalent(value, DoubleArithmetic.convert(Precision.toDouble(that.getValue()), c, deltas));
    }

    /**
     * {@inheritDoc} Against a decimal {@code that} the comparison is decimal, as in {@link #compareTo}, unless this
     * value is NaN or infinite.
     */
    @Override
    public boolean isCloseTo(Quantity<Q> that, double relativeTolerance) {
        Precision.checkRelativeTolerance(relativeTolerance);
        Objects.requireNonNull(that, "that");
        UomUnit<Q> thatUnit = compatible(that.getUnit());
        if (promotes(that) && Double.isFinite(value)) {
            return promoted(that).isCloseTo(that, relativeTolerance);
        }
        return DoubleArithmetic.isCloseTo(
                value,
                unit.engine(),
                Precision.toDouble(that.getValue()),
                thatUnit.engine(),
                bothRelative(that),
                relativeTolerance);
    }

    /**
     * {@inheritDoc} Against a decimal {@code that} the comparison is decimal, as in {@link #compareTo}, unless this
     * value is NaN or infinite.
     */
    @Override
    public boolean isCloseTo(Quantity<Q> that, Quantity<Q> absoluteTolerance) {
        Objects.requireNonNull(that, "that");
        Objects.requireNonNull(absoluteTolerance, "absoluteTolerance");
        UomUnit<Q> toleranceUnit = compatible(absoluteTolerance.getUnit());
        Precision.checkAbsoluteTolerance(absoluteTolerance.getValue());
        UomUnit<Q> thatUnit = compatible(that.getUnit());
        if (promotes(that) && Double.isFinite(value)) {
            return promoted(that).isCloseTo(that, absoluteTolerance);
        }
        return DoubleArithmetic.isCloseTo(
                value,
                unit.engine(),
                Precision.toDouble(that.getValue()),
                thatUnit.engine(),
                bothRelative(that),
                Precision.toDouble(absoluteTolerance.getValue()),
                toleranceUnit.engine());
    }

    /** {@code u} if it has this dimension; throws as {@link #compareTo} does otherwise. */
    private UomUnit<Q> compatible(Unit<Q> u) {
        UomUnit<Q> other = uomUnit(u);
        if (!unit.getDimension().equals(other.getDimension())) {
            throw new UnconvertibleException("incompatible dimensions: " + unit + " and " + other);
        }
        return other;
    }

    /**
     * Compares the system-unit values with {@link Double#compare}, or, if both are {@code RELATIVE}, the system-unit
     * differences (factor only); not consistent with equals. {@code Double.compare} puts {@code -0.0} below
     * {@code 0.0}, so {@code 0 m} is greater than {@code -0 m}, while {@code 0 °C} and {@code -0 °C} compare equal:
     * their system-unit values are both 273.15 K. Against a decimal operand the comparison is decimal, as in the
     * decimal quantity, unless this value is NaN or infinite.
     *
     * @throws NullPointerException if {@code that} is null
     */
    @Override
    public int compareTo(Quantity<Q> that) {
        Objects.requireNonNull(that, "that");
        UomUnit<Q> thatUnit = uomUnit(that.getUnit());
        if (!unit.getDimension().equals(thatUnit.getDimension())) {
            throw new UnconvertibleException("incompatible dimensions: " + unit + " and " + thatUnit);
        }
        // NaN and infinities have no decimal value; they keep the Double.compare order.
        if (promotes(that) && Double.isFinite(value)) {
            // The decimal quantities are Comparable to their quantity type.
            @SuppressWarnings("unchecked")
            Comparable<Quantity<Q>> d = (Comparable<Quantity<Q>>) promoted(that);
            return d.compareTo(that);
        }
        return DoubleArithmetic.compare(
                value,
                unit.engine(),
                Precision.toDouble(that.getValue()),
                thatUnit.engine(),
                bothRelative(that));
    }

    /**
     * Adds as if both operands were in the system unit, with the result back in this unit; linear units skip the
     * system round trip. If either operand is {@code RELATIVE}, {@code addend} is a difference: it is converted by
     * the factor only and added to this value in this unit. The result is {@code RELATIVE} only if both operands
     * are, otherwise {@code ABSOLUTE} (docs/OFFSET-UNITS.md). A decimal {@code addend} gives a decimal result (see
     * the class comment). Throws {@link UnconvertibleException} for a foreign unit or incompatible dimensions.
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

    /** Returns a quantity whose value is {@code -getValue()}, in this unit and scale. */
    @Override
    public R negate() {
        return create(-value, unit, scale);
    }

    /**
     * Multiplies by {@code multiplicand} as a {@code double}; an offset unit goes through the system unit, unless
     * this quantity is {@code RELATIVE}, which is scaled as a plain value. A {@link BigDecimal} or
     * {@link java.math.BigInteger} outside the {@code double} range throws {@link ArithmeticException}.
     *
     * @throws NullPointerException if {@code multiplicand} is null
     */
    @Override
    public R multiply(Number multiplicand) {
        Objects.requireNonNull(multiplicand, "multiplicand");
        return scalar(Precision.toDouble(multiplicand), false);
    }

    /**
     * Divides by {@code divisor} as {@link #multiply(Number)}; division by zero follows IEEE 754.
     *
     * @throws NullPointerException if {@code divisor} is null
     */
    @Override
    public R divide(Number divisor) {
        Objects.requireNonNull(divisor, "divisor");
        return scalar(Precision.toDouble(divisor), true);
    }

    /**
     * Multiplies the system-unit values (offsets count, except for a {@code RELATIVE} operand, which is a difference
     * and counts by its factor only); the result is an {@link Untyped} quantity in the product of the system units,
     * scale {@code ABSOLUTE}, decimal for a decimal {@code multiplicand}. Throws {@link UnconvertibleException} for a
     * foreign unit.
     *
     * @throws NullPointerException if {@code multiplicand} is null
     */
    @Override
    public UomQuantity<?, ?> multiply(Quantity<?> multiplicand) {
        Objects.requireNonNull(multiplicand, "multiplicand");
        UomUnit<?> u = uomUnit(multiplicand.getUnit());
        if (promotes(multiplicand)) {
            return promoted(multiplicand).multiply(multiplicand);
        }
        return untyped(product(multiplicand, u, false), unit.engine().systemProduct(u.engine()));
    }

    /**
     * Divides by the same rule as {@link #multiply(Quantity)}; division by zero follows IEEE 754.
     *
     * @throws NullPointerException if {@code divisor} is null
     */
    @Override
    public UomQuantity<?, ?> divide(Quantity<?> divisor) {
        Objects.requireNonNull(divisor, "divisor");
        UomUnit<?> u = uomUnit(divisor.getUnit());
        if (promotes(divisor)) {
            return promoted(divisor).divide(divisor);
        }
        return untyped(product(divisor, u, true), unit.engine().systemQuotient(u.engine()));
    }

    /** Returns {@code 1 / systemValue} in the inverse system unit, as an {@link Untyped} quantity. */
    @Override
    public UomQuantity<?, ?> inverse() {
        return untyped(
                1 / DoubleArithmetic.systemValue(value, unit.engine(), relative()),
                unit.engine().systemInverse());
    }

    /**
     * Returns this quantity if it already is a {@code type}, otherwise one created by the provider's factory; a
     * unit whose dimension is not that of {@code type} gives {@link ClassCastException} (alternate units count by dimension), a type the provider has no
     * factory for gives {@link UnsupportedOperationException}.
     */
    @Override
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
        return factory.create(value, typed, scale);
    }

    /** A quantity of this class with the given value, unit and scale. */
    R with(double v, UomUnit<?> u, Quantity.Scale s) {
        return create(v, UomUnit.unchecked(u), s);
    }

    /**
     * Writes the type, unit, scale and raw value bits; reading creates the quantity through
     * the factory of this implementation's provider. A type outside the 33 standard quantity
     * interfaces and {@link Untyped} throws {@link java.io.NotSerializableException}.
     *
     * @return the proxy
     * @throws ObjectStreamException if the quantity cannot be serialized
     */
    @Serial
    protected final Object writeReplace() throws ObjectStreamException {
        return QuantityProxy.ofDouble(quantityType(), unit, scale, value);
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

    /** Whether {@code that} holds a {@link BigDecimal}, so the operation runs on the decimal quantity (F23). */
    private static boolean promotes(Quantity<?> that) {
        return Precision.kind(that.getValue()) == Precision.Kind.DECIMAL;
    }

    /**
     * This quantity as a decimal one with the context of {@code that} (unlimited for a foreign quantity), its value
     * read as the shortest decimal that gives this double ({@link BigDecimal#valueOf(double)}), unrounded. A NaN or
     * infinite value throws {@link ArithmeticException}.
     */
    private R promoted(Quantity<?> that) {
        MathContext mc = that instanceof AbstractDecimalQuantity<?, ?> d ? d.mathContext() : MathContext.UNLIMITED;
        return createDecimal(Precision.toDecimal(value), unit, scale, mc);
    }

    /** An untyped quantity in {@code systemUnit}, the unit the engine gives a product, quotient or inverse. */
    private static UomQuantity<?, ?> untyped(double value, org.tinygears.uom.engine.Unit systemUnit) {
        return new UntypedDoubleQuantity(value, UomUnit.unchecked(UomUnit.of(systemUnit)), Quantity.Scale.ABSOLUTE);
    }

    private double product(Quantity<?> that, UomUnit<?> u, boolean divide) {
        return DoubleArithmetic.product(
                value,
                unit.engine(),
                relative(),
                Precision.toDouble(that.getValue()),
                u.engine(),
                that.getScale() == Quantity.Scale.RELATIVE,
                divide);
    }

    private R scalar(double n, boolean divide) {
        return create(DoubleArithmetic.scalar(value, unit.engine(), relative(), n, divide), unit, scale);
    }

    @SuppressWarnings("unchecked")
    private R combine(Quantity<Q> that, boolean subtract) {
        UomUnit<Q> thatUnit = uomUnit(that.getUnit());
        if (promotes(that)) {
            // R is the typed interface, which the decimal quantity's add and subtract return.
            Quantity<Q> d = promoted(that);
            return (R) (subtract ? d.subtract(that) : d.add(that));
        }
        double r = DoubleArithmetic.combine(
                value,
                unit.engine(),
                Precision.toDouble(that.getValue()),
                thatUnit.engine(),
                Engine.converter(thatUnit, unit),
                relative() || that.getScale() == Quantity.Scale.RELATIVE,
                subtract);
        return create(r, unit, Engine.sumScale(scale, that.getScale()));
    }

    private boolean relative() {
        return scale == Quantity.Scale.RELATIVE;
    }

    /** Whether both are differences, so they compare by the factor only. */
    private boolean bothRelative(Quantity<?> that) {
        return relative() && that.getScale() == Quantity.Scale.RELATIVE;
    }

    private static <Q extends Quantity<Q>> UomUnit<Q> uomUnit(Unit<Q> unit) {
        if (Objects.requireNonNull(unit, "unit") instanceof UomUnit<Q> u) {
            return u;
        }
        throw new UnconvertibleException("foreign unit implementation: " + unit.getClass().getName());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AbstractDoubleQuantity<?, ?> q && getClass() == q.getClass()
                && Double.compare(value, q.value) == 0 && unit.equals(q.unit) && scale == q.scale;
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(unit, scale) + Double.hashCode(value);
    }

    @Override
    public String toString() {
        String symbol = unit.getSymbol();
        String text = symbol != null ? symbol : unit.toString();
        // A dimensionless number is written alone, as UomQuantityFormat parses it.
        return text.equals("1") ? String.valueOf(value) : value + " " + text;
    }
}
