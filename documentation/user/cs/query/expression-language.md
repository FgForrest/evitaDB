---
commit: '80a15ba92584e5e722ed24d9671b7eb7d61f5047'
---
# Výrazový jazyk

Vestavěný výrazový jazyk lze použít na různých místech v dotazu. Umožňuje vyhodnocovat výrazy na hodnotu. Výrazový jazyk aktuálně podporuje následující operátory:

## Datové typy

- `long` - 64bitové celé číslo se znaménkem, např. `123`, `-123`
- `double` - 64bitové číslo s plovoucí desetinnou čárkou, např. `123.45`, `-123.45`
- `boolean` - logická hodnota, např. `true`, `false`
- `string` - řetězcová hodnota, např. `'hello'`, `'world'`

## Proměnné

Žádné proměnné nejsou implicitně definovány. Proměnné jsou poskytovány kontextem, ve kterém je výraz vyhodnocován, a jsou dokumentovány na místě (omezení dotazu apod.), kde je výraz povolen.

- `$variable` - proměnná, např. `$x`, `$y`, `$z`

## Aritmetické operátory

- `+` - sčítání
- `-` - odčítání
- `*` - násobení
- `/` - dělení
- `%` - modulo
- `^` - mocnina
- `()` - závorky

## Matematické funkce

- `abs(x)` - absolutní hodnota
- `ceil(x)` - nejmenší long, který není menší než `x`
- `floor(x)` - největší long, který není větší než `x`
- `round(x)` - nejbližší long k `x`
- `sqrt(x)` - druhá odmocnina
- `log(x)` - přirozený logaritmus
- `max(x, y)` - maximum z `x` a `y`
- `min(x, y)` - minimum z `x` a `y`
- `random()` - náhodné long číslo (kladné nebo záporné)
- `random(x)` - náhodné long číslo mezi 0 a `x`

## Logické operátory

- `&&` - logické a
- `||` - logické nebo
- `!` - logické ne

## Porovnávací operátory

- `==` - rovno
- `!=` - nerovno
- `>` - větší než
- `>=` - větší nebo rovno
- `<` - menší než
- `<=` - menší nebo rovno