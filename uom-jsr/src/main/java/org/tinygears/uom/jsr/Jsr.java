/*
 * Copyright (c) 2026 TinyGears.org
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.tinygears.uom.jsr;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.tinygears.uom.Quantity;
import org.tinygears.uom.Scale;
import org.tinygears.uom.Unit;
import org.tinygears.uom.Untyped;
import org.tinygears.uom.internal.Units;
import org.tinygears.uom.jsr.UomUnit;
import org.tinygears.uom.jsr.quantity.AbstractDecimalQuantity;
import org.tinygears.uom.jsr.quantity.QuantityTypes;
import org.tinygears.uom.jsr.quantity.UntypedDecimalQuantity;
import org.tinygears.uom.jsr.quantity.UntypedDoubleQuantity;
import org.tinygears.uom.jsr.quantity.UomQuantity;

/**
 * Converts between the native quantities and units and the JSR-385 quantities and units of uom. Both sides wrap the same
 * engine units, so a conversion copies the value, its number type, scale and context, and wraps the unit; nothing is
 * converted or rounded. A native type maps to the JSR-385 type of the same name ({@code Length} to
 * {@code javax.measure.quantity.Length}), {@link Untyped} to an untyped quantity. A decimal of another
 * implementation's quantity has no context and counts as unlimited.
 */
public final class Jsr {
    // javax.measure.quantity.Length by simple name; the 33 types share their names on both sides
    private static final Map<String, Class<?>> STANDARD = QuantityTypes.types().stream()
            .collect(Collectors.toUnmodifiableMap(Class::getSimpleName, Function.identity()));

    private Jsr() {
    }

    /**
     * The JSR-385 quantity of a native one.
     *
     * @param quantity the native quantity
     * @return the JSR-385 quantity: the typed interface of uom of the same name, or an untyped one
     * @throws IllegalArgumentException for an untyped {@code RELATIVE} quantity, which JSR-385 untyped quantities
     *     cannot carry
     */
    // the standard type is looked up by name, so its factory is one of a raw quantity type
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static UomQuantity<?, ?> toJsr(Quantity<?> quantity) {
        Objects.requireNonNull(quantity, "quantity");
        UomUnit<?> unit = UomUnit.of(Units.engine(quantity.unit()));
        Number value = quantity.value();
        MathContext mc = quantity.mathContext();
        javax.measure.Quantity.Scale scale = quantity.scale() == Scale.RELATIVE
                ? javax.measure.Quantity.Scale.RELATIVE
                : javax.measure.Quantity.Scale.ABSOLUTE;
        Class<?> standard = STANDARD.get(quantity.unit().type().getSimpleName());
        if (standard == null) {
            if (scale == javax.measure.Quantity.Scale.RELATIVE) {
                throw new IllegalArgumentException("an untyped JSR-385 quantity is absolute: " + quantity);
            }
            return value instanceof BigDecimal d
                    ? UntypedDecimalQuantity.of(d, unit, mc)
                    : UntypedDoubleQuantity.of(value.doubleValue(), unit);
        }
        javax.measure.Quantity<?> q = QuantityTypes.factory((Class) standard).create(value, unit, scale);
        return q instanceof AbstractDecimalQuantity<?, ?> d ? d.withMathContext(mc) : (UomQuantity<?, ?>) q;
    }

    /**
     * The JSR-385 quantity of a native one as {@code type}, such as {@code org.tinygears.uom.jsr.quantity.Length}.
     *
     * @param <T> the typed interface
     * @param quantity the native quantity
     * @param type the typed interface of uom
     * @return the JSR-385 quantity
     */
    public static <T extends UomQuantity<?, T>> T toJsr(Quantity<?> quantity, Class<T> type) {
        return toJsr(quantity).as(type);
    }

    /**
     * The native quantity of a JSR-385 one of this implementation, as {@code type}.
     *
     * @param <Q> the native quantity type
     * @param quantity the JSR-385 quantity
     * @param type the native quantity type, such as {@code Length.class}
     * @return the native quantity
     * @throws IllegalArgumentException if the unit is of another implementation or not one of {@code type}
     */
    public static <Q extends Quantity<Q>> Q fromJsr(javax.measure.Quantity<?> quantity, Class<Q> type) {
        Objects.requireNonNull(quantity, "quantity");
        Unit<Untyped> unit = Units.of(Untyped.class, engine(quantity.getUnit()));
        Scale scale = quantity.getScale() == javax.measure.Quantity.Scale.RELATIVE ? Scale.RELATIVE : Scale.ABSOLUTE;
        Untyped q = quantity.getValue() instanceof BigDecimal d
                ? Untyped.of(d, unit, scale, context(quantity))
                : Untyped.of(quantity.getValue().doubleValue(), unit, scale);
        return q.as(type);
    }

    /**
     * The JSR-385 unit of a native one: the unit of this implementation over the same engine unit, with its symbol and
     * name.
     *
     * @param unit the native unit
     * @return the JSR-385 unit
     */
    public static UomUnit<?> toJsr(Unit<?> unit) {
        return UomUnit.of(Units.engine(Objects.requireNonNull(unit, "unit")));
    }

    /**
     * The native unit of a JSR-385 one of this implementation, of the native type of its dimension, as a parsed unit has
     * it ({@code Length} for {@code km}); {@link Untyped} where the dimension has no type.
     *
     * @param unit the JSR-385 unit
     * @return the native unit
     * @throws IllegalArgumentException if the unit is of another implementation
     */
    public static Unit<?> fromJsr(javax.measure.Unit<?> unit) {
        Unit<Untyped> untyped = Units.of(Untyped.class, engine(Objects.requireNonNull(unit, "unit")));
        Class<?> type = org.tinygears.uom.quantity.QuantityTypes.typeOf(untyped);
        return type == null ? untyped : asType(untyped, type);
    }

    @SuppressWarnings("unchecked") // the type is a quantity type, so it is a type of itself
    private static <T extends Quantity<T>> Unit<?> asType(Unit<Untyped> unit, Class<?> type) {
        return unit.asType((Class<T>) type);
    }

    private static MathContext context(javax.measure.Quantity<?> q) {
        return q instanceof AbstractDecimalQuantity<?, ?> d ? d.mathContext() : MathContext.UNLIMITED;
    }

    private static org.tinygears.uom.engine.Unit engine(javax.measure.Unit<?> unit) {
        if (unit instanceof UomUnit<?> u) {
            return u.engine();
        }
        throw new IllegalArgumentException("unit of another implementation: " + unit.getClass().getName());
    }
}
