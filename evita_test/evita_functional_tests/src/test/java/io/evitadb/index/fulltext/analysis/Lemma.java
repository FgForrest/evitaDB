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
import java.text.Normalizer;
import java.util.List;

/**
 * One lemma of a language fixture together with the inflected forms it is measured through — the unit the
 * per-language `*AnalysisFixture`s are written in and {@link LanguageAnalyzerPairRecallTest} consumes.
 *
 * @param lemma dictionary form, used only for reporting
 * @param forms inflected forms
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
record Lemma(@Nonnull String lemma, @Nonnull List<String> forms) {

	/**
	 * What a user typing without their language's layout enters for `text`: NFD decomposition with the
	 * combining marks dropped. Correct for every language whose special letters are combining-mark
	 * compositions — which is all four of them except Polish, whose stroked `ł` NFD leaves alone; see
	 * {@link PolishAnalysisFixture#bareType(String)}.
	 *
	 * @param text accented text
	 * @return the same text as typed on a bare keyboard
	 */
	@Nonnull
	static String stripAccents(@Nonnull String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
	}

}
