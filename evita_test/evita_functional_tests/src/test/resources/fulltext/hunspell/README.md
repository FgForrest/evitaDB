# Hunspell dictionaries used by the full-text analysis test sweeps

These four dictionary pairs are **developer instruments**, not product data. They are read only by the lexicon
coverage sweeps and the branching-stemmer equivalence tests — `CzechVariantStemmerLexiconTest`,
`SlovakVariantStemmerLexiconTest`, `PolishVariantStemmerLexiconTest`, `RomanianVariantStemmerLexiconTest`,
`BranchingCzechStemmerEquivalenceTest`, `BranchingSlovakStemmerEquivalenceTest`,
`BranchingPolishStemmerEquivalenceTest` and `BranchingRomanianStemmerEquivalenceTest` — which use them to
enumerate a language's inflected surface forms and check that the query-side variant stemmer reaches the index
term of every one of them.

**Nothing here is packaged into a distributed evitaDB artifact.** `evita_functional_tests` sets
`maven.deploy.skip=true` and its test-jar carries classes only, so no dictionary byte ever leaves this
repository inside a released jar. They are also never loaded by engine code: the whole `evita_engine`
full-text pipeline is rule-based and has no Hunspell dependency at all.

Each dictionary is an **unmodified** copy of its upstream file and keeps its own licence, which is reproduced
in `evita_test/evita_functional_tests/NOTICE`. evitaDB's own Business Source License does not apply to them.

## Per-dictionary provenance

### `pl_PL.dic` / `pl_PL.aff`

- **Upstream**: Polish dictionary pack of the Polish Native Lang Project, 2008-12-06 snapshot of the
  OpenOffice.org dictionaries. Maintainer Marek Futrega, corrections Marcin Miłkowski.
  <http://extensions.openoffice.org/en/project/polish-dictionary-pack>, project home <http://pl.openoffice.org>.
- **Retrieval path**: `github.com/wooorm/dictionaries` → evitaDB. Repackaged as `dictionary-pl` 2.0.0,
  files `dictionaries/pl/index.dic` and `dictionaries/pl/index.aff` at commit
  `8cfea406b505e4d7df52d5a19bce525df98c54ab` (2024-09-09). That repository repackages upstream dictionaries
  without touching their content; its own MIT licence covers the packaging only, never the dictionary data.
- **Licence**: the upstream `license` file states "GPL, LGPL, MPL (Mozilla Public License) and Creative Commons
  ShareAlike licenses" **without naming versions**; wooorm records the SPDX expression
  `(GPL-3.0 OR LGPL-3.0 OR MPL-2.0)`. evitaDB relies on the **MPL** option.

### `sk_SK.dic` / `sk_SK.aff`

- **Upstream**: sk-spell Slovak dictionaries for ASpell & Hunspell, Zdenko Podobný, <http://www.sk-spell.sk.cx>.
  Version `2.03-1` of 2009-12-19, as stated on the first line of `sk_SK.aff`.
- **Retrieval path**: `github.com/wooorm/dictionaries` → evitaDB. Repackaged as `dictionary-sk` 2.0.0, files
  `dictionaries/sk/index.dic` and `dictionaries/sk/index.aff` at commit
  `8cfea406b505e4d7df52d5a19bce525df98c54ab` (2024-09-09).
- **Licence**: GPL-2.0 / LGPL-2.1 / MPL-1.1 tri-licence. evitaDB relies on the **MPL 1.1** option.

### `ro_RO.dic` / `ro_RO.aff`

- **Upstream**: Romanian Hunspell package, Rospell Team, <http://rospell.sourceforge.net> /
  <https://rospell.wordpress.com>. Word list version 3.3.10, as stated on the first line of `ro_RO.aff`.
- **Retrieval path**: `github.com/wooorm/dictionaries` → evitaDB. Repackaged as `dictionary-ro` 3.0.0, files
  `dictionaries/ro/index.dic` and `dictionaries/ro/index.aff` at commit
  `8cfea406b505e4d7df52d5a19bce525df98c54ab` (2024-09-09).
- **Licence**: GPL-2.0 / LGPL-2.1 / MPL-1.1 tri-licence, stated both in the upstream `license` file and in the
  header of `ro_RO.aff` itself. evitaDB relies on the **MPL 1.1** option.

### `cs_CZ.dic` / `cs_CZ.aff`

- **Upstream**: Czech ispell dictionary by Petr Kolář and contributors, converted to Hunspell and updated in
  2021 by Miroslav Pošta (<https://www.translatoblog.cz>). Shipped as the LibreOffice Czech spelling extension,
  version `2021.07`.
- **Retrieval path**: `github.com/LibreOffice/dictionaries` at commit `8cd38fb513` (2021-06-16, "Czech Hunspell:
  fix word třídička"), files `cs_CZ/cs_CZ.dic` and `cs_CZ/cs_CZ.aff` → evitaDB. The files here are
  byte-identical to that revision.
- **Licence**: **GPL-2.0 only**, per the extension's `README_en.txt` ("GNU/GPL", with GPL v2 attached). This is
  the one dictionary of the four that offers no permissive option.

**Why a GPL-2.0 file sits in a BUSL repository.** The dictionary is a separate, unmodified work. No evitaDB
code links against it, derives from it or embeds any part of it; it is read at test time only, by a coverage
sweep that enumerates word forms, and it is never combined with evitaDB into a distributed program. Keeping it
next to — rather than inside — the licensed work is what GPL-2.0 §2 calls *mere aggregation*, which the licence
explicitly permits.

The alternative that was considered and rejected: wooorm's later `dictionary-cs` 4.0.0 (from translatoblog.cz,
260 460 headwords) is **not** a permissively licensed replacement — it is a different revision of the same
GPL-2.0 lineage.

## FG/evitaDB-authored files in this folder

- **`cs_CZ-stopwords.txt`** (173 words) — authored at FG Forrest. No third-party licence applies.
- **`cs_CZ-synonyms.txt`** — authored for evitaDB (LLM-generated, see its own header). No third-party licence
  applies.

## Checksums of the committed files

```text
f39f7cfea27f4c09e3ff21d4e6d47897e6382ec9ec975d247796263b82efd5f6  cs_CZ.dic
05b6a8c0b1f9bddb4050d334ea596f83ab58807f5854895c3ec59ce32ee0f7fa  cs_CZ.aff
74b6155391025419967c26913541b55b36d58e80b1028037d4a0b8ddcb18d0b7  pl_PL.dic
de7b54a8fa37562d58b481cf3d87a63e3dcc2d74021f6403eb0e4f5df9ecc225  pl_PL.aff
b6cdb6fb512c44881cc91329aea0b179840f5f9a3e6cb024c2ab8547f96f5d94  sk_SK.dic
a79dc992b849aa2355db38eea9c4722239993639d77bc81c9c1aa79f213e723c  sk_SK.aff
c26a9356f598a0ae89e7be650f6bdd9ba70acce66b41d7ab14c0c68639b6ed33  ro_RO.dic
3ee127a667bdec829faee546ae32033e8f10f7c63117f9b572ef03ad9df2b76b  ro_RO.aff
```

Verify with `sha256sum *.dic *.aff` from this directory. A mismatch means the file was edited, which it must
not be: these are upstream copies, and the whole provenance argument above rests on them being unmodified.
