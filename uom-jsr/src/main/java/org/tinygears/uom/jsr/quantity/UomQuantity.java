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

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.MathContext;
import java.util.Objects;
import javax.measure.Quantity;
import javax.measure.Unit;
import javax.measure.spi.SystemOfUnits;

import org.tinygears.uom.jsr.UomUnit;

/**
 * A quantity of this implementation: the JSR-385 {@link Quantity} with the return types narrowed to the typed
 * interface {@code R} ({@code Length.add} returns a {@code Length}), and the additions JSR-385 has no method for.
 * Every quantity this library creates implements it, typed ones through their interface ({@code Length},
 * {@code Speed}, ...) and the others through {@link Untyped}; the products and quotients of quantities and
 * {@code UomQuantityFormat.parse} return it, so its methods chain without casts. Code written against the standard
 * {@link Quantity} keeps working unchanged: nothing here changes what a JSR-385 method does.
 *
 * <p>The additions, and why they exist:
 * <ul>
 *   <li>{@link #as(Class)} narrows to a typed interface of this library; {@link #asType(Class)} cannot, because its
 *       type token must be a {@code Quantity<T>} of itself, which {@code Length} is not.</li>
 *   <li>{@link #isCloseTo(Quantity, double)} and {@link #isCloseTo(Quantity, Quantity)} compare within a tolerance
 *       the caller states; {@link #isEquivalentTo(Quantity)} has none for decimal quantities (exact) and a fixed one
 *       for {@code double} quantities.</li>
 *   <li>{@link #toSystem(SystemOfUnits)} converts to the unit a system prefers for the quantity type;
 *       {@link #to(Unit)} needs the unit.</li>
 *   <li>{@link #toDouble()} and {@link #toDecimal(MathContext)} change the number type, which JSR-385 leaves to the
 *       implementation.</li>
 * </ul>
 * Not meant to be implemented outside this library; methods may be added before 1.0.
 *
 * @param <Q> the standard quantity type, such as {@code javax.measure.quantity.Length}
 * @param <R> the typed interface of this implementation, such as {@code org.tinygears.uom.jsr.quantity.Length}
 */
