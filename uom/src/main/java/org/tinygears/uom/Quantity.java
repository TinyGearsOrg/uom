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

/**
 * An amount of a quantity type {@code Q} in a unit of that type, as a {@code double} or a {@link BigDecimal}, with a
 * {@link Scale}. Unlike JSR-385's {@code Quantity<Q extends Quantity<Q>>}, {@code Q} is the typed interface itself
 * ({@code Length}), so operations take and return it and units are {@code Unit<Length>}.
 *
 * <p>The arithmetic is the engine's, shared with the JSR-385 quantities of uom, with the same rules:
 * <ul>
 *   <li>A decimal result is evaluated exactly and rounded once to the context; an operation with one decimal operand
 *       is decimal, the {@code double} read as its shortest decimal, and two decimals round to the lower context.
 *       Number arguments do not change the number type.</li>
 *   <li>Sums are in the unit of this quantity; if either operand is {@link Scale#RELATIVE}, the other is added as a
 *       difference, and the result is {@code RELATIVE} only if both are. Comparisons of two {@code RELATIVE}
 *       quantities compare differences.</li>
 *   <li>Products and quotients of quantities are in the system units, {@code ABSOLUTE}, with offsets applied to
 *       {@code ABSOLUTE} operands; a declared relation types them ({@code Length.divide(Time)} is a {@code Speed}),
 *       the others are {@link Untyped}.</li>
 * </ul>
 * Operations on units of different dimensions (possible with {@link Untyped}) throw
 * {@link UnitException}. Immutable.
 *
 * @param <Q> the quantity type
 */
public interface Quantity<Q extends Quantity<Q>> extends Comparable<Q> {

    /**
     * The value in {@link #unit()}: a {@link Double} or a {@link BigDecimal}.
     *
     * @return the value
     */
    Number value();

    /**
     * The context that rounds the results of a decimal quantity; null for a {@code double} one.
     *
     * @return the context, or null
     */
    MathContext mathContext();

    /**
     * The unit.
     *
     * @return the unit
     */
    Unit<Q> unit();

    /**
     * Whether this is a point or a difference.
     *
     * @return the scale
     */
    Scale scale();

    /**
     * The same amount in {@code unit}, a decimal one rounded to its context; the scale is kept, and a
     * {@code RELATIVE} quantity converts by the factor only.
     *
     * @param unit the unit
     * @return the converted quantity
     */
    Q to(Unit<Q> unit);

    /**
     * The same amount in the unit {@code system} prefers for this quantity's type, as {@link #to(Unit)}.
     *
     * @param system the system of units
     * @return the converted quantity
     * @throws UnsupportedOperationException if the system has no unit for the type (an {@link Untyped} quantity)
     */
    Q toSystem(SystemOfUnits system);

    /**
     * The sum, in this unit.
     *
     * @param other the other quantity
     * @return the sum
     */
    Q add(Q other);

    /**
     * The difference, in this unit.
     *
     * @param other the other quantity
     * @return the difference
     */
    Q subtract(Q other);

    /**
     * The quantity with the negated value, in this unit and scale.
     *
     * @return the negation
     */
    Q negate();

    /**
     * This amount times a number, in the number type of this quantity; an offset unit goes through the system unit
     * unless this quantity is {@code RELATIVE}.
     *
     * @param factor the number
     * @return the product
     */
    Q multiply(Number factor);

    /**
     * This amount divided by a number, as {@link #multiply(Number)}; a decimal division by zero throws
     * {@link ArithmeticException}.
     *
     * @param divisor the number
     * @return the quotient
     */
    Q divide(Number divisor);

    /**
     * The product with a quantity without a declared relation: untyped, in the product of the system units.
     *
     * @param other the other quantity
     * @return the product
     */
    Untyped multiply(Quantity<?> other);

    /**
     * The quotient by a quantity without a declared relation: untyped, in the quotient of the system units.
     *
     * @param other the other quantity
     * @return the quotient
     */
    Untyped divide(Quantity<?> other);

    /**
     * The reciprocal: untyped, in the inverse system unit.
     *
     * @return the reciprocal
     */
    Untyped inverse();

    /**
     * This quantity as the quantity type {@code type}: itself if it is one, else one with the same value, unit, scale
     * and context. A unit equal to the system unit of {@code type} is replaced by it, so a product labelled
     * {@code kg·m^2/s^2} becomes {@code J} as an {@code Energy}.
     *
     * @param <T> the quantity type
     * @param type the quantity type, such as {@code Speed.class}
     * @return the quantity as {@code type}
     * @throws IllegalArgumentException if the unit's dimension is not the one of {@code type}
     */
    <T extends Quantity<T>> T as(Class<T> type);

    /**
     * The same quantity as a {@code double}; a decimal outside the double range throws {@link ArithmeticException}.
     *
     * @return the double quantity
     */
    Q toDouble();

    /**
     * The same quantity as a decimal rounded to {@code mc}, which becomes its context.
     *
     * @param mc the context
     * @return the decimal quantity
     */
    Q toDecimal(MathContext mc);

    /**
     * Whether both measure the same amount: two decimals exactly, otherwise within a relative {@code 1e-12}, the
     * tolerance of a {@code double}. Different dimensions give false.
     *
     * @param other the other quantity
     * @return true if equivalent
     */
    boolean isEquivalentTo(Q other);

    /**
     * Whether the amounts differ by at most {@code relativeTolerance} times the larger, in the system unit; exact if
     * either is decimal.
     *
     * @param other the other quantity
     * @param relativeTolerance the relative tolerance, finite and not negative
     * @return true if close
     */
    boolean isCloseTo(Q other, double relativeTolerance);

    /**
     * Whether the amounts differ by at most {@code absoluteTolerance}, in the system unit; the tolerance converts as
     * a difference, so {@code 1 °C} of tolerance is {@code 1 K}. Exact if either is decimal.
     *
     * @param other the other quantity
     * @param absoluteTolerance the largest difference, not negative
     * @return true if close
     */
    boolean isCloseTo(Q other, Q absoluteTolerance);
}
