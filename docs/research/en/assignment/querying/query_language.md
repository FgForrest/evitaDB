---
title: Query language
perex: |
  The article introduces the query language used in EvitaDB, covering syntax, querying multiple tables, filtering data, 
  and aggregating data, with code examples.
date: '15.12.2022'
author: 'Ing. Jan Novotný'
proofreading: 'done'
---

The evitaDB query language is composed of a nested set of functions. Each function has its ow name and set of arguments inside
round brackets (like this:`function_name(arguments)`), and any argument can be a plain value of a supported [data type](../model/data_types)
or other functions, arguments and functions are delimited by a comma (like this: `argument1, argument2`) and strings are enveloped by
apostrophes (like this: `'this is string'`).

This language is expected to be used by human operators, because on the code level, the query is represented by a [query object tree](query_api),
that can be constructed directly without an intermediate string language form (on the contrary to an SQL language, which is
strictly string typed). The human-readable form is used in this documentation. The human-readable form can be parsed
to object representations using [the parser](query_api.md#conversion-of-evitaql-from-string-to-ast-and-back).

The query has these four parts:

- **[header](#header):** contains the entity (mandatory) specification
- **[filter](#filter-by):** contains the constraints limiting entities being returned (optional, if missing all are
  returned)
- **[order](#order-by):** defines in what order will the entities return (optional, if missing entities are ordered by
  primary integer key in ascending order)
- **[require](#require):** contains additional information for the query engine, may hold pagination settings, richness
  of the entities and so on (optional, if missing only primary keys of the entities are returned)

The query always returns results in the form of <SourceClass>[EvitaResponseBase.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/io/EvitaResponseBase.java)</SourceClass> containing:

- **<SourceClass>[PaginatedList.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_data_types/src/main/java/io/evitadb/api/dataType/PaginatedList.java)</SourceClass>* of result data
- A [Map](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Map.html) of extra results indexed by their
  class (`<T extends EvitaResponseExtraResult> Map<Class<T>, T>`), see detailed documentation for the individual
  [require](#require) constraints producing extra results

## Grammar

The grammar of the query is as follows:

``` evitaql
query(
    entities('product'),
    filterBy(primaryKey(1, 2, 3)),
    orderBy(desc('name')),
    require(entityBody(), attributes(), associatedData(), allPrices(), references())
)
```

Or a more complex one:

``` evitaql
query(
    entities('product'),
    filterBy(
       and(
          primaryKey(1, 2, 3),
          equals('visible', true)
       )
    ),
    orderBy(
        asc('name'),
        desc('priority')
    ),
    require(
        entityBody(), attributes(), allPrices(),
        facetSummary()
    )
)
```

The filter, order and require constraints are optional, but the first argument specifying the entity type is mandatory. There may be
at most one argument of these types: `filterBy`, `orderBy`, and require. Any of these arguments may be missing or may be
swapped. I.e. the following query is still a valid query:

``` evitaql
query(
    entities('product'),
    filterBy(primaryKey(1)),
    require(entityFetch())
)
```

... or even this one (although, for better readability is recommended to maintain order of: `entities`, `filterBy`,
`orderBy`, require):

``` evitaql
query(
    require(entityFetch()),
    orderBy(attributeNatural('name', ASC)),
    entities('product')
)
```

This also means that there cannot be a constraint of the same name that could be used either in filtering or ordering or a
require constraint. The constraint name uniquely identifies whether the constraint is a filter, order or a require constraint.
The entity type is always a [string](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html).

## Header

The query must specify this entity. This
mandatory [string](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
parameter controls what entity is targeted by the query.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
entities('category')
```
</Note>

## Filter by

The constraint specifies, which entities will be returned from the entity collection. If an entity doesn't fulfill
all the constraints within this constraint, it's not returned by the query engine.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
filterBy(
   and(
       primaryKey(6),
       isTrue('visible'),
       validInTime('validity', 2020-07-30T07:28:13+00:00)
   )
)
```
</Note>

These are the constraint functions that can be used in the filter:

### And

The `and` constraint is the container constraint. It contains two or more inner constraints, whose output is combined by
[logical AND](https://en.wikipedia.org/wiki/Logical_conjunction).

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
and(
    isTrue('visible'),
    validInTime(2020-07-30T07:28:13+00:00)
)
```
</Note>

### Or

The `or` contstraint is also a container constraint. It contains two or more inner constraints, whose output is combined by
[logical OR](https://en.wikipedia.org/wiki/Logical_disjunction).

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
or(
    isTrue('visible'),
    greaterThan('price', 20)
)
```
</Note>

### Not

The `not` constraint is yet another container constraint. It contains a single inner constraint, whose output is negated. Behaves as
[logical NOT](https://en.wikipedia.org/wiki/Negation).

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
not(
    primaryKey(1,2,3)
)
```
</Note>

### Equals

The `equals` constraint compares the value of the attribute whose name passed in the first argument with the value passed in
the second argument. The first argument must be a [string](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html),
the second argument can be anything of the [Comparable](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Comparable.html) type.
The type of the attribute value and the second argument must be convertible one to another, otherwise, the `equals` function returns
false.

The function returns true, if both values are equal.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

```
equals('code', 'abc')
```
</Note>

This function supports attribute arrays. When an attribute value is of the array type, the `equals` function returns true
if *any of the attribute* array element values equal the value in the constraint.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

If we have the attribute `code` with the value `['A','B','C']`, all these constraints will match:

``` evitaql
equals('code','A')
equals('code','B')
equals('code','C')
```
</Note>

### Greater than

The `greaterThan` constraint compares value of the attribute with it's name passed in the first argument with the value
passed in the second argument. The first argument must
be a [string](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html), the second argument may be any
 [comparable](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Comparable.html) type.
The type of the attribute value and second argument must be convertible to one another, otherwise the `greaterThan` function
returns false.

The function returns true, if the value in a filterable attribute of such a name is greater than the value in the second argument.

This function currently doesn't support attribute arrays, and when an attribute is of the array type, the query returns an error.
This may, however, change in the future.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
greaterThan('age', 20)
```
</Note>

### Greater than, equals

The `greaterThanEquals` constraint compares the value of the attribute with it's name passed in the first argument with the value
passed in the second argument. The first argument must
be a [string](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html), the second argument can be
any [Comparable](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Comparable.html) type. The type
of the attribute value and second argument must be convertible one to another, otherwise the `greaterThanEquals` function
returns false.

The function returns true, if a value in a filterable attribute of such a name is greater than the value in the second argument or
equal.

This function currently doesn't support attribute arrays, and when an attribute is of the array type, the query returns an error.
This may, however, change in the future.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

```
greaterThanEquals('age', 20)
```
</Note>

### Less than

The `lessThan` constraint compares the value of the attribute with its name passed in first argument, and with the value passed
in the second argument. The first argument must be a [string](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html),
the second argument can be any [comparable](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Comparable.html) type.
This type of the attribute value and second argument must be convertible to one another, otherwise, the `lessThan` function returns
false.

The Function returns true, if the value in a filterable attribute of such a name is of a lesser value than the in second argument.

This function currently doesn't support attribute arrays, and when an attribute is of the array type, the query returns an error.
This may however change in the future.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
lessThan('age', 20)
```
</Note>

### Less than, equals

The `lessThanEquals` constraint compares the value of the attribute with name passed in the first argument with the value
passed in the second argument. The first argument must
be a [string](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html), the second argument be any
 [Ccomparable](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Comparable.html) type. The type of the attribute value
and second argument must be convertible to one another, otherwise the `lessThanEquals` function returns false.

The function returns true, if the value in a filterable attribute of such a name is of lesser value than the one in the second argument or
equal.

This function currently doesn't support attribute arrays, and when an attribute is of the array type, the query returns an error.
This may, however, change in the future.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
lessThanEquals('age', 20)
```
</Note>

### Between

The `between` constraint compares the value of the attribute with its name passed in the first argument with the value passed in
the second argument, and a value passed in the third argument. The first argument must
be a [string](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html), the second and third argument may be any
[comparable](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Comparable.html) type. Type of the attribute value
and second argument must be convertible to one another, otherwise, the `between` function returns false.

The function returns true, if the value in a filterable attribute of such a name is greater than or equal to the value in second
argument, and lesser than or equal to the value in third argument.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
between('age', 20, 25)
```
</Note>

This function supports attribute arrays, and when an attribute is of the array type, the `between` function returns true, providing
*any of the attribute* array element values is between the passed interval.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

If we have the attribute `amount` with a value of `[1, 9]`, all of these constraints will match:

``` evitaql
between('amount', 0, 50)
between('amount', 0, 5)
between('amount', 8, 10)
```
</Note>

If an attribute is of the <SourceClass>[Range.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_data_types/src/main/java/io/evitadb/api/dataType/Range.java)</SourceClass> type,
the `between` constraint behaves like an overlap - it returns true, if the examined range and any of the attribute ranges
(see previous paragraph about array type behaviour) share anything in common.

<Note type="example">

<NoteTitle toggles="false">

#### Behaviour of between with range type example:
</NoteTitle>

All of the following constraints return true, when we have the attribute `validity` with the following
<SourceClass>[NumberRange.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_data_types/src/main/java/io/evitadb/api/dataType/NumberRange.java)</SourceClass> values: `[[2,5],[8,10]]`:

``` evitaql
between(`validity`, 0, 3)
between(`validity`, 0, 100)
between(`validity`, 9, 10)
```

... but these constraints will return false:

``` evitaql
between(`validity`, 11, 15)
between(`validity`, 0, 1)
between(`validity`, 6, 7)
```
</Note>

### In set

The `inSet` constraint compares the value of the attribute with the name passed in the first argument with all of the values passed
in the second, third and any other additional arguments. The first argument must
be a [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html), additional arguments can be any
[Comparable](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Comparable.html) type. The type
of the attribute value and any additional arguments must be convertible to one another, otherwise the `inSet` function skips the
value comparison and ultimately returns false.

The function returns true if an attribute value is equal to at least one of the additional values.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
inSet('level', 1, 2, 3)
```
</Note>

The function supports attribute arrays, and when an attribute is of the array type, `inSet` returns true, providing that
*any of the attribute* array element values equal the value in the constraint.

<Note type="example">

<NoteTitle toggles="false">

#### Behaviour of inSet with array type example:
</NoteTitle>

If we have the attribute `code` with the value `['A','B','C']`, all these constraints will match:

``` evitaql
inSet('code','A','D')
inSet('code','A','B')
```
</Note>

### Contains

The `contains` constraint searches the value of the attribute with the name passed in the first argument for the presence of the
[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) value passed in the second argument.

The function returns true, if the attribute value contains a secondary argument (starting on any position). The function is
case-sensitive and the comparison is executed using `UTF-8` encoding (Java native).

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
contains('code', 'eve')
```
</Note>

This function supports attribute arrays and when an attribute is of the array type, the `contains` function returns true, providing that
*any of the attribute* array element values contain the value in the constraint.

<Note type="example">

<NoteTitle toggles="false">

#### Behaviour of contains with array type example:
</NoteTitle>
If we have the attribute `code` with the value `['cat','mouse','dog']`, all these constraints will match:

``` evitaql
contains('code','mou')
contains('code','o')
```
</Note>

### Starts with

The `startsWith` constraint searches the value of the attribute with its name passed in first argument for the presence of the
[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) value passed in the second argument.

The Function returns true, if the attribute value contains the secondary argument (starting on first position). In other words,
if the attribute value starts with string passed in second argument. The function is case-sensitive, and the comparison is executed
using `UTF-8` encoding (Java native).

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
startsWith('code', 'vid')
```
</Note>

This Function supports attribute arrays, and when an attribute is of the array type, the `startsWith` function returns true
providing *any of the attribute* array element values start with the value in the constraint.

<Note type="example">

<NoteTitle toggles="false">

#### Behaviour of startsWith with array type example:
</NoteTitle>

If we have the attribute `code` with the value `['cat','mouse','dog']`, all these constraints will match:

``` evitaql
contains('code','mou')
contains('code','do')
```
</Note>

### Ends with

The `endsWith` constraint searches the value of the attribute with name passed in first argument for presence of the
[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) value passed in the second
argument.

The function returns true, if an attribute value contains a secondary argument (using reverse lookup from the last position).
In other words, if the attribute value ends with a string passed in second argument. The function is case-sensitive, and comparison
is executed using `UTF-8` encoding (Java native).

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
endsWith('code', 'ida')
```
</Note>

The function supports attribute arrays, and when an attribute is of the array type, the `endsWith` function returns true,
providing *any of the attribute* array element values end with the value in the constraint.

<Note type="example">

<NoteTitle toggles="false">

#### Behaviour of endsWith with array type example:
</NoteTitle>

If we have the attribute `code` with the value `['cat','mouse','dog']`, all these constraints will match:

``` evitaql
contains('code','at')
contains('code','og')
```
</Note>

### Is true

The `isTrue` constraint compares the value of the attribute with name passed in the first argument with the boolean TRUE value.
The first argument must be a [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html).
The type of the attribute value must be convertible to [boolean](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html),
otherwise the `isTrue` function returns false.

The function returns true, if the attribute value equals to a [Boolean#TRUE](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Boolean.html#TRUE).

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
isTrue('visible')
```
</Note>

The function supports attribute arrays, and when an attribute is of the array type, the `isTrue` function returns true, providing
*any of the attribute* array element values are equal to true.

<Note type="example">

<NoteTitle toggles="false">

#### Behaviour of isTrue with array type example:
</NoteTitle>

If we have the attribute `dead` with the value `['true','false']`, both `isTrue` and `isFalse` match. Hence, we can call
this attribute [Schrödinger](https://en.wikipedia.org/wiki/Schr%C3%B6dinger%27s_cat) one - both dead and undead.
In other words, this constraint will work even if it doesn't make a lot  of a sense.
</Note>

### Is false

The `isFalse` constraint  compares the value of the attribute with its name passed in first argument with the boolean FALSE
value. The first argument must be a [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html).
The type of the attribute value must be convertible to [boolean](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)m,
otherwise, the `isFalse` function returns false.

The function returns true if the attribute value equals
to [Boolean#FALSE](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Boolean.html#FALSE).

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
isFalse('visible')
```
</Note>

The function supports attribute arrays, and when an attribute is of the array type, the `isFalse` function returns true, providing
*any of the attribute* array element values are equal to false.

<Note type="example">

<NoteTitle toggles="false">

#### Behaviour of isFalse with array type example:
</NoteTitle>

If we have the attribute `dead` with the value `['true','false']`, both `isTrue` and `isFalse` match. Hence, we can call
this attribute [Schrödinger](https://en.wikipedia.org/wiki/Schr%C3%B6dinger%27s_cat) one - both dead and undead.
In other words, this constraint will work even if it doesn't make a lot  of a sense.
</Note>

### Is null

The `isNull` constraint checks the existence of a value of the attribute with its name passed in first argument. The first argument
must be a [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html). The attribute
of such name must not exist, and if it doesn't, the `isNull` function returns true.

The function returns true if the attribute doesn't exist.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
isNull('visible')
```
</Note>

Function supports attribute arrays in the same way as plain values.

### Is not null

The `isNotNull` constraint checks for the existence of a value of the attribute with its name passed in first argument. The first argument
must be [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html). The attribute
of such name must exist, and if it does, the `isNotNull` function returns true.

The function returns true if the attribute exists.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
isNotNull('visible')
```
</Note>

The function supports attribute arrays in the same way as plain values.

### In range

The `inRange` constraint compares the value of the attribute with its name passed in the first argument with the date and time
value passed in the second argument. the first argument must be a
[String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html), the second argument must
be a <SourceClass>[Range.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_data_types/src/main/java/io/evitadb/api/dataType/Range.java)</SourceClass> type.

The function returns true, if the second argument is greater than or equal to the range start (from), and is lesser than or equal to the
range end (to).

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
inRange('valid', 2020-07-30T20:37:50+00:00)
inRange('age', 18)
```
</Note>

The function supports attribute arrays, and when an attribute is of the array type, the `inRange` function returns true, providing
*any of the attribute* array element values have a range that envelopes the passed value in the constraint.

<Note type="example">

<NoteTitle toggles="false">

#### Behaviour of inRange with array type example:
</NoteTitle>

If we have the attribute `age` with the value `[[18, 25],[60,65]]`, all of these constraints will match:

``` evitaql
inRange('age', 18)
inRange('age', 24)
inRange('age', 63)
```
</Note>

### Primary key

The `primaryKey` constraint that accepts a set of [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)
values, representing primary keys of the entities, that should be returned.

The function returns true, if entity primary key is equal to any one of the passed set of integers.

<Note type="info">
This kind of entity lookup function is the fastest one. If you have primary keys available, use them
for querying preferably.
</Note>

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
primaryKey(1, 2, 3)
```
</Note>

### Language

The `language` constraint only accepts a single [Locale](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Locale.html)
argument.

The function returns true, if the entity has at least one localized attribute or associated data using the specified locale.

<Note type="info">
If the require constraint part of the query doesn't contain the [dataInLanguage](#data-in-language) requirement constraint,
that would specify the requested data localization, this filtering constraint implicitly sets requirement to the passed
locale argument. In other words, if the entity has two localizations: `en-US` and `cs-CZ`, and the `language('cs-CZ')`
constraint is used in query, the returned entity would only have the `cs-CZ` localization of attributes and associated data
fetched along with it (and also attributes that are locale agnostic).
</Note>

If a query contains no `language` constraint, the filtering logic is applied only on "global" (i.e. language agnostic)
attributes.

<Note type="warning">
Only a single `language` constraint can be used in the query.
</Note>

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
language('en-US')
```
</Note>

### Price in currency

The `priceInCurrency` constraint only accepts a single [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
argument that represents [currency](https://en.wikipedia.org/wiki/ISO_4217) in an ISO 4217 code.

The function returns true, if the entity has at least one price with the specified currency. This function is also affected by the
[priceInPriceLists](#price-in-price-lists) function, limiting the examined prices as well.

<Note type="info">
If the require constraint part of the query doesn't contain any [prices](#prices) requirement constraints
that would specify the requested price currency, this filtering constraint implicitly sets requirement to the passed
currency argument. In other words, if the entity has prices with two currencies: `USD` and `CZK`, and the `priceInCurrency('CZK')`
constraint is used in the query, the returned entity would only have `CZK` prices fetched along with it.
</Note>

<Note type="warning">
Only a single `priceInCurrency` constraint can be used in the query.
</Note>

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
priceInCurrency('USD')
```
</Note>

### Price in price lists

The `priceInPriceLists` constraint accepts one or more [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
arguments representing names (or any other unique identifiers) of the price lists. The price list is a key for distinguishing
multiple prices of a single entity.

The function returns true, if the entity has at least one price in any of the specified price lists. This function is affected
by the [priceInCurrency](#price-in-currency) function, limiting the examined prices as well. The order of the price lists
passed in the argument is crucial, because it defines the priority of the price lists.

<Note type="example">

<NoteTitle toggles="false">

#### Behaviour of `priceInPriceLists` regarding the price list priority:
</NoteTitle>

Let's have a product with following prices:

| priceList       | currency | priceWithTax |
|-----------------|----------|--------------|
| basic           | EUR      | 999.99       |
| registered_user | EUR      | 979.00       |
| b2c_discount    | EUR      | 929.00       |
| b2b_discount    | EUR      | 869.00       |

If the query contains:

``` evitaql
and(
	priceInCurrency('EUR'),
	priceInPriceLists('basic', 'b2b_discount'),
	priceBetween(800.0, 900.0)
)
```

The product will not be found - because the query engine will use the first defined price for the price lists in the defined
order. In our case,  it's the price `999.99`, which is not in the defined price interval 800€-900€. If the price lists in the
argument gets switched to `priceInPriceLists('b2b_discount', 'basic')`, the product will be returned, because the first
price is now from the `b2b_discount` price list - 869€, and this price is within the defined interval.
</Note>

<Note type="info">
If the require constraint part of the query doesn't contain a [prices](#prices) requirement constraint
that would specify the requested price lists, this filtering constraint implicitly sets the requirement to the passed
price list argument. In other words, if the entity has two prices - one from the price list `basic`, second from price list
`b2b`, and the `priceInPriceLists('basic')` price list is used in the query, the returned entity would only have the first price for the
`basic` price list fetched along with it.
</Note>

The non-sellable prices are not taken into account in the search - if the entity **only** has non-sellable
prices, it will never be returned when the [priceInPriceLists](#price-in-price-lists) constraint or any other price
constraint is used in the query. Non-sellable prices behave as if they don't exist. These non-sellable prices still
remain accessible for reading on the fetched entity, in case the product is found by a sellable price satisfying the filter.
If you have a specific price list reserved for non-sellable prices, you may still use it
in the [priceInPriceLists](#price-in-price-lists) constraint. It won't affect the set of returned entities, but it will
ensure you can access those non-sellable prices on entities even when the `RESPECTING_FILTER` in [prices](#prices)
requirement constraint is used.

<Note type="warning">
Only a single `priceInPriceLists` constraint can be used in the query.
</Note>

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
priceInPriceLists(1, 5, 6)
```
</Note>

### Price valid in time

The `priceValidIn` constraint accepts a single [OffsetDateTime](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/OffsetDateTime.html)
argument representing the moment in time for which the entity price must be valid. The argument may be missing, and if so,
the current date and time (now) will be used instead.

The function returns true, if the entity has at least one sellable price, and if the price's validity (valid from) is lesser or equal
to the passed date and time, and the validity end (valid to) is greater than or equal to the passed date and time. This function is
affected by the [priceInCurrency](#price-in-currency) and the [priceInPriceLists](#price-in-price-lists) functions, limiting
the examined prices as well.

<Note type="info">
If the require constraint part of the query doesn't contain a [prices](#prices) requirement constraint
that would specify the requested price lists, this filtering constraint implicitly sets the requirement to the passed
price list argument. In other words, if entity has two prices - one valid from tomorrow, and the second one valid until tomorrow,
and `priceValidIn()` is used in the query (using the `now` moment implicitly), the returned entity would only have the first
price for valid now fetched along with it.
</Note>

<Note type="warning">
Only a single `priceValidIn` constraint can be used in the query. The validity of the prices will not be taken into an account
when `priceValidIn` is not used in the query.
</Note>

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
priceValidIn(2020-07-30T20:37:50+00:00)
```
</Note>

### Price between

The `priceBetween` constraint accepts two [BigDecimal](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/math/BigDecimal.html)
arguments representing lower and higher price bounds (inclusive).

The function returns true, if entity has a sellable price in most prioritized price list according to the
[priceInPriceLists](#price-in-price-lists) constraint greater than or equal to the passed lower bound, and lesser than
or equal to the passed higher bound. This function is affected by other price related constraints, such as the
[priceInCurrency](#price-in-currency) functions limiting the examined prices as well.

The most prioritized price term relates to the [price computation algorithm](price_computation.md) described in a separate article.
Non-sellable prices doesn't participate in the filtering at all.

<Note type="info">
By default, the price with tax is used for filtering, but you can change this by using the [queryPriceMode](#query-price-mode)
require constraint.
</Note>

<Note type="warning">
Only a single `priceBetween` constraint can be used in the query.
</Note>

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
priceBetween(150.25, 220.0)
```
</Note>

### Facet

The `facet` constraint accepts a [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
entity type in first argument,and one or more additional [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)
arguments that represent [references](../index.md#references) the entity is required to have in order to match this
constraint.

The function returns true, if an entity has a facet (faceted reference) for the specified reference name matching any of the passed
primary keys in the additional arguments. In other words, the entity references, whose primary key is equal to any
of the passed primary keys.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
query(
    entities('product'),
    filterBy(
        userFilter(
            facet('category', 4, 5),
            facet('group', 7, 13)
        )
    )
)
```
</Note>

<Note type="info">
The constraint should be used in a [user filter](#user-filter) container because it represents the user selection, which is
considered as optional by the search engine in certain cases. See the detailed description of the [user filter](#user-filter)
constraint container for more information.
</Note>

By default, facets of the same type within the same group are combined by disjunction (OR) and facets of different
reference names / groups are combined by conjunction (AND). This default behaviour can be surgically controlled by using
any of the following require constraints:

- [facet groups conjunction](#facet-groups-conjunction) - changes the relationship between facets in the same group
- [facet groups disjunction](#facet-groups-disjunction) - changes the relationship between facet groups

<Note type="question">

<NoteTitle toggles="true">

#### Why facet relation is specified by require constraint and not filtering one?
</NoteTitle>

The reason is simple - facet relations in certain groups are usually specified system-wide, and don't change in time
frequently. This means that it could be easily cached, and passing this information in an extra require simplifies the query
construction process.

Another reason is that we need to know the relationships among facet groups even for types/groups that haven't yet been
selected by the user in order to compute the [facet summary](#facet-summary) output.
</Note>

### Reference having attribute

The `referenceHavingAttribute` constraint container filters returned entities by attributes specified in their reference
relations. The attributes in the relation specified by the first [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
argument of this container must match the filtering constraints specified in any additional arguments.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
referenceHavingAttribute(
   'categories',
   eq('code', 'KITCHENWARE')
)
```
</Note>

or

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
referenceHavingAttribute(
   'categories',
   and(
      isTrue('visible'),
      eq('code', 'KITCHENWARE')
   )
)
```
</Note>

<Note type="warning">
In order to filter by attributes specified on reference relations, both the reference schema itself and the attribute
must be marked as "filterable", otherwise an error is returned.
</Note>

### Within hierarchy and within root hierarchy

The `withinHierarchy` constraint accepts a [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
reference name in the first argument, a primary key of the [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)
type of entity with [hierarchical placement](../index#hierarchical-placement) in the second argument. There
are also optional additional arguments - see constraints [directRelation](#direct-relation),
[excluding root](#excluding-root) and [excluding](#excluding) for more information.

If you query an entity schema that is hierarchical itself (see [hierarchical placement](../index#hierarchical-placement)),
you need to use just one  numeric argument representing the primary key of the parent entity in an [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html).
This format of the constraint usage may be used ,for example, for returning a category subtree (where we
want to return category entities and also filter them by their own hierarchy placement).

The function returns true, if entity has at least one [reference](../index#references) relating to the specified
reference name either directly or transitively to any other reference with [hierarchical placement](../index#hierarchical-placement)
subordinate to the directly related entity placement (in other words is present in its subtree).

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

Let's have the following hierarchical tree of categories (primary keys are in brackets):

- `TV` (`1`)
    - `Crt` (`2`)
    - `LCD` (`3`)
        - `big` (`4`)
        - `small` (`5`)
    - `Plasma` (`6`)
- `Fridges` (`7`)

When the following query targeting product entities is used:

``` evitaql
withinHierarchy('category', 1)
```

only the products that relate directly to the categories: `TV`, `Crt`, `LCD`, `big`, `small` and `Plasma` will be returned.
Products in `Fridges` will be omitted, because they are not in a subtree of the `TV` hierarchy.
</Note>

<Note type="warning">
Only a single `withinHierarchy` constraint can be used in the query.
</Note>

If you want to constraint the entity that you're querying on, you need to omit the reference specification.

<Note type="example">

<NoteTitle toggles="false">

#### Querying the same entity type:
</NoteTitle>

``` evitaql
query(
   entities('CATEGORY'),
   filterBy(
      withinHierarchy(5)
   )
)
```

This query will return all the categories that belong to the subtree of category with a primary key equal to 5.
</Note>

If you want to list all entities from the root level you need to use a different constraint - `withinRootHierarchy`
using the same notation, but omitting the primary key of the root level entity

<Note type="example">

<NoteTitle toggles="false">

#### Querying the same entity type from root level:
</NoteTitle>

``` evitaql
query(
	entities('CATEGORY'),
	filterBy(
		withinRootHierarchy()
	)
)
```

This query will return all of the categories within the `CATEGORY` entity.
</Note>

<Note type="example">

<NoteTitle toggles="false">

#### Querying the different entity type from root level:
</NoteTitle>

You may use this constraint to list entities that refers to the hierarchical entities:

``` evitaql
query(
   entities('PRODUCT'),
   filterBy(
      withinRootHierarchy('categories')
   )
)
```

This query returns all the products that are attached to any category. Although, this query doesn't make much sense itself,
it starts to be useful when it's combined with additional inner constraints described in the following paragraphs.
</Note>

You can use additional sub constraints the in `withinHierarchy` or `withinRootHierarchy` constraints:

#### Direct relation

The `directRelation` constraint can be used only as sub constraint of
[`withinHierarchy` or `withinRootHierarchy`](#within-hierarchy-and-within-root-hierarchy).
Using this constraint in the query ensures that only the entities directly referencing the matching hierarchy entity
will be returned. In other words, the transitive references (subtree) will not match the parent constraint filter.

<Note type="example">

<NoteTitle toggles="false">

#### Filtering direct related products (different entity) example:
</NoteTitle>

Let's have the following category tree related by following products:

- `TV` (`1`):
    - Product `Philips 32"`
    - Product `Samsung 24"`
    - `Crt` (`2`):
        - Product `Ilyiama 15"`
        - Product `Panasonic 17"`
    - `LCD` (`3`):
        - Product `BenQ 32"`
        - Product `LG 28"`
        - `AMOLED` (`4`):
            - Product `Samsung 32"`

When using this query:

``` evitaql
query(
   entities('PRODUCT'),
   filterBy(
      withinHierarchy('categories', 1)
   )
)
```

All products will be returned.

When this query is used:

``` evitaql
query(
   entities('PRODUCT'),
   filterBy(
      withinHierarchy('categories', 1, directRelation())
   )
)
```

Only products directly related to TV category will be returned - i.e.: `Philips 32"` and `Samsung 24"`. Products related
to sub-categories of `TV` category will be omitted.
</Note>

<Note type="example">

<NoteTitle toggles="false">

#### Filtering direct related categories (entity itself) example:
</NoteTitle>

You can also use this hint to browse the hierarchy of the entity itself - to fetch subcategories of category. If you use
this query:

``` evitaql
query(
   entities('CATEGORY'),
   filterBy(
      withinHierarchy(1)
   )
)
```

All categories under the category subtree of `TV` will be listed (this means categories `TV`, `Crt`, `LCD`, `AMOLED`).

If you use this query:

``` evitaql
query(
   entities('CATEGORY'),
   filterBy(
      withinHierarchy(1, directRelation())
   )
)
```

Only direct sub-categories of category `TV` will be listed (this means categories `Crt` and `LCD`).
</Note>

<Note type="example">

<NoteTitle toggles="false">

#### Filtering root categories only (entity itself) example:
</NoteTitle>

If you need to filter the root categories only, you can use following query:

``` evitaql
query(
	entities('CATEGORY'),
	filterBy(
		withinRootHierarchy(directRelation())
	)
)
```
</Note>

<Note type="info">
Notice that the previous example doesn't make sense for querying any entities of a different type than the hierarchical entity
itself. This query:

``` evitaql
query(
	entities('PRODUCT'),
	filterBy(
		withinRootHierarchy('categories', directRelation())
	)
)
```

will never return any product, because no product can relate the "virtual" super root of category entity. Products will
always target some existing category entity and this means that only the `withinHierarchy` constraint makes any sense in
this case.
</Note>

#### Excluding root

The `excludingRoot` constraint can only be used as sub constraint of
[`withinHierarchy` or `withinRootHierarchy`](#within-hierarchy-and-within-root-hierarchy).
Using this constraint in the query ensures that all the entities referencing the matching hierarchy entity,
will be omitted from the query result.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

Let's have the following category tree with products referencing them:

- `TV` (`1`):
    - Product `Philips 32"`
    - Product `Samsung 24"`
    - `Crt` (`2`):
        - Product `Ilyiama 15"`
        - Product `Panasonic 17"`
    - `LCD` (`3`):
        - Product `BenQ 32"`
        - Product `LG 28"`

When using this query:

``` evitaql
query(
   entities('PRODUCT'),
   filterBy(
      withinHierarchy('categories', 1)
   )
)
```

all the products will be returned.

When this query is used:

```
query(
   entities('PRODUCT'),
   filterBy(
      withinHierarchy('categories', 1, excludingRoot())
   )
)
```

Only products related to sub-categories of the `TV` category will be returned - i.e.: `Ilyiama 15"`, `Panasonic 17"` and
`BenQ 32"`, `LG 28"`. The products related directly to tee `TV` category will not be returned.
</Note>

<Note type="info">
The `excludingRoot` constraint doesn't make sense to be used within the `withinRootHierarchy`.
As you can see, the `excludingRoot` and `directRelation` constraints are mutually exclusive, and should not be used
together within the same parent hierarchy constraint.
</Note>

#### Excluding

The `excluding` constraint can be used only as sub constraint of
[`withinHierarchy` or `withinRootHierarchy`](#within-hierarchy-and-within-root-hierarchy).
Using this constraint in the query ensures that all the entities referencing the matching hierarchy entity or
transitively any of its subordinate entities (subtree), will be omitted from the query result. It accepts one or
more [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) primary keys that mark
the hierarchy's "relative" roots, whose subtrees should be omitted from the result.

Exclusion arguments allow or the excluding of certain parts of the hierarchy tree from examination. This constraint comes handy
when the catalog implementation allows for the making of categories that are "invisible" for some end users while keeping those
categories for different kinds of users accessible.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

Let's have the following hierarchical tree of categories (primary keys are in brackets):

- `TV` (`1`)
    - `Crt` (`2`)
    - `LCD` (`3`)
        - `big` (`4`)
        - `small` (`5`)
    - `Plasma` (`6`)
- `Fridges` (`7`)

When the following query is used:

``` evitaql
query(
	entities('CATEGORY'),
	filterBy(
		withinHierarchy(1, excluding(3))
	)
)
```

only the categories `TV`, `Crt`, `Plasma` will be returned. The category `Fridges` will be omitted, because it isn't present
within `TV` hierarchy tree. The category `LCD` and its sub-categories will be omitted due to the `excluding` constraint.
</Note>

<Note type="example">

<NoteTitle toggles="false">

#### Excluding subtree example for different entity:
</NoteTitle>

Let's have the following category tree with products referencing them:

- `TV` (`1`)
	- `Crt` (`2`)
		- Product `Philips 32"`
		- Product `Samsung 24"`
	- `LCD` (`3`)
		- Product `BenQ 32"`
		- `big` (`4`)
			- Product `Panasonic 40"`
		- `small` (`5`)
			- Product `Ilyiama 15"`
	- `Plasma` (`6`)
		- Product `LG 28"`
- `Fridges` (`7`)

When using this query:

``` evitaql
query(
	entities('PRODUCT'),
	filterBy(
		withinHierarchy(1, excluding(3))
	)
)
```

only the `Philips 32"`, `Samsung 24"` and `LG 28"` will be returned. The products in `Fridges` will be
omitted because they are not in a subtree of the `TV` hierarchy and products directly related to the `LCD` category or any other
sub-category will be omitted because they're part of the excluded subtrees of the `excluding` constraint.
</Note>

### User filter

The `userFilter` constraint container could contain any constraint except [priceInPriceLists](#price-in-price-lists),
[language](#language), [priceInCurrency](#price-in-currency), [priceValidInTime](#price-valid-in-time) and
[within hierarchy](#within-hierarchy-and-within-root-hierarchy), which make no sense to be directly set by the end user 
and affect the overall evaluation of the query. All the constraints placed directly inside `userFilter` are combined with by
conjunction (AND).

The constraints placed in the `userFilter` container should react to the filter selection defined by the end user, and must
be isolated from the base filter so that the [facetSummary](#facet-summary) and [histogram](#attribute-histogram) logic
can distinguish a mandatory filtering constraint for a facet summary computation from the optional user defined one.
The facet summary must compute a so-called baseline count - i.e. the count of the entities that match system constraints excluding
the currently set filter of the end user. Similar logic also applies to histogram computation, where the selection
applied on the attribute the histogram takes the data from must not be taken into an account.

<Note type="warning">
Only single `userFilter` constraint can be used in the query.
</Note>

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
userFilter(
    greaterThanEq('memory', 8),
    priceBetween(150.25, 220.0),
    facet('parameter', 4, 15)
)
```
</Note>

<Note type="example">

<NoteTitle toggles="false">

#### Complex Example usage:
</NoteTitle>

Even more complex queries are supported (although it is hard to make up some real life example for such):

``` evitaql
filterBy(
    and(
        or(
            referenceHavingAttribute('CATEGORY', eq(code, 'abc')),
            referenceHavingAttribute('STOCK', eq(market, 'asia')),
        ),
        eq(visibility, true),
        userFilter(
            or(
                and(
                    greaterThanEq('memory', 8),
                    priceBetween(150.25, 220.0)
                ),
                and(
                    greaterThanEq('memory', 16),
                    priceBetween(800.0, 1600.0)
                ),
            ),
            facet('parameter', 4, 15)
        )
    )
),
require(
    facetGroupDisjunction('parameterType', 4),
    negatedFacets('parameterType', 8),
)

```
</Note>

<Note type="info">
The `userFilter` constraint might be a subject to change and affects advanced searching queries (not planned to be
implemented in the research implementations) such as exclusion facet groups (i.e. facet in group are not represented as
multi-select/checkboxes but as exclusive select/radio), or conditional filters (which can be used to apply a certain
filter only if it would produce a non-empty result, this is good for "sticky" filters).
</Note>

#### Hints for implementation

The user filter envelopes the part of the query that is affected by user selection and that is optional. All constraints
outside user filter are considered mandatory and must never be altered by the [facet summary](#facet-summary) computational
logic.

The base count of the facets are computed for a query having the `userFilter` container contents stripped off. The "what-if"
counts requested by the [impact argument](#facet-summary) are computed from the query including a `userFilter`, creating
multiple sub-queries and checking the result for each additional facet selection.

[Facet](#facet) filtering constraints must be direct children of the `userFilter` container. Their relationship is by
default as follows: facets of the same type within same group are combined by disjunction (OR)- and facets of different
types / groups are combined by conjunction (AND). This default behaviour can be surgically controlled by using any of
the following require constraints:

- [facet groups conjunction](#facet-groups-conjunction) - changes the relationship between facets in the same group
- [facet groups disjunction](#facet-groups-disjunction) - changes the relationship between facet groups

The constraints different from the `facet` filtering constraints (as seen in example) can represent user conditions in
non-faceted inputs, such as interval inputs.

## Order by

The ordering constraints specify in what order entities will be returned from the entity collection. If the entity has no
valid value requested by ordering, secondary, tertiary and other order constraints are taken into account when primary
filtering is all sorted out. When no ordering can be applied to an entity, it'll be appended to the end of the result
in ascending order of its primary key.

An evita DB sample ordering might look like this:

```
orderBy(
    ascending('code'),
    ascending('create'),
    priceDescending()
)
```

The ordering process goes as follows:

- the first ordering is evaluated, entities missing the requested attribute value are moved to an intermediate bucket
- the next ordering is evaluated using entities present in an intermediate bucket,
  entities missing the requested attribute are moved to a new intermediate bucket
- the second step is repeated until all orderings are processed
- the content of the last intermediate bucket is appended to the result ordered by the primary key in ascending order

Entities with the same (equal) values must not be subject to secondary ordering rules and may be sorted randomly within the
scope of entities with the same value (this is subject to change; this behaviour differs from the one used by relational
databases - but might be more performant). See [issue #11](https://github.com/FgForrest/evitaDB/issues/11) for planned
changes in this area.

<Note type="info">
The array type attributes don't support sorting.
</Note>

Ordering functions that can be used in the order by the constraint container are these:

### Ascending

The `ascending` ordering constraint sorts returned entities by values in the attribute with the name passed in the first
argument in ascending order. The argument must be of the [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) type.
The ordering is executed by natural order of the [Comparable](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Comparable.html)
type.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
ascending('age')
```
</Note>

### Descending

The `descending` ordering constraint sorts the returned entities by values in an attribute with the name passed in the first
argument in descending order. The argument must be of the [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) type.
The ordering is executed by reversed natural order of the [Comparable](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Comparable.html)
type.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
descending('age')
```
</Note>

### Price ascending

The `priceAscending` ordering constraint sorts returned entities by price for sale in ascending order. The price for
sale is computed by an [algorithm described in separate chapter](price_computation.md).

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
priceAscending()
```
</Note>

### Price descending

The `priceDesscending` ordering constraint sorts returned entities by price for sale in descending order. The price for
sale is computed by the [algorithm described in separate chapter](price_computation.md).

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
priceDescending()
```
</Note>

### Random

The `random` ordering constraint sorts returned entities in random order.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
random()
```
</Note>

### Reference attribute

The `referenceAttribute` ordering constraint container sorts the returned entities by reference attribute. The name of
the reference is specified in the first [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
argument, the ordering for the attribute is specified in additional argument(s). Price related orderings cannot be used
here, because the references don't possess prices.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
referenceAttribute(
   'CATEGORY',
   ascending('categoryPriority')
)
```
</Note>

or

<Note type="example">

<NoteTitle toggles="false">

#### Usage example with multiple ordering constraints:
</NoteTitle>

``` evitaql
referenceAttribute(
   'CATEGORY',
   ascending('categoryPriority'),
   descending('stockPriority')
)
```
</Note>

## Require

The require constraints specify additional behaviour of the query interpretation or completeness of the returned entities.
Some require constraints trigger the computation of additional data upon returned entities. There is a valid expectation
that the derived statistics computed along with result of the original query will be computed much faster compared to
a situation where they are collected and computed in multiple separate queries. This expectation comes from the idea
that within the same query, the engine could take advantage of shared intermediate results, and avoid repeated work
otherwise necessary in isolated queries.

When no require constraint is used, the primary keys of the first 20 entities matching the passed query are returned, ordered
by their primary key in ascending order.

A sample evitaDB query with the require constraint might look like this:

``` evitaDB
require(
    entityBody(),
    page(1, 20)
)
```

The functions that can be used in the require container are these:

### Page

The `page` constraint controls the count of entities in the query output. It allows for specifying 2 arguments in the following
order:

- **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) pageNumber**: The number of the page of
  results that are expected to be returned, starts with 1, must be greater than zero (mandatory)
- **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) pageSize**: The number of entities on a
  single page, must be greater than zero (mandatory)

<Note type="example">

<NoteTitle toggles="false">

#### Return first page with 24 items:
</NoteTitle>

``` evitaql
page(1, 24)
```
</Note>

### Strip

The `strip` constraint controls the count of entities in the query output. It allows specifying 2 arguments in the following
order:

- **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) offset**: The number of the items that
  should be omitted in the result, must be greater than or equals to zero (mandatory)
- **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) limit**: The number of entities that
  should be returned, must be greater than zero (mandatory)

<Note type="example">

<NoteTitle toggles="false">

#### Return 24 records from index 52:
</NoteTitle>

``` evitaql
strip(52, 24)
```
</Note>

### Entity body

The `entityBody` constraint changes the default behaviour of the query engine, only returning entity primary keys in
the result. When this require constraint is used, the result contains [entity bodies](../index#entity-type), except
`attributes`, `associated data`, `references` and `prices`. These types of data can be fetched either lazily or by
specifying additional require constraints ([attributes](#attributes), [associatedData](#associated-data),
[references](#references), [prices](#prices)) in the query.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
entityBody()
```
</Note>

### Attributes

The `attributes` require constraint changes the default behaviour of the query engine returning only entity primary keys in the result.
When this require constraint is used, the result contains [entity bodies](../index#entity-type) along with
[attributes](../index.md#attributes--unique-filterable-sortable-localized-). Other parts of the entity (`associated data`,
`references` and `prices`) fetching react to similar requirement constraints, but are not related to this one.

The `attributes` require constraint implicitly triggers an [entity](#entity-body) fetch, because attributes cannot be
returned without its entity container. [Localized attributes](../index#localized-attributes) are returned according to the
[data in language](#data-in-language) requirement constraint, or [language](#language) filtering constraint, if
the requirements don't declare it.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
attributes()
```
</Note>

### Prices

The `prices` require constraint changes the default behaviour of the query engine, only returning the entity primary keys in
the result. When this require constraint is used, the result contains [entity prices](../index#prices). Other parts of
the entity (`attributes`, `associated data` and `references`) fetching react to similar requirement constraints, but are
not related to this one.

This require constraint implicitly triggers an [entity](#entity-body) require, because prices cannot be returned without
its entity container. By default, the fetched prices are filtered according to price filtering constraints, should
they have been used in the query. This behaviour might be changed, by a single optional argument of this requirement constraint.

The constraint accepts a single optional argument <SourceClass>[PriceFetchMode.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_query/src/main/java/io/evitadb/api/query/require/PriceFetchMode.java)</SourceClass>
with following options:

- **ALL**: all prices of the entity are returned regardless of the input query constraints, otherwise, prices are filtered
  by those constraints
- **RESPECTING_FILTER (default)**: only prices that match query filter will be returned along with the entity
- **NONE**: no prices will be returned along with entity

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
prices(ALL)
```
</Note>

### Associated data

The `associatedData` require constraint changes default behaviour of the query engine, only returning the entity primary keys
in the result. When this require constraint is used, the result contains [entity bodies](../index#entity-type) along with
[associated data](../index#associated-data)). Other parts of the entity (`attributes`, `references` and `prices`)
fetching react to similar requirement constraints, but are not related to this one.

The `associatedData` require constraint implicitly triggers an [entity](#entity-body) fetch, because associated data cannot
be returned without its entity container. [Localized associated data](../index#localized-associated-data) is returned
according to the [data in language](#data-in-language) requirement constraint, or the [language](#language) filtering constraint,
if the requirements don't declare it.

The constraint accepts one or more [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
arguments that refer to the associated data names that should be fetched along with the entity.
If no argument is passed to the constraint - all associated data is fetched along. Because the associated data is
expected to be quite heavy, we recommend to always fetch selected associated data by their name explicitly and avoid
fetching them all at once.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
associatedData('description', 'gallery-3d')
associatedData()
```
</Note>

### References

The `references` require constraint changes default behaviour of the query engine, only returning the entity primary keys in
the result. When this require constraint is used, result contains [entity bodies](../index#entity-type) along with
references to any internal or external entity types specified in one or more arguments of this require constraint.  Other
parts of the entity (`attributes`, `associated data` and `prices`) fetching react to similar requirement constraints,
but are not related to this one.

This require constraint implicitly triggers an [entity body](#entity-body) require, because the references cannot be
returned without its container entity.

References are always returned with all their attributes (i.e. attributes set on this particular relation to the entity).

The constraint accepts one or more [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
arguments that refer to the reference data names that should be fetched along with the entity.
If no argument is passed to the constraint - all references are fetched along. Because the references are
expected to be quite heavy, we recommend to always fetch selected references by their name explicitly and avoid fetching
them all at once.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
references(BRAND, CATEGORY, 'stock')
references()
```
</Note>

### Data in language

The `dataInLanguage` require constraint accepts zero or more [Locale](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Locale.html)
arguments. If the result requires returning entity bodies along with attributes and associated data, these data will
contain both global data and localized ones. The localization of the data will respect the arguments of this requirement
constraint.

If constraint contains no argument, data localized to all languages is returned.

<Note type="info">
If neither the `dataInLanguage` require constraint, nor the [language filtering constraint](#language) is present in the query,
only global attributes and associated data is returned.
</Note>

<Note type="warning">
Only a single `dataInLanguage` constraint can be used in the query.
</Note>

<Note type="example">

<NoteTitle toggles="false">

#### Fetching global attributes and attributes localized to English:
</NoteTitle>

If you only need to fetch global and `en-US` localized attributes and associated data, (considering there are multiple
language localizations):

``` evitaql
dataInLanguage('en-US')
```
</Note>

<Note type="example">

<NoteTitle toggles="false">

#### Fetching data in all available locales:
</NoteTitle>

The following requirement fetches all of the available global and localized data:

``` evitaql
dataInLanguage()
```
</Note>

### Use of price

The `useOfPrice` require constraint can be used to control the form of prices that will be used for computation in the
[priceBetween](#price-between) filtering, and [price ascending](#price-ascending)/[descending](#price-descending)
ordering. Also, [price histogram](#price-histogram) is sensitive to this setting. By default, the end customer form of
price (e.g. price with tax) is used in all of the above-mentioned constraints. This could be changed by using this requirement
constraint. It has a single [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
argument that can have one of the following values:

- **WITH_TAX (default)**
- **WITHOUT_TAX**

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
useOfPrice(WITH_TAX)
```
</Note>

### Parents

The `parents` require constraint can be used in relation with [hierarchical entities](../index#hierarchical-placement),
and targets the entity type that is requested by the query. The constraint can also have inner require constraints 
that define how rich the returned information should be (by default, only the primary keys are returned, but full entities can
be returned as well).

When this require constraint is used, an additional object of the
<SourceClass>[Parents.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/io/extraResult/Parents.java)</SourceClass> type is
stored to result the index. This data structure contains any information about the referenced entity paths for each entity in
the response.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

An example for returning the parents of the same type as was queried (e.g. parent categories of filtered categories):

``` evitaql
parents()
```
</Note>


<Note type="example">

<NoteTitle toggles="false">

#### Usage example returning full bodies of parent entities:
</NoteTitle>

Any additional data structures, by default, only return the primary keys of those entities, but it can also provide full parent
entities when this form of constraint is used:

``` evitaql
parents(
	entityBody(),
	attributes(),
	associatedData(),
	references(),
	prices()
)
```
</Note>

### Parents of type

The `parentsOfType` require constraint can be used in relation with [hierarchical entities](../index#hierarchical-placement),
and can have one or more [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
arguments that specify the reference name of any hierarchical entity that this entity relates to.
The constraint can also have inner require constraints that define how rich the returned information should be (by default,
only the primary keys are returned, but full entities can be returned as well).

When this require constraint is used, an additional object of the 
<SourceClass>[Parents.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/io/extraResult/Parents.java)</SourceClass> type is
stored to the result index. This data structure contains any information about the referenced entity paths for each entity in
the response.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

The following query returns the parents of the category when the entity type `product` is queried:

``` evitaql
query(
	entities('PRODUCT'),
	filterBy(primaryKey(1, 2, 3)),
	require(
		parentsOfType('category')
	)
)
```
</Note>

<Note type="example">

<NoteTitle toggles="false">

#### Usage example returning full bodies of parent entities:
</NoteTitle>

Any additional data structures ,by default, only return primary keys of those entities, but it can also provide full parent
entities when this form of constraint is used:

``` evitaql
query(
	entities('PRODUCT'),
	filterBy(primaryKey(1, 2, 3)),
	require(
		parentsOfType(
			'category',
			entityBody(),
			attributes(),
			associatedData(),
			references(),
			prices()
		)
	)
)
```
</Note>

### Facet summary

The `facetSummary` requirement constraint triggers the computing and adding of an object of the
<SourceClass>[FacetSummary.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/io/extraResult/FacetSummary.java)</SourceClass>
type to the result index. The data structure is quite complex, but it allows for the rendering of an entire facet listing to end users.
It contains information about all the facets present in the current hierarchy view, along with count of requested entities
that have those facets assigned.

The facet summary respects the current query filtering constraints, excluding the conditions inside the [userFilter](#user-filter)
container constraint.

The constraint optionally accepts a single [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) argument:

- **COUNT (default):** only counts of facets will be computed
- **IMPACT:** counts and selection impact for non-selected facets will be computed

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
facetSummary()
facetSummary(COUNT) //same as previous row - default
facetSummary(IMPACT)
```
</Note>

### Facet groups conjunction

The `facetGroupsConjunction` require constraint allows for the specifying of inter-facet relations inside of facet groups identified
by primary keys in the arguments of this constraint. First, a mandatory [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
argument specifies the reference name of the facet reference. The secondary argument allows you to define one or more primary keys
of the referenced group ids, which the inner facets should consider conjunctive.

This require constraint changes the default behaviour, stating that all facets inside the same facet group are combined by an OR
relation (eg. disjunction). The constraint makes sense only when a [facet](#facet) or a [facet summary](#facet-summary)
constraint is part of the query.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
query(
    entities('PRODUCT'),
    filterBy(
        userFilter(
            facet('stores', 1, 2),
            facet('parameters', 11, 12, 22)
        )
    ),
    require(
        facetGroupsConjunction('parameters', 1, 8, 15)
    )
)
```

This statement means, that facets in the `parameters` reference and collected in groups with the primary key `1`, `8`, `15`, will
be joined with a boolean (AND) relation when selected.

Let's have this facet/group situation:

`Color` `parameters` (group id: `1`):

- `blue` (facet id: `11`)
- `red` (facet id: `12`)

`Size` `parameters` (group id: `2`):

- `small` (facet id: `21`)
- `large` (facet id: `22`)

`Flags` `tag` (group id: `3`):

- `action products` (facet id: `31`)
- `new products` (facet id: `32`)

When the user selects the facets: `blue` (`11`), `red` (`12`), by default, the relation would be:

`get all entities with facet blue(11) OR facet red(12)`

If the require constraint `facetGroupsConjunction('parameters', 1)` is passed in the query, the filtering condition will
be composed differently. It will be composed as follows:

`get all entities with facet blue(11) AND facet red(12)`
</Note>

### Facet groups disjunction

The `facetGroupsDisjunction` require constraint allows for the specifying of any facet relations among different facet groups of certain
primary keys. First, a mandatory [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
argument specifies the name of the reference, whose group behaviour is altered. The secondary argument allows you to define
one more facet group primary keys, which should be considered disjunctive.

The `facetGroupsDisjunction` require constraint changes the default behaviour, stating that facets between two different
facet groups are combined by an AND relation and changes it to a disjunction (OR) relation instead.


<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
query(
    entities('PRODUCT'),
    filterBy(
        userFilter(
            facet('stores', 1, 2),
            facet('parameters', 11, 12, 22)
        )
    ),
    require(
        facetGroupsDisjunction('parameterType', 1, 2)
    )
)
```

This statement means, that facet groups with the primary key `1`, `2` in the `parameters` reference, will be joined with a
boolean (OR) relation when selected.

Let's have this facet/group situation:

`Color` `parameters` (group id: `1`):

- `blue` (facet id: `11`)
- `red` (facet id: `12`)

`Size` `parameters` (group id: `2`):

- `small` (facet id: `21`)
- `large` (facet id: `22`)

`Flags` `tag` (group id: `3`):

- `action products` (facet id: `31`)
- `new products` (facet id: `32`)

When the user selects facets: `blue` (`11`), `large` (`22`), `new products` (`31`) - the default meaning would be:

`get all entities with blue(11) AND large(22) AND new products(31)`

If this require constraint: `facetGroupsDisjunction('tag', 3)` is passed in the query, the filtering condition will be
composed as:

`get all entities with (blue(11) AND large(22)) OR new products(31)`
</Note>

### Facet groups negated

The `facetGroupsNegation` require constraint allows you to declare a reverse impact of a facet selection in facet groups with
specified primary keys. Negative facet selection groups cause the omitting of all entities that have requested a facet in
a query result. First, a mandatory [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
argument specifies the name of the reference, whose group behaviour is altered. The secondary argument allows for the defining of
one more facet group primary keys that should be considered negative.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
query(
	entities('PRODUCT'),
	filterBy(
		userFilter(
			facet('stores', 1, 2),
			facet('parameters', 11, 12, 22)
		)
	),
	require(
		facetGroupsNegation('parameters', 1, 8, 15)
	)
)
```

This statement means that facets in groups with the primary key `1`, `8`, `15` in the `parameters` reference will be joined
with a boolean AND NOT relation when selected.
</Note>

### Attribute histogram

The `attributeHistogram` require constraint, when used, triggers the computation and the adding of an object of the
<SourceClass>[Histogram.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/io/extraResult/Histogram.java)</SourceClass> type,
allowing you to render [histograms](https://en.wikipedia.org/wiki/Histogram) to the query response.

In the first [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) argument, it expects
the number of histogram buckets (columns) that can be safely visualized to the user. Usually, there
is a fixed size area dedicated to histogram visualisation, and there is no point in returning a histogram with a number of
buckets (columns) that wouldn't be possible to render. For example - if there is a 200px width size for the histogram,
and we want to dedicate 10px for one column, it's wise to ask for, at most, 20 buckets.

Additionally, it accepts one or more [String](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/lang/String.html)
arguments as a second, third (and so on) argument, specifying the filterable attribute name for which
[histograms](https://en.wikipedia.org/wiki/Histogram) should be computed. Attribute must be of a numeric type in order
to compute histogram data.

Each attribute is represented by a separate [Histogram](../classes/histogram.md) data structure indexed by its attribute name.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
query(
	entities('PRODUCT'),
	require(
		attributeHistogram(20, 'width', 'height')
	)
)
```

The query results in a response with two histogram data structures computed for the value of its attributes, `width` and `height`.
</Note>

### Price histogram

The `priceHistogram` requirement constraint usage triggers the computation and adding of an object of the
<SourceClass>[PriceHistogram.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/io/extraResult/PriceHistogram.java)</SourceClass> type,
allowing you to render [histograms](https://en.wikipedia.org/wiki/Histogram) to the query response.

In the first [int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) argument, it expects
the number of the histogram buckets (columns) that can be safely visualized to the user. Usually, there
is fixed size area dedicated to the histogram visualisation, and there is no point in returning a histogram with a number of
buckets (columns) that wouldn't be possible to render. For example - if there is a 200px width size for the histogram,
and we want to dedicate 10px for one column, it's wise to ask for, at most, 20 buckets.

The result will be represented with a single [Histogram](../classes/histogram) data structure in the response, containing
the statistics on the price layout in the query result.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
query(
	entities('PRODUCT'),
	require(
		priceHistogram(20)
	)
)
```
</Note>

### Hierarchy statistics

The `hierarchyStatistics` require constraint triggers the computation of the statistics for the referenced hierarchical entities,
and adds an object of the <SourceClass>[HierarchyStatistics.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/io/extraResult/HierarchyStatistics.java)</SourceClass> type
to the query response. The hierarchy statistics requirement helps to render the sensible category menus along with
an insight about the number of the entities within them.

It has at least one [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
argument that specifies the reference name targeting a hierarchical entity. Additional arguments allow for the passing
of requirements for fetching the referenced entity contents, so that the number of queries to evitaDB is minimized,
and all the data is fetched in single query.

The <SourceClass>[HierarchyStatistics.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/io/extraResult/HierarchyStatistics.java)</SourceClass>
data structure is organized in the tree structure, reflecting the hierarchy of the entities of the specified reference name
that are referenced by entities returned by the primary query. For each node in the tree (one hierarchical entity), there is
a number that represents the count of currently queried entities relating to that referenced hierarchical entity node
(either directly or to some subordinate entity of this hierarchical entity) and matching the query filter.

<Note type="example">

<NoteTitle toggles="false">

#### Example usage:
</NoteTitle>

``` evitaql
query(
	entities('PRODUCT'),
	require(
		hierarchyStatisticsOfReference('category')
	)
)
```
</Note>

<Note type="example">

<NoteTitle toggles="false">

#### Menu rendering example with sub tree exclusion:
</NoteTitle>

This require constraint is usually used when hierarchical menu rendering is needed. For example, when we need to render
a menu for the entire e-commerce site, but we want to take excluded subtrees into an account, and also reflect the filtering
conditions that may filter out dozens of products (and thus leading to empty categories), we can invoke following query:

```
query(
    entities('PRODUCT'),
    filterBy(
        and(
            eq('visible', true),
            inRange('valid', 2020-07-30T20:37:50+00:00),
            priceInCurrency('USD'),
            priceValidIn(2020-07-30T20:37:50+00:00),
            priceInPriceLists('vip', 'standard'),
            withinRootHierarchy('categories', excluding(3, 7))
        )
    ),
    require(
        page(1, 20),
        hierarchyStatisticsOfReference('categories', entityBody(), attributes())
    )
)
```

This query would return first page with 20 products (omitting hundreds of others on additional pages), but also returns
a <SourceClass>[HierarchyStatistics.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/io/extraResult/HierarchyStatistics.java)</SourceClass> 
data structure in a different part of the response. This object can contain following structure:

```
`Electronics` -> `1789`
	`TV` -> `126`
		`LED` -> `90`
		`CRT` -> `36`
	`Washing machines` -> `190`
        `Slim` -> `40`
        `Standard` -> `40`
        `With drier` -> `23`
        `Top filling` -> `42`
        `Smart` -> `45`
    `Cell phones` -> `350`
    `Audio / Video` -> `230`
    `Printers` -> `80`
```

The tree will contain the `CATEGORY` entities fetched with their `attributes` instead their names. You see this reflected in the example. The number
next to the arrow represents the count of the products that are referencing this category (either directly or some of
its children - therefore, the nodes higher in the tree always have a bigger or equal count than the sum of counts of their
child nodes). You can see, there are only categories valid for the passed query - the excluded category subtree will
not be a part of the category listing (the query filters out all the products with the excluded category tree) and there is no
category that happens to be empty (e.g. contains no products or only products that don't match the filter constraint).
</Note>