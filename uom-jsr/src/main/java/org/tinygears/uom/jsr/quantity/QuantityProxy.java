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

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.measure.Quantity;
import javax.measure.Unit;
import javax.measure.quantity.Acceleration;
import javax.measure.quantity.AmountOfSubstance;
import javax.measure.quantity.Angle;
import javax.measure.quantity.Area;
import javax.measure.quantity.CatalyticActivity;
import javax.measure.quantity.Dimensionless;
import javax.measure.quantity.ElectricCapacitance;
import javax.measure.quantity.ElectricCharge;
import javax.measure.quantity.ElectricConductance;
import javax.measure.quantity.ElectricCurrent;
import javax.measure.quantity.ElectricInductance;
import javax.measure.quantity.ElectricPotential;
import javax.measure.quantity.ElectricResistance;
import javax.measure.quantity.Energy;
import javax.measure.quantity.Force;
import javax.measure.quantity.Frequency;
import javax.measure.quantity.Illuminance;
import javax.measure.quantity.Length;
import javax.measure.quantity.LuminousFlux;
import javax.measure.quantity.LuminousIntensity;
import javax.measure.quantity.MagneticFlux;
import javax.measure.quantity.MagneticFluxDensity;
import javax.measure.quantity.Mass;
import javax.measure.quantity.Power;
import javax.measure.quantity.Pressure;
import javax.measure.quantity.RadiationDoseAbsorbed;
import javax.measure.quantity.RadiationDoseEffective;
import javax.measure.quantity.Radioactivity;
import javax.measure.quantity.SolidAngle;
import javax.measure.quantity.Speed;
import javax.measure.quantity.Temperature;
import javax.measure.quantity.Time;
import javax.measure.quantity.Volume;
import javax.measure.spi.QuantityFactory;

import org.tinygears.uom.engine.arithmetic.Precision;
import org.tinygears.uom.engine.internal.SerialForm;
import org.tinygears.uom.jsr.UomUnit;
import org.tinygears.uom.jsr.spi.Providers;

/**
 * Serialized form of a quantity: kind, type name, unit, scale and the exact value (raw {@code double} bits, or the
 * unscaled bytes and scale of a decimal with its context). Typed quantities are rebuilt through the current
 * provider's factory, so the provider's class results; package-private so tests can forge streams.
 */
