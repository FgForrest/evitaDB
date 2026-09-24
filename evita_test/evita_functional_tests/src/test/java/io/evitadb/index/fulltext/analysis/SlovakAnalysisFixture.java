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
 * The Slovak vocabulary {@link LanguageAnalyzerPairRecallTest} measures the `slovak`/`slovak-search`
 * analyzer pair against — the Slovak sibling of {@link CzechAnalysisFixture}, built to the same protocol. Unlike the Romanian and Polish fixtures this one validates a **from-scratch stemmer's tables**
 * ({@link SlovakStemmer}) as much as the mechanisms, so its classes map one to one onto the table entries; the
 * plan expects the tables and this fixture to co-evolve over multiple runs.
 *
 * The morphological classes present are deliberate:
 *
 * - adjective plurals in both **rhythmic-law variants**: `žltý` takes the long `-ých`, while `čierny` — whose
 *   stem syllable carries the long diphthong `ie` — takes the shortened `-ych`. Both spellings are correct
 *   Slovak, decided by the preceding syllable; the fixture carries both classes so that both accented table
 *   variants are exercised, and so that folding's merge of the two is measured;
 * - the `ô` alternation (`stôl`/`stola`) — the analogue of Czech `stůl`/`stolů`, which only the `ô`→`o`
 *   rewrite (or folding itself) converges;
 * - epenthetic `-ok`/`-ek` masculines (`náramok`/`náramku`, `darček`/`darčeka`) for the epenthesis rule;
 * - the masculine-animate `k`→`c` alternation (`zákazník`/`zákazníci`) for the `c`→`k` rewrite;
 * - gen-pl **stem lengthenings** (`stoličiek`, `košieľ`, `skríň`, `hodiniek`, `slúchadiel`) — stem-internal
 *   alternations no suffix stripper converges, present so the convergence ceiling is measured rather than
 *   assumed;
 * - soft feminines (`košeľa`, `skriňa`) with `-iach` locatives, and `ľ`/`ň` stem letters for the fold lane;
 * - **the three classes the sk_SK lexicon sweep exposed** (added by run 4 — the first three runs scored a
 *   vocabulary that could not commit these failures): `vitamín` for the `-ín`-vs-possessive-`in` collision
 *   that led to removing the `in` possessive, `chróm` for the `-óm`-vs-ending-`om` fold ambiguity, and
 *   `kategória` for the `i`-stem-vs-soft-plural collision (dissolved by the stem-final-`i` trim rather than a
 *   switch), `bariéra` for the `ié`-loanword-vs-`ie`-shortening ambiguity, and `hypotéka` for the
 *   `-téka`-vs-`ek`-epenthesis ambiguity.
 *
 * **Sourcing.** The forms follow the standard Slovak declension paradigms (chlap/dub/žena/ulica/mesto pattern
 * tables; juls.savba.sk is the reference to verify against). Native review is realistic for Slovak (FG serves
 * Slovak-market shops) and should happen before the numbers are treated as final — see the measurement record.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class SlovakAnalysisFixture {

	/**
	 * A Slovak e-commerce vocabulary: 31 lemmas with the inflected forms an e-shop actually stores or is
	 * queried by. See the class javadoc for the morphological classes and their reasons.
	 */
	static final List<Lemma> VOCABULARY = List.of(
		new Lemma("čierny", List.of("čierny", "čierna", "čierne", "čiernych", "čiernym")),
		new Lemma("biely", List.of("biely", "biela", "biele", "bielych")),
		new Lemma("žltý", List.of("žltý", "žltá", "žlté", "žltých")),
		new Lemma("dámsky", List.of("dámsky", "dámska", "dámske", "dámskych")),
		new Lemma("pánsky", List.of("pánsky", "pánska", "pánske", "pánskych")),
		new Lemma("detský", List.of("detský", "detská", "detské", "detských")),
		new Lemma("kožený", List.of("kožený", "kožená", "kožené", "kožených")),
		new Lemma("drevený", List.of("drevený", "drevená", "drevené", "drevených")),
		new Lemma("strieborný", List.of("strieborný", "strieborná", "strieborné", "strieborných")),
		new Lemma("kuchynský", List.of("kuchynský", "kuchynská", "kuchynské", "kuchynských")),
		new Lemma("anglický", List.of("anglický", "anglická", "anglické", "anglických", "anglickí")),
		new Lemma("veľký", List.of("veľký", "veľká", "veľké", "veľkých")),
		new Lemma("malý", List.of("malý", "malá", "malé", "malých")),
		new Lemma("krásny", List.of("krásny", "krásna", "krásne", "krásnych")),
		new Lemma("záhradný", List.of("záhradný", "záhradná", "záhradné", "záhradných")),
		new Lemma("stôl", List.of("stôl", "stola", "stoly", "stolov")),
		new Lemma("stolička", List.of("stolička", "stoličky", "stoličiek", "stoličkách")),
		new Lemma("tričko", List.of("tričko", "trička", "tričkom")),
		new Lemma("košeľa", List.of("košeľa", "košele", "košieľ", "košeliach")),
		new Lemma("topánka", List.of("topánka", "topánky", "topánok", "topánkach")),
		new Lemma("kabát", List.of("kabát", "kabátu", "kabáty", "kabátov")),
		new Lemma("mikina", List.of("mikina", "mikine", "mikinách")),
		new Lemma("náramok", List.of("náramok", "náramku", "náramky", "náramkov")),
		new Lemma("prívesok", List.of("prívesok", "prívesku", "prívesky", "príveskov")),
		new Lemma("slúchadlá", List.of("slúchadlá", "slúchadiel", "slúchadlám")),
		new Lemma("počítač", List.of("počítač", "počítača", "počítače", "počítačov")),
		new Lemma("nábytok", List.of("nábytok", "nábytku", "nábytkom")),
		new Lemma("skriňa", List.of("skriňa", "skrine", "skríň", "skriniach")),
		new Lemma("darček", List.of("darček", "darčeka", "darčeky", "darčekov")),
		new Lemma("koža", List.of("koža", "kože", "kožou")),
		new Lemma("hodinky", List.of("hodinky", "hodiniek", "hodinkách")),
		new Lemma("zákazník", List.of("zákazník", "zákazníka", "zákazníci")),
		new Lemma("vitamín", List.of("vitamín", "vitamínu", "vitamíny")),
		new Lemma("chróm", List.of("chróm", "chrómu")),
		new Lemma("kategória", List.of("kategória", "kategórie", "kategóriám", "kategóriách")),
		new Lemma("bariéra", List.of("bariéra", "bariéry", "bariér")),
		new Lemma("hypotéka", List.of("hypotéka", "hypotéky", "hypoték"))
	);

	/**
	 * Pairs of **unrelated** lemmas that probe the rules a Slovak stemmer might get wrong — chosen from the
	 * Czech record's confusables wherever the same words exist in Slovak, so the two languages' floors stay
	 * comparable:
	 *
	 * - `ruka`/`rok` and `buk`/`bok` — in Czech these probed the `ů`→`o` vowel shift, which a folded Czech
	 *   stemmer commits blindly (`ruk`→`rok`). The Slovak stemmer has no such rule — the `ô` alternation folds
	 *   onto its own alternant — so these pairs measure that the hazard is genuinely absent, not merely
	 *   unexercised. `rok` doubles as the length-guard probe of the epenthetic `-ok` rule.
	 * - `cesta`/`český` — the Czech palatalization probe. Slovak declension has no `št`→`sk` rewrite
	 *   (`českí`, not `*čeští`), so no configuration should merge these.
	 * - `forma`/`formát` — the Czech `-át` probe. The Slovak tables deliberately omit the `-at-` neuter
	 *   entries, so no configuration should truncate `formát` onto `forma`'s stem.
	 */
	static final List<Lemma> CONFUSABLE_LEMMAS = List.of(
		new Lemma("ruka", List.of("ruka", "ruky", "ruke")),
		new Lemma("rok", List.of("rok", "roku", "roky")),
		new Lemma("buk", List.of("buk", "buku", "buky")),
		new Lemma("bok", List.of("bok", "boku", "boky")),
		new Lemma("cesta", List.of("cesta", "cesty", "ceste")),
		new Lemma("český", List.of("český", "česká", "české", "českých", "českí")),
		new Lemma("forma", List.of("forma", "formy", "forme")),
		new Lemma("formát", List.of("formát", "formátu", "formáty"))
	);

	private SlovakAnalysisFixture() {
	}

}
