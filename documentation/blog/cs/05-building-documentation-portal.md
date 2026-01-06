---
title: Vytvoření vývojářsky přívětivého portálu s dokumentací pomocí Next.js a MDX
perex: Jako vývojář chápu důležitost aktuální dokumentace. Vytvořit portál s dokumentací, který je snadné udržovat a aktualizovat, však může být výzva. Pevně věřím, že když mohou vývojáři psát a publikovat dokumentaci snadno a rychle, motivuje je to věnovat čas zaznamenání klíčových informací. Právě proto jsem se pustil do výzvy vytvořit nový portál s dokumentací pro vývojáře na evitadb.io.
date: '04.5.2023'
author: Miro Alt
motive: ../en/assets/images/05-building-documentation-portal.png
proofreading: done
commit: '0f7ffc51ec069d682dfb02196b403a93893733c4'
---
Mým cílem bylo vytvořit portál s dokumentací, který je snadno udržovatelný a aktualizovatelný, přičemž dokumentační soubory jsou uloženy co nejblíže ke kódu a je možné je verzovat v rámci životního cyklu merge-requestů. Bezproblémová integrace byla pro mě klíčová, a proto jsem zajistil, aby náš portál s dokumentací podporoval zdrojové soubory z různých repozitářů pomocí GitLab API. A samozřejmě jsem potřeboval zajistit, aby se čistý Markdown správně zobrazoval jak na GitLabu, tak na GitHubu.

Tím jsem ale neskončil. Chtěl jsem také poskytnout podporu pro specifické funkce a vlastnosti, o kterých jsme diskutovali ve fázi příprav.

Jak jsem to tedy udělal? V tomto blogovém příspěvku vás provedu tím, jak jsem pomocí Next.js a Next MDX Remote postavil portál dokumentace evitadb.io, který splňuje všechny tyto požadavky.

## Proč jsem zvolil Next.JS + MDX

Jak jsem již zmínil, hlavní prioritou bylo umožnit vývojářům uchovávat své dokumentační soubory `.md` nebo `.mdx` co nejblíže jejich skutečnému kódu. Zároveň jsme chtěli, aby byl portál s dokumentací samostatným repozitářem, odděleným od rušných repozitářů vývojářů.

