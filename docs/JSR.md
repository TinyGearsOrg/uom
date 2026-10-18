# The JSR-385 implementation: `uom-jsr`

The artifact `uom-jsr` (JPMS module `org.tinygears.uom.jsr`) implements JSR-385 (`javax.measure`, unit-api 2.2; the TCK
passes 113 of 113 tests) on the engine of uom, with the same catalogs, formats, arithmetic and results as the native API.
It brings `uom`, `uom-engine` and `javax.measure:unit-api` 2.2, the only third-party runtime dependency.

```xml
<dependency>
  <groupId>org.tinygears</groupId>
  <artifactId>uom-jsr</artifactId>
  <version>0.1.0</version>
</dependency>
```

On the module path: `requires org.tinygears.uom.jsr;` (it requires `org.tinygears.uom`, `org.tinygears.uom.engine` and
`java.measure` transitively).

The rules of quantities and units are those of [NATIVE.md](NATIVE.md): decimals and precision, equality and
equivalence, offset units and `Scale`, systems of units and formats. This page covers what differs: the class names,
the JSR-385 typing, the service provider and the comparison with Indriya. [UNITS.md](UNITS.md) lists every unit.

| Native (`uom`) | JSR-385 (`uom-jsr`) |
|---|---|
| `org.tinygears.uom.quantity.Length` | `org.tinygears.uom.jsr.quantity.Length` (extends `javax.measure.quantity.Length`) |
| `Unit<Length>` | `UomUnit<javax.measure.quantity.Length>` (implements `javax.measure.Unit`) |
| `Scale` | `javax.measure.Quantity.Scale` |
| `UnitFormat`, `QuantityFormat` | `UomUnitFormat`, `UomQuantityFormat` (same texts) |
| `SystemOfUnits.load` | `UomSystemOfUnits.load` |
| `value()`, `unit()`, `scale()` | `getValue()`, `getUnit()`, `getScale()` |
| typed products (`Length.divide(Time)` is a `Speed`) | untyped products, narrowed with `as(Speed.class)` |

## Typed quantities and unit declarations

The typed interfaces have the simple names of the standard ones (`org.tinygears.uom.jsr.quantity.Length` extends
`javax.measure.quantity.Length`), so a file that imports ours cannot also import the standard type; and JSR-385's
generics (`Unit<Q extends Quantity<Q>>`) keep our types out of unit types. Declare units as `Unit<?>` or with `var` (or
name the standard type fully qualified), and quantities with our types:

```java jsr
var km = SI.KILOMETRE;                       // UomUnit<javax.measure.quantity.Length>
Length run = Length.of(10, km);
```

Every quantity of `uom-jsr` is a `UomQuantity`: the JSR-385 `Quantity` with our typed interface as the return type
of its operations, plus what the standard has no method for. Products, quotients and `UomQuantityFormat.parse` return a
`UomQuantity`, so:

- `as(Speed.class)` narrows to our typed interface, by dimension as `asType` does: `length.divide(time).as(Speed.class)`.
  `asType` cannot take our interface (`Speed` is not a `Quantity<Speed>`), so it needs the standard class and a cast.
- `isCloseTo(that, 1e-9)` (relative) and `isCloseTo(that, Length.of(1, SI.MILLIMETRE))` (absolute) compare within a
  tolerance you choose. The standard `isEquivalentTo` is exact for decimal quantities and uses a fixed `1e-12` for
  `double` quantities.
- `toSystem(system)`, `toDouble()` and `toDecimal(mc)` convert to a system's preferred unit and between number types.

```java jsr
Angle degrees = Angle.of(BigDecimal.ONE, SI.RADIAN).to(SI.DEGREE);   // rounded to 34 digits
System.out.println(degrees.isEquivalentTo(Angle.of(BigDecimal.ONE, SI.RADIAN))); // prints: false
System.out.println(degrees.isCloseTo(Angle.of(BigDecimal.ONE, SI.RADIAN), 1e-30)); // prints: true
```

