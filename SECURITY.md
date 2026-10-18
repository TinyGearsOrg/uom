# Security

## Supported versions

uom has not been released yet. Until 1.0, only the latest release (and the `main` branch) receives fixes; from 1.0,
the latest minor release of the current major version does.

## Reporting a vulnerability

Do not open a public issue. Report it privately through GitHub's private vulnerability reporting of
[TinyGearsOrg/uom](https://github.com/TinyGearsOrg/uom/security/advisories/new), or by email to the
maintainer, Thomas Neidhart (thomas.neidhart@gmail.com). Include the version, a minimal reproduction and the impact you
see. Reports are answered on a best-effort basis; a fix is released before the details are published.

## Java serialization

Units and quantities of both APIs are `Serializable` through serialization proxies. A proxy rebuilds the object through the public
factories, so reading re-checks the invariants and a forged stream that violates them fails with
`InvalidObjectException`. The steps of a multi-step converter that cannot be checked against its factor and offset
(a part over 4096 bits) are dropped, so it reads as one step. An alternate atom whose symbol is not registered (an atom
of a user `.units` file that is not loaded) cannot be checked and reads as an unregistered atom; the built-in ones are
registered from the start. A quantity of `uom` reads back through the factory of its type; one of `uom-jsr` through the factory
of this implementation's provider, else of the current `ServiceProvider`. Systems and formats are not serializable. The serialized form may change in any release
before 1.0 (see [docs/DESIGN.md](docs/DESIGN.md), section 8).

The checks run after the JDK has read the stream: the JDK allocates its arrays and strings, and resolves its classes,
before any of our code runs. Never read a stream you do not trust without an `ObjectInputFilter` that limits the sizes
and allows only the needed classes. This filter, also used by `serialization()` in
[`Examples.java`](uom-examples/src/main/java/org/tinygears/uom/examples/Examples.java), is a starting point:

```
maxarray=65536;maxbytes=1048576;maxdepth=20;maxrefs=10000;org.tinygears.uom.**;javax.measure.**;java.lang.*;java.math.*;!*
```

`org.tinygears.uom.**` covers the engine and both APIs, including the quantity classes the proxies resolve to, which the
filter checks too; `javax.measure.**` is needed for `uom-jsr` only. Set it for the whole JVM with
`-Djdk.serialFilter=<filter>`, or per stream:

```java
String filter = "maxarray=65536;maxbytes=1048576;maxdepth=20;maxrefs=10000;"
        + "org.tinygears.uom.**;javax.measure.**;java.lang.*;java.math.*;!*";
Length length = Length.of(new BigDecimal("1.50"), SI.KILOMETRE);
ByteArrayOutputStream bytes = new ByteArrayOutputStream();
try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
    out.writeObject(length);
}
try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
    in.setObjectInputFilter(ObjectInputFilter.Config.createFilter(filter));
    Object read = in.readObject();
    System.out.println(read + " " + read.equals(length));  // prints: 1.50 km true
}
```

## User `.units` files

Loading a `.units` file runs no code, but its `~` lines register symbols for the life of the JVM, and its includes
read files: load files only from trusted locations. See the caveats in [docs/CUSTOM-UNITS.md](docs/CUSTOM-UNITS.md).
