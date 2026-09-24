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
 * The Polish vocabulary {@link LanguageAnalyzerPairRecallTest} measures the `polish`/`polish-search`
 * analyzer pair against — the Polish sibling of {@link CzechAnalysisFixture}, built to the same protocol.
 *
 * **Bare typing is language-specific here.** A Polish user without the Polish keyboard layout types `l` for
 * `ł`, but `ł` is a stroked letter that Unicode decomposition leaves alone — so this fixture passes its own
 * {@link #bareType(String)} instead of the default NFD stripping. Without it every `ł`-bearing
 * form would be silently excluded from the bare-typed metrics (its "bare typing" would equal itself), and the
 * `ł` class is precisely the one the survey flags as the biggest Polish fold hazard.
 *
 * **Sourcing.** No native Polish speaker was available when this fixture was authored; every inflected form
 * follows the standard declension/conjugation paradigms as published in WSJP/Wiktionary inflection tables,
 * and the fixture must get a native review before its numbers are treated as more than a first run. The
 * morphological classes present are deliberate:
 *
 * - the adjective genitive/locative plural `-ych`/`-ich` and instrumental `-ym`/`-im` — the classes a folded
 *   query loses today (evitaDB's `pl` chain has **no** fold lane at all);
 * - the `ł` past-tense family (`kupił`/`kupiła`/`kupili`/`kupiły`) — the benefit case of the folded `ł`
 *   endings, with `metal`/`metale`/`metali` as the in-vocabulary cost case they over-stem;
 * - `ą`/`ę` endings (`ręka`, `męski`, `dziecięcy`) and the `ó`↔`o` stem alternation (`stół`/`stołu`,
 *   `ogród`/`ogrodu`) — the class folding *repairs* for free, measured rather than asserted;
 * - epenthetic-`e` genitive plurals (`kurtek`, `krzeseł`, `łóżek`, `słuchawek`) — stem-internal alternations
 *   no suffix stripper converges, present so the convergence ceiling is measured;
 * - `ś`-spelled noun endings (`pierścionek`) and sibilant-final stems (`liść`) for the soft-consonant fold
 *   class.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class PolishAnalysisFixture {

	/**
	 * A Polish e-commerce vocabulary: 30 lemmas with the inflected forms an e-shop actually stores or is
	 * queried by. See the class javadoc for the morphological classes and their reasons.
	 */
	static final List<Lemma> VOCABULARY = List.of(
		new Lemma("żółty", List.of("żółty", "żółta", "żółte", "żółtych", "żółtym")),
		new Lemma("biały", List.of("biały", "biała", "białe", "białych")),
		new Lemma("czarny", List.of("czarny", "czarna", "czarne", "czarnych")),
		new Lemma("czerwony", List.of("czerwony", "czerwona", "czerwone", "czerwonych")),
		new Lemma("duży", List.of("duży", "duża", "duże", "dużych")),
		new Lemma("mały", List.of("mały", "mała", "małe", "małych")),
		new Lemma("męski", List.of("męski", "męska", "męskie", "męskich")),
		new Lemma("damski", List.of("damski", "damska", "damskie", "damskich")),
		new Lemma("dziecięcy", List.of("dziecięcy", "dziecięca", "dziecięce", "dziecięcych")),
		new Lemma("skórzany", List.of("skórzany", "skórzana", "skórzane", "skórzanych")),
		new Lemma("drewniany", List.of("drewniany", "drewniana", "drewniane", "drewnianych")),
		new Lemma("tani", List.of("tani", "tania", "tanie", "tanich")),
		new Lemma("stół", List.of("stół", "stołu", "stoły", "stołów")),
		new Lemma("krzesło", List.of("krzesło", "krzesła", "krzeseł")),
		new Lemma("koszula", List.of("koszula", "koszule", "koszul", "koszulach")),
		new Lemma("but", List.of("but", "buty", "butów", "butach")),
		new Lemma("kurtka", List.of("kurtka", "kurtki", "kurtek", "kurtkach")),
		new Lemma("spódnica", List.of("spódnica", "spódnicy", "spódnic")),
		new Lemma("zegarek", List.of("zegarek", "zegarka", "zegarki", "zegarków")),
		new Lemma("naszyjnik", List.of("naszyjnik", "naszyjnika", "naszyjniki", "naszyjników")),
		new Lemma("pierścionek", List.of("pierścionek", "pierścionka", "pierścionki")),
		new Lemma("słuchawki", List.of("słuchawki", "słuchawek", "słuchawkach")),
		new Lemma("komputer", List.of("komputer", "komputera", "komputery", "komputerów")),
		new Lemma("telefon", List.of("telefon", "telefonu", "telefony", "telefonów")),
		new Lemma("szafa", List.of("szafa", "szafy", "szaf", "szafach")),
		new Lemma("łóżko", List.of("łóżko", "łóżka", "łóżek")),
		new Lemma("ręka", List.of("ręka", "ręki", "ręce", "rękach")),
		new Lemma("ogród", List.of("ogród", "ogrodu", "ogrody")),
		new Lemma("prezent", List.of("prezent", "prezentu", "prezenty", "prezentów")),
		new Lemma("kupił", List.of("kupił", "kupiła", "kupili", "kupiły")),
		new Lemma("malowany", List.of("malowany", "malowana", "malowane")),
		new Lemma("metal", List.of("metal", "metale", "metali"))
	);

	/**
	 * Pairs of **unrelated** lemmas chosen because the fold-ambiguous ending groups of
	 * {@link FoldedPolishStemmer} — or folding itself — would collapse them onto one term:
	 *
	 * - `łoś`/`los` — the survey's own example: the two words differ **only** by the stroke, so their folded
	 *   surface forms are identical and every folded chain merges them. This pair measures the floor folding
	 *   itself pays, independent of any stemmer.
	 * - `skała`/`skala` — the same stroke-only collision in feminine declension, where the folded `ł` endings
	 *   additionally act on the folded forms (`skaly` matches the folded `ały`).
	 * - `pączek`/`paczka` — the `ą`-fold collision the plan names: bare-typed `paczki` is both the plural of
	 *   *paczka* and the bare typing of *pączki*.
	 * - `liść`/`lista` — the sibilant collapse probe: folded `lisc`/`liscie` sit one letter from `list`/
	 *   `liscie` (locative of *lista* is spelled `liście` too, but only the non-homograph forms are carried so
	 *   that any merge is the mechanism's doing, not an identical surface form).
	 */
	static final List<Lemma> CONFUSABLE_LEMMAS = List.of(
		new Lemma("łoś", List.of("łoś", "łosie")),
		new Lemma("los", List.of("los", "losy")),
		new Lemma("skała", List.of("skała", "skały", "skałach")),
		new Lemma("skala", List.of("skala", "skali")),
		new Lemma("pączek", List.of("pączek", "pączki")),
		new Lemma("paczka", List.of("paczka", "paczki", "paczek")),
		new Lemma("liść", List.of("liść", "liście")),
		new Lemma("lista", List.of("lista", "listy"))
	);

	private PolishAnalysisFixture() {
	}

	/**
	 * What a Polish user typing without the Polish layout enters for `text`: NFD stripping for the
	 * combining-mark letters (`ą`, `ę`, `ó`, `ś`, `ż`, `ź`, `ć`, `ń`) plus the `ł`→`l` mapping NFD cannot make.
	 *
	 * @param text accented text
	 * @return the same text as typed on a bare keyboard
	 */
	@Nonnull
	static String bareType(@Nonnull String text) {
		return Lemma.stripAccents(text).replace('ł', 'l').replace('Ł', 'L');
	}

}
