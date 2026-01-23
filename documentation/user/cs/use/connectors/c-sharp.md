---
title: 'C#'
perex: 'Hlavním cílem C# driveru pro evitaDB bylo vytvořit co nejpodobnější API tomu v Javě, a to kvůli konzistenci a usnadnění přechodu vývojářů mezi jazyky. Existují však drobné rozdíly mezi těmito dvěma jazyky, takže C# API není stoprocentně totožné s tím v Javě. Mnohé z těchto rozdílů jsou převážně sémantické a týkají se jazykových konvencí.'
date: '10.11.2023'
author: Ing. Tomáš Pozler
preferredLang: cs
commit: '326bb602a2e94395b5ee6e1cb294421d3b671143'
---
<LS to="e,j,g,r">
Tato kapitola popisuje C# driver pro evitaDB a nedává smysl pro jiné jazyky. Pokud vás zajímají detaily implementace v C#, změňte si prosím preferovaný jazyk v pravém horním rohu.
</LS>
<LS to="c">
Tato unifikace API byla možná díky společnému [gRPC](grpc.md) protokolu a datovému formátu protobuf, který používají oba klienti.
Je postavena na stejných rozhraních (zejména <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass>
a <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>) pro klienta i samotnou databázi,
kde na straně C# došlo pouze k adaptaci bez nutnosti těchto konkrétních rozhraní – byly použity třídy.

**Podporované verze .NET**

Protože se jedná o poměrně nový projekt, který v implementaci využívá mnoho novějších jazykových prvků, C# driver
nebude zpětně kompatibilní se staršími verzemi .NET než .NET 8.

**Jak nainstalovat**

