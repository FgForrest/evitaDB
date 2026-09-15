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
 * The Romanian vocabulary {@link LanguageAnalyzerPairRecallTest} measures the `romanian`/`romanian-search`
 * analyzer pair against — the Romanian sibling of {@link CzechAnalysisFixture}, built to the same protocol.
 *
 * **Sourcing.** No native Romanian speaker was available when this fixture was authored; every inflected form
 * follows the standard declension/conjugation paradigms as published in DEX-derived inflection tables
 * (dexonline.ro is the reference to verify against) rather than native recall, and the fixture must get a
 * native review before its numbers are treated as more than a first run — see the vocabulary protocol in the
 * SK/PL/RO measurement record. The morphological classes present are deliberate, because a mechanism can only
 * be measured against a failure it is given the chance to commit:
 *
 * - the feminine singular in `-ă` and its bare typing (`mașină`/`masina`) — the Romanian core of the bare-typed
 *   problem, the analogue of the Czech `-ých` classes;
 * - the `-esc`/`-ească`/`-ești` adjective paradigm (`bărbătesc`) — its plural is spelled with `ș` and probes
 *   the `ş`-verb-ending fold ambiguity from the noun-adjective side;
 * - the `-ate`/`-ăți`/`-ății` abstract-noun family (`calitate`) the survey names, plus `-ție`/`-ții` nouns
 *   (`garanție`, `promoție`);
 * - participles in `-at`/`-ată`/`-ați` and `-it`/`-ită` (`lucrat`, `vopsit`);
 * - stem-internal vowel alternations the suffix tables cannot converge (`masă`/`mese`, `cană`/`căni`,
 *   `cămașă`/`cămăși`, `cască`/`căști`) — the Romanian analogue of Czech `stůl`/`stolů`, present so that the
 *   convergence ceiling of every suffix-stripping mechanism is measured rather than assumed;
 * - **cedilla-vs-comma encoding probes**: `mașină`/`garanție` are carried in both the comma-below spelling
 *   (`ș` U+0219, `ț` U+021B — the correct one) and the legacy cedilla spelling (`ş` U+015F, `ţ` U+0163 — the
 *   one the Lucene 9.12.3 Snowball tables and stop list are written in). Real Romanian corpora contain both,
 *   which is why Lucene 10 gained a normalization filter; the pinned Lucene has none, and these forms measure
 *   what that costs. This class has no Czech precedent;
 * - `chestiune` — a genuine `-tiune` (plain `t`) word, the cost probe of the folded `ţiune`→`t` rewrite;
 * - the verb `cumpăra` with its `-ăm` form, giving the folded-`am` ambiguity something to fire on.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class RomanianAnalysisFixture {

	/**
	 * A Romanian e-commerce vocabulary: 31 lemmas with the inflected forms an e-shop actually stores or is
	 * queried by — colors, garments, furniture, electronics, and the store-vocabulary abstractions
	 * (`calitate`, `garanție`, `promoție`). See the class javadoc for the morphological classes and their
	 * reasons.
	 */
	static final List<Lemma> VOCABULARY = List.of(
		new Lemma("negru", List.of("negru", "neagră", "negri", "negre")),
		new Lemma("alb", List.of("alb", "albă", "albi", "albe")),
		new Lemma("galben", List.of("galben", "galbenă", "galbeni", "galbene")),
		new Lemma("roșu", List.of("roșu", "roșie", "roșii")),
		new Lemma("albastru", List.of("albastru", "albastră", "albaștri", "albastre")),
		new Lemma("verde", List.of("verde", "verzi")),
		new Lemma("ieftin", List.of("ieftin", "ieftină", "ieftine")),
		new Lemma("bărbătesc", List.of("bărbătesc", "bărbătească", "bărbătești")),
		new Lemma("femeiesc", List.of("femeiesc", "femeiască", "femeiești")),
		new Lemma("lucrat", List.of("lucrat", "lucrată", "lucrate", "lucrați")),
		new Lemma("vopsit", List.of("vopsit", "vopsită", "vopsite")),
		// the comma-below and cedilla spellings of one word are deliberately both present - the encoding probe
		new Lemma("mașină", List.of("mașină", "mașina", "mașini", "mașinile", "maşină", "maşini")),
		new Lemma("masă", List.of("masă", "masa", "mese", "mesele")),
		new Lemma("cămașă", List.of("cămașă", "cămăși")),
		new Lemma("pantof", List.of("pantof", "pantofi", "pantofii")),
		new Lemma("rochie", List.of("rochie", "rochia", "rochii")),
		new Lemma("scaun", List.of("scaun", "scaune", "scaunele")),
		new Lemma("dulap", List.of("dulap", "dulapuri")),
		new Lemma("cană", List.of("cană", "căni")),
		new Lemma("ceas", List.of("ceas", "ceasuri")),
		new Lemma("brățară", List.of("brățară", "brățări")),
		new Lemma("cercel", List.of("cercel", "cercei")),
		new Lemma("inel", List.of("inel", "inele", "inelul")),
		new Lemma("bucătărie", List.of("bucătărie", "bucătăria", "bucătării")),
		new Lemma("grădină", List.of("grădină", "grădini")),
		new Lemma("telefon", List.of("telefon", "telefonul", "telefoane")),
		new Lemma("calculator", List.of("calculator", "calculatorul", "calculatoare")),
		new Lemma("cască", List.of("cască", "căști")),
		new Lemma("calitate", List.of("calitate", "calitatea", "calități", "calității")),
		new Lemma("garanție", List.of("garanție", "garanția", "garanții", "garanţie")),
		new Lemma("promoție", List.of("promoție", "promoția", "promoții")),
		new Lemma("chestiune", List.of("chestiune", "chestiuni")),
		new Lemma("cumpăra", List.of("cumpăra", "cumpără", "cumpărăm"))
	);

	/**
	 * Pairs of **unrelated** lemmas chosen because the fold-ambiguous rules of {@link FoldedRomanianStemmer}
	 * would collapse them onto one term — chosen by inspecting the stemmer tables for collision-prone endings
	 * and finding real word pairs that commit them, the `forma`/`formát` method of the Czech fixture:
	 *
	 * - `formă`/`format` — the direct Romanian analogue of the Czech pair: the feminine noun stems to `form`
	 *   through the final-vowel rule while the masculine `format` carries a genuine `-at` the standard-suffix
	 *   table wants to eat wherever R2 lets it.
	 * - `vestă`/`poveste` — probes the `ş`-verb-ending fold: with those endings included, folded `veste`
	 *   (indefinite plural of *vestă*, a garment) matches the `eşte` verb ending and loses it, landing next to
	 *   whatever *poveste* stems to.
	 * - `copil`/`copie` — both stem toward `copi` under vowel stripping (`copil`→pl. `copii`, `copie`→pl.
	 *   `copii` are genuine homographs; the fixture carries only the non-homograph forms so that the merge is
	 *   the stemmer's doing, not an identical surface form).
	 */
	static final List<Lemma> CONFUSABLE_LEMMAS = List.of(
		new Lemma("formă", List.of("formă", "forma", "forme")),
		new Lemma("format", List.of("format", "formate", "formatul")),
		new Lemma("vestă", List.of("vestă", "vesta", "veste")),
		new Lemma("poveste", List.of("poveste", "povestea", "povești")),
		new Lemma("copil", List.of("copil", "copilul")),
		new Lemma("copie", List.of("copie", "copia"))
	);

	private RomanianAnalysisFixture() {
	}

}
