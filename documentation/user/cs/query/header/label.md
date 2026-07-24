---
title: Štítek
date: '12.12.2024'
perex: Štítky umožňují označit dotaz pro pozdější identifikaci.
author: Ing. Jan Novotný
proofreading: done
preferredLang: evitaql
commit: cabcf999e7be5b00e0b13e1228a76a8d9e91cb78
translated: 'true'
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

<SourceCodeTabs requires="/evita_test/evita_documentation_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

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

### Kardinalita štítků a export do Prometheus

Štítky jsou navrženy pro označení dotazu za účelem jeho pozdější identifikace v trasování a záznamech provozu, kde je
neomezený počet různých hodnot očekávaný a neškodný - každé trasování nebo zaznamenaný dotaz se stejně ukládá
samostatně. To ale neplatí pro [Prometheus metriky](../../operate/observe.md#metriky): každá odlišná kombinace hodnot
štítků se stane vlastní časovou řadou, takže štítek s neomezenými nebo unikátními hodnotami pro každý požadavek (ID
uživatele, ID session, časové razítko, celá URL, libovolný text) by donekonečna vytvářel nové časové řady a mohl by
zahltit Prometheus i libovolný dashboard nad ním postavený.

Z tohoto důvodu se ve výchozím stavu do Prometheus neexportuje žádný štítek. Operátor může jednotlivé názvy štítků
povolit pomocí nastavení `exportedQueryLabels` Observability API (viz
[konfigurace Observability](../../operate/configure.md#konfigurace-observability)) - názvy štítků jsou libovolné a volí
je operátor, který tím přebírá odpovědnost za udržení jejich hodnot omezených. Dokud není název nakonfigurován, jsou
jeho hodnoty vidět pouze v trasování, záznamech provozu a JFR událostech, nikdy v Prometheus.

Několik inherentně vysokokardinálních štítků automaticky připojovaných systémem - `trace-id`, `client-id`,
`ip-address` a `uri` - je vyhrazených a nelze je do Prometheus nikdy exportovat, bez ohledu na konfiguraci. Při volbě
štítků k exportu (nebo při rozhodování, zda je vůbec bezpečné danou hodnotu k dotazu připojit) dbejte na to, aby byly
omezené a výčtového charakteru - identifikátor dávkové úlohy, název REST endpointu nebo metody kontroleru - a nikoli
odvozené od uživatelského vstupu, identifikátorů požadavků nebo časových razítek.

