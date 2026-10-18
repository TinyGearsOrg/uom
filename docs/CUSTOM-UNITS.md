# Custom units

A `.units` file defines units in text. `SystemOfUnits.load` reads one at run time and returns a system of units
that formats, parses and converts like the built-in catalogs. The examples use `uom`; in `uom-jsr` the same classes are
`SystemOfUnits`, `UnitFormat` and `QuantityFormat`, with the same rules and messages. The built-in catalogs (`SI`, `USCustomary`, `Imperial`,
`CGS`, `ESU`, `EMU`, `Gaussian`, `Information`, see [UNITS.md](UNITS.md)) are generated from files in the same syntax in
[`data/`](../data); those files are not shipped, and a few constructs only matter to the generator (noted below).

Every `units` and `java` block of this document is loaded, compiled and, where it shows `// prints:`, run by
`DocsSnippetsTest`; every `units-error` block fails with the message of its `# error:` line. The `units` blocks are
loaded by both APIs.

## A first file

```units medieval.units
@system Medieval                # the system name
league:league   3 mi            # symbol:name formula
furlong:furlong 220 yd
rod:rod         1|40 furlong    # an exact fraction
@prefixed league k              # adds kleague
@preferred Length furlong       # q.toSystem(system) gives furlongs for a Length
```

```java
String text = """
        @system Medieval
        league:league   3 mi
        furlong:furlong 220 yd
        rod:rod         1|40 furlong
        @prefixed league k
        @preferred Length furlong
        """;
SystemOfUnits medieval = SystemOfUnits.load("medieval.units", new StringReader(text), null);
QuantityFormat format = new QuantityFormat(UnitFormat.standard().with(medieval));
Quantity<?> parsed = format.parse("2 league");
Length distance = parsed.as(Length.class);
System.out.println(medieval.name() + " " + medieval.units());  // prints: Medieval [league, furlong, rod, kleague]
System.out.println(distance.toSystem(medieval));                          // prints: 48.0 furlong
System.out.println(distance.to(SI.KILOMETRE));                      // prints: 9.656064 km
System.out.println(format.parse("1 Mleague").as(Length.class).to(SI.KILOMETRE));
// prints: 4828032.0 km
```

A loaded file cannot add Java types: a unit gets the quantity type of its system unit (the league is a `Unit<Length>`),
else it is a `Unit<Untyped>`, and `as` narrows a parsed quantity to one of the 33 types. In `uom-jsr` the loaded units
are `Unit<?>`, narrowed with `asType`.

## Loading

- `SystemOfUnits.load(Path file)` reads the file as UTF-8. `@include` and `@extends` names, in nested files too,
  resolve against the directory of this root file (one flat namespace). A name must be relative and stay inside that
  directory, both as written and after resolving symbolic links, else it cannot be included. The system name is the
  `@system` name, else the file name without its extension. An unreadable root file throws `IOException`
  (`NoSuchFileException`, `MalformedInputException` when it is not UTF-8).
- `SystemOfUnits.load(String name, Reader reader, DefinitionParser.Resolver resolver)` reads from any `Reader` (not
  closed). `name` is used in messages and, verbatim with its extension, as the system name when there is no `@system`
  line. `resolver` opens included and extended files by name (null: none, so `@include` and `@extends` fail).
- An error in a file, included files too, throws `DefinitionException` with the message `file:line: text`.
- Nothing is registered globally, except `~` lines (see the caveats). To parse and format the new symbols, add the
  system to a format: `UnitFormat.standard().with(system)` returns a new format and leaves the old one unchanged.

```java
SystemOfUnits fromFile = SystemOfUnits.load(Path.of("units/medieval.units"));
```

```java
Map<String, String> files = Map.of(
        "common.units", "league:league 3 mi\n",
        "shop.units", "@include common.units\nleagues:double_league 2 league\n");
SystemOfUnits shop = SystemOfUnits.load("shop.units", new StringReader(files.get("shop.units")),
        name -> files.containsKey(name) ? new StringReader(files.get(name)) : null);
System.out.println(shop.name() + " " + shop.units());  // prints: shop.units [leagues]
```

`with` rejects a symbol of the new system that already means a different unit in the format, so two user systems that
define the same symbol differently cannot share one format:

