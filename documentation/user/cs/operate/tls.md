---
title: Nastavení TLS
perex: Všechny API evitaDB podporují zabezpečenou vrstvu (HTTPS). gRPC je zcela založeno na protokolu HTTP/2, který je binární a vyžaduje šifrování. Z tohoto důvodu všechny externí API evitaDB standardně fungují na zabezpečeném protokolu, aby byla zajištěna jednotná bezpečnost.
date: '1.3.2023'
author: Bc. Tomáš Pozler
proofreading: done
commit: cef96d8320d36c91c100c5dfc9c45020b5a7ad0d
translated: true
---
<UsedTerms>
    <h4>Použité pojmy</h4>
   <dl>
      <dt>certifikační autorita</dt>
      <dd>
         certifikační autorita je subjekt, který uchovává, podepisuje a vydává digitální certifikáty. Digitální certifikát
         potvrzuje vlastnictví veřejného klíče uvedeného subjektu certifikátu. To umožňuje ostatním (důvěřujícím stranám)
         spoléhat na podpisy nebo tvrzení učiněná o soukromém klíči, který odpovídá certifikovanému veřejnému klíči. CA působí jako důvěryhodná třetí strana – důvěryhodná jak pro subjekt (vlastníka) certifikátu, tak pro stranu, která na certifikát spoléhá. [zdroj](https://en.wikipedia.org/wiki/Certificate_authority)
      </dd>
      <dt>certifikát</dt>
      <dd>
         certifikát je elektronický dokument používaný k ověření platnosti veřejného klíče a obsahuje veřejný
         klíč spolu s řadou dalších informací [zdroj](https://en.wikipedia.org/wiki/Public_key_certificate)
      </dd>
      <dt>soukromý klíč</dt>
      <dd>
         soukromý klíč je tajná část páru veřejný/soukromý klíč – nikdy nesmí opustit zabezpečené prostředí.
         Subjekt, který se prokazuje soukromým klíčem, je považován za autentický – soukromý klíč je podobný občanskému průkazu
         [více zde](https://en.wikipedia.org/wiki/Public-key_cryptography)
      </dd>
      <dt>veřejný klíč</dt>
      <dd>
         veřejný klíč je druhá část páru veřejný/soukromý klíč – může být volně šířen a jeho vlastnictví
         nezakládá žádná práva, slouží pouze k ověření pravosti soukromého klíče
         [více zde](https://en.wikipedia.org/wiki/Public-key_cryptography)
      </dd>
   </dl>
</UsedTerms>

<Note type="question">

<NoteTitle toggles="true">

##### Jak je možné, že jsem nemusel konfigurovat žádný certifikát a všechno „prostě“ funguje hned po spuštění?
</NoteTitle>

Nechceme vývojářům a nováčkům situaci komplikovat, ale to neznamená, že výchozí chování je bezpečné, protože být nemůže. Server evitaDB automaticky generuje samopodepsaný <Term name="certificate">serverový certifikát</Term>.
Tento certifikát nebude klienty považován za důvěryhodný, pokud je k tomu nenutíte. Obvykle stačí přepnout pár přepínačů a pro vývojové účely to stačí. Pro produkční prostředí důrazně doporučujeme vystavit vlastní <Term>certifikát</Term> pomocí autority [Let's Encrypt](https://letsencrypt.org),
což lze automatizovat a je to dnes součástí všech důvěryhodných řetězců certifikátů.

</Note>

Pokud máte s prací s certifikáty zkušenosti, můžete přeskočit celou kapitolu [vytvoření certifikátu](#vytvoření-certifikátu)
a přejít rovnou na [konfiguraci](configure.md#konfigurace-tls) nebo si přečíst o podpoře [mTLS](#vzájemné-tls) pro
protokol gRPC.

## Vytvoření certifikátu

K prokázání identity, ať už jste server nebo klient, potřebujete <Term>certifikát</Term>.

Certifikáty lze rozdělit do dvou skupin podle způsobu podepsání certifikátu:

- Certifikáty podepsané veřejně důvěryhodnou kořenovou certifikační autoritou
- Certifikáty podepsané privátní certifikační autoritou

### Veřejně důvěryhodná certifikační autorita

<Term>Certifikát</Term> si můžete zakoupit u komerčních <Term name="certificate authority">certifikačních autorit</Term>,
nebo si jej zdarma vygenerovat pomocí [Let's Encrypt](https://letsencrypt.org). O tomto procesu najdete na internetu mnoho informací, proto jej zde nebudeme opakovat. Pro vygenerování bezplatného serverového certifikátu postupujte podle návodu na [stránkách Certbot](https://certbot.eff.org/).

### Samopodepsaná certifikační autorita

V tomto průvodci se zaměříme na druhou skupinu: samopodepsané certifikáty. Při použití [mTLS](#vzájemné-tls) je
nutné, aby server měl přístup k <Term>certifikační autoritě</Term>, která mu důvěřuje, a aby klienti, kteří
prokazují svou identitu <Term>certifikátem</Term> vydaným touto autoritou, mohli komunikovat.

K vygenerování certifikátu použijeme nástroj [OpenSSL](https://www.openssl.org/). Ten je předinstalovaný na mnoha
distribucích Linuxu i na novějších verzích systému MacOS. Na operačních systémech Windows je potřeba nástroj stáhnout a nainstalovat.

<LS to="jscript">

<Note type="info">
Pokud se potřebujete připojit k serveru, který poskytuje samopodepsaný certifikát z Node.js aplikace, musíte nastavit
proměnnou `NODE_TLS_REJECT_UNAUTHORIZED` na hodnotu `0`. Toto nastavení však způsobí, že Node.JS vypíše varovnou hlášku:

```plain
Setting the NODE_TLS_REJECT_UNAUTHORIZED environment variable to '0' makes TLS connections and HTTPS requests insecure
by disabling certificate verification.
```

</Note>

</LS>

#### Vytvoření certifikační autority

Pro vytvoření <Term>certifikační autority</Term> spusťte následující příkaz:

```shell
openssl req -x509 \
        -newkey rsa:2048 \
        -keyout rootCA.key \
        -out rootCA.crt \
        -days 365
```

Tím se vygeneruje <Term>certifikát</Term> `rootCA.crt` a <Term>soukromý klíč</Term> `rootCA.key` pro
<Term>certifikační autoritu</Term> (po potvrzení budete muset zadat heslo).

<Note type="warning">
Po spuštění příkazů v krocích 1 a 2 musíte zadat heslo – pokud chcete nešifrovaný certifikát bez hesla, přidejte do příkazu parametr `-nodes`.
</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Co znamenají argumenty příkazu?
</NoteTitle>

<Table>
    <Thead>
        <Tr>
            <Th>Argument</Th>
            <Th>Popis</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>`-x509`</Td>
            <Td>vytvoří x509 certifikát – podle standardu, který definuje formát digitálních
                certifikátů používaných v SSL/TLS spojení</Td>
        </Tr>
        <Tr>
            <Td>`-newkey cipher:bits`</Td>
            <Td>
                určuje použitou [šifru](https://en.wikipedia.org/wiki/RSA_(cryptosystem)) a její složitost,
                všechny podporované šifry zobrazíte příkazem `openssl ciphers`
            </Td>
        </Tr>
        <Tr>
            <Td>`-days`</Td>
            <Td>
                určuje počet dní, po které je certifikát platný; po uplynutí této doby je certifikát
                považován za neplatný a server i klient s ním odmítnou pracovat
            </Td>
        </Tr>
        <Tr>
            <Td>`-keyout`</Td>
            <Td>
                název souboru, kam se uloží <Term>soukromý klíč</Term>
            </Td>
        </Tr>
        <Tr>
            <Td>`-out`</Td>
            <Td>
                název souboru, kam se uloží <Term>certifikát</Term> s veřejným klíčem
            </Td>
        </Tr>
    </Tbody>
</Table>
</Note>

#### Žádost o podepsání certifikátu

Nyní je potřeba vytvořit textový soubor s názvem `domain.ext` s následujícím obsahem. V sekci DNS nahraďte `[domain]`
názvem domény (nebo [více doménami](https://easyengine.io/wordpress-nginx/tutorials/ssl/multidomain-ssl-subject-alternative-names/)),
na které budete provozovat server evitaDB:

```plain
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
subjectAltName = @alt_names
[alt_names]
DNS.1 = [domain]
```

Poté vytvořte žádost o podepsání certifikátu `domain.csr` pomocí následujícího příkazu:

```shell
openssl req -newkey rsa:2048 \
        -keyout domain.key \
        -out domain.csr
```

Tím se vygeneruje <Term>soukromý klíč</Term> `domain.key` pro klienta a žádost `domain.csr` k podepsání
<Term>certifikační autoritou</Term> (po potvrzení budete muset zadat heslo).

Nyní jste připraveni na poslední krok.

#### Vydání podepsaného certifikátu

Nakonec jste připraveni vygenerovat podepsaný certifikát, který můžete použít pro server evitaDB nebo pro některého z klientů, pokud je povoleno [mTLS](#vzájemné-tls).

Vygenerujte nový <Term>certifikát</Term> podepsaný [certifikační autoritou](#vytvoření-certifikační-autority)
reprezentovanou souborem `rootCA.crt` a jejím <Term>soukromým klíčem</Term> `rootCA.key` pomocí následujícího příkazu:

```shell
openssl x509 -req -CA rootCA.crt -CAkey rootCA.key \
        -in domain.csr -out domain.crt -days 365 \
        -extfile domain.ext
```

Tento příkaz můžete opakovat vícekrát a vygenerovat tak různé certifikáty jak pro server, tak pro každého
klienta zvlášť.

<Note type="warning">
Nepoužívejte stejný podepsaný certifikát pro server i klienta! Nepoužívejte stejný certifikát pro různé klienty! Každý subjekt v komunikaci musí být reprezentován (identifikován) jiným certifikátem.

Pokud se budete těmito doporučeními řídit, budete moci odmítat jednotlivé klienty samostatně. Pokud vydáte certifikát klientovi třetí strany a smlouva s ním skončí, jednoduše jeho certifikát smažete ze seznamu povolených klientských certifikátů a efektivně tak znemožníte komunikaci jeho klientské aplikace.
</Note>

<Note type="info">
Server i klient mohou být vybaveni:

- certifikátem ve formátu `.crt`, `.cer` nebo `.pem`
- soukromým klíčem ve formátu `.key` nebo `.pem`
</Note>

<Note type="question">

<NoteTitle toggles="true">

##### Co znamenají argumenty příkazu?
</NoteTitle>

<Table>
    <Thead>
        <Tr>
            <Th>Argument</Th>
            <Th>Popis</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>`-CA`</Td>
            <Td>identifikuje veřejnou část <Term>certifikační autority</Term></Td>
        </Tr>
        <Tr>
            <Td>`-CAkey`</Td>
            <Td>identifikuje <Term>soukromý klíč</Term> <Term>certifikační autority</Term></Td>
        </Tr>
        <Tr>
            <Td>`-in`</Td>
            <Td>identifikuje [žádost o podepsání](#preparing-certificate-signing-request), která řídí operaci</Td>
        </Tr>
        <Tr>
            <Td>`-days`</Td>
            <Td>
                určuje počet dní, po které je certifikát platný; po uplynutí této doby je certifikát
                považován za neplatný a server i klient s ním odmítnou pracovat
            </Td>
        </Tr>
        <Tr>
            <Td>`-extfile`</Td>
            <Td>identifikuje soubor s popisem domény</Td>
        </Tr>
        <Tr>
            <Td>`-out`</Td>
            <Td>
                název souboru, kam se uloží vygenerovaný <Term>certifikát</Term> s veřejným klíčem
            </Td>
        </Tr>
    </Tbody>
</Table>
</Note>

## Vzájemné TLS

Všechny API – včetně gRPC, a tedy i <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClient.java</SourceClass>,
nabízí také možnost autentizace pomocí [vzájemného TLS](https://en.wikipedia.org/wiki/Mutual_authentication), kdy si klient a server ověřují své identity výměnou certifikátů.

mTLS lze ovládat v konfiguračním souboru <SourceClass>evita_server/src/main/resources/evita-configuration.yaml</SourceClass> v sekci
`api.endpointDefaults.mTLS` (nebo v konkrétní sekci pro každý protokol zvlášť). Na stejném místě lze
nastavit seznam klientských certifikátů, které jsou povoleny ke komunikaci se serverem gRPC.
Klient, který se neprokáže akceptovaným certifikátem, bude serverem odmítnut.

Klient musí v [konfiguraci](../use/connectors/java.md) nastavit cestu ke svému <Term>certifikátu</Term>, <Term>soukromému klíči</Term> a případně heslu ke
soukromému klíči.

Použití `mTLS` doporučujeme v případě, že je komunikace povolena z veřejné sítě, alespoň do doby, než bude v evitaDB implementováno plnohodnotné ověřování a autorizace (viz issue [#25](https://github.com/FgForrest/evitaDB/issues/25)).
mTLS zabraňuje velkému množství útoků a zvyšuje bezpečnost komunikace.

Příklady útoků, kterým mTLS brání:

- [on-path attack](https://www.wallarm.com/what/what-is-an-on-path-attacker)
- [man in the middle](https://en.wikipedia.org/wiki/Man-in-the-middle_attack)
- [spoofing](https://en.wikipedia.org/wiki/Spoofing_attack)
- [credential stuffing](https://en.wikipedia.org/wiki/Credential_stuffing)
- [brute force attack](https://en.wikipedia.org/wiki/Brute-force_attack)
- [phishing](https://www.cloudflare.com/learning/access-management/phishing-attack/)

### Výchozí chování mTLS (nebezpečné)

mTLS není ve výchozím stavu povoleno. Pokud jej povolíte, potřebné certifikáty se vygenerují automaticky, ale stále je to
**nebezpečné** a mělo by být použito pouze pro vývoj. Pokud je evitaDB spuštěna a `generateAndUseSelfSigned` je nastaveno na `true` (výchozí), vygenerují se tři páry veřejný/soukromý klíč:

1. serverový <Term>certifikát</Term> v souboru `server.crt` a jeho <Term>soukromý klíč</Term> v souboru `server.key`
2. klientský <Term>certifikát</Term> v souboru `client.crt` a jeho <Term>soukromý klíč</Term> v souboru `client.key`

Soubor `client.crt` je automaticky přidán do seznamu důvěryhodných klientských certifikátů. Oba soubory `client.crt` a `client.key`
jsou dostupné ke stažení přes endpoint `system`. Uvidíte je při startu serveru evitaDB:

```plain
API `system` listening on               http://your-domain:5555/system/
   - client certificate served at:      http://your-domain:5555/system/client.crt
   - client private key served at:      http://your-domain:5555/system/client.key
```

Pokud klient gRPC startuje a má následující nastavení (vše jsou výchozí hodnoty):

- `useGeneratedCertificate`: `true`
- `mtlsEnabled`: `true`

Automaticky stáhne výchozí klientský certifikát spolu se soukromým klíčem a použije je pro komunikaci. Jsme si vědomi, že toto je **nebezpečné** a popírá logiku `mTLS`, ale umožňuje to otestovat celý proces a vyhnout se problémům v testovacím/produkčním prostředí. Pro lokální vývojové prostředí je to však dostačující a umožňuje testovat funkčnost mTLS.

<Note type="warning">

<NoteTitle toggles="true">

##### Jak můžeme ověřit integritu komunikace?
</NoteTitle>

Pokud potřebujete ověřit, že klient komunikuje se serverem přímo bez jakéhokoliv
[man-in-the-middle](https://en.wikipedia.org/wiki/Man-in-the-middle_attack), můžete zkontrolovat otisky prstů
použité <Term>certifikační autority</Term> jak na straně serveru, tak klienta.

**Otisk prstu na straně serveru**

Otisk prstu je vypsán do konzole při startu serveru – vypadá například takto:

```plain
Server certificate fingerprint: 84:F0:29:87:D8:F5:F6:92:B4:7B:AA:AE:F3:5A:29:A1:C1:86:C4:B2:4D:44:63:6B:2D:F2:AD:75:B7:C6:F2:7E
```

**Otisk prstu na straně klienta**

Klient vypíše otisk prstu pomocí [nastavené logovací knihovny](run.md#řízení-logování) na úrovni `INFO` v tomto tvaru:

```plain
16:11:18.712 INFO  i.e.d.c.ClientCertificateManager - Server's certificate fingerprint: 04:B0:9C:00:FB:32:D8:8A:7A:C9:34:19:5D:90:48:8A:BF:BF:E8:22:32:53:4C:4F:14:E1:EC:FA:C2:99:C3:DD
```
</Note>

### Doporučené použití mTLS (bezpečné)

Pro každého gRPC klienta vygenerujte vlastní <Term>certifikát</Term> pomocí důvěryhodné <Term>certifikační autority</Term>
(například [Let's Encrypt](https://letsencrypt.org)), nebo vlastní [samopodepsané autority](#vytvoření-certifikační-autority).
Vypněte `generateAndUseSelfSigned` a nastavte serverový certifikát a certifikáty jednotlivých klientů v
[konfiguraci](configure.md#konfigurace-tls).

<Note type="question">

<NoteTitle toggles="true">

##### Lze serverový certifikát a povolené klientské certifikáty měnit bez restartu serveru?
</NoteTitle>

Ano, serverový certifikát i platné klientské certifikáty lze měnit bez restartu serveru. Server
pravidelně kontroluje časové razítko poslední změny serverového certifikátu a seznamu cest ke klientským certifikátům. Pokud se některý z těchto souborů změní, server automaticky načte novou konfiguraci.
To vám umožní měnit serverový i klientské certifikáty bez nutnosti zastavovat server.

</Note>