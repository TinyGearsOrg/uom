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
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

import org.tinygears.uom.engine.Dimension;
import org.tinygears.uom.engine.Factor;
import org.tinygears.uom.internal.Units;
import org.tinygears.uom.quantity.QuantityTypes;

/**
 * A unit of the quantity type {@code Q} over an engine unit; the type is checked against the unit's dimension, and
 * {@link Untyped} takes any. Unlike JSR-385's {@code Unit<Q>}, {@code Q} is this library's typed interface:
 * {@code Unit<Length>}. Two units are equal if their engine units are (the same atoms and converter), whatever their
 * type and labels. Immutable; serializable as its type name and engine unit, checked again when read.
 *
 * @param <Q> the quantity type
 */
public final class Unit<Q extends Quantity<Q>> implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** The quantity type. */
    private final Class<Q> type;
    /** The engine unit. */
    private final org.tinygears.uom.engine.Unit engine;

    static {
        // the generated catalogs and uom-jsr reach the engine side of units through internal.Units
        Units.setAccess(new Units.Access() {
            @Override
            public <T extends Quantity<T>> Unit<T> of(Class<T> type, org.tinygears.uom.engine.Unit engine) {
                return Unit.of(type, engine);
            }

            @Override
            public org.tinygears.uom.engine.Unit engine(Unit<?> unit) {
                return unit.engine;
            }
        });
    }

    private Unit(Class<Q> type, org.tinygears.uom.engine.Unit engine) {
        this.type = type;
        this.engine = engine;
    }

    /**
     * The engine unit {@code engine} as a unit of {@code type}.
     *
     * @param <Q> the quantity type
     * @param type the quantity type
     * @param engine the engine unit
     * @return the unit
     * @throws IllegalArgumentException if the unit's dimension is not the type's
     */
    static <Q extends Quantity<Q>> Unit<Q> of(Class<Q> type, org.tinygears.uom.engine.Unit engine) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(engine, "engine");
        if (type != Untyped.class) {
            Dimension d = QuantityTypes.dimension(type);
            if (d == null) {
                throw new IllegalArgumentException("not a quantity type: " + type.getName());
            }
            if (!d.equals(engine.dimension())) {
                throw new IllegalArgumentException(
                        engine + " (" + engine.dimension() + ") is not a unit of " + type.getSimpleName());
            }
        }
        return new Unit<>(type, engine);
    }

    /**
     * The quantity type.
     *
     * @return the type
     */
    public Class<Q> type() {
        return type;
    }

    /** The engine unit. */
    org.tinygears.uom.engine.Unit engine() {
        return engine;
    }

    /**
     * The symbol, or null.
     *
     * @return the symbol
     */
    public String symbol() {
        return engine.symbol();
    }

    /**
     * The name, or null.
     *
     * @return the name
     */
    public String name() {
        return engine.name();
    }

    /**
     * The dimension.
     *
     * @return the dimension
     */
    public Dimension dimension() {
        return engine.dimension();
    }

    /**
     * This unit if it already is a named system unit; for another named unit of a standard quantity type that type's
     * system unit ({@code Pa} for {@code kPa}); else the unscaled unit of the same atoms, of this type, labelled with
     * its atoms ({@code N·m} is not a name, so its system unit is {@code kg·m^2/s^2}).
     *
     * @return the system unit
     */
    @SuppressWarnings("unchecked") // the system unit of Q
    public Unit<Q> systemUnit() {
        org.tinygears.uom.engine.Unit system = engine.systemUnit();
        Unit<?> named = system != engine && engine.name() != null ? QuantityTypes.systemUnit(type) : null;
        return named != null ? (Unit<Q>) named : new Unit<>(type, system);
    }

    /**
     * Whether {@code that} has this dimension.
     *
     * @param that the other unit
     * @return true if the dimensions are equal
     */
    public boolean isCompatible(Unit<?> that) {
        return engine.isCompatible(that.engine);
    }

    /**
     * The converter from this unit to {@code that}.
     *
     * @param that the target unit
     * @return the converter
     * @throws UnitException if the dimensions differ or no exact converter exists
     */
    public Converter converterTo(Unit<?> that) {
        return new Converter(engineConverterTo(that));
    }

    /** The engine converter to {@code that}, with the exceptions of {@link #converterTo}. */
    org.tinygears.uom.engine.Converter engineConverterTo(Unit<?> that) {
        if (!isCompatible(that)) {
            throw new UnitException("incompatible dimensions: " + this + " and " + that);
        }
        try {
            return engine.converterTo(that.engine);
        } catch (ArithmeticException e) {
            throw new UnitException("cannot convert " + this + " to " + that + " exactly: " + e.getMessage(), e);
        }
    }

    /**
     * The product; an offset takes part by its factor only.
     *
     * @param that the other unit
     * @return the product, untyped
     */
    public Unit<Untyped> multiply(Unit<?> that) {
        return new Unit<>(Untyped.class, engine.multiply(that.engine));
    }

    /**
     * The quotient; an offset takes part by its factor only.
     *
     * @param that the divisor
     * @return the quotient, untyped
     */
    public Unit<Untyped> divide(Unit<?> that) {
        return new Unit<>(Untyped.class, engine.divide(that.engine));
    }

    /**
     * The {@code n}-th power.
     *
     * @param n the exponent
     * @return the power, untyped
     */
    public Unit<Untyped> pow(int n) {
        return new Unit<>(Untyped.class, engine.pow(n));
    }

    /**
     * The reciprocal.
     *
     * @return the reciprocal, untyped
     */
    public Unit<Untyped> inverse() {
        return new Unit<>(Untyped.class, engine.inverse());
    }

    /**
     * This unit times a number as written: an integer exactly, a {@code double} as its shortest decimal
     * ({@code 0.3048}), a {@link java.math.BigDecimal} as is; unlabelled.
     *
     * @param factor the number
     * @return the scaled unit of the same type
     * @throws IllegalArgumentException if {@code factor} is NaN or infinite
     */
    public Unit<Q> multiply(Number factor) {
        return scaledBy(Factor.valueOf(factor));
    }

    /**
     * This unit divided by a number as written, as {@link #multiply(Number)}.
     *
     * @param divisor the number, not zero
     * @return the scaled unit of the same type
     * @throws IllegalArgumentException if {@code divisor} is NaN or infinite
     * @throws ArithmeticException if {@code divisor} is zero
     */
    public Unit<Q> divide(Number divisor) {
        return scaledBy(Factor.ONE.divide(Factor.valueOf(divisor)));
    }

    /**
     * This unit shifted by an offset as written ({@code 273.15} exactly), unlabelled: the kelvin shifted by
     * {@code 273.15} is the degree Celsius.
     *
     * @param offset the number
     * @return the shifted unit of the same type
     * @throws IllegalArgumentException if {@code offset} is NaN or infinite
     */
    public Unit<Q> shift(Number offset) {
        return shiftedBy(Factor.valueOf(offset));
    }

    /**
     * This unit times an exact factor, unlabelled; for factors with pi or roots, which no number writes.
     *
     * @param factor the factor
     * @return the scaled unit of the same type
     */
    public Unit<Q> scaledBy(Factor factor) {
        return new Unit<>(type, engine.scaledBy(factor));
    }

    /**
     * This unit shifted by an exact offset, unlabelled; for offsets with pi or roots.
     *
     * @param offset the offset
     * @return the shifted unit of the same type
     */
    public Unit<Q> shiftedBy(Factor offset) {
        return new Unit<>(type, engine.shiftedBy(offset));
    }

    /**
     * This unit with a symbol and a name.
     *
     * @param symbol the symbol
     * @param name the name
     * @return the labelled unit, equal to this one
     */
    public Unit<Q> named(String symbol, String name) {
        return new Unit<>(type, engine.named(symbol, name));
    }

    /**
     * This unit as a unit of {@code type}.
     *
     * @param <T> the quantity type
     * @param type the quantity type
     * @return the unit
     * @throws IllegalArgumentException if the dimension is not the one of {@code type}
     */
    @SuppressWarnings("unchecked") // the type is Class<T>, so the unit is one of T
    public <T extends Quantity<T>> Unit<T> asType(Class<T> type) {
        return this.type == type ? (Unit<T>) this : of(type, engine);
    }

    /**
     * The serialization proxy.
     *
     * @return the proxy
     */
    @Serial
    private Object writeReplace() {
        return new SerialProxy.OfUnit(this);
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

    @Override
    public boolean equals(Object o) {
        return o instanceof Unit<?> u && engine.equals(u.engine);
    }

    @Override
    public int hashCode() {
        return engine.hashCode();
    }

    /** The symbol, else the definition ({@code 1|1000·m}). */
    @Override
    public String toString() {
        return engine.toString();
    }
}