Po určitém průzkumu jsme objevili
[MDX Remote příklad](https://github.com/vercel/next.js/tree/canary/examples/with-mdx-remote), který ukazoval, jak lze jednoduchý blog vytvořit pomocí knihovny [next-mdx-remote](https://github.com/hashicorp/next-mdx-remote). Tato knihovna poskytuje funkci a komponentu, `serialize` a ``<MDXRemote />``. Umožňuje také načítat MDX obsah přes `getStaticProps` nebo `getServerSideProps`. Samotný obsah lze načítat z lokální složky, databáze nebo **jakéhokoliv jiného umístění**, což bylo zásadní.

Jednou z vlastností Next.js je způsob, jakým vykresluje na straně serveru i klienta. To je klíčové pro single page aplikace (SPA), které vytváří, a pomáhá těmto SPA dosahovat mnohem lepších výsledků z hlediska SEO (optimalizace pro vyhledávače).

Kromě toho jsme věděli, že Next.js má další výhody jako:

- Výborný výkon z hlediska doby načítání
- Rychlé načítání díky „lazy loadingu“ a automatickému dělení kódu
- Skvělá podpora pro vývojáře
- Výborný uživatelský zážitek
- Rychlejší uvedení na trh
- Skvělé SEO

## Gitlab/Github API

Zpočátku dokumenty „žily“ v soukromém repozitáři na **Gitlabu** a věděli jsme, že můžeme
[získat RAW obsah](https://docs.gitlab.com/ee/api/repository_files.html#get-raw-file-from-repository) souborů pomocí jejich API a tím pádem poskytnout externí zdroj pro „nalití“ do MDX provideru.

> Později bylo rozhodnuto přesunout repozitář, kde jsou všechny dokumentační soubory, na Github a přizpůsobení se tomu bylo jen otázkou úpravy samotného požadavku podle [Github API guide](https://docs.github.com/en/rest/guides/getting-started-with-the-rest-api?apiVersion=2022-11-28).

Zde je příklad URL, která získává data z Gitlabu:

```
https://gitlab.example.com/api/v4/projects/13083/repository/files/app%2Fmodels%2Fkey%2Erb/raw?ref=master
```

Důležité atributy pro nás byly:

- `id` - možnost přepínat mezi repozitáři v případě potřeby.
- `file_path` - URL-enkódovaná úplná cesta k souboru, například `lib%2Fclass%2test.md`.
- `raw` - potřebovali jsme RAW obsah.
- `ref` - větev, ze které čerpáme.

A zde je příklad pomocné funkce, kterou jsem použil pro získání obsahu souboru.

```tsx
import config from "../config/default";
import {escapePathGitlab} from "./escapePathGitlab";
// im main config I keep all important information (HEADERS, ID, etc.)
const {gitlabApiUrl} = config.genericDocsProps;
const {headers, projectId, docsUser} = config.DocProps;
const {branch: postsBranch} = docsUser;

export default async function fetchGlContentData(path) {
    const url = `${gitlabApiUrl}/${projectId}/repository/files/${escapePathGitlab(
        path
    )}/raw?ref=${postsBranch}`;
    try {
        const response = await fetch(url, {
            method: "GET",
            headers,
            redirect: "follow",
        });
        if (response.ok) {
            const text = await response.text();
            return text;
        } else {
            if (response.status === 404) {
            } else {
                console.log("Full error response:", response);
            }
        }
    } catch (error) {
        console.error("Error fetching data:", error);
    }
}
```

S dostupnými daty jsem mohl začít s jejich zpracováním.

## Navigace v dokumentaci

Definovat routy s předdefinovanými cestami nestačí pro složité aplikace. V Next.js můžete přidat závorky k názvu stránky (`[param]`) pro vytvoření dynamické routy (URL slugu).

Vzdálený repozitář jsme nastavili tak, že zdrojová složka je `docs`. Odtud jsme začali dokumenty rozdělovat do více tematicky zaměřených podsložek. Jako výchozí bod v každé podsložce jsme vytvořili soubor `menu.json` jako zdroj navigace pro naši SPA.

Struktura našeho portálu s dokumentací tedy vypadá zhruba takto:

```go
├── docs
|   ├── research
|   |   └── ...
|   ├── user
|       └── en
|           ├── use
|           |   └── doc.md
|           └── menu.json
```

V následujícím zjednodušeném příkladu můžete vidět, jak používáme data, `serialise` (pro přidání podpory dalších Markdown funkcí jako jsou tabulky pomocí [remarkGfm](https://github.com/remarkjs/remark-gfm)). Také používáme [gray-matter](https://www.npmjs.com/package/gray-matter), což je YAML formátovaný klíč/hodnota pár dat, který například využíváme k zobrazení jména autora.

```tsx

import {serialize} from 'next-mdx-remote/serialize'
import {MDXRemote} from 'next-mdx-remote'
import {customMdxComponents} from './utils/customMdxComponents';

export default function DocPage({source}) {
    return (
        <div className="wrapper">
            <h1>{source.scope.title}</h1>
            <MDXRemote {...source!} components={customMdxComponents}/>
        </div>
    )
}

export async function getStaticProps() {
    const documentationPath = CONFIG.docsProps.documentationPath;
    const {params} = props;
    const gitHubFilePath = `${[documentationPath, params?.slug].join('/')}.md`;
    
    const text = await fetchGlContentData(gitHubFilePath);
    let post;
    let mdxSource;
    const {content, data} = grayMatter(text);
    post = {content, ...data};
    mdxSource = await serialize(content, {
        mdxOptions: {
            remarkPlugins: [remarkGfm],
            rehypePlugins: [rehypeSlug],
        },
        scope: data,
    });
    
    return {
        props: {
            source: mdxSource,
        },
        notFound: post == null,
    };
}

```

> Kompilátor podporuje mnoho [remark](https://github.com/remarkjs/remark/blob/main/doc/plugins.md#list-of-plugins)
> a [rehype](https://github.com/rehypejs/rehype/blob/main/doc/plugins.md#list-of-plugins) pluginů.

## Vlastní komponenty

V této fázi jsem vyzkoušel základní nastavení a seznamoval jsem se s implementací
[MDX Remote Custom Components](https://github.com/vercel/next.js/tree/canary/examples/with-mdx-remote#conditional-custom-components).

Věděl jsem, že základní Markdown by nám nestačil. S přidanou podporou vlastních komponent v našich dokumentačních souborech byly možnosti téměř neomezené. Jak vývojáři začali přidávat více obsahu, přicházeli jsme s dalšími funkcemi pro vylepšení zážitku čtenářů.

Začal jsem tedy vytvářet vlastní komponenty, které vývojářům poskytují doplňkové funkce. Když se podíváte na
[raw Markdown Content](https://raw.githubusercontent.com/FgForrest/evitaDB/dev/documentation/user/en/use/api/write-tests.md)
jednoho z našich zdrojových souborů, uvidíte mnoho vlastních komponent, které v naší dokumentaci používáme.

Existuje mnoho knihoven plných komponent, které vyhovují našim potřebám, a u mnoha jsme se inspirovali jinde a upravili je podle potřeby. Některé funkce a komponenty jsme však vytvořili zcela od začátku, aby vyhovovaly našim požadavkům.

V dalším příspěvku shrnu klíčové funkce některých pokročilejších vlastností, které jsme na našem Developers Portálu použili. Například jak jsme optimalizovali naše komponenty, aby byly „přechodné“ s čistým Markdownem, Gitlab/Github routováním a zobrazením.

## Co dál?

Náš portál s dokumentací se neustále vyvíjí, aby vyhovoval potřebám našich vývojářů. V současné době pracujeme na implementaci sofistikovanějšího zpracování a reportování chyb, abychom zajistili hladký chod všeho. V tuto chvíli, když dojde k chybě, musí vývojáři hledat problém v produkčních serverových logách, což není ideální. Přestože již existuje průvodce [jak řešit potíže s MDX](https://mdxjs.com/docs/troubleshooting-mdx/) a na co si dát pozor, jsme odhodláni zlepšovat naše pracovní postupy a přidávat další funkce a vlastnosti. Takže zůstaňte naladěni, protože to nejlepší teprve přijde!