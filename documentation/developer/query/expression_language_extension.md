# EvitaEL expression language extension

The Evita Expression Language (EvitaEL) is an expression evaluation engine used internally for computed
attribute values, dynamic attribute histograms, and other scenarios where user-defined expressions are
evaluated against entity data at query time.

EvitaEL exposes four Service Provider Interface (SPI) extension points that allow external modules
(or third-party code) to extend the language with new capabilities:

- `FunctionProcessor` - custom functions invoked as `functionName(args...)` (e.g. `abs(x)`, `sqrt(x)`)
- `ObjectPropertyAccessor` - dot-notation property access on objects (e.g. `entity.attributes`)
- `ObjectElementAccessor` - bracket-notation element access on objects (e.g. `map['key']`, `list[0]`)
- `ObjectMethodAccessor` - dot-notation method invocation on objects (e.g. `list.any($ > 5)`, `map.size()`)

All four SPIs are discovered via `java.util.ServiceLoader` and registered in singleton registries at
startup. The SPI interfaces live in the `evita_query` module, package
`io.evitadb.api.query.expression.function.processor` (functions) and
`io.evitadb.api.query.expression.object.accessor` (object accessors).


## FunctionProcessor

### Interface hierarchy

The function processor SPI consists of three layers:

- `io.evitadb.api.query.expression.function.processor.FunctionProcessor` - the base interface every
  function must implement. It declares two methods:

```java
public interface FunctionProcessor {
    @Nonnull
    String getName();

    @Nonnull
    Serializable process(@Nonnull List<Serializable> arguments)
        throws ExpressionEvaluationException;
}
```

- `io.evitadb.api.query.expression.function.processor.NumericFunctionProcessor` - extends
  `FunctionProcessor` for functions that produce numeric results. It adds a method used by the engine
  to narrow the range of tested variable values during histogram computation:

```java
public interface NumericFunctionProcessor extends FunctionProcessor {
    @Nonnull
    BigDecimalNumberRange determinePossibleRange(
        @Nonnull List<BigDecimalNumberRange> argumentPossibleRanges
    ) throws UnsupportedDataTypeException;
}
```

- `io.evitadb.api.query.expression.function.processor.AbstractMathFunctionProcessor` - a
  package-private abstract base class that implements `NumericFunctionProcessor` and provides common
  argument validation and type conversion utilities:
    - `requireArgumentCount(arguments, expectedCount)` - validates exact argument count
    - `requireArgumentCountBetween(arguments, minCount, maxCount)` - validates argument count range
    - `toBigDecimal(argument, argumentName)` - converts a `Number` argument to `BigDecimal` via
      `EvitaDataTypes.toTargetType()`
    - `toLong(argument, argumentName)` - converts a `Number` argument to `Long`

### Registry

`io.evitadb.api.query.expression.function.processor.FunctionProcessorRegistry` is a lazily-initialized
singleton that loads all `FunctionProcessor` implementations via `ServiceLoader` and indexes them by
function name into an internal `Map<String, FunctionProcessor>`. The expression evaluator calls
`FunctionProcessorRegistry.getInstance().getFunctionProcessor(name)` to resolve function calls at
evaluation time.

### Built-in implementations

All built-in processors live in module `evita_query`, package
`io.evitadb.api.query.expression.function.processor`.

| Class | Function | Args | Description |
|---|---|---|---|
| `AbsFunctionProcessor` | `abs` | 1 | Absolute value (`BigDecimal.abs()`) |
| `CeilFunctionProcessor` | `ceil` | 1 | Ceiling - smallest integer >= argument |
| `FloorFunctionProcessor` | `floor` | 1 | Floor - largest integer <= argument |
| `LogFunctionProcessor` | `log` | 1 | Natural logarithm via `Math.log()` |
| `MaxFunctionProcessor` | `max` | 2 | Returns the larger of two numeric arguments |
| `MinFunctionProcessor` | `min` | 2 | Returns the smaller of two numeric arguments |
| `PowFunctionProcessor` | `pow` | 2 | Raises base to an integer exponent (`BigDecimal.pow(int)`) |
| `RandomFunctionProcessor` | `random` | 0-1 | Random `long` (unbounded or bounded by argument) |
| `RoundFunctionProcessor` | `round` | 1 | Rounds to nearest integer using `HALF_UP` mode |
| `SqrtFunctionProcessor` | `sqrt` | 1 | Square root via `BigDecimal.sqrt(DECIMAL64)` |

### Implementing a custom function processor

The following example shows a custom function processor that computes the factorial of a non-negative
integer argument:

```java
package com.example.evita.expression;

import io.evitadb.api.query.expression.function.processor.FunctionProcessor;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public class FactorialFunctionProcessor implements FunctionProcessor {

    @Nonnull
    @Override
    public String getName() {
        return "factorial";
    }

    @Nonnull
    @Override
    public Serializable process(@Nonnull List<Serializable> arguments)
        throws ExpressionEvaluationException {
        if (arguments.size() != 1) {
            throw new ExpressionEvaluationException(
                "Function `factorial` requires exactly 1 argument, but got "
                    + arguments.size() + "."
            );
        }
        final Serializable arg = arguments.get(0);
        if (!(arg instanceof Number)) {
            throw new ExpressionEvaluationException(
                "Function `factorial` requires a numeric argument."
            );
        }
        final long n = Objects.requireNonNull(
            EvitaDataTypes.toTargetType(arg, Long.class)
        );
        if (n < 0) {
            throw new ExpressionEvaluationException(
                "Function `factorial` requires a non-negative argument."
            );
        }
        long result = 1;
        for (long i = 2; i <= n; i++) {
            result *= i;
        }
        return BigDecimal.valueOf(result);
    }
}
```

*Note: if the function produces numeric results and you want the engine to use output range narrowing
for histogram optimization, implement `NumericFunctionProcessor` instead and provide a
`determinePossibleRange()` implementation. Use `PossibleRange.transform()` for single-argument
functions and `PossibleRange.combine()` for two-argument functions.*


## ObjectPropertyAccessor

### Interface

`io.evitadb.api.query.expression.object.accessor.ObjectPropertyAccessor` enables dot-notation property
access in EvitaEL expressions (e.g. `entity.attributes`). Implementations declare which types they
handle and provide the property lookup logic:

```java
public interface ObjectPropertyAccessor {
    @Nonnull
    Class<? extends Serializable>[] getSupportedTypes();

    @Nullable
    Serializable get(@Nonnull Serializable object, @Nonnull String propertyIdentifier)
        throws ExpressionEvaluationException;
}
```

### Registry

`io.evitadb.api.query.expression.object.accessor.ObjectAccessorRegistry` is a lazily-initialized
singleton that loads both `ObjectPropertyAccessor` and `ObjectElementAccessor` implementations via
`ServiceLoader`. Accessors are indexed by their supported types. The registry supports type hierarchy
traversal: if no exact type match is found, it checks `isAssignableFrom` across all registered types.
Resolved lookups are cached in a `ConcurrentHashMap` for performance.

### Built-in implementations

| Class | Module | Supported types | Properties |
|---|---|---|---|
| `MapPropertyAccessor` | `evita_query` | `Map` | `entries` - returns `List<SerializableMapEntry>` |
| `MapEntryPropertyAccessor` | `evita_query` | `SerializableMapEntry` | `key`, `value` |
| `EntityContractAccessor` | `evita_api` | `EntityContract` | `attributes`, `localizedAttributes`, `associatedData`, `localizedAssociatedData`, `references` |
| `ReferenceContractAccessor` | `evita_api` | `ReferenceContract` | `referencedPrimaryKey`, `attributes`, `localizedAttributes` |

The `evita_query` accessors cover generic collection types, while the `evita_api` accessors provide
domain-specific access to evitaDB entity and reference contracts.

### Implementing a custom property accessor

The following example shows a property accessor for a hypothetical `Money` value object:

```java
package com.example.evita.expression;

import io.evitadb.api.query.expression.object.accessor.ObjectPropertyAccessor;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

public class MoneyPropertyAccessor implements ObjectPropertyAccessor {

    @Nonnull
    @Override
    public Class<? extends Serializable>[] getSupportedTypes() {
        //noinspection unchecked
        return new Class[] { Money.class };
    }

    @Nullable
    @Override
    public Serializable get(
        @Nonnull Serializable object,
        @Nonnull String propertyIdentifier
    ) throws ExpressionEvaluationException {
        if (!(object instanceof Money money)) {
            throw new ExpressionEvaluationException(
                "Expected Money, got " + object.getClass().getName()
            );
        }
        return switch (propertyIdentifier) {
            case "amount" -> money.getAmount();
            case "currency" -> money.getCurrency().getCurrencyCode();
            default -> throw new ExpressionEvaluationException(
                "Property `" + propertyIdentifier + "` does not exist on Money."
            );
        };
    }
}
```


## ObjectMethodAccessor

### Interface

`io.evitadb.api.query.expression.object.accessor.ObjectMethodAccessor` enables dot-notation method
invocation in EvitaEL expressions (e.g. `list.any($ > 5)`, `map.size()`). Unlike property and element
accessors, method accessors receive the `ExpressionEvaluationContext` and the raw argument
`ExpressionNode` list. This means method arguments are lazily evaluated - the accessor controls when
and how arguments are computed, which is essential for predicate-style methods like `any()` where
each element needs its own evaluation context:

```java
public interface ObjectMethodAccessor {
    @Nonnull
    Class<? extends Serializable>[] getSupportedTypes();

    Serializable invoke(
        @Nonnull ExpressionEvaluationContext context,
        @Nonnull Serializable object,
        @Nonnull String methodIdentifier,
        @Nonnull List<ExpressionNode> args
    ) throws ExpressionEvaluationException;
}
```

### Registry

`ObjectMethodAccessor` implementations are managed by the same `ObjectAccessorRegistry` that handles
property and element accessors. The registry loads method accessors via `ServiceLoader`, indexes them
by supported types, and supports type hierarchy traversal with concurrent caching - exactly the same
dispatch mechanism as for property and element accessors.

### Built-in implementations

| Class | Module | Supported types | Methods |
|---|---|---|---|
| `ListMethodAccessor` | `evita_query` | `List` | `size()`, `any(predicate)`, `all(predicate)`, `none(predicate)` |
| `ArrayMethodAccessor` | `evita_query` | All primitive arrays, `Object[]` | `size()`, `any(predicate)`, `all(predicate)`, `none(predicate)` |
| `MapMethodAccessor` | `evita_query` | `Map` | `size()` |

For predicate methods (`any`, `all`, `none`), the predicate argument is an expression where `$`
refers to the current element. The accessor evaluates the predicate for each element using
`context.withThis(element)` to bind the `$` variable.

### Implementing a custom method accessor

The following example shows a method accessor for a hypothetical `Money` type that provides a
`convertTo(currencyCode)` method:

```java
package com.example.evita.expression;

import io.evitadb.api.query.expression.object.accessor.ObjectMethodAccessor;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

public class MoneyMethodAccessor implements ObjectMethodAccessor {

    @Nonnull
    @Override
    public Class<? extends Serializable>[] getSupportedTypes() {
        //noinspection unchecked
        return new Class[] { Money.class };
    }

    @Override
    public Serializable invoke(
        @Nonnull ExpressionEvaluationContext context,
        @Nonnull Serializable object,
        @Nonnull String methodIdentifier,
        @Nonnull List<ExpressionNode> args
    ) throws ExpressionEvaluationException {
        if (!(object instanceof Money money)) {
            throw new ExpressionEvaluationException(
                "Expected Money, got " + object.getClass().getName()
            );
        }
        return switch (methodIdentifier) {
            case "convertTo" -> {
                if (args.size() != 1) {
                    throw new ExpressionEvaluationException(
                        "Method `convertTo` requires exactly 1 argument."
                    );
                }
                // evaluate the argument to get the target currency code
                final Serializable currencyArg = args.get(0).compute(context);
                if (!(currencyArg instanceof String currencyCode)) {
                    throw new ExpressionEvaluationException(
                        "Method `convertTo` requires a string argument."
                    );
                }
                yield money.convertTo(currencyCode);
            }
            default -> throw new ExpressionEvaluationException(
                "Method `" + methodIdentifier + "` does not exist on Money."
            );
        };
    }
}
```


## ObjectElementAccessor

### Interface

`io.evitadb.api.query.expression.object.accessor.ObjectElementAccessor` enables bracket-notation
element access in EvitaEL expressions. It supports two access modes:

- keyed access via string key: `object['key']`
- indexed access via numeric index: `object[0]`

Both `get` methods have default implementations that throw `UnsupportedOperationException`, so
implementations only need to override the access mode(s) they actually support:

```java
public interface ObjectElementAccessor {
    @Nonnull
    Class<? extends Serializable>[] getSupportedTypes();

    @Nullable
    default Serializable get(@Nonnull Serializable object, @Nonnull String elementName)
        throws ExpressionEvaluationException { /* throws UnsupportedOperationException */ }

    @Nullable
    default Serializable get(@Nonnull Serializable object, int elementIndex)
        throws ExpressionEvaluationException { /* throws UnsupportedOperationException */ }
}
```

### Built-in implementations

| Class | Module | Supported types | Access mode |
|---|---|---|---|
| `ArrayElementAccessor` | `evita_query` | All primitive arrays, `Object[]` | Indexed |
| `ListElementAccessor` | `evita_query` | `List` | Indexed |
| `MapElementAccessor` | `evita_query` | `Map` | Keyed (locale-aware key conversion) |
| `AttributesContractAccessor` | `evita_api` | `AttributesContract` | Keyed (attribute name lookup, localization-aware) |
| `AssociatedDataContractAccessor` | `evita_api` | `AssociatedDataContract` | Keyed (associated data name lookup, localization-aware) |
| `ReferencesContractAccessor` | `evita_api` | `ReferencesContract` | Keyed (reference name lookup) |