A quantity whose static type is the standard `Quantity<?>`, such as one from code written against JSR-385 only, is
narrowed with `X.from`: `Speed.from(quantity)` narrows the same way, returns a quantity of this implementation as it is,
and refuses one of another implementation with a `ClassCastException`. `asType` on units and quantities goes by
dimension, as the JSR-385 Javadoc says: `Hz`, `Bq` and `1/s` are units of `Frequency` and of `Radioactivity`.

## Converting to and from the native API

The native and the JSR-385 quantities share their engine units, so `Jsr` converts between them without converting the
value: `Jsr.toJsr(length)` (or `Jsr.toJsr(length, org.tinygears.uom.jsr.quantity.Length.class)`) and
`Jsr.fromJsr(quantity, Length.class)` keep value, number type, context and scale.

## The service provider

uom registers one JSR-385 service provider, `UomServiceProvider`, with the name `TinyGears` and priority 100. Another
implementation on the same class path or module path registers its own: Indriya 2.2 is named `Default` and has priority
10. unit-api picks the **current** provider, the one with the highest priority unless the program sets one with
`ServiceProvider.setCurrent`. Generic JSR-385 code that calls `ServiceProvider.current()` gets the factories, formats and
systems of units of that provider.

With uom and Indriya both present, uom is current because its priority is higher. To use Indriya for generic code, set it:
`ServiceProvider.setCurrent(ServiceProvider.of("Default"))`. Providers are found by name, `TinyGears` or `Default`; a
provider without a name is found only by the value of its `toString()`.

```java jsr
import javax.measure.spi.ServiceProvider;
System.out.println(ServiceProvider.of("TinyGears").getPriority()); // prints: 100
System.out.println(ServiceProvider.current());                     // prints: TinyGears
```

uom does not depend on which provider is current. Narrowing a quantity or a unit with `asType`, reading a serialized
quantity, printing an unnamed unit and the system unit of a named unit need uom's own factories and formats, and they
find them themselves: the current provider if it is uom's, otherwise uom's provider among
`ServiceProvider.available()`, which is kept after the first lookup. The provider is part of `uom-jsr`, so it is always
there. The lookup uses the class loader of the first caller; in an application server with one copy of uom per
application this is the application's.

```java jsr
Quantity<?> speed = Length.of(6, SI.METRE).divide(Time.of(2, SI.SECOND)).asType(javax.measure.quantity.Speed.class);
System.out.println(speed);                                     // prints: 3.0 m/s
```

An unnamed unit, such as `SI.HERTZ.prefix(MetricPrefix.MILLI)` or `SI.METRE.multiply(1000)`, prints as the standard
format of uom writes it: `mHz`, `km`; if the format has no text for the unit (a negative factor, say), it prints the
definition: `-2·m`.

Quantities and units of two implementations are not interchangeable. A uom quantity rejects a unit or quantity of
another implementation with `UnconvertibleException` (`add`, `to`, `isEquivalentTo` and the others), so convert by value
and unit explicitly. A quantity written by one implementation is not read by the other. `ProviderCoexistenceTest` runs
uom and Indriya together in both directions.

## Compared with Indriya

