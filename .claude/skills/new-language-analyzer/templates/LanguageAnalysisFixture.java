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
 * TEMPLATE — copy to `evita_test/evita_functional_tests/src/test/java/io/evitadb/index/fulltext/analysis/
 * {@code <Lang>AnalysisFixture.java}` and replace every `Language`. The fixture outlives the matrix: once the
 * language ships, {@link LanguageAnalyzerPairRecallTest} pins the production pair's numbers against it.
 *
 * The {@code Language} vocabulary the analysis matrix and, later, the recall test measure the language's
 * analyzer pair against — a sibling of {@link CzechAnalysisFixture}, built to the same protocol.
 *
 * **Sourcing.** State here whether a native speaker authored or reviewed the forms. When neither, say so and
 * name the reference every inflected form was checked against (an inflection-table site, a grammar); the
 * numbers stay "a first run" until a native review happens. Never write a form from memory.
 *
 * **Morphological classes present, and why** — a mechanism can only be measured against a failure it is given
 * the chance to commit, so list each class and the rule it exercises:
 *
 * - the endings users type without diacritics and the stemmer needs accented — the core of the bare-typed
 *   problem for this language;
 * - for every fold-ambiguous rule of the folded stemmer port: one class where the rule *helps* and one where
 *   it *costs* (a rule measured against a vocabulary that cannot exercise it scores zero for the wrong reason);
 * - stem-internal alternations no suffix stripper can converge, so the convergence ceiling is measured rather
 *   than assumed;
 * - the language's own classes with no Czech precedent — alternative orthographies or encodings of the same
 *   word, letters NFD stripping leaves alone, spelling variants the language allows.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
final class LanguageAnalysisFixture {

	/**
	 * An e-commerce vocabulary of roughly thirty lemmas with the inflected forms an e-shop actually stores or
	 * is queried by — colours, garments, furniture, electronics, and the store-vocabulary abstractions
	 * (quality, warranty, promotion). The domain is fixed so that false-merge floors stay comparable with the
	 * other languages' records.
	 */
	static final List<Lemma> VOCABULARY = List.of(
		// TEMPLATE: new Lemma("lemma", List.of("form1", "form2", "form3")),
		new Lemma("example", List.of("example", "examples"))
	);

	/**
	 * Pairs of **unrelated** lemmas chosen because the fold-ambiguous rules of the folded stemmer port would
	 * collapse them onto one term — found by inspecting the stemmer tables for collision-prone endings and then
	 * finding real word pairs that commit them (the `forma`/`formát` method of the Czech fixture). Each pair
	 * gets a sentence saying which rule it probes.
	 */
	static final List<Lemma> CONFUSABLE_LEMMAS = List.of(
		// TEMPLATE: new Lemma("unrelatedA", List.of(...)), new Lemma("unrelatedB", List.of(...)),
		new Lemma("form", List.of("form", "forms")),
		new Lemma("format", List.of("format", "formats"))
	);

	/**
	 * What a user typing without the language's keyboard layout enters for `text`. Delete this method and use
	 * {@link Lemma#stripAccents(String)} directly when every special letter of the language is a base letter
	 * plus a combining mark; keep it when some are not (Polish `ł`, Nordic `ø`, Croatian `đ`, German `ß`), and
	 * map each such letter to what a user actually types. Without the mapping a form's "bare typing" equals the
	 * form itself and the measurer skips it as vacuous — the whole letter class silently drops out.
	 *
	 * @param text accented text
	 * @return the same text as typed on a bare keyboard
	 */
	@Nonnull
	static String bareType(@Nonnull String text) {
		// TEMPLATE: return Lemma.stripAccents(text).replace('ł', 'l');
		return Lemma.stripAccents(text);
	}

	private LanguageAnalysisFixture() {
	}

}