final class QuantityProxy implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    static final String DOUBLE = "double";
    static final String DECIMAL = "decimal";
    static final String UNTYPED_DOUBLE = "untyped-double";
    static final String UNTYPED_DECIMAL = "untyped-decimal";

    /** Bound on the unscaled bytes of a decimal value: the length of the largest value of MAX_PRECISION digits. */
    static final int MAX_VALUE_BYTES = 20_763;

    private static final String VALUE_TOO_LARGE = "invalid quantity: value too large";

    /** Types by name, so a stream never makes the reader load a class it names. */
    private static final Map<String, Class<?>> TYPES = Stream.of(
            Acceleration.class,
            AmountOfSubstance.class,
            Angle.class,
            Area.class,
            CatalyticActivity.class,
            Dimensionless.class,
            ElectricCapacitance.class,
            ElectricCharge.class,
            ElectricConductance.class,
            ElectricCurrent.class,
            ElectricInductance.class,
            ElectricPotential.class,
            ElectricResistance.class,
            Energy.class,
            Force.class,
            Frequency.class,
            Illuminance.class,
            Length.class,
            LuminousFlux.class,
            LuminousIntensity.class,
            MagneticFlux.class,
            MagneticFluxDensity.class,
            Mass.class,
            Power.class,
            Pressure.class,
            RadiationDoseAbsorbed.class,
            RadiationDoseEffective.class,
            Radioactivity.class,
            SolidAngle.class,
            Speed.class,
            Temperature.class,
            Time.class,
            Volume.class,
            Untyped.class).collect(Collectors.toUnmodifiableMap(Class::getName, Function.identity()));

    private transient String kind;
    private transient String type;
    private transient UomUnit<?> unit;
    private transient String scale;
    private transient long bits;
    private transient byte[] unscaled;
    private transient int decimalScale;
    private transient int precision;
    private transient String roundingMode;
    private transient Class<?> typeClass;
    private transient QuantityFactory<?> factory;

    QuantityProxy(
            String kind,
            String type,
            UomUnit<?> unit,
            String scale,
            long bits,
            byte[] unscaled,
            int decimalScale,
            int precision,
            String roundingMode
    ) {
        this.kind = kind;
        this.type = type;
        this.unit = unit;
        this.scale = scale;
        this.bits = bits;
        this.unscaled = unscaled;
        this.decimalScale = decimalScale;
        this.precision = precision;
        this.roundingMode = roundingMode;
    }

    static QuantityProxy ofDouble(Class<?> type, UomUnit<?> unit, Quantity.Scale scale, double value)
            throws NotSerializableException {
        String name = typeName(type);
        return new QuantityProxy(
                type == Untyped.class ? UNTYPED_DOUBLE : DOUBLE,
                name,
                unit,
                scale.name(),
                Double.doubleToRawLongBits(value),
                null,
                0,
                0,
                null);
    }

    static QuantityProxy ofDecimal(
            Class<?> type,
            UomUnit<?> unit,
            Quantity.Scale scale,
            BigDecimal value,
            MathContext mc
    ) throws NotSerializableException {
        String name = typeName(type);
        byte[] b = value.unscaledValue().toByteArray();
        if (b.length > MAX_VALUE_BYTES) {
            throw new NotSerializableException(VALUE_TOO_LARGE);
        }
        if (mc.getPrecision() > Precision.MAX_PRECISION) {
            throw new NotSerializableException("invalid quantity: precision out of range: " + mc.getPrecision());
        }
        return new QuantityProxy(
                type == Untyped.class ? UNTYPED_DECIMAL : DECIMAL,
                name,
                unit,
                scale.name(),
                0,
                b,
                value.scale(),
                mc.getPrecision(),
                mc.getRoundingMode().name());
    }

    /** Refuses a type the reader would reject, so the failure is at the writing end. */
    private static String typeName(Class<?> type) throws NotSerializableException {
        if (TYPES.get(type.getName()) != type) {
            throw new NotSerializableException("invalid quantity: unknown type " + type.getName());
        }
        return type.getName();
    }

    private static boolean isDecimal(String kind) {
        return DECIMAL.equals(kind) || UNTYPED_DECIMAL.equals(kind);
    }

    @Serial
    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        out.writeUnshared(kind);
        out.writeUnshared(type);
        out.writeUnshared(unit);
        out.writeUnshared(scale);
        if (isDecimal(kind)) {
            out.writeInt(unscaled == null ? -1 : unscaled.length);
            if (unscaled != null) {
                out.write(unscaled);
            }
            out.writeInt(decimalScale);
            out.writeInt(precision);
            out.writeUnshared(roundingMode);
        } else {
            out.writeLong(bits);
        }
    }

    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        kind = SerialForm.readString(in, "invalid quantity: kind");
        if (kind == null) {
            throw invalid("null kind");
        }
        if (!DOUBLE.equals(kind) && !UNTYPED_DOUBLE.equals(kind) && !isDecimal(kind)) {
            throw invalid("unknown kind " + kind);
        }
        type = SerialForm.readString(in, "invalid quantity: type");
        typeClass = typeClass();
        if (typeClass != Untyped.class) {
            // Before the unit: the factory may register the type's alternate atoms, whatever was loaded before.
            factory = factory();
        }
        unit = SerialForm.read(in, UomUnit.class);
        scale = SerialForm.readString(in, "invalid quantity: scale");
        if (isDecimal(kind)) {
            unscaled = SerialForm.readBytes(in, MAX_VALUE_BYTES, VALUE_TOO_LARGE);
            decimalScale = in.readInt();
            precision = in.readInt();
            roundingMode = SerialForm.readString(in, "invalid quantity: rounding mode");
        } else {
            bits = in.readLong();
        }
    }

    @Serial
    private Object readResolve() throws InvalidObjectException {
        try {
            boolean untyped = typeClass == Untyped.class;
            if (unit == null) {
                throw invalid("null unit");
            }
            Quantity.Scale s = scale();
            if (!isDecimal(kind)) {
                double v = Double.longBitsToDouble(bits);
                return untyped ? new UntypedDoubleQuantity(v, UomUnit.unchecked(unit), s) : typed(v, s, null);
            }
            BigDecimal v = decimal();
            MathContext mc = mathContext();
            return untyped ? new UntypedDecimalQuantity(v, UomUnit.unchecked(unit), s, mc) : typed(v, s, mc);
        } catch (RuntimeException e) {
            throw invalid(e);
        }
    }

    /** The type named by the stream, checked against the kind. */
    private Class<?> typeClass() throws InvalidObjectException {
        if (type == null) {
            throw invalid("null type");
        }
        Class<?> t = TYPES.get(type);
        if (t == null) {
            throw invalid("unknown type " + type);
        }
        boolean untyped = UNTYPED_DOUBLE.equals(kind) || UNTYPED_DECIMAL.equals(kind);
        if (untyped != (t == Untyped.class)) {
            throw invalid("kind " + kind + " does not match type " + type);
        }
        return t;
    }

    private Quantity.Scale scale() throws InvalidObjectException {
        if (scale == null) {
            throw invalid("null scale");
        }
        for (Quantity.Scale s : Quantity.Scale.values()) {
            if (s.name().equals(scale)) {
                return s;
            }
        }
        throw invalid("unknown scale " + scale);
    }

    private BigDecimal decimal() throws InvalidObjectException {
        if (unscaled == null) {
            throw invalid("null value");
        }
        if (unscaled.length == 0) {
            throw invalid("empty value");
        }
        return Precision.checked(new BigDecimal(new BigInteger(unscaled), decimalScale));
    }

    private MathContext mathContext() throws InvalidObjectException {
        if (precision < 0) {
            throw invalid("negative precision " + precision);
        }
        if (precision > Precision.MAX_PRECISION) {
            throw invalid("precision out of range: " + precision);
        }
        if (roundingMode == null) {
            throw invalid("null rounding mode");
        }
        for (RoundingMode m : RoundingMode.values()) {
            if (m.name().equals(roundingMode)) {
                return new MathContext(precision, m);
            }
        }
        throw invalid("unknown rounding mode " + roundingMode);
    }

    /** The provider's quantity re-created with exactly this value, scale and context ({@code null} for double). */
    private Object typed(Number value, Quantity.Scale s, MathContext mc) throws InvalidObjectException {
        Unit<?> system = factory.getSystemUnit();
        if (system == null) {
            throw invalid("factory for " + type + " has no system unit");
        }
        if (!(system instanceof UomUnit<?>)) {
            throw invalid("factory for " + type + " has a foreign system unit");
        }
        if (!unit.getDimension().equals(((UomUnit<?>) system).getDimension())) {
            throw invalid("unit " + unit + " of dimension " + unit.getDimension() + " does not measure " + type);
        }
        Quantity<?> q = create(factory, value, s);
        if (mc == null && q instanceof AbstractDoubleQuantity<?, ?> d) {
            return d.with(value.doubleValue(), unit, s);
        }
        if (mc != null && q instanceof AbstractDecimalQuantity<?, ?> d) {
            return d.with((BigDecimal) value, unit, s, mc);
        }
        throw invalid(
                "the factory for " + type + " created " + (q == null ? "null" : q.getClass().getName()) + ", not a "
                        + kind + " quantity of this implementation");
    }

    @SuppressWarnings({ "unchecked", "rawtypes" }) // typeClass is one of the quantity interfaces
    private QuantityFactory<?> factory() throws InvalidObjectException {
        QuantityFactory<?> f;
        try {
            f = Providers.quantityFactory((Class) typeClass);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            throw invalid("no quantity factory for " + type, e);
        } catch (RuntimeException | ServiceConfigurationError e) {
            // No provider at all, a broken service file, or a provider that fails otherwise.
            throw invalid(e.getMessage() != null ? e.getMessage() : e.getClass().getName(), e);
        }
        return f;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" }) // the unit's dimension matches the factory's system unit
    private Quantity<?> create(QuantityFactory factory, Number value, Quantity.Scale s) {
        return factory.create(value, (Unit) unit, s);
    }

    private static InvalidObjectException invalid(String message) {
        return new InvalidObjectException("invalid quantity: " + message);
    }

    private static InvalidObjectException invalid(String message, Throwable cause) {
        return (InvalidObjectException) new InvalidObjectException("invalid quantity: " + message).initCause(cause);
    }

    /** Wraps an unexpected failure; one without a message is named by its class. */
    private static InvalidObjectException invalid(RuntimeException e) {
        return invalid(e.getMessage() != null ? e.getMessage() : e.getClass().getName(), e);
    }
}