```java
SystemOfUnits a = SystemOfUnits.load("a.units", new StringReader("@system A\nxx 1 m\n"), null);
SystemOfUnits b = SystemOfUnits.load("b.units", new StringReader("@system B\nxx 2 m\n"), null);
try {
    UnitFormat.standard().with(a, b);
} catch (IllegalArgumentException e) {
    System.out.println(e.getMessage());  // prints: symbol 'xx' of B is a different unit in A
}
```

## Lines

A file is a sequence of lines. Blank lines and lines starting with `#` are ignored, `#` starts a comment anywhere
else, and a byte order mark at the start of the file is skipped. A line is either a directive (it starts with `@`) or a
definition:

```
symbol[:name] body [Type] # comment
```

- **symbol**: no whitespace, none of the delimiters `* / · ( ) ^ | = # [ ] ! ~ @ , + -`, not starting with a digit or
  `.`, not `pi`. Each symbol is defined once over the root file and the files it includes, and must not be a symbol
  the built-in catalogs already resolve (see [Symbols](#symbols)). A file uses the symbols it defines, those of the
  files it includes or extends (also through their own includes) and the built-in ones; units of other files are
  `unknown unit`, even when the root defined them earlier.
- **name**: optional, `_` stands for a space (`degree_Celsius`); without it the name is the symbol.
- **body**: a formula (the next section), or one of the special forms: `= f(x)` after a formula (affine unit), `~`
  (alternate unit) or `!` (base unit).
- **[Type]**: a quantity type name such as `[Speed]`, spelled `[A-Z][A-Za-z0-9]*`. The generator uses it to type the
  catalog constants; at run time only its spelling is checked, and a unit is typed by its system unit.

A definition must come before its use: there is one pass and no forward reference.

```units
# blank lines and comments are ignored

m3:cubic_metre_alias m^3 [Volume]   # a name, a type and a comment
pace 0.75 m                         # without a name, the name is 'pace'
```

## Formulas

```
formula = expr [shift]
expr    = product {("*" | "·" | "/") product}     equal precedence, left to right
product = term {term}                             juxtaposition: binds tighter than * · /
term    = atom ["^" integer]                      the integer may be negative: s^-1
        | atom superscript-integer                superscript digits glued to the atom: m², s⁻¹, (m/s)²
atom    = number | pi ["|" digits] | symbol | "(" expr ")"
number  = digits ["." digits] [("e" | "E") ["+" | "-"] digits]  |  digits "|" digits
shift   = ("+" | "-") number                      only at the end, never inside parentheses
```

- Numbers are exact: `0.3048` is 3048/10000, `1.602176634e-19` is exact, `1|3` is one third (no floating point
  anywhere). Decimal exponents are limited to 10000 and a formula to 10,000 characters and 100 nested parentheses.
- `pi` is the exact constant: `pi|180 rad` is a degree, `250 pi^-1 A / m` the oersted.
- Juxtaposition binds tighter than `/`, so `J / kg K` is `J / (kg K)`; `*`, `·` and `/` associate to the left, so
  `m / s * s` is `m`.
- Spaces are optional around operators and `^`; juxtaposed symbols need one (`kgm` is one symbol).
- An exponent is `^n` or superscript digits glued to a symbol, a `pi` or a `)`: `m/s²`, `s⁻¹`, `kg·m²/s³`, `(m/s)²`
  (the digits `⁰¹²³⁴⁵⁶⁷⁸⁹` and `⁻`). A symbol that is a unit as a whole wins, so the catalog's `m²` and `cm²` are what
  they are, and `km²` is `(km)^2`. Superscripts are read everywhere a formula is, also in `.units` files.
- `UnitFormat` writes `^n` by default (`m/s^2`); `withSuperscripts(true)` returns a format that writes superscripts
  (`m/s²`), also through a `QuantityFormat` over it. `toString` of units and quantities stays ASCII. An exponent right
  after a symbol that already ends in a superscript keeps its caret (`m²^2`), because `m²²` would read as `m^22`.

```java
UnitFormat ascii = UnitFormat.standard();
UnitFormat unicode = ascii.withSuperscripts(true);
System.out.println(ascii.format(ascii.parse("m/s²")));          // prints: m/s^2
System.out.println(unicode.format(ascii.parse("m/s^2")));       // prints: m/s²
System.out.println(unicode.format(ascii.parse("kg·m²/s³")));    // prints: kg·m²/s³
System.out.println(ascii.parse("km²").equals(ascii.parse("(km)^2")));  // prints: true
```

```units
@system Formulas
acre4 4 ac                    # a number times a built-in unit
spec_heat J / kg K            # J / (kg K)
quarter_turn pi|2 rad         # pi with a fraction
fluxish 250 pi^-1 A / m       # a power of pi
millikat 2.5e-3 kat           # exponent notation, exact
```

### Shifts and the glued minus

A formula may end in a shift `+ n` or `- n` (`n` a decimal or `a|b`) that applies to the whole formula: a value `v` in
`K + 273.15` is `v + 273.15` K. The shift is top level only. A `-` glued on both sides to a plain integer (`s-1`,
`m^2-1`) is an error, because other notations read it as a negative exponent: write `s^-1` for the exponent or
`s - 1` for a shift. A glued minus before a decimal or a fraction (`K-273.15`, `K-1|3`) is a shift.

```units
@system Shifts
°Ré:degree_Reaumur 5|4·K + 273.15     # a value v is 5|4 v + 273.15 K
Kshift K-1|3                          # glued, but a fraction: a shift
```

```units-error
per_second 1/s
bad s-1
# error: example.units:2: ambiguous 's-1': write 's^-1' for a negative exponent or 's - 1' for a shift
```

```units-error
bad (K + 1)/s
# error: example.units:1: shift is only allowed at the end of a formula
```

## Kinds of units

### Scaled units

A formula without a special form defines a scaled unit: a factor times a product of powers of other units. Any
built-in symbol works in a formula, and so does a prefix on a prefixable built-in unit (`km`, `µs`, `MiB`; not `kft`,
since the foot takes no prefix).

### Affine units: `=`

`symbol formula = f(x)` defines a unit in which a value `x` is `f(x)` in the unit of the formula. `f` uses `x`,
decimals, fractions `a|b`, `+`, `-`, `*`, `/`, unary minus and parentheses, and must be affine (`a·x + b`): `x * x` and
`1 / x` are errors. A shift is the short form of the same thing: `K + 273.15` is `K = x + 273.15`.

```units
@system Affine
°De:degree_Delisle K = 373.15 - x * 2|3     # a value x is 373.15 - 2|3 x K
°N:degree_Newton °C = x * 100|33
```

Offset units cannot be prefixed (`@prefixed` reports `unit 'x' can not be prefixed`).

### Alternate units: `~`

`symbol ~ formula` defines a new atom with the dimension of the formula, distinct from it, as `Hz ~ 1/s` and
`Bq ~ 1/s` are in `SI`: a quantity in hertz is not a quantity in becquerel. `symbol ~` alone defines a new
dimensionless atom. The formula must be unscaled (`~ 1000 m` is `alternate unit must be an unscaled unit`).
Alternate units are JVM-wide: see the caveats.

```units
@system Imaging
px:pixel ~              # a dimensionless atom
@prefixed px k M
```

### Base units: `!`

`symbol !L` marks the base unit of a dimension (`L M T I Θ N J`, and `K` for `Θ`) in the generator's `si.units`. The seven base units
are built in, so a user file cannot define one: the symbol is taken.

```units-error
kg:kilogram !M
# error: example.units:1: symbol 'kg' is already defined
```

## Directives

A directive line starts with `@`; `#` starts a comment.

### `@system Name`

Names the system (a Java identifier, not a keyword); at most once per file, included files too. Without it the name
comes from the file name (see Loading). In an included file it names nothing; in an `@extends`-ed file it names that
parent system.

### `@include file`

Makes the units of another user file usable in this file's formulas and directives, and in those of the files that
include this one; they do not become units of this system. A file is read once per load (a second `@include` of it is
a no-op) and include cycles are errors. Names resolve through the resolver
(`load(Path)`: relative to the root file's directory). The built-in data files are not available: `@include
si.units` fails unless your resolver provides a file of that name; the built-in symbols are always usable without it.

```units common.units
@system Common
league:league 3 mi
@prefixed league k
@preferred Length league
```

```units shop.units
@system Shop
@include common.units
leagues:double_league 2 league   # league is usable, but not a unit of Shop
```

### `@extends file`

Includes the file and makes it a parent: its units (and those of its own parents) become units of this system, its
`@prefixed` units stay prefixable, and a quantity type without a `@preferred` line here takes the parent's preferred
unit (the first `@extends` wins; without one, `SI`'s). The parent file is also a system of its own, built from the same
parse: it sees only its own includes and parents, never the file that extends it. A file may extend several files,
each once.

```units road.units
@system Road
@extends common.units
stage 2 league                   # Road has stage, league and kleague; Length prefers league
```

```java
Map<String, String> files = Map.of(
        "common.units", "@system Common\nleague:league 3 mi\n@prefixed league k\n@preferred Length league\n",
        "road.units", "@system Road\n@extends common.units\nstage 2 league\n");
SystemOfUnits road = SystemOfUnits.load("road.units", new StringReader(files.get("road.units")),
        name -> files.containsKey(name) ? new StringReader(files.get(name)) : null);
System.out.println(road.units());                     // prints: [stage, league, kleague]
System.out.println(Length.of(1, SI.KILOMETRE).toSystem(road)); // prints: 0.207123730745778 league
System.out.println(road.isPrefixable("league"));         // prints: true
```

### `@member symbol...`

Makes units of an included file units of this system without defining new ones. The symbols must be defined in an
included file (not in this file, not a built-in symbol), and each is listed once. A member keeps the prefixability
of its own file.

```units trail.units
@system Trail
@include common.units
@member league
step 0.75 m
```

### `@preferred Type symbol`

Sets the unit `quantity.toSystem(system)` converts to for one of the 33 standard quantity types (simple name, such as
`Length` or `MagneticFlux`). The unit must be defined in the user files before this line, be a unit of the system and
have the type's system unit, not only its dimension: an alternate unit of another atom is refused (`Bq` is not a
preferred unit for `Frequency`, though `as` (`asType` in `uom-jsr`) views a becquerel quantity as one). One line per type. A type without a line takes the first parent's preferred unit, else
`SI`'s.

```units-error
@system Pulses
flash:flash_rate ~ 1/s     # dimension 1/s, but its own atom: not the hertz
@preferred Frequency flash
# error: example.units:3: unit 'flash' is not a unit of Frequency
```

### `@prefixed symbol prefix...`

Defines the prefixed units (`@prefixed league k` adds `kleague`, named `kiloleague`) and makes the unit prefixable:
in a format with the system every metric prefix on it parses (`Mleague`, as in the first example). The unit must be
defined in the user files and be linear; the prefixes are the JSR-385 metric prefix symbols (`µ` is the micro sign
U+00B5) and binary prefix symbols (`Ki`, `Mi`, ... `Yi`), mixed in one line if wanted. A line with a binary prefix
makes every binary prefix on the unit parse, and then only the metric prefixes from kilo up, as IEC 80000-13 uses them
for information units (the built-in `Information` system: `kB` and `MiB` parse, `dB` does not). A prefixed unit is not
prefixed again:

```units-error
league:league 3 mi
@prefixed league k
@prefixed kleague M
# error: example.units:3: unit 'kleague' is already prefixed
```

`UnitFormat.label` rejects a label that already reads as another unit, exactly or as a prefix on a prefixable unit
(`Gm` is the gigametre). In code, `Unit.prefix` only scales: the result has no symbol, because whether a prefix applies
(`km`, but not `kmin` or `km²`) and what else the symbol means is known to a system, not to the unit; a system names
the units of its `@prefixed` lines and a format reads and writes a prefix on a prefixable symbol: an unnamed
`SI.HERTZ.prefix(MetricPrefix.MILLI)` prints and formats as `mHz`.

## Errors

Every error names the file and line. The common messages, by cause:

| Cause | Message text after `file:line: ` |
|---|---|
| a symbol defined twice | `duplicate symbol 'x'` |
| a symbol the built-in catalogs resolve, after `!` | `symbol 'kg' is already defined` (a new symbol: `base unit symbol must be 'm'`) |
| a bad letter after `!`, or a space before it | `unknown base dimension 'Q'`, `the dimension letter must follow '!' directly` |
| a symbol the built-in catalogs resolve | `symbol 'x' is already defined` |
| a malformed symbol, or `pi` | `invalid symbol 'x'`, `symbol 'pi' is reserved` |
| an empty name after `:` | `empty name for 'x'` |
| no formula (`#` starts a comment, also right after a symbol) | `missing formula for 'x'`, `missing formula for 'x' ('#' starts a comment)` |
| an unknown symbol in a formula or directive | `unknown unit 'x'` |
| a glued minus before an integer | `ambiguous 's-1': write 's^-1' for a negative exponent or 's - 1' for a shift` |
| a shift inside parentheses | `shift is only allowed at the end of a formula` |
| a non-integer exponent | `exponent must be an integer` |
| limits | `formula too long`, `formula nested too deeply`, `number out of range` |
| an `f(x)` that is not affine | `function is not affine` |
| a scaled formula after `~` | `alternate unit must be an unscaled unit` |
| a bad `[Type]` spelling, or text after it | `malformed quantity type 'x'`, `the quantity type [X] must end the definition`, `missing ']' after the quantity type` |
| an unknown directive | `unknown directive '@x'` |
| a missing or unreadable include | `cannot include 'x.units'` |
| an include cycle | `include cycle: a.units -> b.units -> a.units` |
| a bad `@system` name, or two of them | `invalid system name 'x'`, `duplicate '@system'` |
| an unknown or mismatched `@preferred` | `unknown quantity type 'X'`, `unit 'x' is not a unit of Speed` |
| a second `@preferred` for a type | `duplicate preferred unit for 'Length'` |
| a prefix that is neither a metric nor a binary prefix symbol | `unknown prefix 'x'` |
| `@member` of a unit of the same file, or twice | `unit 'x' is defined in this file`, `duplicate member 'x'` |
| the same file extended twice | `duplicate '@extends' of 'x.units'` |
| an offset unit in `@prefixed` | `unit 'x' can not be prefixed` |

```units-error
@system Errors
rod 5.0292 m
rod 1|40 furlong
# error: example.units:3: duplicate symbol 'rod'
```

```units-error
ft:my_foot 0.3 m
# error: example.units:1: symbol 'ft' is already defined
```

```units-error
@include missing.units
# error: example.units:1: cannot include 'missing.units'
```

```units-error
@preferred Length mi
# error: example.units:1: unknown unit 'mi'
```

```units-error
@system Errors
tick 1 s
@preferred Length tick
# error: example.units:3: unit 'tick' is not a unit of Length
```

## Symbols

A user symbol must not be one that `UnitFormat.standard()` resolves: an exact catalog symbol, or a prefix on
a prefixable catalog unit (`km`, `µs`, `GiB`). Labels added with `UnitFormat.label` are not visible to the loader.
`@prefixed u k` checks only the generated `ku` against the built-in symbols, though every prefix `u` takes is
readable at run time; an exact built-in symbol wins over such a reading.

Symbols are compared as exact strings, so look-alike characters are different symbols: the Greek small mu U+03BC is
not the micro sign U+00B5 of the metric prefix, and the ohm sign U+2126 is not the Greek capital omega U+03A9 that
`SI` uses for the ohm. A file can therefore define `μm` (Greek mu) or `Ω` (ohm sign) next to the built-in `µm` and
`Ω`; avoid such symbols, they are indistinguishable in most fonts.

## Caveats

- **Alternate units are permanent.** A `~` line registers its symbol in a JVM-wide registry for the life of the JVM:
  loading the same definition again works, but a different definition for the same symbol is an error until the JVM
  restarts, even after its system was discarded. A load that fails registers nothing. The symbols of the built-in
  alternate units (`rad`, `sr`, `Hz`, `Bq`, `Gy`, `Sv`, `bit`) are taken from the start.
- **The resolver contract.** A `Resolver` returns a fresh `Reader` for a name, or null when there is no such file
  (`cannot include`); an `IOException` is reported the same way with the exception as cause. Within one `load` it is
  called at most once per name; the reader is read fully and closed by the loader. A reader that fails while it is
  read throws `UncheckedIOException` (`cannot read 'x.units': the resolver returned a failing, closed or reused
  reader`). The resolver decides which files are reachable: give it only names you trust.
- **Path containment.** `load(Path)` refuses absolute names and names that leave the root file's directory, also
  through symbolic links (`cannot include '../common.units'`). The check and the read are two steps: a link swapped
  between them is not detected, so do not load from directories that others can write to.
- **Reader names.** For the root file, `load(name, reader, resolver)` uses `name` verbatim as the default system name
  (`shop.units`) and `load(Path)` strips the extension; a parent named by `@extends` without `@system` loses its
  extension in both. Use `@system` for a stable name.
- **Only user units in directives.** `@preferred`, `@member` and `@prefixed` name units of the user files; a built-in
  symbol such as `mi` is `unknown unit 'mi'` there (define an alias, `mile 1 mi`, if needed).
- **Aliases.** A unit equal to another unit of the system (`marker 1 league`) is listed once by `units()`, under
  the first symbol; both symbols parse.
- **`[Type]` is not checked at run time.** Only its spelling is; a loaded unit is typed by its system unit (`uom`) or is a
  `Unit<?>` (`uom-jsr`).
