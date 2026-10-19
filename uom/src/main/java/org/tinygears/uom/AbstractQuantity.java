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

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

import org.tinygears.uom.engine.Converter;
import org.tinygears.uom.engine.arithmetic.DecimalArithmetic;
import org.tinygears.uom.engine.arithmetic.DoubleArithmetic;
import org.tinygears.uom.engine.arithmetic.Precision;
import org.tinygears.uom.quantity.QuantityTypes;

/**
 * Base class of the quantities: a {@code double}, or a decimal with its context, a unit and a scale; the arithmetic
 * is the engine's (see {@link Quantity} for the rules). The generated classes extend it; not meant to be extended
 * outside this library. Serializable through a proxy: type name, unit, scale and the exact value, rebuilt through
 * the type's factory when read.
 *
 * @param <Q> the quantity type
 */
public abstract class AbstractQuantity<Q extends Quantity<Q>> implements Quantity<Q>, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** The double value; 0 for a decimal quantity. */
    private final double value;
    /** The decimal value; null for a double quantity. */
    private final BigDecimal decimal;
    /** The context of a decimal quantity; null for a double one. */
    private final MathContext mathContext;
    /** The unit. */
    private final Unit<Q> unit;
    /** The scale. */
    private final Scale scale;

    /**
     * A {@code double} quantity if {@code decimal} is null, else a decimal one; the decimal's scale is bounded by
     * 100000, its digits and the context's precision by 50000 ({@code ArithmeticException}).
     *
     * @param value the double value, ignored for a decimal
     * @param decimal the decimal value, or null
     * @param mathContext the context of a decimal, ignored for a double
     * @param unit the unit
     * @param scale the scale
     */
    protected AbstractQuantity(double value, BigDecimal decimal, MathContext mathContext, Unit<Q> unit, Scale scale) {
        this.value = decimal == null ? value : 0;
        this.decimal = decimal == null ? null : Precision.checked(decimal);
        this.mathContext = decimal == null
                ? null
                : Precision.checked(Objects.requireNonNull(mathContext, "mathContext"));
        this.unit = Objects.requireNonNull(unit, "unit");
        this.scale = Objects.requireNonNull(scale, "scale");
    }

    /**
     * A quantity of this type, as the constructor takes it.
     *
     * @param value the double value
     * @param decimal the decimal value, or null
     * @param mc the context of a decimal
     * @param unit the unit
     * @param scale the scale
     * @return the quantity
     */
    protected abstract Q create(double value, BigDecimal decimal, MathContext mc, Unit<Q> unit, Scale scale);

    /**
     * Creates quantities of a type, as its constructor; used by the generated types and {@link QuantityTypes}.
     *
     * @param <R> the quantity type
     */
    @FunctionalInterface
    public interface Factory<R extends Quantity<R>> {
        /**
         * A quantity.
         *
         * @param value the double value
         * @param decimal the decimal value, or null
         * @param mc the context of a decimal
         * @param unit the unit
         * @param scale the scale
         * @return the quantity
         */
        R create(double value, BigDecimal decimal, MathContext mc, Unit<R> unit, Scale scale);
    }

    @Override
    public final Number value() {
        return decimal != null ? decimal : (Number) value;
    }

    @Override
    public final MathContext mathContext() {
        return mathContext;
    }

    @Override
    public final Unit<Q> unit() {
        return unit;
    }

    @Override
    public final Scale scale() {
        return scale;
    }

    @Override
    public Q to(Unit<Q> target) {
        Converter c = unit.engineConverterTo(target);
        if (decimal == null) {
            return create(DoubleArithmetic.convert(value, c, relative()), null, null, target, scale);
        }
        return create(0, DecimalArithmetic.convert(decimal, c, relative(), mathContext), mathContext, target, scale);
    }

    @Override
    public Q toSystem(SystemOfUnits system) {
        Objects.requireNonNull(system, "system");
        Unit<Q> target = system.unit(unit.type());
        if (target == null) {
            throw new UnsupportedOperationException(
                    "system " + system.name() + " has no unit for type " + unit.type().getSimpleName());
        }
        return to(target);
    }

    @Override
    public Q add(Q other) {
        return combine(other, false);
    }

    @Override
    public Q subtract(Q other) {
        return combine(other, true);
    }

    private Q combine(Q other, boolean subtract) {
        Objects.requireNonNull(other, "other");
        Unit<Q> u = other.unit();
        boolean delta = relative() || other.scale() == Scale.RELATIVE;
        Scale result = relative() && other.scale() == Scale.RELATIVE ? Scale.RELATIVE : Scale.ABSOLUTE;
        if (!isDecimal(other)) {
            double r = DoubleArithmetic.combine(
                    value,
                    unit.engine(),
                    other.value().doubleValue(),
                    u.engine(),
                    u.engineConverterTo(unit),
                    delta,
                    subtract);
            return create(r, null, null, unit, result);
        }
        // the exact rule needs no converter, so irrational offsets that no exact converter adds up work too
        requireCompatible(u);
        MathContext mc = contextWith(other);
        BigDecimal r = DecimalArithmetic
                .combine(decimal(), unit.engine(), Precision.toDecimal(other.value()), u.engine(), delta, subtract, mc);
        return create(0, r, mc, unit, result);
    }

    @Override
    public Q negate() {
        return decimal == null
                ? create(-value, null, null, unit, scale)
                : create(0, decimal.negate(), mathContext, unit, scale);
    }

    @Override
    public Q multiply(Number factor) {
        return scalar(factor, false);
    }

    @Override
    public Q divide(Number divisor) {
        return scalar(divisor, true);
    }

    private Q scalar(Number n, boolean divide) {
        Objects.requireNonNull(n, "number");
        if (decimal == null) {
            double r = DoubleArithmetic.scalar(value, unit.engine(), relative(), Precision.toDouble(n), divide);
            return create(r, null, null, unit, scale);
        }
        BigDecimal r = DecimalArithmetic
                .scalar(decimal, unit.engine(), relative(), Precision.toDecimal(n), divide, mathContext);
        return create(0, r, mathContext, unit, scale);
    }

    @Override
    public Untyped multiply(Quantity<?> other) {
        return product(
                other,
                false,
                Unit.of(Untyped.class, unit.engine().systemProduct(other.unit().engine())),
                UntypedValue::new);
    }

    @Override
    public Untyped divide(Quantity<?> other) {
        return product(
                other,
                true,
                Unit.of(Untyped.class, unit.engine().systemQuotient(other.unit().engine())),
                UntypedValue::new);
    }

    @Override
    public Untyped inverse() {
        Unit<Untyped> u = Unit.of(Untyped.class, unit.engine().systemInverse());
        if (decimal == null) {
            return new UntypedValue(
                    1 / DoubleArithmetic.systemValue(value, unit.engine(), relative()),
                    null,
                    null,
                    u,
                    Scale.ABSOLUTE);
        }
        BigDecimal r = DecimalArithmetic.inverse(decimal, unit.engine(), relative(), mathContext);
        return new UntypedValue(0, r, mathContext, u, Scale.ABSOLUTE);
    }

    /**
     * {@code sys(this)·sys(other)} or {@code sys(this)/sys(other)} as an {@code ABSOLUTE} quantity of {@code unit},
     * the system unit of the result; decimal if either operand is.
     *
     * @param <R> the result type
     * @param other the other quantity
     * @param divide whether to divide
     * @param unit the unit of the result
     * @param type creates the result
     * @return the product or quotient
     */
    protected final <R extends Quantity<R>> R product(
            Quantity<?> other,
            boolean divide,
            Unit<R> unit,
            Factory<R> type
    ) {
        Objects.requireNonNull(other, "other");
        boolean otherRelative = other.scale() == Scale.RELATIVE;
        if (!isDecimal(other)) {
            double r = DoubleArithmetic.product(
                    value,
                    this.unit.engine(),
                    relative(),
                    other.value().doubleValue(),
                    other.unit().engine(),
                    otherRelative,
                    divide);
            return type.create(r, null, null, unit, Scale.ABSOLUTE);
        }
        MathContext mc = contextWith(other);
        BigDecimal r = DecimalArithmetic.product(
                decimal(),
                this.unit.engine(),
                relative(),
                Precision.toDecimal(other.value()),
                other.unit().engine(),
                otherRelative,
                divide,
                mc);
        return type.create(0, r, mc, unit, Scale.ABSOLUTE);
    }

    @Override
    public <T extends Quantity<T>> T as(Class<T> type) {
        Objects.requireNonNull(type, "type");
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        Unit<T> u = named(unit.asType(type), type);
        Factory<T> f = type == Untyped.class ? untyped() : QuantityTypes.factory(type);
        return f.create(value, decimal, mathContext, u, scale);
    }

    // An equal unit takes the label of the system unit (J for kg·m^2/s^2); an equivalent one (1/s for Hz) is another unit.
    @SuppressWarnings("unchecked") // the system unit of type is a Unit<T>
    private static <T extends Quantity<T>> Unit<T> named(Unit<T> u, Class<T> type) {
        Unit<?> system = QuantityTypes.systemUnit(type);
        return system != null && system.equals(u) ? (Unit<T>) system : u;
    }

    // T is Untyped, so the factory of UntypedValue is one of T.
    @SuppressWarnings("unchecked")
    private static <T extends Quantity<T>> Factory<T> untyped() {
        Factory<Untyped> f = UntypedValue::new;
        return (Factory<T>) (Factory<?>) f;
    }

    @Override
    public Q toDouble() {
        return create(doubleValue(), null, null, unit, scale);
    }

    @Override
    public Q toDecimal(MathContext mc) {
        Objects.requireNonNull(mc, "mc");
        return create(0, decimal().round(mc), mc, unit, scale);
    }

    @Override
    public boolean isEquivalentTo(Q other) {
        Objects.requireNonNull(other, "other");
        if (!unit.isCompatible(other.unit())) {
            return false;
        }
        Converter c = other.unit().engineConverterTo(unit);
        boolean deltas = bothRelative(other);
        Number n = other.value();
        if (decimal != null && Precision.kind(n) == Precision.Kind.DECIMAL) {
            return compareTo(other) == 0;
        }
        if (decimal == null && Precision.kind(n) != Precision.Kind.DECIMAL) {
            return DoubleArithmetic.equivalent(value, DoubleArithmetic.convert(n.doubleValue(), c, deltas));
        }
        // one double: its tolerance, compared in decimal; NaN and infinities are equivalent to no decimal
        if (!Double.isFinite(decimal == null ? value : n.doubleValue())) {
            return false;
        }
        BigDecimal b = DecimalArithmetic.convert(Precision.toDecimal(n), c, deltas, MathContext.DECIMAL128);
        return Precision.equivalentAsDoubles(decimal(), b);
    }

    @Override
    public boolean isCloseTo(Q other, double relativeTolerance) {
        Precision.checkRelativeTolerance(relativeTolerance);
        Objects.requireNonNull(other, "other");
        requireCompatible(other.unit());
        return closeTo(other, relativeTolerance, null);
    }

    @Override
    public boolean isCloseTo(Q other, Q absoluteTolerance) {
        Objects.requireNonNull(other, "other");
        Objects.requireNonNull(absoluteTolerance, "absoluteTolerance");
        requireCompatible(absoluteTolerance.unit());
        Precision.checkAbsoluteTolerance(absoluteTolerance.value());
        requireCompatible(other.unit());
        return closeTo(other, 0, absoluteTolerance);
    }

    private boolean closeTo(Q other, double relative, Q absolute) {
        Number n = other.value();
        boolean deltas = bothRelative(other);
        if (decimalPath(other)) {
            if (!finite(n)) {
                return false;
            }
            BigDecimal b = Precision.toDecimal(n);
            BigDecimal t = absolute == null ? null : Precision.toDecimal(absolute.value());
            return DecimalArithmetic.isCloseTo(
                    decimal(),
                    unit.engine(),
                    b,
                    other.unit().engine(),
                    deltas,
                    absolute == null ? BigDecimal.valueOf(relative) : null,
                    t,
                    absolute == null ? null : absolute.unit().engine(),
                    contextWith(other));
        }
        double b = Precision.toDouble(n);
        return absolute == null
                ? DoubleArithmetic.isCloseTo(value, unit.engine(), b, other.unit().engine(), deltas, relative)
                : DoubleArithmetic.isCloseTo(
                        value,
                        unit.engine(),
                        b,
                        other.unit().engine(),
                        deltas,
                        Precision.toDouble(absolute.value()),
                        absolute.unit().engine());
    }

    /**
     * Compares the amounts in the system unit, exactly if either is decimal; two {@code RELATIVE} quantities as
     * differences. NaN and infinities keep the order of {@link Double#compare}. Not consistent with equals.
     *
     * @throws UnitException if the dimensions differ
     */
    @Override
    public int compareTo(Q other) {
        Objects.requireNonNull(other, "other");
        requireCompatible(other.unit());
        Number n = other.value();
        boolean deltas = bothRelative(other);
        if (decimalPath(other) && finite(n)) {
            return DecimalArithmetic.compare(
                    decimal(),
                    unit.engine(),
                    Precision.toDecimal(n),
                    other.unit().engine(),
                    deltas,
                    contextWith(other));
        }
        return DoubleArithmetic
                .compare(doubleValue(), unit.engine(), Precision.toDouble(n), other.unit().engine(), deltas);
    }

    private void requireCompatible(Unit<?> u) {
        if (!unit.isCompatible(u)) {
            throw new UnitException("incompatible dimensions: " + unit + " and " + u);
        }
    }

    private boolean relative() {
        return scale == Scale.RELATIVE;
    }

    private boolean bothRelative(Quantity<?> other) {
        return relative() && other.scale() == Scale.RELATIVE;
    }

    private boolean isDecimal(Quantity<?> other) {
        return decimal != null || other.mathContext() != null;
    }

    /** Whether a comparison with {@code other} is decimal: either is, and this value is finite. */
    private boolean decimalPath(Quantity<?> other) {
        return isDecimal(other) && (decimal != null || Double.isFinite(value));
    }

    /** False for a NaN or infinite {@code double}; a decimal is finite whatever its magnitude. */
    private static boolean finite(Number n) {
        return Precision.kind(n) != Precision.Kind.DOUBLE || Double.isFinite(n.doubleValue());
    }

    private double doubleValue() {
        return decimal == null ? value : Precision.toDouble(decimal);
    }

    /** This value as a decimal: a double as its shortest decimal; NaN and infinities throw. */
    private BigDecimal decimal() {
        return decimal != null ? decimal : Precision.toDecimal(value);
    }

    /** The lower context of the two; a {@code double} operand adopts the other's. */
    private MathContext contextWith(Quantity<?> other) {
        MathContext o = other.mathContext();
        if (mathContext == null || o == null) {
            return mathContext != null ? mathContext : o;
        }
        return Precision.lower(mathContext, o);
    }

    /**
     * The serialization proxy.
     *
     * @return the proxy
     * @throws ObjectStreamException if the decimal value is too large to write
     */
    @Serial
    protected final Object writeReplace() throws ObjectStreamException {
        return new SerialProxy.OfQuantity(this);
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

    /**
     * Same class, unit, scale and number type, and an equal value: decimals numerically ({@code 1.0 m} equals
     * {@code 1.00 m}), doubles by {@link Double#compare}.
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof AbstractQuantity<?> q) || getClass() != q.getClass() || !unit.equals(q.unit)
                || scale != q.scale) {
            return false;
        }
        return decimal == null
                ? q.decimal == null && Double.compare(value, q.value) == 0
                : q.decimal != null && decimal.compareTo(q.decimal) == 0;
    }

    @Override
    public int hashCode() {
        int v = decimal == null ? Double.hashCode(value) : decimal.stripTrailingZeros().hashCode();
        return 31 * Objects.hash(unit, scale) + v;
    }

    /** The value and the unit's symbol ({@code 1.5 km}); a dimensionless value alone. */
    @Override
    public String toString() {
        String u = unit.toString();
        return u.equals("1") ? String.valueOf(value()) : value() + " " + u;
    }
}
