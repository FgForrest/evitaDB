---
title: Štítek
date: '12.12.2024'
perex: Štítky umožňují označit dotaz pro pozdější identifikaci.
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
commit: cabcf999e7be5b00e0b13e1228a76a8d9e91cb78
translated: true
---
## Štítek

```evitaql-syntax
label(
    argument:string!,
    argument:any!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        povinný řetězcový argument představující název štítku
    </dd>
    <dt>argument:any!</dt>
    <dd>
        povinný argument libovolného typu představující hodnotu štítku, 
        lze použít jakýkoli [podporovaný typ](../../use/data-types.md#jednoduché-datové-typy)
    </dd>
</dl>

Tato podmínka `label` umožňuje zadat v hlavičce dotazu jeden název štítku s přiřazenou hodnotou a
propagovat jej do trasování generovaného pro dotaz. Dotaz může být označen více štítky.

Štítky jsou také zaznamenávány spolu s dotazem v [záznamu provozu](../../operate/observe.md#záznam-provozu) a lze je
použít k vyhledání dotazu při inspekci nebo opakování provozu. Štítky jsou také připojeny k JFR událostem souvisejícím
s dotazem.

Každý štítek je dvojice klíč-hodnota připojená k hlavičce dotazu, jak je ukázáno v následujícím příkladu:

<SourceCodeTabs requires="/evita_test/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Připojení štítků k dotazu](/documentation/user/en/query/header/examples/labels.evitaql)

</SourceCodeTabs>

<Note type="info">

Štítky můžete také zadat pomocí HTTP hlaviček ve formátu `X-EvitaDB-Label: <label-name>=<label-value>`.
Více štítků lze nastavit zadáním více hlaviček `X-EvitaDB-Label` v rámci jednoho požadavku.

Existují také automatické štítky, které jsou k dotazu přidávány systémem, například:

- `client-ip`: IP adresa klienta, který odeslal dotaz (skutečná IP adresa klienta může být předána pomocí
  hlavičky [X-Forwarded-For](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/X-Forwarded-For))
- `client-uri`: URI klienta, který odeslal dotaz, je přítomno pouze pokud je přítomna hlavička [X-Forwarded-Uri](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/X-Forwarded-Uri)
- `client-id`: identifikace klienta – viz [clientId](../../use/connectors/java.md#konfigurace)
- `trace-id`: aktuální ID trasování, pokud je [trasování](../../operate/observe.md#tracing) povoleno

<LS to="g">Pokud používáte GraphQL API, je zde také štítek `operation-name` odvozený z názvu dotazu (pokud je nějaký název definován).</LS>

</Note>