### Implementing a custom element accessor

The following example shows an element accessor for a hypothetical `Tuple` type that supports indexed
access:

```java
package com.example.evita.expression;

import io.evitadb.api.query.expression.object.accessor.ObjectElementAccessor;
import io.evitadb.exception.ExpressionEvaluationException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

public class TupleElementAccessor implements ObjectElementAccessor {

    @Nonnull
    @Override
    public Class<? extends Serializable>[] getSupportedTypes() {
        //noinspection unchecked
        return new Class[] { Tuple.class };
    }

    @Nullable
    @Override
    public Serializable get(@Nonnull Serializable object, int elementIndex)
        throws ExpressionEvaluationException {
        if (!(object instanceof Tuple tuple)) {
            throw new ExpressionEvaluationException(
                "Expected Tuple, got " + object.getClass().getName()
            );
        }
        if (elementIndex < 0 || elementIndex >= tuple.size()) {
            throw new ExpressionEvaluationException(
                "Tuple index " + elementIndex + " out of bounds [0, "
                    + (tuple.size() - 1) + "]."
            );
        }
        return tuple.get(elementIndex);
    }
}
```


## Registration

All four SPIs use the standard Java `ServiceLoader` mechanism. Registration requires two steps:

### Step 1 - META-INF/services file

Create a file named after the SPI interface under `META-INF/services/` in your module's resources
directory. Each line lists one fully-qualified implementation class name.

For a custom `FunctionProcessor`:

```
# src/main/resources/META-INF/services/io.evitadb.api.query.expression.function.processor.FunctionProcessor
com.example.evita.expression.FactorialFunctionProcessor
```

For a custom `ObjectPropertyAccessor`:

```
# src/main/resources/META-INF/services/io.evitadb.api.query.expression.object.accessor.ObjectPropertyAccessor
com.example.evita.expression.MoneyPropertyAccessor
```

For a custom `ObjectElementAccessor`:

```
# src/main/resources/META-INF/services/io.evitadb.api.query.expression.object.accessor.ObjectElementAccessor
com.example.evita.expression.TupleElementAccessor
```

For a custom `ObjectMethodAccessor`:

```
# src/main/resources/META-INF/services/io.evitadb.api.query.expression.object.accessor.ObjectMethodAccessor
com.example.evita.expression.MoneyMethodAccessor
```

### Step 2 - module-info.java provides clause

When using Java modules, the `META-INF/services` file alone is not sufficient. You must also declare
the `provides` clause in your module's `module-info.java`:

```java
module com.example.evita.extension {
    requires evita.query;

    provides io.evitadb.api.query.expression.function.processor.FunctionProcessor
        with com.example.evita.expression.FactorialFunctionProcessor;

    provides io.evitadb.api.query.expression.object.accessor.ObjectPropertyAccessor
        with com.example.evita.expression.MoneyPropertyAccessor;

    provides io.evitadb.api.query.expression.object.accessor.ObjectElementAccessor
        with com.example.evita.expression.TupleElementAccessor;

    provides io.evitadb.api.query.expression.object.accessor.ObjectMethodAccessor
        with com.example.evita.expression.MoneyMethodAccessor;
}
```

The `uses` directives for all four SPIs are already declared in the `evita.query` module, so consumer
modules only need `provides`.

### Reference - built-in module declarations

The built-in implementations are registered in two modules:

**`evita.query`** (`evita_query/src/main/java/module-info.java`):

```java
provides FunctionProcessor with
    AbsFunctionProcessor, CeilFunctionProcessor, FloorFunctionProcessor,
    LogFunctionProcessor, MaxFunctionProcessor, MinFunctionProcessor,
    PowFunctionProcessor, RandomFunctionProcessor, RoundFunctionProcessor,
    SqrtFunctionProcessor;

provides ObjectPropertyAccessor with
    MapPropertyAccessor, MapEntryPropertyAccessor;

provides ObjectElementAccessor with
    ListElementAccessor, ArrayElementAccessor, MapElementAccessor;

provides ObjectMethodAccessor with
    ListMethodAccessor, ArrayMethodAccessor, MapMethodAccessor;
```

**`evita.api`** (`evita_api/src/main/java/module-info.java`):

```java
provides ObjectPropertyAccessor with
    EntityContractAccessor, ReferenceContractAccessor;

provides ObjectElementAccessor with
    AttributesContractAccessor, AssociatedDataContractAccessor,
    ReferencesContractAccessor;
```
