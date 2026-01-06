---
title: Řazení podle největší slevy v evitaDB
perex: S potěšením představujeme výkonnou novou funkci v evitaDB — omezující podmínku řazení `priceDiscount`. Tato funkce vám umožňuje řadit produktové nabídky podle výše slevy, což vám pomůže zvýraznit ty nejlepší nabídky pro vaše zákazníky. V tomto příspěvku si ukážeme, jak tuto funkci efektivně využívat, včetně reálných příkladů, praktických ukázek dotazů a pohledů na různé cenové strategie, včetně práce s produkty s variantami a sadami produktů.
date: '23.09.2024'
author: Ing. Jan Novotný
motive: ../en/assets/images/14-price-discount-ordering.png
proofreading: done
commit: f640dfcd764f7ef87a771ce62b6f437743278ace
---
V neustále konkurenčním prostředí e-commerce může prezentace nejlepších nabídek vašim zákazníkům znamenat zásadní rozdíl. 
Abychom vám v tom pomohli, s nadšením představujeme výkonnou novou funkci v evitaDB —
omezení řazení [`priceDiscount`](https://evitadb.io/documentation/query/ordering/price#price-discount).
Tato funkce vám umožňuje řadit produkty podle výše slevy, což usnadňuje zvýraznění významných úspor a motivuje zákazníky k nákupu.

### Jak se sleva počítá

Sleva se počítá jako rozdíl mezi **prodejní cenou** (cenou, kterou zákazník platí) a
**referenční cenou** (původní nebo uvedenou cenou). Obě ceny jsou určeny stejným algoritmem,
který zohledňuje upřednostněné ceníky, které zadáte, a datum platnosti.

**Kroky výpočtu:**

1. **Prodejní cena**: První platná cena nalezená v omezení [`priceInPriceLists`](https://evitadb.io/documentation/query/filtering/price#price-in-price-lists),
   odpovídající datu a měně zadané v [`priceValidIn`](https://evitadb.io/documentation/query/filtering/price#price-valid-in) a
   [`priceInCurrency`](https://evitadb.io/documentation/query/filtering/price#price-in-currency).
   Ceny jsou zvažovány v pořadí zadaných ceníků.
2. **Referenční cena**: První platná cena nalezená v cenících zadaných v
   omezení [`priceDiscount`](https://evitadb.io/documentation/query/ordering/price#price-discount),
   odpovídající stejnému datu a měně.
3. **Sleva**: Počítá se jako `Referenční cena - Prodejní cena`.

Pokud cena v daném ceníku není dostupná nebo není v daném čase platná, je přeskočena.
Algoritmus automaticky pokračuje k dalšímu ceníku v pořadí priority, dokud nenalezne platnou cenu.

<Note type="info">

Neindexované ceny nejsou do výpočtu zahrnuty. **Ceny musí být indexovány** v evitaDB, aby byly zohledněny při řazení
podle slevy. To zajišťuje efektivní výkon, zejména u velkých datových sad.

</Note>

**Speciální úpravy pro produkty s variantami nebo sadami**

- **Strategie `LOWEST_PRICE`**: U produktů s variantami se sleva počítá na základě varianty vybrané k prodeji.
  Obvykle jde o tu s nejnižší cenou, nebo pokud je použit filtr [`priceBetween`](https://evitadb.io/documentation/query/filtering/price#price-between),
  o tu s nejnižší cenou, která stále splňuje zvolený cenový rozsah. Referenční cena musí pocházet
  ze stejné varianty v různých cenících.
- **Strategie `SUM`**: U produktových sad je prodejní cena součtem prodejních cen všech komponent.
  Referenční cena se vypočítá součtem referenčních cen stejných komponent, přičemž se vynechávají komponenty,
  které neměly prodejní cenu, aby byla zachována konzistence.

## Implementace `priceDiscount` ve vašem e-commerce řešení

Podívejme se, jak můžete omezení `priceDiscount` implementovat v reálných scénářích, včetně zpracování produktů
s variantami a produktových sad.

### Scénář: Zvýraznění největších slev během bleskového výprodeje

Představte si, že pořádáte bleskový výprodej a chcete zobrazit produkty s nejvyššími slevami. Máte několik ceníků:

- **"flash-sale"**: Obsahuje speciální ceny pro bleskový výprodej.
- **"standard"**: Obsahuje běžné ceny.
- **"msrp"**: Obsahuje doporučené maloobchodní ceny výrobce.

Chcete zobrazit produkty seřazené podle nejvyšší slevy, vypočítané mezi prodejní cenou z ceníku "flash-sale"
a referenční cenou z ceníku "msrp".

#### Sestavení dotazu

```evitaql
query(
    collection("Product"),
    filterBy(
        priceInPriceLists("flash-sale", "basic),
        priceInCurrency("USD"),
        priceValidIn(2023-11-07T12:00:00-05:00)
    ),
    orderBy(
        priceDiscount("msrp", "basic")
    ),
    require(
        entityFetch(
            priceContentRespectingFilter("msrp")
        )
    )
)
```

Tento dotaz filtruje produkty, které mají platné ceny v ceníku "flash-sale", jsou v USD a platné
v určeném čase. Řadí je podle výše slevy oproti ceníku "msrp". Pokud produkty nemají cenu
v ceníku "msrp" nebo "flash-sale", použije se jejich cena z ceníku "basic".

#### Jak algoritmus vybírá ceny

- **Prodejní cena**: Algoritmus hledá platnou cenu v ceníku "flash-sale" k `2023-11-07T12:00:00-05:00`.
  Pokud ji nenajde, pokusí se najít cenu v ceníku "basic" a pokud ani ta není nalezena, produkt zcela přeskočí.
- **Referenční cena**: Hledá platnou cenu v ceníku "msrp" s případným přechodem na "basic" ve stejném čase.

### Podrobný příklad: Bleskový výprodej v obchodě s elektronikou

Představte si, že spravujete online obchod s elektronikou a připravujete se na 24hodinový bleskový výprodej. Vaše produkty mají ceny
v různých cenících, některé s časově omezenou platností.

#### Produktová data platná 7. listopadu 2023

**Standardní produkty:**

| Produkt               | Cena MSRP | Základní cena | Cena Flash Sale | Platnost ceny Flash Sale |
|-----------------------|-----------|---------------|-----------------|--------------------------|
| 4K Smart TV           | $1,000    | $950          | $800            | Celý den                 |
| Herní notebook        | $2,000    | $1,950        | $1,600          | Celý den                 |
| Bluetooth reproduktor | $100      | $95           | (Nedostupné)    |                          |

**Produkt s variantami (strategie `LOWEST_PRICE`):**

| Produkt                      | Varianta | Cena MSRP | Základní cena | Cena Flash Sale | Platnost ceny Flash Sale |
|------------------------------|----------|-----------|---------------|-----------------|-------------------------|
| Sluchátka s potlačením hluku | Černá    | $200      | $190          | $150            | Do 13:00                |
|                              | Stříbrná | $200      | $180          | (Nedostupné)    |                         |
|                              | Zlatá    | $200      | $170          | (Nedostupné)    |                         |

**Produktová sada (strategie `SUM`):**

| Produkt                | Komponenta     | Cena MSRP | Základní cena | Cena Flash Sale | Platnost ceny Flash Sale |
|------------------------|---------------|-----------|---------------|-----------------|--------------------------|
| Domácí kino (sada)     | Soundbar      | $500      | $450          | $400            | Do 13:00                 |
|                        | Subwoofer     | $300      | $280          | (Nedostupné)    |                          |
|                        | Zadní repro   | $200      | $190          | $150            | Celý den                 |

#### Dotaz ve 12:00

Pomocí aktualizovaného dotazu s `priceInPriceLists("flash-sale", "basic")` a `priceDiscount("msrp", "basic")`
v `priceValidIn(2023-11-07T12:00:00-00:00)` se podívejme, jak jsou ceny vybírány a slevy počítány.

**Výběr cen a výpočet slevy:**

| Produkt                     | Prodejní cena                    | Referenční cena     | Sleva   |
|-----------------------------|----------------------------------|---------------------|---------|
| 4K Smart TV                 | $800 (flash-sale)                | $1,000 (msrp)       | $200    |
| Herní notebook              | $1,600 (flash-sale)              | $2,000 (msrp)       | $400    |
| Bluetooth reproduktor       | $95 (basic)                      | $100 (msrp)         | $5      |
| Sluchátka s potlačením hluku| $150 (flash-sale, černá varianta)| $200 (msrp)         | $50     |
| Domácí kino (sada)          | $830 (komponenty)                | $1,000 (komponenty) | $170    |

<Note type="info">

Sleva nikdy nemůže být záporná, takže pokud by prodejní cena byla vyšší než referenční cena,
sleva je považována za $0.

</Note>

**Výsledné pořadí:**

1. Herní notebook ($400 sleva)
2. 4K Smart TV ($200 sleva)
3. Domácí kino (sada) ($170 sleva)
4. Sluchátka s potlačením hluku ($10 sleva)
5. Bluetooth reproduktor ($5 sleva)

Platnost cen ovlivňuje jak zařazení do výsledků, tak výši slevy.

#### Dotaz ve 14:00

V `priceValidIn(2023-11-07T14:00:00-00:00)` už cena "flash-sale" pro sluchátka s potlačením hluku není
platná.

**Aktualizovaný výběr cen a výpočet slevy:**

- **Sluchátka s potlačením hluku**:
   - **Prodejní cena**: $170 (basic) – nyní je nejlevnější varianta zlatá
   - **Referenční cena**: $200 (msrp)
   - **Sleva**: $200 - $170 = **$30**
- **Domácí kino (sada):**
   - **Prodejní cena**: $450 (Soundbar, basic – flash sale cena už není platná) + $280 (Subwoofer, basic) + $150 (Zadní repro, flash-sale) = $880
   - **Referenční cena**: Zůstává $1,000 (msrp)
   - **Sleva**: $1,000 - $880 = **$120**

<Note type="question">

<NoteTitle>

##### Co když chybí cena MSRP a základní cena pro komponentu?

Při výpočtu referenční ceny produktové sady algoritmus vynechává komponenty, které nemají prodejní cenu.
Ale co když chybí referenční cena komponenty? V takovém případě algoritmus použije jako referenční cenu komponenty
její prodejní cenu, aby byla zachována konzistence.

</NoteTitle>

</Note>

## Závěr

Omezení řazení `priceDiscount` je výkonný nástroj pro vylepšení vaší e-commerce platformy. Řazením produktů
podle výše slevy můžete efektivně propagovat akční nabídky a zvýšit zapojení zákazníků. Jak je vidět
z podrobných příkladů a okrajových případů, správný výpočet slevy není triviální, zejména u produktů s variantami
a produktových sad. Různí uživatelé mají přístup k různým cenám, vyhledávání se musí správně přizpůsobit při použití
omezení `priceBetween` a výsledky se mohou kdykoli změnit v závislosti na časové platnosti cen. Zajistit rychlost tohoto procesu
u velkých datových sad vyžaduje pečlivé indexování a efektivní algoritmy, které byste v běžné databázi jen těžko získali.

Implementace této funkce je díky flexibilnímu dotazovacímu jazyku evitaDB snadná. Pochopení, jak jsou určovány prodejní a referenční ceny,
zohlednění časové platnosti a využití vhodných cenových strategií – včetně zpracování produktů s variantami a sadami – vám umožní přizpůsobit tuto funkci potřebám vašeho podnikání.

Tato funkce bude dostupná v připravovaném vydání evitaDB `2024.10`, ale již je k dispozici v canary
verzi a také na [evitaDB Demo stránce](https://demo.evitadb.io).

---

## Připojte se k diskuzi

Rádi bychom slyšeli o vašich zkušenostech s implementací omezení `priceDiscount`. Připojte se do naší komunity
na [Discordu](https://discord.gg/VsNBWxgmSw), podělte se o své postřehy a spojte se s dalšími vývojáři!