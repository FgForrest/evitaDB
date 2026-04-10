# Expression Language (EvitaEL)

The evitaDB expression language (EvitaEL) is a lightweight, side-effect-free language for writing
inline expressions that evaluate to a single value. It is used in query constraints and computed
attribute formulas where dynamic evaluation is needed. Expressions can perform arithmetic, compare
values, call math functions, navigate complex object structures, and handle null values gracefully.

## Data Types

EvitaEL supports the following literal data types:

| Type | Description | Examples |
|---|---|---|
| `long` | 64-bit signed integer | `123`, `-42`, `0` |
| `decimal` | Arbitrary-precision decimal (BigDecimal) | `3.14`, `-0.5`, `100.0` |
| `boolean` | Boolean value | `true`, `false` |
| `string` | Text in single or double quotes | `'hello'`, `"world"` |

but it can handle all evitaDB supported data types under the hood.

Collections (lists, arrays, maps) cannot be defined as literals but are commonly encountered as
values returned from variables and object access expressions.

## Variables

Variables are referenced using the `$` prefix followed by an identifier:

```
$pageNumber
$entity
$price
```

Variable identifiers must start with a lowercase letter and can contain letters and digits
(`[a-z][a-zA-Z0-9]*`). Variables are provided by the evaluation context and cannot be defined
within the expression itself.