public interface UomQuantity<Q extends Quantity<Q>, R extends UomQuantity<Q, R>>
        extends Quantity<Q>, Comparable<Quantity<Q>> {

    /** The unit, always one of this implementation. */
    @Override
    UomUnit<Q> getUnit();

    @Override
    R add(Quantity<Q> addend);

    @Override
    R subtract(Quantity<Q> subtrahend);

    @Override
    R to(Unit<Q> unit);

    @Override
    R negate();

    @Override
    R multiply(Number multiplicand);

    @Override
    R divide(Number divisor);

    /** The product, a quantity of this implementation (untyped, in the product of the system units). */
    @Override
    UomQuantity<?, ?> multiply(Quantity<?> multiplicand);

    /** The quotient, a quantity of this implementation (untyped, in the quotient of the system units). */
    @Override
    UomQuantity<?, ?> divide(Quantity<?> divisor);

    /** The reciprocal, a quantity of this implementation (untyped, in the inverse system unit). */
    @Override
    UomQuantity<?, ?> inverse();

    /**
     * Converts to the unit {@code system} gives for this quantity type ({@link SystemOfUnits#getUnit(Class)}),
     * keeping scale and context. JSR-385 has only {@link #to(Unit)}, which needs the unit itself.
     *
     * @param system the system of units
     * @return the converted quantity
     * @throws NullPointerException if {@code system} is null
     * @throws UnsupportedOperationException if {@code system} has no unit for this quantity type (the systems of this
     *     library have one for every standard type)
     * @throws javax.measure.UnconvertibleException if the unit of {@code system} is of another implementation
     */
    R toSystem(SystemOfUnits system);

    /**
     * The same quantity as a {@code double}; a value outside the double range throws ArithmeticException. JSR-385
     * has no number-type conversion.
     *
     * @return the double quantity
     */
    R toDouble();

    /**
     * The same quantity as a decimal rounded to {@code mc}, which becomes its context; a precision over 50000
     * throws ArithmeticException. JSR-385 has no number-type conversion.
     *
     * @param mc the math context of the result
     * @return the decimal quantity
     */
    R toDecimal(MathContext mc);

    /**
     * Whether the two amounts differ by at most {@code relativeTolerance} times the larger of their magnitudes,
     * compared in the system unit: {@code |a - b| <= relativeTolerance * max(|a|, |b|)}, inclusive and symmetric,
     * with offsets taken into account (two {@code RELATIVE} quantities compare as differences, as in
     * {@link #compareTo}). A decimal comparison is exact; a {@code double} one is computed in {@code double}.
     *
     * <p>Unlike {@link #isEquivalentTo(Quantity)}, the standard method, which compares decimal quantities exactly
     * and {@code double} quantities within a fixed {@code 1e-12}, the caller chooses the slack, for values rounded on
     * their way or measured. Near zero a relative tolerance accepts almost nothing (zero is close only to zero);
     * use {@link #isCloseTo(Quantity, Quantity)} there. Not transitive. NaN is close to nothing.
     *
     * @param that the other quantity
     * @param relativeTolerance the relative tolerance, finite and not negative ({@code 1e-9} is nine digits)
     * @return true if the quantities are within the tolerance
     * @throws NullPointerException if {@code that} is null
     * @throws IllegalArgumentException if {@code relativeTolerance} is negative, NaN or infinite
     * @throws javax.measure.UnconvertibleException if {@code that} has another dimension or a unit of another
     *     implementation
     */
    boolean isCloseTo(Quantity<Q> that, double relativeTolerance);

    /**
     * Whether the two amounts differ by at most {@code absoluteTolerance}, compared in the system unit:
     * {@code |a - b| <= |t|}, inclusive and symmetric, with offsets taken into account for the two amounts (two
     * {@code RELATIVE} quantities compare as differences, as in {@link #compareTo}). The tolerance is a difference,
     * so it converts by the factor only: {@code 1 °C} of tolerance is {@code 1 K}. A decimal comparison is exact; a
     * {@code double} one is computed in {@code double}.
     *
     * <p>Unlike {@link #isEquivalentTo(Quantity)}, the standard method, the caller chooses the slack, in a unit,
     * which also works near zero. Not transitive. NaN is close to nothing.
     *
     * @param that the other quantity
     * @param absoluteTolerance the largest difference allowed, not negative in its own unit
     * @return true if the quantities are within the tolerance
     * @throws NullPointerException if {@code that} or {@code absoluteTolerance} is null
     * @throws IllegalArgumentException if the tolerance's value is negative, NaN or infinite
     * @throws javax.measure.UnconvertibleException if {@code that} or the tolerance has another dimension or a unit
     *     of another implementation
     */
    boolean isCloseTo(Quantity<Q> that, Quantity<Q> absoluteTolerance);

    /**
     * This quantity as the typed interface {@code type} of this library, such as {@code Speed} for the quotient of
     * a length and a time: this quantity if it already is one, else a quantity of {@code type} with the same value,
     * unit, scale and context, created as {@link #asType(Class)} creates it (by dimension).
     *
     * <p>{@link #asType(Class)}, the standard method, takes the standard interface ({@code
     * javax.measure.quantity.Speed}) and returns a {@code Quantity<Speed>}, so getting this library's {@code Speed}
     * needs a cast; it cannot take our {@code Speed}, which is not a {@code Quantity} of itself. For a quantity whose
     * static type is the standard {@code Quantity<?>}, such as one from another API, the typed interfaces offer
     * {@code Speed.from(quantity)}.
     *
     * @param <T> the typed interface
     * @param type the typed interface, such as {@code org.tinygears.uom.jsr.quantity.Speed.class}
     * @return the quantity as {@code type}
     * @throws NullPointerException if {@code type} is null
     * @throws IllegalArgumentException if {@code type} does not name its standard type, as a typed interface does
     * @throws ClassCastException if the dimension of the unit is not the one of {@code type}, or the provider creates
     *     a quantity that is not a {@code type}
     * @throws UnsupportedOperationException if the provider has no factory for the type
     */
    // asType takes the standard type S of T, known only at run time (standardType).
    @SuppressWarnings("unchecked")
    default <T extends UomQuantity<?, T>> T as(Class<T> type) {
        Objects.requireNonNull(type, "type");
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        Quantity<?> narrowed = asType(standardType(type));
        if (!type.isInstance(narrowed)) {
            throw new ClassCastException(narrowed.getClass().getName() + " is not a " + type.getName());
        }
        return type.cast(narrowed);
    }

    /** The {@code S} of {@code type}'s {@code UomQuantity<S, type>}, raw: the type token asType takes. */
    @SuppressWarnings("rawtypes") // S is recorded in the class file, generics cannot express it here
    private static Class standardType(Class<?> type) {
        Type standard = standardOf(type);
        if (!(standard instanceof Class<?> c)) {
            throw new IllegalArgumentException(type.getName() + " does not name its standard quantity type");
        }
        return c;
    }

    private static Type standardOf(Class<?> type) {
        for (Type t : type.getGenericInterfaces()) {
            if (t instanceof ParameterizedType p && p.getRawType() == UomQuantity.class) {
                return p.getActualTypeArguments()[0];
            }
            Type found = t instanceof Class<?> c
                    ? standardOf(c)
                    : t instanceof ParameterizedType p && p.getRawType() instanceof Class<?> c ? standardOf(c) : null;
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
