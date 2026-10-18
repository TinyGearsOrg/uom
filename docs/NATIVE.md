# The native API: `uom`

The artifact `uom` (JPMS module `org.tinygears.uom`) is the API of uom: quantities and units typed with this library's
33 quantity types, typed products, `double` and exact `BigDecimal` values, eight catalogs of units, formats and user
`.units` files. It depends only on `uom-engine`. For JSR-385 (`javax.measure`), see [JSR.md](JSR.md); everything below
holds for `uom-jsr` too, with the differences listed there.

```xml
<dependency>
  <groupId>org.tinygears</groupId>
  <artifactId>uom</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

On the module path: `requires org.tinygears.uom;`. Java 17 or later; not published yet (build it with
`mvn -q -B install -DskipTests`).

Packages: `org.tinygears.uom` (`Quantity`, `Unit`, `Scale`, `Untyped`, `UnitFormat`, `QuantityFormat`,
`SystemOfUnits`), `org.tinygears.uom.quantity` (the 33 generated types, `Length`, `Speed`, ...) and
`org.tinygears.uom.systems` (the generated catalogs `SI`, `USCustomary`, `Imperial`, `CGS`, `ESU`, `EMU`, `Gaussian`,
`Information`; [UNITS.md](UNITS.md) lists every unit).

## Quantities

A quantity has a value, a unit and a scale. `Length.of(42.195, SI.KILOMETRE)` holds a `double`,
`Length.of(new BigDecimal("0.1"), SI.METRE)` a `BigDecimal` with a `MathContext` (default `DECIMAL128`). Units are typed
with the same types: `Unit<Length> km = SI.KILOMETRE`.

```java
Length marathon = Length.of(42.195, SI.KILOMETRE);
System.out.println(marathon.to(USCustomary.MILE));                 // prints: 26.218757456454306 mi
Speed pace = marathon.divide(Time.of(3, SI.HOUR));                  // a typed product: Length / Time is a Speed
System.out.println(pace.to(USCustomary.MILE_PER_HOUR));             // prints: 8.739585818818103 mph
Temperature boiling = Temperature.of(100, SI.DEGREE_CELSIUS);
System.out.println(boiling.toSystem(USCustomary.SYSTEM));           // prints: 212.0 °F
Length exact = Length.of(new BigDecimal("0.1"), SI.METRE).add(Length.of(new BigDecimal("0.2"), SI.METRE));
System.out.println(exact);                                          // prints: 0.3 m
QuantityFormat format = QuantityFormat.standard();
System.out.println(format.parse("20 km").as(Length.class).to(SI.METRE));
// prints: 20000.0 m
```

Quantities and units are immutable and thread-safe. `add`, `subtract`, `negate` and the scalar `multiply(Number)` and
`divide(Number)` keep the type and the unit of the left operand; `to(unit)` converts; `as(Speed.class)` narrows an
`Untyped` quantity to a type of the same system unit and throws `ClassCastException` otherwise.

## Typed products

Products and quotients are typed where a relation between the types is declared (`Length / Time` is a `Speed`,
`Force · Length` an `Energy`; [data/quantity-relations.txt](../data/quantity-relations.txt) lists the 22 relations),
else `Untyped`. The result is in system units.

```java
Energy work = Force.of(10, SI.NEWTON).multiply(Length.of(2, SI.METRE));
Power power = work.divide(Time.of(4, SI.SECOND));
System.out.println(power);                                          // prints: 5.0 W
Untyped momentum = Mass.of(2, SI.KILOGRAM).multiply(Speed.of(3, SI.METRE_PER_SECOND));
System.out.println(momentum);                                       // prints: 6.0 kg·m/s
```

## Units

A unit is a product of atoms (the seven SI base units and the alternate units such as `rad`, `Hz`, `bit`) with an exact
factor (rationals, powers of pi and roots), an optional offset, a symbol and a name. Equality is structural: `km` equals
`m` times 1000 and `N` equals `kg·m/s^2`, whatever their labels; `Hz` and `1/s` are different units, equivalent to each
other. `unit.systemUnit()` is the named system unit of the unit's type (`Pa` for `kPa`); an unnamed product keeps the
label of its atoms (`N·m` has `kg·m^2/s^2`). A unit with an offset (`°C`) takes part in products by its factor only,
see "Offset units" below.

## Decimals and precision

A `double` quantity computes in `double`. A decimal quantity computes exactly and rounds every result once, to its
`MathContext`; `UNLIMITED` keeps results exact where they terminate. When a `double` and a decimal meet, the decimal
wins: the `double` is read as its shortest decimal (`0.2` is `0.2`) and the result is decimal, in either operand order.
Two decimals give the smaller context. `toDouble()` and `toDecimal(mc)` convert explicitly. A decimal value or context
has at most 50000 digits.

```java
Length tenth = Length.of(new BigDecimal("0.1"), SI.METRE);
System.out.println(tenth.add(Length.of(0.2, SI.METRE)));                                   // prints: 0.3 m
System.out.println(Length.of(1, SI.METRE).divide(3));                                      // prints: 0.3333333333333333 m
System.out.println(Length.of(BigDecimal.ONE, SI.METRE, Scale.ABSOLUTE, MathContext.DECIMAL32).divide(3)); // prints: 0.3333333 m
```

## Equality, equivalence and closeness

`equals` is strict: same type, unit, scale and value, `double` and decimal kept apart (`1 km` does not equal `1000 m`).
`isEquivalentTo` compares the amounts: exactly for two decimals, within a relative `1e-12` when a `double` is involved.
`isCloseTo` takes the tolerance you choose, relative or as a quantity. `compareTo` orders by amount and is not
consistent with `equals`.

```java
Length km = Length.of(1, SI.KILOMETRE);
System.out.println(km.equals(Length.of(1000, SI.METRE)) + " " + km.isEquivalentTo(Length.of(1000, SI.METRE))); // prints: false true
System.out.println(km.isCloseTo(Length.of(1001, SI.METRE), 1e-2));                       // prints: true
System.out.println(km.isCloseTo(Length.of(1001, SI.METRE), Length.of(1, SI.CENTIMETRE))); // prints: false
```

## Offset units

Some units have an offset: `0 °C` is `273.15 K`, and `32 °F` is `0 °C`. The same number then means two different
things: a **point** on the scale ("it is 20 °C outside") or a **difference** between two points ("it is 5 °C warmer").
A point converts through the offset (20 °C is 293.15 K); a difference does not (a rise of 5 °C is a rise of 5 K).

uom keeps the two apart with the scale of every quantity (`Scale`; in `uom-jsr` the standard `Quantity.Scale`, with the
same values and rules):

| Scale | Meaning | How to get one |
|---|---|---|
| `ABSOLUTE` (default) | a point; conversions apply the offset | `Temperature.of(20, SI.DEGREE_CELSIUS)` |
| `RELATIVE` | a difference; conversions use the factor only | `Temperature.of(5, SI.DEGREE_CELSIUS, Scale.RELATIVE)` |

For a unit without an offset (metre, kelvin, hertz, ...) the scale makes no difference to any result. It is kept and
compared by `equals`, written by serialization and not written by the format; a parse always gives `ABSOLUTE`.

### The rules

| Operation | Operands | Result |
|---|---|---|
| `to(unit)`, `toSystem(system)` | ABSOLUTE | converted through the offset, scale ABSOLUTE |
| | RELATIVE | converted by the factor only (5 K is 5 °C), scale RELATIVE |
| `add`, `subtract` | neither is RELATIVE | the literal rule below, scale ABSOLUTE |
| | one or both are RELATIVE | a plain delta: the right operand converted by the factor only and combined with the left value in the left unit; scale RELATIVE if both are RELATIVE, else ABSOLUTE |
| `multiply(Number)`, `divide(Number)` | ABSOLUTE | the literal rule below, scale ABSOLUTE |
| | RELATIVE | the value times or over the number, scale RELATIVE |
| `negate` | any | the value negated in the same unit, scale kept |
| `compareTo`, `isEquivalentTo` | both RELATIVE | compared as differences (factor only) |
| | otherwise | compared as system-unit values (offset applied) |
| `multiply(Quantity)`, `divide(Quantity)`, `inverse()` | an ABSOLUTE operand | its system-unit value, offset applied (a point) |
| | a RELATIVE operand | its system-unit value by the factor only (a difference) |
| Unit products: `multiply(Unit)`, `divide(Unit)`, `pow`, `root`, `inverse` and formulas such as `°C/m` | any | the offset is dropped; see below |

The **literal rule** is the one in the `javax.measure.Quantity` Javadoc: the operands behave as if they were first
converted to the system unit, combined, and the result converted back to the unit of the left operand. The Javadoc does
not mention `Scale`. For a unit without an offset the rule is plain arithmetic. For an offset unit it gives results
that are correct for conversion but odd for sums of points, because adding two points has no physical meaning.

A quantity-by-quantity product returns an `ABSOLUTE` quantity in system units, whatever the scales of the operands:
typed where a relation between the types is declared, else untyped.

### Derived units: °C/m, J/(kg·°C)

A unit that is a product of an offset unit with other units measures differences: a gradient in °C/m, a specific heat in
J/(kg·°C), a rate in °C/s. So an offset unit takes part in `multiply(Unit)`, `divide(Unit)`, `pow`, `root` and `inverse`,
and in formulas, by its **factor only**: `°C/m` is `K/m`, `°C·m` is `K·m`, and `°F/m` is 5/9 of `K/m`. The unit keeps its
symbol (`°C/m`), is equal to the unit built from kelvin, and parses and formats. `pow(1)` and `root(1)` return the unit
itself, offset included.

The offset is dropped without a warning, so such a unit must not be used for point values: 20 °C divided by 2 m as
`ABSOLUTE` quantities is 146.575 K/m (the point 293.15 K, over 2 m), while the gradient 20 °C/m is 20 K/m.

### Examples

A point plus a difference is a point; a difference converts without the offset:

```java
Temperature boiling = Temperature.of(100, SI.DEGREE_CELSIUS);
Temperature rise = Temperature.of(5, SI.DEGREE_CELSIUS, Scale.RELATIVE);
System.out.println(boiling.add(rise));            // prints: 105.0 °C
System.out.println(rise.to(SI.KELVIN));           // prints: 5.0 K
System.out.println(boiling.to(SI.KELVIN));        // prints: 373.15 K
```

A difference in kelvin is added to a point in degrees Celsius by its size, not through the offset:

```java
Temperature start = Temperature.of(2, SI.DEGREE_CELSIUS);
Temperature gain = Temperature.of(5, SI.KELVIN, Scale.RELATIVE);
System.out.println(start.add(gain));              // prints: 7.0 °C
System.out.println(start.subtract(gain));         // prints: -3.0 °C
```

Two differences give a difference, and a difference can be scaled:

```java
Temperature a = Temperature.of(2, SI.DEGREE_CELSIUS, Scale.RELATIVE);
Temperature b = Temperature.of(1, SI.DEGREE_CELSIUS, Scale.RELATIVE);
System.out.println(a.add(b));                     // prints: 3.0 °C
System.out.println(a.multiply(2));                // prints: 4.0 °C
System.out.println(a.isEquivalentTo(Temperature.of(2, SI.KELVIN, Scale.RELATIVE))); // prints: true
```

Two points follow the literal rule (the default scale is `ABSOLUTE`):

```java
Temperature two = Temperature.of(2, SI.DEGREE_CELSIUS);
Temperature one = Temperature.of(1, SI.DEGREE_CELSIUS);
System.out.println(two.add(one));                 // prints: 276.15 °C
System.out.println(two.subtract(one));            // prints: -272.15 °C
System.out.println(two.multiply(2));              // prints: 277.15 °C
```

To get the difference between two points, subtract them in a unit without an offset, and mark the result as a
difference when it should convert without the offset (a point minus a point is not returned as a difference):

```java
Temperature later = Temperature.of(25, SI.DEGREE_CELSIUS);
Temperature earlier = Temperature.of(20, SI.DEGREE_CELSIUS);
Temperature kelvin = later.to(SI.KELVIN).subtract(earlier.to(SI.KELVIN));
Temperature difference = Temperature.of(kelvin.value().doubleValue(), SI.KELVIN, Scale.RELATIVE);
System.out.println(difference.to(SI.DEGREE_CELSIUS)); // prints: 5.0 °C
```

A difference divided by a length is a gradient, and the derived units parse and convert by their factor:

```java
Temperature rise = Temperature.of(20, SI.DEGREE_CELSIUS, Scale.RELATIVE);
Temperature point = Temperature.of(20, SI.DEGREE_CELSIUS);
Length two = Length.of(2, SI.METRE);
System.out.println(rise.divide(two));             // prints: 10.0 K/m
System.out.println(point.divide(two));            // prints: 146.575 K/m
System.out.println(UnitFormat.standard().parse("°C/m").equals(UnitFormat.standard().parse("K/m"))); // prints: true
System.out.println(UnitFormat.standard().parse("J/(kg·°C)").equals(UnitFormat.standard().parse("J/(kg·K)"))); // prints: true
System.out.println(SI.DEGREE_CELSIUS.divide(SI.METRE));  // prints: °C/m
```

`BigDecimal` quantities follow the same rules and are exact, where `double` shows the rounding of the offset:

```java
Temperature hot = Temperature.of(new BigDecimal("20"), SI.DEGREE_CELSIUS);
Temperature zero = Temperature.of(new BigDecimal("0"), SI.DEGREE_CELSIUS);
System.out.println(hot.subtract(zero));           // prints: -253.15 °C
System.out.println(Temperature.of(20, SI.DEGREE_CELSIUS).subtract(Temperature.of(0, SI.DEGREE_CELSIUS)));
// prints: -253.14999999999998 °C
```

The rule for two `ABSOLUTE` operands is the Javadoc's and agrees with Indriya 2.2 (`2 °C + 1 °C` is `276.15 °C`). The
`Unit` Javadoc names Celsius per metre as a supported product; Indriya creates such units but cannot convert them, uom
converts them by their factor. The TCK tests neither `Scale` nor offset arithmetic (DESIGN DR-01, DR-03).

## Systems of units

A system is a catalog of units plus a preferred unit for each of the 33 quantity types; `quantity.toSystem(system)`
converts to it. Every unit is an exact factor of SI units over the SI dimensions: `CGS` holds `cm`, `g` and `s`, but
they are not base units of their own, so `systemUnit()` is always the SI unit, and a quantity without a type, such as a
product in `kg·m/s`, has no unit in a system (`toSystem` throws `UnsupportedOperationException`; convert it with
`to(unit)`). For `ESU`, `EMU` and `Gaussian` this means that values convert correctly, but the formulas of those systems
do not hold: in Gaussian units a charge has the dimension g^½·cm^3/2·s^-1 and the B and H fields share a dimension,
while here every unit keeps its SI dimension, so the gauss and the oersted are not compatible. Systems with base units
or dimensions of their own are out of scope (DESIGN DR-13). A type without a preferred unit of its own (or of a parent
system) takes SI's, which is then not a member of the system: `USCustomary.SYSTEM` converts a time to `s` but has no
unit `s` (DR-24).

```java
System.out.println(Force.of(1, SI.NEWTON).toSystem(CGS.SYSTEM));                // prints: 100000.0 dyn
System.out.println(ElectricCharge.of(1, SI.COULOMB).toSystem(Gaussian.SYSTEM)); // prints: 2.99792458E9 Fr
System.out.println(EMU.GAUSS.isCompatible(EMU.OERSTED));                         // prints: false
```

## Formats

`UnitFormat.standard()` parses and formats unit symbols and formulas (`kg·m/s^2`) over all eight systems: exact
symbols, metric prefixes on prefixable units (`Mm`, `mHz`), binary prefixes on information units (`MiB`), and, read
but never written, the Greek mu for micro and `l` for the litre. `QuantityFormat.standard()` (or
`new QuantityFormat(unitFormat)`) formats a quantity as value, space, unit, in `Locale.ROOT`; both are immutable. A
parsed unit or quantity has the type of its system unit (`90 km/h` is a `Speed`), else it is `Untyped`.

- `withLocale(Locale.GERMANY)` writes and reads the number in that locale (`1,5 km`); unit symbols are never localized.
  Grouping separators are never written and are a parse error unless `withGrouping(true)`.
- `withDecimals(MathContext.DECIMAL64)` parses to `BigDecimal` quantities that keep every digit and the scale of the
  text (`1.50 km` has scale 2); the default parses to `double`.
- `UnitFormat.withSuperscripts(true)` writes `m/s²`; superscripts are always read.
- `label(unit, "kph")` gives a unit a text in that format instance only; `toString` never uses labels.
- A `jlink` image of an application that formats in a locale other than English needs the module
  `jdk.localedata` (`--add-modules jdk.localedata`); without it, `Locale.GERMANY` formats `1.5 km`.
- An offset unit without a symbol formats as a shifted formula (the kelvin shifted by 5 is `K + 5`, `5|9·K + 459.67` is
  the degree Fahrenheit) and parses back to an equal unit. Units with a negative or root factor, an irrational shift
  or a formula over 10,000 characters have no faithful form: `format` throws `IllegalArgumentException`. A glued minus
  before an integer (`s-1`) is an error: write `s^-1` or `s - 1`.

```java
QuantityFormat german = QuantityFormat.standard().withLocale(Locale.GERMANY);
System.out.println(german.format(Length.of(1.5, SI.KILOMETRE)));          // prints: 1,5 km
QuantityFormat decimals = QuantityFormat.standard().withDecimals(MathContext.DECIMAL64);
Quantity<?> parsed = decimals.parse("1.50 km");
System.out.println(parsed + " " + ((BigDecimal) parsed.value()).scale()); // prints: 1.50 km 2
System.out.println(UnitFormat.standard().format(SI.KELVIN.shift(5))); // prints: K + 5
```

## Your own units

A `.units` file defines units, one per line as `symbol:name formula` (`_` in a name is a space); `SystemOfUnits.load`
reads it at run time and `UnitFormat.with(system)` adds it to a format. A unit gets the quantity type of its system
unit, else it is `Untyped`. [CUSTOM-UNITS.md](CUSTOM-UNITS.md) is the full guide.

```units medieval.units
@system Medieval                # system name (else the file name)
league:league   3 mi            # formulas use the built-in symbols
furlong:furlong 220 yd
rod:rod         1|40 furlong    # exact fraction
@prefixed league k              # adds kleague
@preferred Length furlong       # q.toSystem(system) gives furlongs
```

```java
SystemOfUnits medieval = SystemOfUnits.load(Path.of("medieval.units"));
QuantityFormat format = new QuantityFormat(UnitFormat.standard().with(medieval));
Length d = format.parse("2 league").as(Length.class);
d.toSystem(medieval);                                  // 48.0 furlong
```

## Serialization

Units and quantities are `Serializable` through serialization proxies that re-check every invariant on reading. Never
read untrusted streams without an `ObjectInputFilter`: [SECURITY.md](../SECURITY.md) has the recommended filter. The
serialized form may change before 1.0. Systems and formats are not serializable.

## JSR-385

`uom-jsr` implements JSR-385 on the same engine; `Jsr.toJsr(length)` and `Jsr.fromJsr(quantity, Length.class)` convert
between the two APIs without converting the value ([JSR.md](JSR.md)).

## Not included

Values are `double` or `BigDecimal`; there are no mixed quantities (`1 ft 6 in`), quantity ranges, logarithmic units,
annotated units, unit symbols per locale or quantity types other than the 33 standard ones (DESIGN, section 1).
