---
title: Výběr HTTP serveru pro evitaDB
perex: Plánujeme nabídnout několik způsobů komunikace s klienty evitaDB. K tomu jsme potřebovali univerzální HTTP server, který by zpracovával síťový provoz.
date: '17.6.2022'
author: Lukáš Hornych, Jan Novotný
motive: ../en/assets/images/03-choosing-http-server.png
commit: '6044b1e2017245d156efdcfad48501560551f62d'
---
Hlavním cílem je nyní
poskytnout [GraphQL](https://graphql.org/), [REST](https://en.wikipedia.org/wiki/Representational_state_transfer),
[gRPC](https://grpc.io/) a případně také [WebSockets](https://en.wikipedia.org/wiki/WebSocket)
nebo [SSE](https://en.wikipedia.org/wiki/Server-sent_events) API pro některé specifické případy použití. V tuto chvíli však
neexistuje žádný základ pro HTTP komunikaci, protože evitaDB je dostupná pouze přes Java API. Z tohoto důvodu bylo potřeba najít nějaký
HTTP server, knihovnu nebo framework, který by sloužil jako společný základ pro všechny zmíněné API.

<Note type="info">

<NoteTitle toggles="false">

##### Článek aktualizován k červnu 2024 (přidán server Armeria)

</NoteTitle>

Všechny verze webových serverů byly aktualizovány na nejnovější verze k červnu 2024.
Do seznamu jsme také přidali [Armeria](https://armeria.dev/) postavený na serveru Netty.

<Table caption="Výsledky propustnosti (ops/s - vyšší je lepší).">
  <Thead>
    <Tr>
      <Th>Server, knihovna nebo framework</Th>
      <Th>JMH skóre (ops/s)</Th>
      <Th>Min JMH skóre (ops/s)</Th>
      <Th>Max JMH skóre (ops/s)</Th>
    </Tr>
  </Thead>
  <Tbody>
    <Tr>
      <Td>microhttp</Td>
      <Td>16478</Td>
      <Td>15527</Td>
      <Td>17429</Td>
    </Tr>
    <Tr>
      <Td>Netty</Td>
      <Td>16109</Td>
      <Td>15010</Td>
      <Td>17208</Td>
    </Tr>
    <Tr>
      <Td>Vert.x</Td>
      <Td>15189</Td>
      <Td>14597</Td>
      <Td>15781</Td>
    </Tr>
    <Tr>
      <Td>Undertow</Td>
      <Td>14733</Td>
      <Td>14021</Td>
      <Td>15445</Td>
    </Tr>
    <Tr>
      <Td>Armeria</Td>
      <Td>14256</Td>
      <Td>13935</Td>
      <Td>14576</Td>
    </Tr>
    <Tr>
      <Td>Javalin</Td>
      <Td>14114</Td>
      <Td>11269</Td>
      <Td>16960</Td>
    </Tr>
    <Tr>
      <Td>Quarkus</Td>
      <Td>13529</Td>
      <Td>13420</Td>
      <Td>13639</Td>
    </Tr>
    <Tr>
      <Td>Micronaut</Td>
      <Td>13507</Td>
      <Td>12337</Td>
      <Td>14677</Td>
    </Tr>
    <Tr>
      <Td>Spring Boot WebFlux</Td>
      <Td>13129</Td>
      <Td>12703</Td>
      <Td>13555</Td>
    </Tr>
    <Tr>
      <Td>Spring Boot MVC</Td>
      <Td>10675</Td>
      <Td>10533</Td>
      <Td>10817</Td>
    </Tr>
    <Tr>
      <Td>NanoHTTPD</Td>
      <Td>7744</Td>
      <Td>7561</Td>
      <Td>7927</Td>
    </Tr>
  </Tbody>
</Table>

<Table caption="Výsledky průměrného času (us/op - menší je lepší).">
  <Thead>
    <Tr>
      <Th>Server, knihovna nebo framework</Th>
      <Th>JMH skóre (us/op)</Th>
      <Th>Min JMH skóre (us/op)</Th>
      <Th>Max JMH skóre (us/op)</Th>
    </Tr>
  </Thead>
  <Tbody>
    <Tr>
      <Td>Microhttp</Td>
      <Td>367.523</Td>
      <Td>341.455</Td>
      <Td>393.591</Td>
    </Tr>
    <Tr>
      <Td>Netty</Td>
      <Td>381.255</Td>
      <Td>355.639</Td>
      <Td>406.871</Td>
    </Tr>
    <Tr>
      <Td>Vert.x</Td>
      <Td>383.202</Td>
      <Td>365.798</Td>
      <Td>400.606</Td>
    </Tr>
    <Tr>
      <Td>Undertow</Td>
      <Td>392.591</Td>
      <Td>373.907</Td>
      <Td>411.275</Td>
    </Tr>
    <Tr>
      <Td>Armeria</Td>
      <Td>452.791</Td>
      <Td>425.552</Td>
      <Td>480.030</Td>
    </Tr>
    <Tr>
      <Td>Quarkus</Td>
      <Td>455.963</Td>
      <Td>428.281</Td>
      <Td>483.645</Td>
    </Tr>
    <Tr>
      <Td>Micronaut</Td>
      <Td>459.101</Td>
      <Td>430.433</Td>
      <Td>487.769</Td>
    </Tr>
    <Tr>
      <Td>Spring Boot WebFlux</Td>
      <Td>460.533</Td>
      <Td>433.276</Td>
      <Td>487.790</Td>
    </Tr>
    <Tr>
      <Td>Javalin</Td>
      <Td>465.533</Td>
      <Td>407.634</Td>
      <Td>523.432</Td>
    </Tr>
    <Tr>
      <Td>Spring Boot MVC</Td>
      <Td>558.134</Td>
      <Td>540.083</Td>
      <Td>576.185</Td>
    </Tr>
    <Tr>
      <Td>NanoHTTPD</Td>
      <Td>800.347</Td>
      <Td>764.524</Td>
      <Td>836.170</Td>
    </Tr>
  </Tbody>
</Table>

</Note>

<Note type="info">

<NoteTitle toggles="false">

##### Článek aktualizován k červnu 2023
</NoteTitle>

Díky komentářům Francesca Nigra z RedHat v [Issue #1](https://github.com/FgForrest/HttpServerEvaluationTest/issues/1)
(děkujeme!) jsme aktualizovali:

- aktualizovali jsme verze všech testovaných webových serverů na nejnovější verze,
- opravili jsme problém v implementaci výkonového testu Netty, který chybně uzavíral HTTP spojení v každé iteraci,
- vynutili jsme verzi HTTP protokolu 1.1, aby servery umožňující upgrade na HTTP/2 neměly výhodu,
- změnili jsme chování výkonového testu tak, aby každý JMH thread používal samostatného HTTP klienta

... a znovu jsme přeměřili všechny testy s tím, že běžel vždy jen jeden webový server paralelně.

<Table caption="Výsledky propustnosti (ops/s - vyšší je lepší).">
  <Thead>
    <Tr>
      <Th>Server, knihovna nebo framework</Th>
      <Th>JMH skóre (ops/s)</Th>
      <Th>Min JMH skóre (ops/s)</Th>
      <Th>Max JMH skóre (ops/s)</Th>
    </Tr>
  </Thead>
  <Tbody>
    <Tr>
      <Td>Netty</Td>
      <Td>32310</Td>
      <Td>31372</Td>
      <Td>33248</Td>
    </Tr>
    <Tr>
      <Td>microhttp</Td>
      <Td>31344</Td>
      <Td>30597</Td>
      <Td>32092</Td>
    </Tr>
    <Tr>
      <Td>Vert.x</Td>
      <Td>30463</Td>
      <Td>28915</Td>
      <Td>32010</Td>
    </Tr>
    <Tr>
      <Td>Javalin</Td>
      <Td>26649</Td>
      <Td>24502</Td>
      <Td>28796</Td>
    </Tr>
    <Tr>
      <Td>Undertow</Td>
      <Td>25214</Td>
      <Td>22444</Td>
      <Td>27985</Td>
    </Tr>
    <Tr>
      <Td>Micronaut</Td>
      <Td>22169</Td>
      <Td>19626</Td>
      <Td>24712</Td>
    </Tr>
    <Tr>
      <Td>Quarkus</Td>
      <Td>21269</Td>
      <Td>19650</Td>
      <Td>22887</Td>
    </Tr>
    <Tr>
      <Td>Spring Boot WebFlux</Td>
      <Td>20016</Td>
      <Td>18677</Td>
      <Td>21355</Td>
    </Tr>
    <Tr>
      <Td>Spring Boot MVC</Td>
      <Td>15550</Td>
      <Td>15059</Td>
      <Td>16041</Td>
    </Tr>
    <Tr>
      <Td>Quarkus (v nativním režimu)</Td>
      <Td>15433</Td>
      <Td>14516</Td>
      <Td>16351</Td>
    </Tr>
    <Tr>
      <Td>NanoHTTPD</Td>
      <Td>9400</Td>
      <Td>9068</Td>
      <Td>9733</Td>
    </Tr>
  </Tbody>
</Table>

<Table caption="Výsledky průměrného času (us/op - menší je lepší).">
  <Thead>
    <Tr>
      <Th>Server, knihovna nebo framework</Th>
      <Th>JMH skóre (us/op)</Th>
      <Th>Min JMH skóre (us/op)</Th>
      <Th>Max JMH skóre (us/op)</Th>
    </Tr>
  </Thead>
  <Tbody>
    <Tr>
      <Td>Microhttp</Td>
      <Td>193</Td>
      <Td>184</Td>
      <Td>202</Td>
    </Tr>
    <Tr>
      <Td>Vert.x</Td>
      <Td>198</Td>
      <Td>194</Td>
      <Td>201</Td>
    </Tr>
    <Tr>
      <Td>Netty</Td>
      <Td>198</Td>
      <Td>182</Td>
      <Td>215</Td>
    </Tr>
    <Tr>
      <Td>Javalin</Td>
      <Td>229</Td>
      <Td>225</Td>
      <Td>233</Td>
    </Tr>
    <Tr>
      <Td>Undertow</Td>
      <Td>253</Td>
      <Td>232</Td>
      <Td>274</Td>
    </Tr>
    <Tr>
      <Td>Micronaut</Td>
      <Td>283</Td>
      <Td>262</Td>
      <Td>304</Td>
    </Tr>
    <Tr>
      <Td>Quarkus</Td>
      <Td>287</Td>
      <Td>264</Td>
      <Td>309</Td>
    </Tr>
    <Tr>
      <Td>Spring Boot WebFlux</Td>
      <Td>306</Td>
      <Td>281</Td>
      <Td>331</Td>
    </Tr>
    <Tr>
      <Td>Spring Boot MVC</Td>
      <Td>385</Td>
      <Td>378</Td>
      <Td>392</Td>
    </Tr>
    <Tr>
      <Td>Quarkus (v nativním režimu)</Td>
      <Td>391</Td>
      <Td>369</Td>
      <Td>412</Td>
    </Tr>
    <Tr>
      <Td>NanoHTTPD</Td>
      <Td>646</Td>
      <Td>635</Td>
      <Td>657</Td>
    </Tr>
  </Tbody>
</Table>

Stále používáme server Undertow, i když už není jedním z nejrychlejších. Doufáme, že změní svou
interní implementaci na Netty server, jak bylo [slíbeno pro verzi 3.x](https://undertow.io/blog/2019/04/15/Undertow-3.html).

</Note>

## Kritéria a požadavky

Hlavním požadavkem byla co nejnižší latence a co nejvyšší propustnost při zpracování HTTP požadavků a také jednoduchá kódová základna zvoleného serveru, knihovny nebo frameworku, aby byla menší pravděpodobnost „magického“ chování a neočekávaných překvapení. Další důležitou vlastností byla možnost vložit HTTP základ do stávající kódové základny evitaDB bez nutnosti upravovat celý běhový a buildovací proces evitaDB na zvolené řešení, protože v budoucnu mohou být i jiné způsoby komunikace s uživateli. Výhodou ke všem těmto požadavkům by bylo jednoduché a přímočaré API pro zpracování HTTP požadavků a chyb. To znamená, že by nebyla potřeba žádná zbytečná nízkoúrovňová práce s HTTP komunikací, pokud to výslovně nevyžaduje nějaký konkrétní případ použití. V neposlední řadě by bylo dobré mít alespoň částečnou vestavěnou podporu pro práci s WebSockets pro budoucí specifické případy použití. Nakonec je výhodou server nebo knihovna, která je veřejně známá tím, že funguje nebo je testována s GraalVM nebo má přímou podporu GraalVM.

## Servery, knihovny a frameworky

Rozhodli jsme se věnovat až 8 hodin průzkumu ekosystému a vybrali jsme těchto 10 Java HTTP serverů a frameworků k testování:

- [microhttp](https://github.com/ebarlas/microhttp)
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)
- [Netty](https://github.com/netty/netty)
- [Undertow](https://github.com/undertow-io/undertow)
- [Spring MVC](https://docs.spring.io/spring-framework/docs/current/reference/html/web.html#mvc)
  se [Spring Boot](https://docs.spring.io/spring-boot/docs/current/reference/html/) *(běží na Tomcat, Jetty nebo
  Undertow, použili jsme Tomcat jako výchozí)*
- [Spring WebFlux](https://docs.spring.io/spring-framework/docs/current/reference/html/web-reactive.html#webflux)
  se [Spring Boot](https://docs.spring.io/spring-boot/docs/current/reference/html/) *(běží na Tomcat, Jetty nebo
  Undertow, použili jsme Tomcat jako výchozí)*
- [Vert.x](https://github.com/eclipse-vertx/vert.x) *(běží na Netty)*
- [Quarkus Native](https://quarkus.io/) *(běží na Netty přes Vert.x)*
- [Micronaut](https://micronaut.io/) *(běží na Netty)*
- [Javalin](https://github.com/tipsy/javalin) *(běží na Jetty)*

Tento seznam obsahuje nízkoúrovňové servery, malé knihovny i velké a známé frameworky pro tvorbu webových aplikací.
Rozhodli jsme se zahrnout i tyto velké frameworky, abychom měli srovnání výkonu s nízkoúrovňovými servery a věděli, zda případná oběť v podobě chybějících vysokoúrovňových abstrakcí opravdu stojí za to. Servery, knihovny a frameworky byly vybírány podle několika doporučujících článků a hlavně podle popularity na GitHubu, tj. počtu hvězdiček, počtu issues, dat posledních commitů atd.

## Testovací prostředí

Servery, knihovny a frameworky jsme testovali na jednoduchém „echo“ GraphQL API, protože jsme chtěli otestovat základní latenci zpracování HTTP požadavků pro GraphQL API. GraphQL API bylo zvoleno, protože je to první API, které bude evitaDB podporovat, a věříme, že tento přístup nám dá přesné měření i pro další budoucí API.
Toto zjednodušené testovací API bylo následně implementováno nad každým vybraným serverem, knihovnou a frameworkem.

„Echo“ API obsahuje jediný dotaz, který přijímá jeden řetězcový argument:

```graphql
query Echo {
    echo(message: "hello") {
        message
    }
}
```

který následně vrací ve zprávě odpovědi:

```json
{
  "data": {
    "echo": {
      "message": "hello"
    }
  }
}
```

Není zde implementována žádná další business logika.

Pro samotné testování a interpretaci výsledků jsme použili
[Java Microbenchmark Harness (JMH)](https://github.com/openjdk/jmh). Testovací workflow se skládá ze dvou částí:
spuštění serverů s implementovanými API a spuštění JMH testů. Nejprve je spuštěna aplikace s testovanými HTTP servery,
která spustí každý server v samostatném vlákně na vlastním portu. Poté je spuštěna JMH aplikace a každý test,
pro každý server, vytvoří Java HTTP klienta, který pak kontinuálně generuje požadavky na příslušný server po dobu jedné
minuty z několika vláken.

## Implementace serverů

Implementace jednotlivých serverů spočívala v podstatě v nalezení oficiálních příkladů knihovny nebo serveru a jejich transformaci na „echo“ GraphQL API s pomocí [GraphQL Java knihovny](https://www.graphql-java.com/) (která je de-facto průmyslovým standardem pro psaní GraphQL API na Java platformě). Kromě toho, že tento přístup byl časově velmi efektivní při práci s tolika servery, knihovnami a frameworky, také ukázal, jak snadné je s každým jednotlivým serverem nebo knihovnou pracovat a jaká překvapení se objeví i při stavbě jednoduchého serveru podle oficiálního příkladu. Další výhodou tohoto přístupu je, že ukazuje základní výkon konkrétního řešení bez potřeby složité nízkoúrovňové konfigurace. Pokud je složitá konfigurace potřeba už na začátku, obáváme se, že v budoucnu by to mohlo být obtížné na údržbu.

**microhttp**, **Javalin**, **Vert.x** a **Undertow** byly poměrně přímočaré na zpracování. Stačilo napsat jen několik řádků kódu pro implementaci jednoduchých HTTP handlerů. Implementace zbývajících serverů a knihoven byla o něco složitější.

**NanoHTTPD** má sice jednoduché API, ale nebyl navržen pro práci s JSON požadavky, pouze se základními HTTP POST těly. Bylo tedy nutné přeimplementovat parsování těla požadavku. Také se zdá, že již není aktivně udržován, i když na GitHubu stále probíhá nějaká diskuse v issues.

**Netty** naopak vyžadoval ruční konfiguraci několika pracovních vláken a dalších komunikačních možností. V případě jejich HTTP kodeku existuje poměrně velká a opravdu ne přímočará abstrakce pro třídy HTTP požadavků a odpovědí. Existuje několik zvláštně pojmenovaných tříd (alespoň pro nové uživatele tohoto serveru), které jsou nakonec složeny dohromady a předány handlerům. To byl problém při hledání způsobu, jak přečíst tělo POST požadavku. Museli jsme zaregistrovat Netty’s body aggregator a použít specifickou třídu HTTP požadavku, která má nakonec k tělu přístup.

**Spring MVC** a **WebFlux** nebyly složité na implementaci, ale byly složité na nastavení, protože vyžadují vlastní Spring Boot Maven plugin pro kompilaci, což vylučuje možnost jejich embedování do jiných aplikací.
Naštěstí bylo řešení poměrně jednoduché – vytvořit samostatné jar soubory pro každý Spring server.

Implementace služby ve frameworku **Quarkus** byla mnohem obtížnější. Pokud pomineme obtíže
s [GraalVM](https://www.graalvm.org/) samotnou, jako je vlastní buildování, byly zde i obtíže s implementací samotných controllerů. Největší překážkou byla nemožnost rozdělit implementaci do více modulů, i když by to [mělo být možné](https://quarkus.io/guides/maven-tooling#multi-module-maven). Proto na rozdíl od ostatních implementací musely být všechny controllery v jednom místě společně s hlavní třídou aplikace.

V případě **Micronaut** byla samotná implementace poměrně jednoduchá, pravděpodobně nejjednodušší ze všech serverů a knihoven (s pomocí vestavěného controlleru pro GraphQL). Problém byl v nastavení Maven modulů. Micronaut používá vlastní Maven parent POM a existují zde skryté závislosti, bez kterých nebylo možné Micronaut spustit jako součást většího multi-modulového Maven projektu.

## Benchmarkování serverů

Finální testy byly spuštěny s 1 warm-up iterací, 5 měřícími iteracemi a 2 forky na notebooku s Ubuntu 21.10 a 8jádrovým procesorem Intel Core i7-8550U a 16 GB RAM. Testy byly spuštěny ve dvou režimech: propustnost (operace za sekundu) a průměrný čas (mikrosekundy na operaci).

<Table caption="Výsledky propustnosti (ops/s - vyšší je lepší).">
	<Thead>
      <Tr>
          <Th>Server, knihovna nebo framework</Th>
          <Th>JMH skóre (ops/s)</Th>
          <Th>Min JMH skóre (ops/s)</Th>
          <Th>Max JMH skóre (ops/s)</Th>
      </Tr>
	</Thead>
	<Tbody>
      <Tr>
          <Td>microhttp</Td>
          <Td>30,199</Td>
          <Td>30,034</Td>
          <Td>30,401</Td>
      </Tr>
      <Tr>
          <Td>Netty</Td>
          <Td>28,689</Td>
          <Td>28,617</Td>
          <Td>28,748</Td>
      </Tr>
      <Tr>
          <Td>Undertow</Td>
          <Td>25,760</Td>
          <Td>25,745</Td>
          <Td>25,793</Td>
      </Tr>
      <Tr>
          <Td>Javalin</Td>
          <Td>23,650</Td>
          <Td>23,399</Td>
          <Td>23,995</Td>
      </Tr>
      <Tr>
          <Td>Vert.x</Td>
          <Td>22,850</Td>
          <Td>22,477</Td>
          <Td>23,070</Td>
      </Tr>
      <Tr>
          <Td>Micronaut</Td>
          <Td>19,572</Td>
          <Td>19,394</Td>
          <Td>19,841</Td>
      </Tr>
      <Tr>
          <Td>Spring Boot WebFlux</Td>
          <Td>18,158</Td>
          <Td>17,991</Td>
          <Td>18,234</Td>
      </Tr> 
      <Tr>
          <Td>Spring Boot MVC</Td>
          <Td>17,674</Td>
          <Td>17,603</Td>
          <Td>17,786</Td>
      </Tr>
      <Tr>
          <Td>Quarkus (v nativním režimu)</Td>
          <Td>11,509</Td>
          <Td>11,383</Td>
          <Td>11,642</Td>
      </Tr>
      <Tr>
          <Td>NanoHTTPD</Td>
          <Td>6,171</Td>
          <Td>6,051</Td>
          <Td>6,254</Td>
      </Tr>
	</Tbody>
</Table>

<Table caption="Výsledky průměrného času (us/op - menší je lepší).">
	<Thead>
		<Tr>
			<Th>Server, knihovna nebo framework</Th>
			<Th>JMH skóre (us/op)</Th>
			<Th>Min JMH skóre (us/op)</Th>
			<Th>Max JMH skóre (us/op)</Th>
		</Tr>
	</Thead>
	<Tbody>
		<Tr>
			<Td>microhttp</Td>
			<Td>131</Td>
			<Td>129</Td>
			<Td>133</Td>
		</Tr>
		<Tr>
			<Td>Netty</Td>
			<Td>145</Td>
			<Td>142</Td>
			<Td>146</Td>
		</Tr>
		<Tr>
			<Td>Undertow</Td>
			<Td>156</Td>
			<Td>156</Td>
			<Td>156</Td>
		</Tr>
		<Tr>
			<Td>Javalin</Td>
			<Td>172</Td>
			<Td>168</Td>
			<Td>175</Td>
		</Tr>
		<Tr>
			<Td>Vert.x</Td>
			<Td>173</Td>
			<Td>172</Td>
			<Td>174</Td>
		</Tr>
		<Tr>
			<Td>Micronaut</Td>
			<Td>202</Td>
			<Td>201</Td>
			<Td>203</Td>
		</Tr>
		<Tr>
			<Td>Spring Boot WebFlux</Td>
			<Td>224</Td>
			<Td>223</Td>
			<Td>225</Td>
		</Tr>
		<Tr>
			<Td>Spring Boot MVC</Td>
			<Td>224</Td>
			<Td>222</Td>
			<Td>233</Td>
		</Tr>
		<Tr>
			<Td>Quarkus (v nativním režimu)</Td>
			<Td>348</Td>
			<Td>345</Td>
			<Td>353</Td>
		</Tr>
		<Tr>
			<Td>NanoHTTPD</Td>
			<Td>642</Td>
			<Td>625</Td>
			<Td>649</Td>
		</Tr>
	</Tbody>
</Table>

*Syrové výsledky najdete [zde](https://gist.github.com/novoj/cef56bd940a015b4cfb1ad389d2b6705) a
grafy pro vizualizaci [zde](https://jmh.morethan.io/?gist=cef56bd940a015b4cfb1ad389d2b6705&topBar=HTTP%20web%20server%20upgraded%20versions%20from%2003/2023%20(optimalized)).*

Z výše uvedených výsledků jsou tři hlavní adepti na vítěze: microhttp, Netty a Undertow. Poměrně zajímavé a překvapivé jsou výsledky Javalin, což je ve skutečnosti framework postavený na [Jetty](https://www.eclipse.org/jetty/)
serveru a ne čistý HTTP server.

Výsledky populárního serveru Netty, což je nízkoúrovňový server s nejtěžším API ze všech, jsou také velmi dobré. Také jsme očekávali, že server Quarkus, který běžel v nativním režimu s využitím
[GraalVM](https://www.graalvm.org/), skončí na vyšších příčkách. Oproti tomu velké frameworky jako Spring a Vert.x byly mnohem výkonnější, než jsme čekali, vzhledem k jejich komplexní abstrakci.

## Výběr finálního řešení

Finální rozhodnutí – který server, knihovnu nebo framework zvolit – se zúžilo na tři řešení: **microhttp**, **Javalin**
a **Undertow**. Protože jejich výkon byl velmi podobný, rozhodnutí bylo učiněno na základě jejich výhod a nevýhod relevantních pro evitaDB.

<Note type="info">

<NoteTitle toggles="true">

##### Proč byl server Netty vyřazen z výběru?
</NoteTitle>

V počátečních výkonových testech jsme udělali chybu, která vedla k nízkému výkonu serveru Netty ve srovnání s ostatními řešeními. Tato chyba byla opravena až o několik měsíců později po komentáři Francesca Nigra. Kvůli původně nedostatečnému počtu serverů a složitému API jsme Netty vyřadili ze seznamu webových serverů vybraných pro použití v evitaDB.

</Note>

Zpočátku se server microhttp jevil jako ten pravý, hlavně díky své výjimečně malé kódové základně (cca 500 řádků kódu) a jednoduchému a přímočarému API. Celkově tento server splňoval téměř všechny požadavky kromě podpory WebSockets. Nejistá budoucnost tohoto poměrně nového projektu však byla zásadní překážkou. Další možné nevýhody jsou absence podpory SSL a dalších pokročilých funkcí. S tímto vědomím jsme se zaměřili na výběr mezi Javalin a Undertow. Javalin je lehký framework postavený na
[Jetty](https://www.eclipse.org/jetty/) serveru a Undertow je skutečný HTTP server, přesto měly téměř stejný výkon. Oba splňují všechny požadavky. Oba jsou výkonné, snadno embedovatelné, dostatečně malé, aby omezily možnosti „magických“ překvapení, mají jednoduchá a přímočará API a podporují i WebSockets. Oba jsou populární a pravidelně aktualizované. Oba podporují neblokující zpracování požadavků a oba by pravděpodobně měly běžet na GraalVM v budoucnu, pokud to bude potřeba. Javalin přichází s pohodlnými API metodami pro konfiguraci endpointů, vestavěnou konverzí JSON na třídy pomocí [Jackson](https://github.com/FasterXML/jackson), validací požadavků a jednoduchým způsobem zpracování chyb. Na druhou stranu je Undertow v některých ohledech úspornější, ale umožňuje konfigurovat spoustu nízkoúrovňových věcí. Podobně jako Javalin má Undertow také některé vestavěné funkce jako routování nebo různé HTTP handlery, ale samotné zpracování HTTP požadavků není tak jednoduché jako v Javalin kvůli chybějící vestavěné konverzi JSON na třídy.

Obecně jsou v obou případech implementované servery poměrně jednoduché a stačilo jen několik řádků kódu k získání funkčního GraphQL API se základním routováním. Pro webovou aplikaci by to byla snadná výhra pro Javalin díky všem těmto zkratkám. Ale pro evitaDB, což je specializovaná databáze a ne webová aplikace, kde HTTP API nejsou hlavní věcí, si myslíme, že tyto zkratky by mohly být v budoucnu pro rozšiřování evitaDB nedostatečné nebo by se ani nevyužily. Dalším bodem proti Javalinu v případě evitaDB je absence nízkoúrovňové konfigurace HTTP komunikace. Aktuálně pro to nemáme konkrétní využití, ale myslíme si, že ztráta této možnosti by mohla být zbytečně omezující do budoucna.
Proto jsme zvolili server **Undertow**.

## Závěr

Každý jednotlivý server, knihovna nebo framework byl úspěšně použit k vytvoření serveru s ukázkovým „echo“ API. To znamená, že z hlediska funkčnosti by všechna tato řešení byla dostačující. Když jsme však zohlednili požadavky na výkon a možnosti embedování, výběr možných serverů, knihoven a frameworků se zúžil. Například řešení Spring Boot vyžadovala vlastní buildovací Maven pluginy a workflow, Quarkus také vyžadoval vlastní workflow a ani se nám jej nepodařilo zprovoznit napříč více Maven moduly, i když by to mělo být možné. Micronaut byl pravděpodobně nejhorší, co se týče nastavení, protože nejenže vyžadoval vlastní strukturu kódu a vlastní Maven plugin, ale také vyžadoval vlastní Maven parent POM, což je v multi-modulových projektech jako evitaDB docela problém. Tento nedostatek by šlo pravděpodobně překonat investováním více času do pochopení potřebných závislostí, ale obáváme se, že pokud je jednoduché nastavení takto obtížné, mohou se v budoucnu objevit další překvapení. Ostatní řešení bylo možné embedovat poměrně snadno. Výjimkou byl server Netty. Implementace serveru na Netty vyžadovala spoustu nízkoúrovňové HTTP konfigurace (což pro nové uživatele působí téměř jako šifra). Další obtíží bylo obrovské množství různých tříd HTTP požadavků a odpovědí, které se nějak skládají do těch finálních. To je však pravděpodobně dáno univerzálností celého Netty ekosystému.

Veškeré zdrojové kódy a celou testovací sadu najdete
v [našem Github repozitáři](https://github.com/FgForrest/HttpServerEvaluationTest). Uvítáme jakoukoli konstruktivní zpětnou vazbu a doporučujeme vám experimentovat se zdroji a udělat si vlastní závěry.