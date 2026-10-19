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

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;
import javax.measure.Quantity;
import javax.measure.UnconvertibleException;
import javax.measure.Unit;

import org.tinygears.uom.jsr.UomUnit;

/**
 * Phantom quantity type of products, quotients and inverses, and of a quantity in a unit of any dimension, made with
 * {@code of}; narrow with {@link UomQuantity#as} or {@link Quantity#asType}.
 */
public interface Untyped extends UomQuantity<Untyped, Untyped> {

    /**
     * An absolute double quantity.
     *
     * @param value the value
     * @param unit the unit, of any dimension
     * @return the quantity
     * @throws UnconvertibleException if the unit is of another implementation
     */
    static Untyped of(double value, Unit<?> unit) {
        return of(value, unit, Quantity.Scale.ABSOLUTE);
    }

    /**
     * A double quantity.
     *
     * @param value the value
     * @param unit the unit, of any dimension
     * @param scale the scale
     * @return the quantity
     * @throws UnconvertibleException if the unit is of another implementation
     */
    static Untyped of(double value, Unit<?> unit, Quantity.Scale scale) {
        return new UntypedDoubleQuantity(value, uomUnit(unit), scale);
    }

    /**
     * An absolute decimal quantity with context {@code DECIMAL128}; the value is kept as given, not rounded.
     *
     * @param value the value
     * @param unit the unit, of any dimension
     * @return the quantity
     * @throws UnconvertibleException if the unit is of another implementation
     */
    static Untyped of(BigDecimal value, Unit<?> unit) {
        return of(value, unit, Quantity.Scale.ABSOLUTE);
    }

    /**
     * A decimal quantity with context {@code DECIMAL128}; the value is kept as given, not rounded.
     *
     * @param value the value
     * @param unit the unit, of any dimension
     * @param scale the scale
     * @return the quantity
     * @throws UnconvertibleException if the unit is of another implementation
     */
    static Untyped of(BigDecimal value, Unit<?> unit, Quantity.Scale scale) {
        return of(value, unit, scale, MathContext.DECIMAL128);
    }

    /**
     * A decimal quantity with context {@code mc}; the value is kept as given, not rounded. A precision over
     * 50000 or a value of more than 50000 digits throws ArithmeticException.
     *
     * @param value the value
     * @param unit the unit, of any dimension
     * @param scale the scale
     * @param mc the math context
     * @return the quantity
     * @throws UnconvertibleException if the unit is of another implementation
     */
    static Untyped of(BigDecimal value, Unit<?> unit, Quantity.Scale scale, MathContext mc) {
        return new UntypedDecimalQuantity(value, uomUnit(unit), scale, mc);
    }

    private static UomUnit<Untyped> uomUnit(Unit<?> unit) {
        if (Objects.requireNonNull(unit, "unit") instanceof UomUnit<?> u) {
            return UomUnit.unchecked(u);
        }
        throw new UnconvertibleException("foreign unit implementation: " + unit);
    }
}
