# Expression language

A built-in expression language can be used at various points in the query. It allows expressions to be evaluated to 
a value. The expression language currently supports the following operators:

## Data types

- `long` - 64-bit signed integer, e.g. `123`, `-123`
- `double` - 64-bit floating point number, e.g. `123.45`, `-123.45`
- `boolean` - boolean value, e.g. `true`, `false`
- `string` - string value, e.g. `'hello'`, `'world'`

## Variables

No variables are defined implicitly. Variables are provided by the context in which the expression is evaluated and are 
documented at the place (query constraint etc.) where the expression is allowed.

- `$variable` - variable, e.g. `$x`, `$y`, `$z`

## Arithmetic operators

- `+` - addition
- `-` - subtraction
- `*` - multiplication
- `/` - division
- `%` - modulo
- `^` - power
- `()` - parentheses

## Math functions

- `abs(x)` - absolute value
- `ceil(x)` - smallest long not less than `x`
- `floor(x)` - largest long not greater than `x`
- `round(x)` - nearest long to `x`
- `sqrt(x)` - square root
- `log(x)` - natural logarithm
- `max(x, y)` - maximum of `x` and `y`
- `min(x, y)` - minimum of `x` and `y`
- `random()` - random long number (positive or negative)
- `random(x)` - random long number between 0 and `x`

## Logical operators

- `&&` - logical and
- `||` - logical or
- `!` - logical not
- 
## Comparison operators

- `==` - equal
- `!=` - not equal
- `>` - greater than
- `>=` - greater than or equal
- `<` - less than
- `<=` - less than or equal