The bare `$` symbol (without an identifier) refers to the `this` in a current context. Usually used to reference
collection item inside a [spread expression](#spread-operator).

## Arithmetic Operators

| Operator | Description | Example | Result |
|---|---|---|---|
| `+` | Addition | `1 + 3 + 5` | `9` |
| `-` | Subtraction | `2 - 5` | `-3` |
| `*` | Multiplication | `2 * (8 - 4)` | `8` |
| `/` | Division | `$pageNumber / 2` | half of pageNumber |
| `%` | Modulo (remainder) | `$pageNumber % 2` | `0` or `1` |
| `+` (unary) | Positive sign | `+1` | `1` |
| `-` (unary) | Negation | `-(1 + 2)` | `-3` |
| `()` | Grouping | `(2 + 4) * 2` | `12` |

Standard mathematical precedence applies: multiplication, division, and modulo are evaluated before
addition and subtraction. Use parentheses to override precedence.

## Comparison Operators

| Operator | Description | Example | Result |
|---|---|---|---|
| `==` | Equal to | `5 == 5` | `true` |
| `!=` | Not equal to | `5 != 4` | `true` |
| `>` | Greater than | `10 > 5` | `true` |
| `>=` | Greater than or equal | `$pageNumber >= 5` | depends on variable |
| `<` | Less than | `5 < 10` | `true` |
| `<=` | Less than or equal | `$pageNumber <= 5` | depends on variable |

Comparison operators work with numeric values and strings. String comparison uses
`'abc' == 'abc'` syntax.

## Logical Operators

| Operator | Description | Example | Result |
|---|---|---|---|
| `&&` | Logical AND | `true && false` | `false` |
| `\|\|` | Logical OR | `true \|\| false` | `true` |
| `!` | Logical NOT | `!true` | `false` |
| `^` | Logical XOR | `true ^ false` | `true` |

Logical operators can be combined with comparison operators to build complex predicates:

```
$pageNumber > 2 && $pageNumber < 10 && $pageNumber % 2 == 0
```

## Math Functions

The following built-in math functions are available:

| Function | Description | Example | Result |
|---|---|---|---|
| `abs(x)` | Absolute value | `abs(-4)` | `4` |
| `ceil(x)` | Round up to nearest integer | `ceil($n / 2)` | ceiling |
| `floor(x)` | Round down to nearest integer | `floor($n / 2)` | floor |
| `round(x)` | Round to nearest integer (half-up) | `round(2.5)` | `3` |
| `sqrt(x)` | Square root | `sqrt(3 + 13)` | `4` |
| `log(x)` | Natural logarithm | `round(log(20))` | `3` |
| `pow(x, y)` | Raise x to the power of y | `pow(2, 6)` | `64` |
| `max(x, y)` | Larger of two values | `max(4, 8)` | `8` |
| `min(x, y)` | Smaller of two values | `min(4, 8)` | `4` |
| `random()` | Random long value | `random()` | random long |
| `random(x)` | Random long in range [0, x) | `random(5)` | `0`..`4` |

Functions can be nested:

```
floor(sqrt($pageNumber))
round(log(20))
```

## Object Access

EvitaEL supports navigating complex object structures using dot notation for properties and bracket
notation for elements.

### Property Access (Dot Notation)

Access named properties on objects using the dot (`.`) operator:

```
$entity.attributes
$entity.references
```

### Element Access (Bracket Notation)

Access elements by string key or integer index using brackets (`[]`):

```
$entity.attributes['code']
$entity.references['tags'][0]
```

The element identifier inside brackets must evaluate to either a string or an integer.

### Chained Access

Property and element access can be chained to navigate deeply nested structures:

```
$entity.references['brand'].attributes['distributor']
$entity.references['brand'].referencedPrimaryKey
```

### Entity-Specific Accessors

When working with evitaDB entity objects, the following access paths are available:

**Entity properties:**

| Property | Description |
|---|---|
| `$entity.attributes` | Access to non-localized (global) attributes |
| `$entity.localizedAttributes` | Access to localized attributes (returns map of locale to value) |
| `$entity.associatedData` | Access to non-localized associated data |
| `$entity.localizedAssociatedData` | Access to localized associated data |
| `$entity.references` | Access to entity references by name |

**Attribute access:**

```
$entity.attributes['code']                   // global attribute value
$entity.attributes['tags']                   // global array attribute
$entity.attributes['tags'][0]                // first element of global array attribute
$entity.localizedAttributes['url']           // map of locale -> localized value
$entity.localizedAttributes['url']['en']     // localized value for locale 'en'
```

Accessing a localized attribute via `.attributes` (or a global attribute via
`.localizedAttributes`) will throw an error. Use the correct accessor for each attribute type.

**Reference access:**

```
$entity.references['brand']                              // single reference
$entity.references['brand'].referencedPrimaryKey         // referenced entity PK
$entity.references['brand'].attributes['distributor']    // reference attribute
$entity.references['categories']                         // list of references
```

When there is exactly one reference of a given name, the result is a single reference object.
When there are multiple references (e.g. `categories`), the result is a list.

**Reference properties:**

| Property | Description |
|---|---|
| `$ref.referencedPrimaryKey` | Primary key of the referenced entity |
| `$ref.attributes` | Access to non-localized reference attributes |
| `$ref.localizedAttributes` | Access to localized reference attributes |

## Spread Operator (`.*[expr]`)

The spread operator applies a mapping expression to each element of a collection, or map.
Inside the mapping expression, the bare `$` variable refers to the current item being processed.

### Basic Spreading

```
$entity.references['categories'].*[$.referencedPrimaryKey]
// extracts referencedPrimaryKey from each category reference -> [1, 2]

$entity.references['categories'].*[$.attributes['categoryPriority']]
// extracts categoryPriority from each reference -> [16, 17]
```

### Spreading with Transformations

The mapping expression can contain any valid EvitaEL expression:

```
$entity.references['categories'].*[-$.attributes['categoryPriority']]
// negates each priority -> [-16, -17]

$obj.mapWithNumbers.*[max($, 7)]
// clamps each value to a minimum of 7
```

### Compact Variant (`.*![expr]`)

The compact variant filters out `null` values from the result:

```
$entity.references['categories'].*![$.attributes['categoryTag']]
// extracts categoryTag, skipping nulls -> ['new']
```

Compare with the non-compact variant:

```
$entity.references['categories'].*![$.attributes['categoryTag']]
// includes nulls -> ["new", null]
```

### Spreading Maps

When the spread operator is applied to a map, it maps over the **values** while preserving keys:

```
$obj.map.*[$]
// identity: returns the same map -> {"a": 5, "b": 6, "c": 7, "d": 8}

$obj.map.*[max($, 7)]
// clamps values -> {"a": 7, "b": 7, "c": 7, "d": 8}
```

### Map Entries

To access both keys and values of a map, use the `.entries` property to convert the map into a
list of entry objects, then spread over those entries. Each entry has `.key` and `.value`
properties:

```
$obj.map.entries.*[$.key]
// extracts all keys -> ["a", "b", "c", "d"]

$obj.map.entries.*[$.value]
// extracts all values -> [5, 6, 7, 8]

$obj.map.entries.*[min($.value, 6)]
// clamps values with min -> [5, 6, 6, 6]
```

## Null-Safety (Safe Navigation)

By default, accessing a property or element on a `null` value throws an error. Use the safe
navigation operators to short-circuit to `null` instead:

| Syntax | Description |
|---|---|
| `?.` | Safe property access |
| `?[` | Safe element access |
| `?.*` | Safe spread access |

Examples:

```
$obj.optionalNested?.map['c'].list[0]   // returns null if optionalNested is null
$obj.optionalList?[0]                   // returns null if optionalList is null
$obj.optionalMap?['b']                  // returns null if optionalMap is null
```

The null-safety operator short-circuits the entire remaining access chain. In
`$obj.optionalNested?.map['c'].list[0]`, if `optionalNested` is `null`, the whole expression
evaluates to `null` without attempting to access `map`, `'c'`, `list`, or `[0]`.

**When null-safety is required:** Any time the operand to the left of `.`, `[`, or `.*` may be
`null`, you must use the safe variant. Without it, an error is thrown:

```
// ERROR: throws ExpressionEvaluationException if optionalNested is null
$obj.optionalNested.map['c']

// SAFE: returns null if optionalNested is null
$obj.optionalNested?.map['c']
```

When dealing with nullable items in collections, the `$` (this) reference inside a spread
expression may itself be null. Use `$?` to safely access its properties:

```
$obj.listWithMissingValues.*[$?.attribute]
```


Use `?.*` when the collection itself might be null:

```
$obj.optionalList?.*[$ + 1]
// returns null if optionalList is null, otherwise maps each item
```

### Null Coalescing Operator (`??`)

The null coalescing operator provides a default value when an expression evaluates to `null`:

```
expression ?? defaultValue
```

If the left side is non-null, its value is returned. If the left side is `null`, the right side is
evaluated and returned.

Examples:

```
$entity.attributes['ean'] ?? '1234'
// returns the ean attribute value, or '1234' if ean is null

$entity.references['brand'].attributes['brandTag'] ?? 'new'
// returns the brandTag, or 'new' if it is null
```

The `??` operator can be used inside spread expressions as well:

```
$obj.items.*[$?.name ?? 'unknown']
```

#### Spread Null Coalescing (`*?` and `?*?`)

The spread null coalescing operator replaces `null` items within a collection, or map with a
default value. This is different from `??` which operates on a single value - `*?` operates on each
element inside the collection:

```
collection *? defaultValue
```

Iterates over all elements and replaces any `null` element with the default value:

```
$entity.references['categories'].*[$.attributes['categoryTag']] *? 'new'
// replaces null categoryTags with 'new' -> ["new", "new"]

$obj.objectListWithMissingValues.*[$?.attribute] *? 10
// replaces null attributes with 10 -> ["basic attribute", 10]
```

For maps, only the values are coalesced while keys are preserved:

```
$obj.mapWithPrimitiveMissingValues *? 10
// {"a": 1, "b": null} -> {"a": 1, "b": 10}
```

If the collection itself might be `null`, use `?*?` to return `null` instead of throwing an error:

```
$entity.localizedAttributes['prevUrl'] *? 'https://example.com'
// replaces null locale values with the default URL

$maybeNullList ?*? 0
// returns null if the list is null, otherwise coalesces null items with 0
```

Without the `?`, applying `*?` to a `null` operand throws an error. You can combine this with the null coalescing 
operator to return a default value for the entire collection.

## Operator Precedence

Operators are listed from highest to lowest precedence:

| Precedence | Operators | Description |
|---|---|---|
| 1 | `()` | Parenthesized grouping |
| 2 | `.` `?.` `[]` `?[]` `.*[]` `?.*[]` | Object access, element access, spread |
| 3 | `!` `+` (unary) `-` (unary) | Negation, unary plus/minus |
| 4 | `*` `/` `%` | Multiplication, division, modulo |
| 5 | `+` `-` | Addition, subtraction |
| 6 | `>` `>=` `<` `<=` | Relational comparisons |
| 7 | `==` `!=` | Equality comparisons |
| 8 | `^` | Logical XOR |
| 9 | `&&` | Logical AND |
| 10 | `\|\|` | Logical OR |
| 11 | `*?` `?*?` | Spread null coalescing |
| 12 | `??` | Null coalescing |