Indriya is the reference implementation of JSR-385. Generic code that uses only the `javax.measure` API runs on both,
but the two answer some questions differently. The examples below run uom and Indriya 2.2.4 on one class path (in
`DocsSnippetsTest`, with Indriya as a test dependency) and print both results, so this section breaks when either
library changes. Most differences are deliberate (DESIGN, section 1: F5, F20, F21, F23). The examples use
`ServiceProvider.of("TinyGears")` and `ServiceProvider.of("Default")` (Indriya's provider) where generic code is meant,
and each library's own constants (`SI`, `Units`) otherwise.

### Equality is structural in uom

A uom unit equals another when both have the same atoms and the same converter to the system unit, however it was
written (F21). Indriya compares how the unit was built: `km` from a prefix and `m·1000` are different units there, though
they are equivalent. `isEquivalentTo` agrees in both.

```java jsr
import tech.units.indriya.unit.Units;
System.out.println(SI.KILOMETRE.equals(SI.METRE.multiply(1000)));                    // prints: true
System.out.println(MetricPrefix.KILO(Units.METRE).equals(Units.METRE.multiply(1000))); // prints: false
System.out.println(MetricPrefix.KILO(Units.METRE).isEquivalentTo(Units.METRE.multiply(1000))); // prints: true
// the same for N and kg·m/s², J and N·m, °C and K + 273.15
System.out.println(SI.NEWTON.equals(SI.KILOGRAM.multiply(SI.METRE).divide(SI.SECOND.pow(2)))); // prints: true
System.out.println(Units.NEWTON.equals(Units.KILOGRAM.multiply(Units.METRE).divide(Units.SECOND.pow(2)))); // prints: false
System.out.println(SI.DEGREE_CELSIUS.equals(SI.KELVIN.shift(273.15)));               // prints: true
System.out.println(Units.CELSIUS.equals(Units.KELVIN.shift(273.15)));                // prints: false
```

Both keep alternate units apart from what they are derived from: `Hz` is not `1/s` and `rad` is not `one` in either,
while both are equivalent.

### Names, symbols and how unnamed units print

uom gives prefixed units a symbol and a lower-case name (`km`, `kilometre`; `newton`); Indriya leaves a prefixed unit
without either and capitalises some names (`Newton`). An unnamed unit prints in uom as the standard format writes it,
which looks for an equal unit of the catalogs (`km`, `ft`); Indriya prints how it was built.

```java jsr
import tech.units.indriya.unit.Units;
System.out.println(SI.KILOMETRE.getSymbol() + " " + SI.KILOMETRE.getName());                 // prints: km kilometre
System.out.println(MetricPrefix.KILO(Units.METRE).getSymbol() + " " + MetricPrefix.KILO(Units.METRE).getName()); // prints: null null
System.out.println(SI.NEWTON.getName() + " " + Units.NEWTON.getName());                       // prints: newton Newton
System.out.println(SI.METRE.multiply(1000) + " " + SI.METRE.multiply(0.3048));                // prints: km ft
System.out.println(Units.METRE.multiply(1000) + " " + Units.METRE.multiply(0.3048));          // prints: m*1000 m*0.3048
```

### Exponents and special characters

The uom format writes ASCII exponents by default (`m/s^2`); `UomUnitFormat.withSuperscripts(true)` writes `m/s²`.
Indriya writes superscripts, and degree Celsius as the compatibility character `℃` (U+2103). Indriya writes the micro
prefix as the Greek letter mu `μ` (U+03BC); uom uses the micro sign `µ` (U+00B5) that `MetricPrefix.MICRO` defines.

```java jsr
import tech.units.indriya.unit.Units;
System.out.println(SI.METRE.divide(SI.SECOND.pow(2)) + " " + Units.METRE.divide(Units.SECOND.pow(2))); // prints: m/s^2 m/s²
System.out.println(SI.CUBIC_METRE + " " + Units.CUBIC_METRE);                         // prints: m³ m³
System.out.println(SI.METRE.pow(3) + " " + Units.METRE.pow(3));                       // prints: m^3 m³
System.out.println(SI.DEGREE_CELSIUS + " " + Units.CELSIUS);                          // prints: °C ℃
System.out.println(SI.METRE.prefix(MetricPrefix.MICRO) + " " + MetricPrefix.MICRO(Units.METRE)); // prints: µm μm
```

uom reads the Greek mu as the micro prefix, so `μm` that Indriya wrote parses back (and prints as `µm`); `℃` and `㎥`
do not parse (DESIGN DR-26; see "Parsing units").

### System units of prefixed derived units

Both give the named system unit: `kPa` has the system unit `Pa`, so a pressure in kilopascal converted to system units
prints as `5000.0 Pa`. In uom a named unit takes the system unit of its quantity type (DESIGN DR-25); an unnamed
product keeps the label of its base units, so `N·m` does not become `J`.

```java jsr
import tech.units.indriya.unit.Units;
System.out.println(SI.KILOPASCAL.getSystemUnit());                                    // prints: Pa
System.out.println(MetricPrefix.KILO(Units.PASCAL).getSystemUnit());                  // prints: Pa
System.out.println(SI.NEWTON.multiply(SI.METRE).getSystemUnit());                     // prints: kg·m^2/s^2
```

### Values are double or BigDecimal in uom

A uom quantity holds a `double` or a `BigDecimal` (F5); any other `Number` becomes a `double`. Indriya keeps the
`Number` it was given, so an `Integer` stays an `Integer` and prints without a decimal point, and integer division
gives a `BigDecimal`.

```java jsr
import tech.units.indriya.quantity.Quantities;
import tech.units.indriya.unit.Units;
System.out.println(Length.of(1, SI.KILOMETRE));                                        // prints: 1.0 km
System.out.println(Quantities.getQuantity(1, MetricPrefix.KILO(Units.METRE)));         // prints: 1 km
System.out.println(Length.of(1, SI.METRE).divide(3).getValue());                      // prints: 0.3333333333333333
System.out.println(Quantities.getQuantity(1, Units.METRE).divide(3).getValue());      // prints: 0.3333333333333333333333333333333333
```

### Equality is typed and keeps the precision apart

Quantity `equals` is strict in both: `1 km` does not equal `1000 m`, and `compareTo` gives 0 for them. uom also keeps
`double` and `BigDecimal` quantities apart (F20) and a typed quantity apart from an untyped one of the same unit;
Indriya compares the values numerically and has a single quantity class.

```java jsr
import javax.measure.spi.ServiceProvider;
import tech.units.indriya.quantity.Quantities;
import tech.units.indriya.unit.Units;
System.out.println(Length.of(1, SI.METRE).equals(Length.of(BigDecimal.ONE, SI.METRE)));   // prints: false
System.out.println(Quantities.getQuantity(1.0, Units.METRE).equals(Quantities.getQuantity(BigDecimal.ONE, Units.METRE))); // prints: true
System.out.println(Length.of(1, SI.METRE).isEquivalentTo(Length.of(BigDecimal.ONE, SI.METRE))); // prints: true
var uomGeneric = ServiceProvider.of("TinyGears").getQuantityFactory(javax.measure.quantity.Dimensionless.class);
var indriyaGeneric = ServiceProvider.of("Default").getQuantityFactory(javax.measure.quantity.Dimensionless.class);
System.out.println(Length.of(1, SI.METRE).equals(uomGeneric.create(1, (Unit) SI.METRE)));  // prints: false
System.out.println(Quantities.getQuantity(1, Units.METRE).equals(indriyaGeneric.create(1, (Unit) Units.METRE))); // prints: true
```

### isEquivalentTo has a tolerance in uom

uom treats two `double` quantities as equivalent when their values in the same unit agree within the relative
tolerance `1e-12` (`AbstractDoubleQuantity.EQUIVALENCE_TOLERANCE`, F20), so rounding noise from a conversion does not
matter; two `BigDecimal` quantities are compared exactly, as Indriya compares every pair (DR-18). Quantities of
different dimensions are not equivalent in uom; Indriya throws.

```java jsr
import tech.units.indriya.quantity.Quantities;
import tech.units.indriya.unit.Units;
System.out.println(Length.of(1, SI.METRE).isEquivalentTo(Length.of(Math.nextUp(1.0), SI.METRE)));  // prints: true
System.out.println(Quantities.getQuantity(1.0, Units.METRE).isEquivalentTo(Quantities.getQuantity(Math.nextUp(1.0), Units.METRE))); // prints: false
System.out.println(Length.of(1, SI.METRE).isEquivalentTo(Length.of(1.000000001, SI.METRE)));       // prints: false
System.out.println(((Quantity) Length.of(1, SI.METRE)).isEquivalentTo(Time.of(1, SI.SECOND)));      // prints: false
try {
    ((Quantity) Quantities.getQuantity(1, Units.METRE)).isEquivalentTo(Quantities.getQuantity(1, Units.SECOND));
} catch (UnconvertibleException e) {
    System.out.println("Indriya throws");                                              // prints: Indriya throws
}
```

### Parsing quantities

The number type of a parsed quantity is chosen by the format in uom and by the text in Indriya. The uom quantity format
parses every number as a `double`; with `withDecimals(mc)` it parses every number as a `BigDecimal` that keeps its
digits and scale, with `mc` as the context of later arithmetic. Indriya's default format (`SimpleQuantityFormat`) never
gives a `double`: a number without a fraction becomes the smallest integer type that holds it, one with a fraction a
`BigDecimal`.

| Text | uom | uom `withDecimals` | Indriya |
|---|---|---|---|
| `2`, `-1` | `Double` | `BigDecimal` | `Integer` |
| `2147483648` | `Double` | `BigDecimal` | `Long` |
| `9223372036854775808` | `Double` | `BigDecimal` | `BigInteger` |
| `1.5`, `1.50`, `0.1` | `Double` | `BigDecimal`, scale kept | `BigDecimal`, scale kept |
| `1e-3` | `Double` | `BigDecimal` | `BigDecimal` |
| `1e3`, `1.5e3` | `Double` | `BigDecimal` | `Integer` |
| `1e400` | error | `BigDecimal` | `BigInteger` of 401 digits |
| `NaN`, `Infinity` | `Double` | error | error |

This difference is deliberate. The arithmetic of a quantity follows its number type (in Indriya, `2 m` divided by 3 is
a 34-digit `BigDecimal`, while in uom the context decides), so with Indriya two lines of the same input can convert with
different types and rounding although the caller chose neither. In uom the caller chooses the precision once, on the
format, and every parsed quantity converts with it; mixing a `double` and a decimal quantity gives a decimal (F23).

```java jsr
import javax.measure.format.QuantityFormat;
import javax.measure.spi.ServiceProvider;
QuantityFormat uom = ServiceProvider.of("TinyGears").getFormatService().getQuantityFormat();
QuantityFormat indriya = ServiceProvider.of("Default").getFormatService().getQuantityFormat();
UomQuantityFormat decimals = new UomQuantityFormat(UomUnitFormat.standard()).withDecimals(MathContext.DECIMAL128);
for (String text : List.of("2 m", "1.50 m", "1e3 m")) {
    Number a = uom.parse(text).getValue();
    Number b = decimals.parse(text).getValue();
    Number c = indriya.parse(text).getValue();
    System.out.println(a + " " + a.getClass().getSimpleName() + " | " + b + " " + b.getClass().getSimpleName()
            + " | " + c + " " + c.getClass().getSimpleName());
}
// prints: 2.0 Double | 2 BigDecimal | 2 Integer
// prints: 1.5 Double | 1.50 BigDecimal | 1.50 BigDecimal
// prints: 1000.0 Double | 1E+3 BigDecimal | 1000 Integer
```

Neither default format parses `1.5km` or `1,5 km`; uom reads `1,5 km` with `withLocale(Locale.GERMANY)`. Indriya's
other quantity formats (`NUMBERDELIMITER`, `EBNF`, `LOCAL`) use `java.text.NumberFormat`, give a `Double` for `1.5`,
and read the start of a number they cannot read whole without an error: `1e3 m` and `1e-3 m` parse as 1, `0x10 m` as 0,
`1_000 m` as 1, and `1,5 m` as 15 in an English locale. uom reads `1e3 m` and `1e-3 m` as 1000 and 0.001 and rejects the
others.

### Parsing units

Both parse the SI units and symbols, run-time metric prefixes (`Mm`, `dam`, `mHz`, `kK`) and formulas with `/`, `·`,
`*` and `^`. The other symbols differ:

| Text | uom | Indriya |
|---|---|---|
| `L`, `mL` (litre) | yes | no |
| `l`, `ml` (litre; uom prints `L`, `mL`) | yes | yes |
| `t`, `ha`, `eV`, `Da`, `au` (accepted for use with the SI) | yes | no |
| `in`, `ft`, `yd`, `mi`, `lb`, `oz` and the other catalogs ([UNITS.md](UNITS.md)) | yes | no |
| `bit`, `B`, `kB`, `MiB` | yes | no |
| `1/s`, `1` | yes | no |
| `kg m/s^2` (a space as product), `s⁻¹` (superscript minus) | yes | no |
| `one`, `%` | no | yes |
| `m2` (exponent without `^`) | no | yes |
| `μm` (Greek mu; uom prints `µm`) | yes | yes |
| `℃`, `m°C`, `㎥` | no | yes |
| `bar`, `kWh`, `ohm`, `atm`, `mmHg`, `Å`, `°`, `′`, `″`, `um` | no | no |

uom has the degree, arcminute and arcsecond as `deg`, `arcmin` and `arcsec`.

```java jsr
import javax.measure.format.UnitFormat;
import javax.measure.spi.ServiceProvider;
UnitFormat uom = ServiceProvider.of("TinyGears").getFormatService().getUnitFormat();
UnitFormat indriya = ServiceProvider.of("Default").getFormatService().getUnitFormat();
System.out.println(uom.parse("kg m/s^2") + " " + uom.parse("L") + " " + uom.parse("MiB"));        // prints: kg·m/s^2 L MiB
System.out.println(indriya.parse("m2") + " " + indriya.parse("l") + " " + indriya.parse("%"));   // prints: m² l %
```

### A parsed formula keeps its text

A unit that uom parses from a formula keeps the formula, normalised, as its symbol: `m*s^-1` prints as `m·s^-1`,
though it equals `m/s`. Indriya prints the unit it built: `m/s`. uom keeps what the user wrote (DESIGN DR-27): printing
the equal catalog unit would turn `kg·m^2/s^2` into `J`.

```java jsr
import javax.measure.format.UnitFormat;
import javax.measure.spi.ServiceProvider;
UnitFormat uom = ServiceProvider.of("TinyGears").getFormatService().getUnitFormat();
UnitFormat indriya = ServiceProvider.of("Default").getFormatService().getUnitFormat();
System.out.println(uom.parse("m*s^-1") + " " + uom.parse("m*s^-1").equals(SI.METRE_PER_SECOND));  // prints: m·s^-1 true
System.out.println(indriya.parse("m*s^-1"));                                                      // prints: m/s
```

### Format names

Indriya offers several named formats (`SIMPLE_ASCII`, `EBNF`, `LOCAL`, `SIMPLE_DEFAULT` for units; `EBNF`, `LOCAL`,
`SIMPLE`, `NUMBERDELIMITER` for quantities); uom has one, `default`, for each, configured through `UomUnitFormat` and
`UomQuantityFormat` (`withSuperscripts`, `withLocale`, `withDecimals`).

### Offset arithmetic

uom and Indriya 2.2 agree on two `ABSOLUTE` operands (`2 °C + 1 °C` is `276.15 °C`), on a point plus or minus a
difference, a difference plus a difference, a difference times a number, and the conversion of a difference. They
differ on a difference minus a point: Indriya 2.2 returned `-545.30 °C` for `2 °C` (relative) minus `1 °C` (absolute);
uom returns the delta `1 °C`. Indriya creates products of offset units (`℃/m`) but cannot convert them
(`UnsupportedOperationException: ℃ is non-linear, cannot convert`); uom converts them by their factor
([NATIVE.md](NATIVE.md), "Offset units").

### Only in Indriya

Indriya 2.2.4 goes beyond JSR-385 in several places that uom does not cover, by decision for 1.0 (DESIGN, section 1):

| Indriya | uom |
|---|---|
| Any `Number` as a value (`NumberSystem`, `RationalNumber`) | `double` and `BigDecimal` |
| Mixed quantities such as `1 ft 6 in` (`MixedQuantity`, `MixedRadix`) | none |
| Ranges of quantities (`QuantityRange`) | none |
| Logarithmic and exponential converters (`LogConverter`, `ExpConverter`) | affine converters only |
| Annotated units, `m{annotation}` (`AnnotatedUnit`) | none |
| `EBNFUnitFormat`, and `LocalUnitFormat` with symbols per locale | one standard format with labels; locale-aware numbers |
| Stream helpers (`QuantityStreams`, `QuantitySummaryStatistics`) | none |
| Quantity types of any interface | the 33 standard types; poise, stokes and oersted are untyped |
