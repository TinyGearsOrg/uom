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

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.measure.IncommensurableException;
import javax.measure.MeasurementException;
import javax.measure.Prefix;
import javax.measure.Quantity;
import javax.measure.UnconvertibleException;
import javax.measure.Unit;
import javax.measure.UnitConverter;
import javax.measure.spi.QuantityFactory;

import org.tinygears.uom.engine.Factor;
import org.tinygears.uom.engine.UnitAtom;
import org.tinygears.uom.engine.UnitException;
import org.tinygears.uom.engine.internal.BoundedCache;
import org.tinygears.uom.engine.internal.SerialForm;
import org.tinygears.uom.jsr.spi.Providers;

/**
 * The JSR-385 unit over an engine {@link org.tinygears.uom.engine.Unit}: a product of atoms with integer exponents,
 * an affine converter to the system unit, and labels. The engine does the algebra; this class adds what JSR-385
 * defines ({@code asType}, the text of an unnamed unit, its exceptions). Serializable through a proxy that carries the
 * engine unit; the serialized form may change before 1.0.
 *
 * @param <Q> the quantity type
 */
public final class UomUnit<Q extends Quantity<Q>> implements Unit<Q>, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** The engine unit; the class is written through its Proxy. */
    private final org.tinygears.uom.engine.Unit unit;
    /** Its converter to the system unit, as a JSR-385 converter. */
    private final transient UomConverter toSystem;
    /** Its dimension, as a JSR-385 dimension. */
    private final transient UomDimension dimension;
    static final int CONVERTER_CACHE_SIZE = 16;
    /** Converters to the most recently used targets, so a lookup gives the same instance; bounded. */
    private final transient BoundedCache<UomUnit<?>, UomConverter> converterCache = new BoundedCache<>(
            CONVERTER_CACHE_SIZE);

    private UomUnit(org.tinygears.uom.engine.Unit unit) {
        this.unit = unit;
        this.toSystem = AffineConverter.of(unit.toSystem());
        this.dimension = UomDimension.of(unit.dimension());
    }

    /**
     * The JSR-385 unit of an engine unit.
     *
     * @param <Q> the quantity type, which the caller guarantees
     * @param unit the engine unit
     * @return the unit
     */
    public static <Q extends Quantity<Q>> UomUnit<Q> of(org.tinygears.uom.engine.Unit unit) {
        return new UomUnit<>(Objects.requireNonNull(unit, "unit"));
    }

    /**
     * The engine unit.
     *
     * @return the engine unit
     */
    public org.tinygears.uom.engine.Unit engine() {
        return unit;
    }

    /** This unit for an engine result equal in identity to its own unit, else a new one. */
    private <T extends Quantity<T>> UomUnit<T> wrap(org.tinygears.uom.engine.Unit u) {
        if (u == unit) {
            @SuppressWarnings("unchecked") // the same unit, so the same quantity type
            UomUnit<T> self = (UomUnit<T>) this;
            return self;
        }
        if (u == org.tinygears.uom.engine.Unit.ONE) {
            return unchecked(BaseUnits.ONE);
        }
        return new UomUnit<>(u);
    }

    /**
     * Caller guarantees the unit belongs to Q; for generated code.
     *
     * @param <Q> the quantity type
     * @param unit the unit
     * @return the same unit
     */
    @SuppressWarnings("unchecked")
    public static <Q extends Quantity<Q>> UomUnit<Q> unchecked(UomUnit<?> unit) {
        return (UomUnit<Q>) Objects.requireNonNull(unit, "unit");
    }

    /**
     * A unit of atoms with non-zero exponents (zero exponents are dropped).
     *
     * @param <Q> the quantity type
     * @param atoms the atoms and their exponents
     * @param toSystem the converter to the system unit
     * @param symbol the symbol, or null
     * @param name the name, or null
     * @return the unit
     * @throws ArithmeticException if the factor of {@code toSystem} is zero, as {@link #transform} does
     */
    public static <Q extends Quantity<Q>> UomUnit<Q> of(
            Map<UnitAtom, Integer> atoms,
            UomConverter toSystem,
            String symbol,
            String name
    ) {
        Objects.requireNonNull(toSystem, "toSystem");
        return new UomUnit<>(org.tinygears.uom.engine.Unit.of(atoms, AffineConverter.engine(toSystem), symbol, name));
    }

    /**
     * This unit with other labels.
     *
     * @param symbol the symbol, or null
     * @param name the name, or null
     * @return the relabelled unit
     */
    public UomUnit<Q> named(String symbol, String name) {
        return new UomUnit<>(unit.named(symbol, name));
    }

    /**
     * The atoms and their exponents, sorted by symbol.
     *
     * @return an unmodifiable map
     */
    public Map<UnitAtom, Integer> atoms() {
        return unit.atoms();
    }

    /**
     * The converter to the system unit.
     *
     * @return the converter
     */
    public UomConverter toSystemConverter() {
        return toSystem;
    }

    @Override
    public String getSymbol() {
        return unit.symbol();
    }

    @Override
    public String getName() {
        return unit.name();
    }

    @Override
    public UomDimension getDimension() {
        return dimension;
    }

    /**
     * This unit if it already is a named system unit (it keeps its symbol and name: {@code N}, not
     * {@code kg·m/s^2}); for another named unit the system unit of its standard quantity type ({@code Pa} for
     * {@code kPa}); else the unscaled unit of the same atoms, whose symbol is the label of the atoms. A symbol built
     * from other units ({@code m·m}, {@code N·m}) is not a name, so the product of two lengths is {@code m^2}.
     */
    @Override
    @SuppressWarnings("unchecked") // the type's system unit has this unit's atoms, so it measures Q
    public UomUnit<Q> getSystemUnit() {
        org.tinygears.uom.engine.Unit system = unit.systemUnit();
        if (system != unit && unit.name() != null
                && Providers.namedSystemUnit(wrap(system)) instanceof UomUnit<?> named) {
            return (UomUnit<Q>) named;
        }
        return wrap(system);
    }

    /** Null for a base unit: ONE or a single atom with exponent 1, both with the identity converter. */
    @Override
    public Map<UomUnit<?>, Integer> getBaseUnits() {
        Map<org.tinygears.uom.engine.Unit, Integer> base = unit.baseUnits();
        if (base == null) {
            return null;
        }
        Map<UomUnit<?>, Integer> m = new LinkedHashMap<>();
        base.forEach((u, n) -> m.put(new UomUnit<>(u), n));
        return Collections.unmodifiableMap(m);
    }

    @Override
    public boolean isCompatible(Unit<?> that) {
        return that.getDimension().equals(getDimension());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UomUnit<?> u && unit.equals(u.unit);
    }

    @Override
    public int hashCode() {
        return unit.hashCode();
    }

    // Guards the provider's format from printing the unit it is formatting again.
    private static final ThreadLocal<Boolean> PRINTING = ThreadLocal.withInitial(() -> false);

    /**
     * The symbol, or for an unnamed unit its text in the standard format of the provider of this implementation
     * ({@code mHz}, {@code km}), or without one its definition: {@code 1|1000·m} or {@code 5|9·K + 45967|100}.
     */
    @Override
    public String toString() {
        if (unit.symbol() != null) {
            return unit.symbol();
        }
        if (!PRINTING.get()) {
            PRINTING.set(true);
            try {
                String text = Providers.unitText(this);
                if (text != null) {
                    return text;
                }
            } finally {
                PRINTING.set(false);
            }
        }
        return unit.definition();
    }

    /** Same dimension and an identity converter; alternate atoms may differ, so rad is equivalent to one. */
    @Override
    public boolean isEquivalentTo(Unit<Q> that) {
        if (!(Objects.requireNonNull(that, "that") instanceof UomUnit<?> u) || !isCompatible(u)) {
            return false;
        }
        try {
            return converterTo(u).isIdentity();
        } catch (UnconvertibleException e) {
            return false;
        }
    }

    @Override
    public UomConverter getConverterTo(Unit<Q> that) {
        UomUnit<?> target = uomUnit(that);
        if (!isCompatible(target)) {
            throw new UnconvertibleException("incompatible dimensions: " + this + " and " + target);
        }
        return converterTo(target);
    }

    @Override
    public UomConverter getConverterToAny(Unit<?> that) throws IncommensurableException {
        UomUnit<?> target = uomUnit(that);
        if (!isCompatible(target)) {
            throw new IncommensurableException(this + " is not compatible with " + target);
        }
        return converterTo(target);
    }

    private static UomUnit<?> uomUnit(Unit<?> that) {
        if (that instanceof UomUnit<?> u) {
            return u;
        }
        throw new UnconvertibleException("foreign unit implementation: " + that.getClass().getName());
    }

    /** Caller has checked compatibility. */
    private UomConverter converterTo(UomUnit<?> that) {
        try {
            return converterCache.get(that, t -> AffineConverter.of(unit.converterTo(t.unit)));
        } catch (ArithmeticException e) {
            // e.g. an irrational and a rational offset, which no exact factor adds up
            throw (UnconvertibleException) new UnconvertibleException(
                    "cannot convert " + this + " to " + that + " exactly: " + e.getMessage()).initCause(e);
        }
    }

    /**
     * This unit as a unit of {@code type} if its dimension is the dimension of the type, as the {@code Unit} Javadoc
     * says; alternate units count by dimension, so {@code 1/s} and {@code Bq} are also units of {@code Frequency}, and
     * {@code rad·m} is a unit of {@code Length}. Throws {@link ClassCastException} if the dimensions differ.
     */
    @Override
    public <T extends Quantity<T>> Unit<T> asType(Class<T> type) {
        QuantityFactory<T> factory;
        try {
            factory = Providers.quantityFactory(type);
        } catch (IllegalStateException e) {
            throw new IllegalStateException("no javax.measure ServiceProvider available", e);
        }
        if (!getDimension().equals(factory.getSystemUnit().getDimension())) {
            throw new ClassCastException(this + " is not a unit of " + type.getName());
        }
        @SuppressWarnings("unchecked") // the system units match, so this unit measures T
        Unit<T> typed = (Unit<T>) this;
        return typed;
    }

    @Override
    public UomUnit<Q> alternate(String symbol) {
        try {
            return new UomUnit<>(unit.alternate(symbol));
        } catch (UnitException e) {
            throw new MeasurementException(e.getMessage(), e);
        }
    }

    @Override
    public UomUnit<Q> shift(Number offset) {
        return new UomUnit<>(unit.shiftedBy(toFactor(offset)));
    }

    @Override
    public UomUnit<Q> shift(double offset) {
        return shift((Number) offset);
    }

    @Override
    public UomUnit<Q> multiply(Number multiplier) {
        return new UomUnit<>(unit.scaledBy(toFactor(multiplier)));
    }

    @Override
    public UomUnit<Q> multiply(double multiplier) {
        return multiply((Number) multiplier);
    }

    /**
     * This unit multiplied by an exact factor.
     *
     * @param factor the factor
     * @return the scaled unit
     */
    public UomUnit<?> scaledBy(Factor factor) {
        return new UomUnit<>(unit.scaledBy(factor));
    }

    @Override
    public UomUnit<Q> divide(Number divisor) {
        return new UomUnit<>(unit.scaledBy(Factor.ONE.divide(toFactor(divisor))));
    }

    @Override
    public UomUnit<Q> divide(double divisor) {
        return divide((Number) divisor);
    }

    /** {@code operation} converts from the new unit to this one. */
    @Override
    public UomUnit<Q> transform(UnitConverter operation) {
        if (!(operation instanceof UomConverter c)) {
            throw new IllegalArgumentException("not a UomConverter: " + operation);
        }
        return new UomUnit<>(unit.transform(AffineConverter.engine(c)));
    }

    /**
     * This unit scaled by the value of {@code prefix}, without a symbol or name: whether a prefix applies to a unit
     * ({@code km} but not {@code kmin} or {@code km²}) and whether the result collides with another symbol is
     * known to a system of units, not to the unit. Name the result with {@link #named}, or let a system
     * ({@code @prefixed}) or {@code UomUnitFormat} resolve it.
     */
    @Override
    public UomUnit<Q> prefix(Prefix prefix) {
        return new UomUnit<>(unit.scaledBy(Factor.decimal(prefix.getValue().toString()).pow(prefix.getExponent())));
    }

    /**
     * The product with {@code multiplier}. A unit with an offset takes part by its factor only, so {@code °C·m}
     * is {@code K·m}: the offset belongs to points, and a product unit measures differences (docs/NATIVE.md#offset-units).
     */
    @Override
    public UomUnit<?> multiply(Unit<?> multiplier) {
        return wrap(unit.multiply(uomUnit(multiplier).unit));
    }

    /** The quotient by {@code divisor}; an offset is dropped as in {@link #multiply(Unit)}. */
    @Override
    public UomUnit<?> divide(Unit<?> divisor) {
        return wrap(unit.divide(uomUnit(divisor).unit));
    }

    @Override
    public UomUnit<?> inverse() {
        return wrap(unit.inverse());
    }

    @Override
    public UomUnit<?> pow(int n) {
        return wrap(unit.pow(n));
    }

    @Override
    public UomUnit<?> root(int n) {
        return wrap(unit.root(n));
    }

    /**
     * The serialization proxy.
     *
     * @return the proxy
     */
    @Serial
    private Object writeReplace() {
        return new Proxy(unit);
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

    /** Serialized form: the engine unit, which validates itself; package-private so tests can forge streams. */
    static final class Proxy implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        // any serializable object, so tests can forge the engine's proxy
        private transient Serializable unit;

        Proxy(Serializable unit) {
            this.unit = unit;
        }

        @Serial
        private void writeObject(ObjectOutputStream out) throws IOException {
            out.defaultWriteObject();
            out.writeUnshared(unit);
        }

        @Serial
        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            unit = SerialForm.read(in, org.tinygears.uom.engine.Unit.class);
        }

        @Serial
        private Object readResolve() throws InvalidObjectException {
            if (unit == null) {
                throw new InvalidObjectException("invalid unit: null unit");
            }
            return new UomUnit<>((org.tinygears.uom.engine.Unit) unit);
        }
    }

    private static Factor toFactor(Number n) {
        return Factor.valueOf(n);
    }
}