Jak již bylo zmíněno, pro použití tohoto klienta je nutné mít nainstalován alespoň .NET 8.
Pro instalaci klienta můžete použít správce balíčků NuGet nebo dotnet CLI. Pro alternativní způsoby instalace
prosím navštivte [nuget repozitář](https://www.nuget.org/packages/EvitaDB.Client).

<CodeTabs>
<CodeTabsBlock>
```.NET CLI
dotnet add package EvitaDB.Client
```
</CodeTabsBlock>
<CodeTabsBlock>
```Package Manager
Install-Package EvitaDB.Client
```
</CodeTabsBlock>
</CodeTabs>

Jak si můžete všimnout, instalační příkazy neobsahují specifikaci verze. Je to proto, že klient je aktuálně navržen tak,
aby byl vždy kompatibilní s nejnovější verzí serveru v [master branch](https://github.com/FgForrest/evitaDB/tree/master),
která odpovídá nejnovějšímu evitaDB [docker image](https://hub.docker.com/r/evitadb/evitadb). Toto se může v budoucnu změnit.

*Doporučení k použití*
- ve většině případů byste měli při inicializaci <SourceClass>EvitaDB.Client/EvitaClient.cs</SourceClass> a <SourceClass>EvitaDB.Client/EvitaClientSession.cs</SourceClass> používat klíčové slovo `using`, abyste využili jejich implementaci `IDisposable` pro automatické uvolňování zdrojů
- při práci s dotazy byste měli staticky používat rozhraní `IQueryConstrains` pro kompaktnější a přehlednější dotazy

## Poznámky
<SourceClass>EvitaDB.Client/EvitaClient.cs</SourceClass>
je thread-safe a v aplikaci se očekává použití pouze jedné instance.

<Note type="info">
Instance klienta je vytvořena bez ohledu na to, zda je server dostupný. Pro ověření, že je možné se serverem komunikovat,
je potřeba na něm zavolat nějakou metodu. Typickým scénářem je [otevření nové session](#open-session-to-catalog)
do existujícího <Term location="/documentation/user/en/index.md">katalogu</Term>.
</Note>

<Note type="warning">
<SourceClass>EvitaDB.Client/EvitaClient.cs</SourceClass>
udržuje pool otevřených zdrojů a měl by být ukončen metodou `Close()`, když jej přestanete používat.
</Note>

### Konfigurace TLS

Následující nastavení je třeba nakonfigurovat v
<SourceClass>EvitaDB.Client/Config/EvitaClientConfiguration.cs</SourceClass>
konfiguraci na straně klienta:

- **`UseGeneratedCertificate`**: (`true` ve výchozím stavu) pokud je nastaveno na `true`, klient si automaticky stáhne kořenový certifikát
  serverové certifikační autority z endpointu `system`
- **`TrustCertificate`**: (`false` ve výchozím stavu) pokud je nastaveno na `true`, certifikát získaný z endpointu `system`
  nebo ručně přes `CertificatePath` je automaticky přidán do lokálního úložiště důvěryhodných certifikátů.

  Pokud je nastaveno na `false` a je poskytnut nedůvěryhodný (self-signed) certifikát, klient mu nebude důvěřovat a
  spojení se serverem selže. Použití hodnoty `true` pro toto nastavení v produkci obecně nedoporučujeme.
- **`CertificateFolderPath`**: (ve výchozím stavu podadresář `evita-client-certificates` v pracovním adresáři)
  představuje cestu ke složce, kde je uložen certifikát autority
- **`RootCaCertificatePath`**: (`null` ve výchozím stavu) relativní cesta z `CertificateFolderPath` ke kořenovému
  certifikátu serveru. Pokud je vypnutý příznak `UseGeneratedCertificate`, je nutné nastavit cestu k ručně poskytnutému
  certifikátu, jinak ověření selže a spojení nebude navázáno.
- **`CertificatePath`**: (`null` ve výchozím stavu) relativní cesta z `CertificateFolderPath` ke klientskému certifikátu.
- **`CertificateKeyPath`**: (`null` ve výchozím stavu) relativní cesta z `CertificateFolderPath` k soukromému klíči klienta
- **`CertificateKeyPassword`**: (`null` ve výchozím stavu) heslo k soukromému klíči klienta (pokud je nastaveno)

<Note type="warning">
Pokud je na straně serveru povoleno `mTLS` a `UseGeneratedCertificate` je nastaveno na `false`, musíte v nastaveních
`CertificatePath` a `CertificateKeyPath` zadat svůj ručně vygenerovaný certifikát, jinak ověření selže a spojení nebude navázáno.
</Note>

### Caching schémat

Jak katalogová, tak entitní schémata jsou používána poměrně často – každá načtená entita má referenci na své schéma. Zároveň je schéma poměrně složité a často se nemění. Proto je výhodné schéma na klientovi cachovat a vyhnout se jeho opakovanému stahování ze serveru při každé potřebě.

Cache zajišťuje třída <SourceClass>EvitaDB.Client/EvitaEntitySchemaCache.cs</SourceClass>,
která řeší dva scénáře přístupu ke schématům:

#### Přístup k posledním verzím schémat

Klient si udržuje poslední známé verze schémat pro každý katalog. Tato cache je invalidována pokaždé, když daný klient změní schéma, kolekce je přejmenována nebo smazána, nebo když klient načte entitu, která používá novější verzi schématu, než je poslední uložená.

#### Přístup ke konkrétním verzím schémat

Klient také udržuje cache konkrétních verzí schémat. Pokaždé, když klient načte entitu, entita vrácená ze serveru nese informaci o verzi schématu, na kterou odkazuje. Klient se pokusí najít schéma této konkrétní verze ve své cache, a pokud ho nenajde, stáhne jej ze serveru a uloží do cache. Cache je jednou za čas (každou minutu) invalidována a stará schémata, která nebyla dlouho použita (4 hodiny), jsou odstraněna.

<Note type="info">

Výše uvedené intervaly aktuálně nejsou konfigurovatelné, protože věříme, že jsou optimální pro většinu případů použití. Pokud potřebujete jejich změnu, kontaktujte nás prosím se svým konkrétním případem a zvážíme přidání konfigurační možnosti.

</Note>
</LS>