/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.fulltext.analysis;


import javax.annotation.Nonnull;
import java.util.List;

/**
 * The Czech vocabulary {@link LanguageAnalyzerPairRecallTest} measures the `czech`/`czech-search` analyzer pair
 * against. Keeping the vocabulary out of the test is what makes the four languages comparable — a mechanism
 * that scores better on its own vocabulary has not been shown to score better at all.
 *
 * The metric definitions live in {@link LanguageAnalyzerPairRecallTest}; this class holds only what is Czech:
 * the lemmas, the inflected forms, and the record of why each morphological class is present.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class CzechAnalysisFixture {

	/**
	 * A Czech e-commerce vocabulary: 32 lemmas, each with the inflected forms an e-shop actually stores or is
	 * queried by. The classes present here are deliberate, because a mechanism can only be measured against a
	 * failure it is given the chance to commit:
	 *
	 * - the adjective genitive/locative plural `-ých`/`-ích` and instrumental `-ým`, and the noun
	 *   dative/locative plural `-ám`/`-ách`/`-ům` — the classes the production folding order loses;
	 * - the `stůl`/`stolů` vowel shift, which only the `u`→`o` rewrite converges;
	 * - the **palatalized** nominative plural `dětští`, `pánští`, `kuchyňští`, `angličtí` — `sk`↔`št` and
	 *   `ck`↔`čt` alternation, which only the palatalization rewrite converges. These were **missing from the
	 *   first two measurement runs**, which is why those runs reported the palatalization rewrite as buying
	 *   nothing: the fixture never gave it a pair to converge. A rule measured against a vocabulary that
	 *   cannot exercise it scores zero for the wrong reason;
	 * - the neuter `-ata` paradigm `rajče`/`rajčata`, whose plural the `at`-family entries exist for. Missing
	 *   from the first three runs, which is why those runs reported dropping the entries as free: the drop's
	 *   cost — the singular splitting from the plural — needs an `-ata` neuter to commit, and `kabát` (the
	 *   drop's *benefit*) is a masculine. Same lesson as the palatalized plurals, opposite direction: a rule's
	 *   removal must also be measured against a vocabulary that exercises what the rule was for.
	 */
	static final List<Lemma> VOCABULARY = List.of(
		new Lemma("černý", List.of("černý", "černá", "černé", "černých", "černým")),
		new Lemma("bílý", List.of("bílý", "bílá", "bílé", "bílých", "bílým")),
		new Lemma("šedý", List.of("šedý", "šedá", "šedé", "šedých")),
		new Lemma("žlutý", List.of("žlutý", "žlutá", "žluté", "žlutých")),
		new Lemma("dámský", List.of("dámský", "dámská", "dámské", "dámských")),
		new Lemma("pánský", List.of("pánský", "pánská", "pánské", "pánských", "pánští")),
		new Lemma("dětský", List.of("dětský", "dětská", "dětské", "dětských", "dětští")),
		new Lemma("kožený", List.of("kožený", "kožená", "kožené", "kožených")),
		new Lemma("dřevěný", List.of("dřevěný", "dřevěná", "dřevěné", "dřevěných")),
		new Lemma("stříbrný", List.of("stříbrný", "stříbrná", "stříbrné", "stříbrných")),
		new Lemma("kuchyňský", List.of("kuchyňský", "kuchyňská", "kuchyňské", "kuchyňských", "kuchyňští")),
		new Lemma("anglický", List.of("anglický", "anglická", "anglické", "anglických", "angličtí")),
		new Lemma("velký", List.of("velký", "velká", "velké", "velkých")),
		new Lemma("malý", List.of("malý", "malá", "malé", "malých")),
		new Lemma("zahradní", List.of("zahradní", "zahradního", "zahradních")),
		new Lemma("stůl", List.of("stůl", "stolů")),
		new Lemma("židle", List.of("židle", "židli", "židlí")),
		new Lemma("tričko", List.of("tričko", "trička", "tričkem")),
		new Lemma("košile", List.of("košile", "košili", "košilí")),
		new Lemma("bota", List.of("botě", "botám", "botách")),
		new Lemma("kabát", List.of("kabát", "kabátu", "kabáty", "kabátů")),
		new Lemma("mikina", List.of("mikině", "mikinách")),
		new Lemma("hodinkář", List.of("hodinkář", "hodinkářů")),
		new Lemma("náramek", List.of("náramek", "náramku", "náramky", "náramků")),
		new Lemma("přívěsek", List.of("přívěsek", "přívěsku", "přívěsky", "přívěsků")),
		new Lemma("sluchátka", List.of("sluchátka", "sluchátek", "sluchátkům")),
		new Lemma("počítač", List.of("počítač", "počítače", "počítači", "počítačů")),
		new Lemma("nábytek", List.of("nábytek", "nábytku", "nábytkem")),
		new Lemma("skříň", List.of("skříň", "skříně", "skříni", "skříní")),
		new Lemma("dárek", List.of("dárek", "dárku", "dárky", "dárků")),
		new Lemma("kůže", List.of("kůže", "kůži", "kůží")),
		new Lemma("rajče", List.of("rajče", "rajčata", "rajčat"))
	);

	/**
	 * Pairs of **unrelated** lemmas chosen because the two risky rules of a folded-space Czech stemmer would
	 * collapse them onto one term. They exist purely to give the false-merge measurement something to find:
	 * a precision metric over a vocabulary containing no confusable words measures nothing.
	 *
	 * - `cesta`/`český` and `list`/`líska` probe the palatalization rule. `CzechStemmer` rewrites `št` to `sk`
	 *   to make `český`/`čeští` converge — hence the `čeští` form below, so that the rule's *benefit* is
	 *   measured on the same vocabulary as its cost; folded, `št` is indistinguishable from a genuine `st`, so
	 *   a blanket `st→sk` also rewrites `cest` and `list`.
	 * - `ruka`/`rok` and `buk`/`bok` probe the vowel shift. `CzechStemmer` rewrites a penultimate `ů` to `o` so
	 *   that `dům`/`domu` converge; folded, `ů` is indistinguishable from `u`, so a blanket rule also rewrites
	 *   `ruk` and `buk`.
	 * - `forma`/`formát` probe the `-at` family entries wherever they fire on a genuine `-át` root — under the
	 *   kept single-pass entries and under two-step stemming alike: `formát` truncated by the `at` entry lands
	 *   on the stem of the unrelated `-a` feminine, `formát → form` ≡ `forma → form`. Without this pair every
	 *   configuration that eats `-át` roots scores its precision cost as zero.
	 */
	static final List<Lemma> CONFUSABLE_LEMMAS = List.of(
		new Lemma("cesta", List.of("cesta", "cesty", "cestě", "cestách")),
		new Lemma("český", List.of("český", "česká", "české", "českých", "čeští")),
		new Lemma("list", List.of("list", "listu", "listy")),
		new Lemma("líska", List.of("líska", "lísky", "lísce")),
		new Lemma("ruka", List.of("ruka", "ruky", "ruce")),
		new Lemma("rok", List.of("rok", "roku", "roky")),
		new Lemma("buk", List.of("buk", "buku", "buky")),
		new Lemma("bok", List.of("bok", "boku", "boky")),
		new Lemma("forma", List.of("forma", "formy", "formě")),
		new Lemma("formát", List.of("formát", "formátu", "formáty"))
	);

	private CzechAnalysisFixture() {
	}

}
