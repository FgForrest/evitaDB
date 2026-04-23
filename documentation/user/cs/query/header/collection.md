---
title: Kolekce
date: '12.12.2024'
perex: V hlavičce dotazu lze použít pouze několik omezení. Kolekce určuje cílovou entitní kolekci pro dotaz.
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
translated: true
---
## Kolekce

```evitaql-syntax
collection(
    argument:string!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        povinný řetězcový argument představující název kolekce entit, která má být dotazována
    </dd>
</dl>

<LS to="e,j,c">Tato restrikce definuje kolekci entit, na kterou je tento dotaz zaměřen.</LS>
<LS to="g,r">Cílová definice kolekce entit je určena jako součást <LS to="g">názvu GraphQL dotazu</LS><LS to="r">URL koncového bodu</LS>.</LS>
Lze ji vynechat <LS to="g,r">při použití obecného <LS to="g">GraphQL dotazu</LS><LS to="r">koncového bodu</LS></LS>
pokud [filterBy](../basics.md#filtrování) obsahuje restrikci, která cílí na globálně unikátní atribut.
To je užitečné pro jeden z nejdůležitějších e-commerce scénářů, kdy požadované URI musí odpovídat jedné z
existujících entit (podrobný návod naleznete v kapitole [routing](../../solve/routing.md)).