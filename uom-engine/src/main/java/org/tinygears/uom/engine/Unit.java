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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.tinygears.uom.engine.internal.AlternateUnits;
import org.tinygears.uom.engine.internal.Atoms;
import org.tinygears.uom.engine.internal.BoundedCache;
import org.tinygears.uom.engine.internal.SerialForm;

/**
 * A product of atoms with integer exponents, an affine converter to the system unit, and labels: the unit algebra
 * without the JSR-385 API. Equal by atoms and converter; symbol and name are labels. Serializable through a proxy
 * (atoms, converter, symbol and name); the serialized form may change before 1.0.
 */
public final class Unit implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** The dimensionless unit one, {@code 1}. */
    public static final Unit ONE = new Unit(Map.of(), Converter.IDENTITY, "1", "one");

    static final int CONVERTER_CACHE_SIZE = 16;

    /** Atoms and their exponents, sorted by symbol; the class is written through its Proxy. */
    @SuppressWarnings("serial")
    private final Map<UnitAtom, Integer> atoms;
    /** The converter to the system unit. */
    private final Converter toSystem;
    /** The symbol, or null. */
    private final String symbol;
    /** The name, or null. */
    private final String name;
    /** The dimension of the atoms. */
    private final Dimension dimension;
    /** Hash of the atoms and the converter; units are cache keys, so it is computed once. */
    private final transient int hash;
    /** Converters to the most recently used targets; bounded, so the targets are not all kept alive. */
    private final transient BoundedCache<Unit, Converter> converterCache = new BoundedCache<>(CONVERTER_CACHE_SIZE);
    /** {@link #systemProduct} and {@link #systemQuotient} by the other unit, bounded as the converters. */
    private final transient BoundedCache<Unit, Unit> productCache = new BoundedCache<>(CONVERTER_CACHE_SIZE);
    private final transient BoundedCache<Unit, Unit> quotientCache = new BoundedCache<>(CONVERTER_CACHE_SIZE);
    /** {@link #systemInverse}, computed once. */
    private transient volatile Unit systemInverse;

    private Unit(Map<UnitAtom, Integer> atoms, Converter toSystem, String symbol, String name) {
        this.atoms = atoms;
        this.toSystem = toSystem;
        this.symbol = symbol;
        this.name = name;
        this.dimension = Atoms.dimension(atoms);
        this.hash = Objects.hash(atoms, toSystem);
    }

    /**
     * A unit of atoms with non-zero exponents (zero exponents are dropped).
     *
     * @param atoms the atoms and their exponents
     * @param toSystem the converter to the system unit
     * @param symbol the symbol, or null
     * @param name the name, or null
     * @return the unit
     * @throws ArithmeticException if the factor of {@code toSystem} is zero, as {@link #transform} does
     */
    public static Unit of(Map<UnitAtom, Integer> atoms, Converter toSystem, String symbol, String name) {
        Objects.requireNonNull(toSystem, "toSystem");
        if (toSystem.factor().isZero()) {
            throw new ArithmeticException("zero scale factor");
        }
        return new Unit(Atoms.normalize(atoms), toSystem, symbol, name);
    }

    /**
     * The base unit of {@code atom}, labelled with the atom's symbol and {@code name}.
     *
     * @param atom the atom
     * @param name the name, or null
     * @return the unit
     */
    public static Unit base(UnitAtom atom, String name) {
        return new Unit(Map.of(atom, 1), Converter.IDENTITY, atom.symbol(), name);
    }

    /**
     * This unit with other labels.
     *
     * @param symbol the symbol, or null
     * @param name the name, or null
     * @return the relabelled unit
     */
    public Unit named(String symbol, String name) {
        return new Unit(atoms, toSystem, symbol, name);
    }

    /**
     * The atoms and their exponents, sorted by symbol.
     *
     * @return an unmodifiable map
     */
    public Map<UnitAtom, Integer> atoms() {
        return atoms;
    }

    /**
     * The converter to the system unit.
     *
     * @return the converter
     */
    public Converter toSystem() {
        return toSystem;
    }

    /**
     * The symbol.
     *
     * @return the symbol, or null
     */
    public String symbol() {
        return symbol;
    }

    /**
     * The name.
     *
     * @return the name, or null
     */
    public String name() {
        return name;
    }

    /**
     * The dimension of the atoms.
     *
     * @return the dimension
     */
    public Dimension dimension() {
        return dimension;
    }

    /**
     * This unit if it already is a named system unit (it keeps its symbol and name: {@code N}, not
     * {@code kg·m/s^2}), else the unscaled unit of the same atoms, whose symbol is the label of the atoms. A symbol
     * built from other units ({@code m·m}) is not a name, so the product of two lengths is {@code m^2}.
     *
     * @return the system unit
     */
    public Unit systemUnit() {
        if (toSystem.isIdentity() && name != null) {
            return this;
        }
        return new Unit(atoms, Converter.IDENTITY, systemLabel(), null);
    }

    /**
     * The unit of the product of two quantities in this unit and in {@code that}: the system unit of the product of
     * the two system units ({@code kg·m/s} for kilograms times kilometres per hour). It depends on the atoms only, so
     * it is cached by {@code that}: products of quantities would otherwise rebuild it with the exact algebra each time.
     *
     * @param that the other unit
     * @return the system unit of the product
     */
    public Unit systemProduct(Unit that) {
        return productCache.get(that, t -> systemUnit().multiply(t.systemUnit()).systemUnit());
    }

    /**
     * The unit of the quotient of two quantities in this unit and in {@code that}, as {@link #systemProduct}.
     *
     * @param that the divisor's unit
     * @return the system unit of the quotient
     */
    public Unit systemQuotient(Unit that) {
        return quotientCache.get(that, t -> systemUnit().divide(t.systemUnit()).systemUnit());
    }

    /**
     * The unit of the reciprocal of a quantity in this unit: the system unit of the inverse system unit, computed once.
     *
     * @return the system unit of the inverse
     */
    public Unit systemInverse() {
        Unit u = systemInverse;
        if (u == null) {
            u = systemUnit().inverse().systemUnit();
            systemInverse = u;
        }
        return u;
    }

    private String systemLabel() {
        String num = join(true);
        String den = join(false);
        if (den.isEmpty()) {
            return num.isEmpty() ? "1" : num;
        }
        boolean several = atoms.values().stream().filter(n -> n < 0).count() > 1;
        return (num.isEmpty() ? "1" : num) + "/" + (several ? "(" + den + ")" : den);
    }

    private String join(boolean positive) {
        return atoms.entrySet().stream().filter(e -> e.getValue() > 0 == positive)
                .map(e -> e.getKey().symbol() + (Math.abs(e.getValue()) == 1 ? "" : "^" + Math.abs(e.getValue())))
                .collect(Collectors.joining("·"));
    }

    /**
     * The atoms as units with their exponents, or null for a base unit: {@link #ONE} or a single atom with exponent
     * 1, both with the identity converter.
     *
     * @return the base units, or null
     */
    public Map<Unit, Integer> baseUnits() {
        boolean simple = atoms.isEmpty() || atoms.size() == 1 && atoms.containsValue(1);
        if (simple && toSystem.equals(Converter.IDENTITY)) {
            return null;
        }
        Map<Unit, Integer> m = new LinkedHashMap<>();
        atoms.forEach((atom, n) -> m.put(new Unit(Map.of(atom, 1), Converter.IDENTITY, atom.symbol(), null), n));
        return Collections.unmodifiableMap(m);
    }

    /**
     * Whether {@code that} has this dimension.
     *
     * @param that the other unit
     * @return true if the dimensions are equal
     */
    public boolean isCompatible(Unit that) {
        return that.dimension.equals(dimension);
    }

    /**
     * The converter from this unit to {@code that}, cached; the caller checks compatibility, so only valid targets
     * are cached.
     *
     * @param that the target, of this dimension
     * @return the converter
     * @throws ArithmeticException if no exact converter exists (an irrational and a rational offset)
     */
    public Converter converterTo(Unit that) {
        return converterCache.get(that, t -> t.toSystem.inverse().concatenate(toSystem));
    }

    /**
     * {@code f·S + s}: the factor {@code f} and the shift {@code s = offset / f} of the converter to the system unit
     * {@code S}, such as {@code 1|1000·m} or {@code 5|9·K + 45967|100}.
     *
     * @return the definition
     */
    public String definition() {
        String system = systemLabel();
        if (toSystem.isIdentity()) {
            return system;
        }
        String f = text(toSystem.factor());
        StringBuilder out = new StringBuilder(f.equals("1") ? system : f + "·" + system);
        if (!toSystem.isLinear()) {
            Factor shift = toSystem.offset().divide(toSystem.factor());
            out.append(shift.signum() < 0 ? " - " : " + ").append(text(shift.signum() < 0 ? shift.negate() : shift));
        }
        return out.toString();
    }

    /** An exact fraction as {@code n|d}, any other factor as {@link Factor#toString()}. */
    private static String text(Factor f) {
        String t = f.toString();
        return t.matches("-?\\d+(/\\d+)?") ? t.replace('/', '|') : t;
    }

    /**
     * An alternate unit of this unscaled unit, registered under {@code symbol} (JVM-wide, for the life of the JVM;
     * the symbols of the built-in catalogs are taken from the start). The alternate of an atom's unit under the atom's
     * own symbol is that unit.
     *
     * @param symbol the symbol
     * @return the alternate unit
     * @throws IllegalArgumentException if this unit is not unscaled
     * @throws UnitException if the symbol is invalid or names a different unit
     */
    public Unit alternate(String symbol) {
        Objects.requireNonNull(symbol, "symbol");
        if (!toSystem.equals(Converter.IDENTITY)) {
            throw new IllegalArgumentException("not an unscaled standard unit: " + this);
        }
        return new Unit(Map.of(AlternateUnits.register(symbol, atoms), 1), Converter.IDENTITY, symbol, null);
    }

    /**
     * The unit {@code operation} converts from to this one, without labels.
     *
     * @param operation the converter from the new unit to this one
     * @return the new unit
     * @throws ArithmeticException if the factor becomes zero
     */
    public Unit transform(Converter operation) {
        Converter c = toSystem.concatenate(operation);
        if (c.factor().isZero()) {
            throw new ArithmeticException("zero scale factor: " + this);
        }
        return new Unit(atoms, c, null, null);
    }

    /**
     * This unit multiplied by an exact factor.
     *
     * @param factor the factor
     * @return the scaled unit
     */
    public Unit scaledBy(Factor factor) {
        return transform(Converter.scale(factor));
    }

    /**
     * This unit shifted by an exact offset.
     *
     * @param offset the offset
     * @return the shifted unit
     */
    public Unit shiftedBy(Factor offset) {
        return transform(Converter.shift(offset));
    }

    /**
     * The product with {@code multiplier}. A unit with an offset takes part by its factor only, so {@code °C·m}
     * is {@code K·m}: the offset belongs to points, and a product unit measures differences (docs/OFFSET-UNITS.md).
     *
     * @param multiplier the other unit
     * @return the product
     */
    public Unit multiply(Unit multiplier) {
        return combine(multiplier, false);
    }

    /**
     * The quotient by {@code divisor}; an offset is dropped as in {@link #multiply(Unit)}.
     *
     * @param divisor the other unit
     * @return the quotient
     */
    public Unit divide(Unit divisor) {
        return combine(divisor, true);
    }

    private Unit combine(Unit that, boolean quotient) {
        Map<UnitAtom, Integer> m = new LinkedHashMap<>(atoms);
        that.atoms.forEach((a, n) -> {
            int e = m.getOrDefault(a, 0);
            m.put(a, quotient ? Math.subtractExact(e, n) : Math.addExact(e, n));
        });
        Factor f = quotient
                ? toSystem.factor().divide(that.toSystem.factor())
                : toSystem.factor().multiply(that.toSystem.factor());
        String s = symbol == null || that.symbol == null
                ? null
                : group(symbol, "/") + (quotient ? "/" + group(that.symbol, "·/") : "·" + group(that.symbol, "/"));
        return of(m, Converter.scale(f), s, null);
    }

    /**
     * The reciprocal.
     *
     * @return this to the power -1
     */
    public Unit inverse() {
        return pow(-1);
    }

    /**
     * The {@code n}-th power; an offset is dropped.
     *
     * @param n the exponent
     * @return {@link #ONE} for 0, this unit for 1, else the power
     */
    public Unit pow(int n) {
        if (n == 0) {
            return ONE;
        }
        if (n == 1) {
            return this;
        }
        Factor f = toSystem.factor().pow(n);
        Map<UnitAtom, Integer> m = new LinkedHashMap<>();
        atoms.forEach((a, e) -> m.put(a, Math.multiplyExact(e, n)));
        return of(m, Converter.scale(f), symbol == null ? null : group(symbol, "·/^") + "^" + n, null);
    }

    /**
     * The {@code n}-th root; every exponent must be a multiple of {@code n}, and an offset is dropped.
     *
     * @param n the root index, not zero
     * @return this unit for 1, else the root
     * @throws ArithmeticException if {@code n} is zero or an exponent is not a multiple of it
     */
    public Unit root(int n) {
        if (n == 0) {
            throw new ArithmeticException("zeroth root");
        }
        if (n == 1) {
            return this;
        }
        Map<UnitAtom, Integer> m = new LinkedHashMap<>();
        atoms.forEach((a, e) -> {
            if (e % n != 0) {
                throw new ArithmeticException("exponent " + e + " of " + a.symbol() + " not divisible by " + n);
            }
            m.put(a, Math.toIntExact((long) e / n));
        });
        return of(m, Converter.scale(toSystem.factor().root(n)), null, null);
    }

    /** Parenthesizes {@code s} if it contains any of {@code operators}. */
    private static String group(String s, String operators) {
        return s.chars().anyMatch(c -> operators.indexOf(c) >= 0) ? "(" + s + ")" : s;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Unit u && atoms.equals(u.atoms) && toSystem.equals(u.toSystem);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    /** The symbol, else the definition ({@code 1|1000·m}, {@code 5|9·K + 45967|100}). */
    @Override
    public String toString() {
        return symbol != null ? symbol : definition();
    }

    /**
     * The serialization proxy.
     *
     * @return the proxy
     */
    @Serial
    private Object writeReplace() {
        return new Proxy(
                atoms.keySet().toArray(UnitAtom[]::new),
                atoms.values().stream().mapToInt(Integer::intValue).toArray(),
                toSystem,
                symbol,
                name);
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

    /** Serialized form of a unit. */
    static final class Proxy implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private static final String TOO_MANY_ATOMS = "invalid unit: too many atoms";
        private static final String TOO_MANY_EXPONENTS = "invalid unit: too many exponents";

        private transient UnitAtom[] atoms;
        private transient int[] exponents;
        private transient Converter toSystem;
        private transient String symbol;
        private transient String name;

        Proxy(UnitAtom[] atoms, int[] exponents, Converter toSystem, String symbol, String name) {
            this.atoms = atoms;
            this.exponents = exponents;
            this.toSystem = toSystem;
            this.symbol = symbol;
            this.name = name;
        }

        @Serial
        private void writeObject(ObjectOutputStream out) throws IOException {
            out.defaultWriteObject();
            SerialForm.writeObjects(out, atoms, TOO_MANY_ATOMS);
            SerialForm.writeInts(out, exponents, TOO_MANY_EXPONENTS);
            out.writeUnshared(toSystem);
            SerialForm.writeString(out, symbol, "invalid unit: symbol");
            SerialForm.writeString(out, name, "invalid unit: name");
        }

        @Serial
        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            atoms = SerialForm.readObjects(in, UnitAtom.class, UnitAtom[]::new, TOO_MANY_ATOMS);
            exponents = SerialForm.readInts(in, TOO_MANY_EXPONENTS);
            toSystem = SerialForm.read(in, Converter.class);
            symbol = SerialForm.readString(in, "invalid unit: symbol");
            name = SerialForm.readString(in, "invalid unit: name");
        }

        @Serial
        private Object readResolve() throws InvalidObjectException {
            try {
                Objects.requireNonNull(toSystem, "null converter");
                return of(Atoms.map(atoms, exponents), toSystem, symbol, name);
            } catch (RuntimeException e) {
                throw (InvalidObjectException) new InvalidObjectException("invalid unit: " + e.getMessage())
                        .initCause(e);
            }
        }
    }
}
