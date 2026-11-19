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
package io.evitadb.api.requestResponse.schema.analysis;

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;

/**
 * The two phases of a fulltext analyzer as one analyzer defines them, either for every language or for one.
 *
 * - the **text phase** changes characters before the text is split into words (`html-strip`, `remove-characters`),
 * - the **words phase** changes each word after splitting (`lowercase`, `stop-words`, the stemming steps, ...).
 *
 * Splitting itself belongs to the language, not to an analyzer. A phase that is `null` is not defined here: it is
 * taken from the analyzer's all-languages phases, then from the analyzer it is based on, and an analyzer based on
 * nothing analyzes an undefined phase with no steps. An empty list is a defined phase with no steps, which overrides.
 *
 * @param text  steps of the text phase in execution order, or `null` when this definition leaves the phase undefined
 * @param words steps of the words phase in execution order, or `null` when this definition leaves the phase undefined
 */
public record FulltextAnalysisPhases(
	@Nullable List<AnalysisStep> text,
	@Nullable List<AnalysisStep> words
) implements Serializable {
	/**
	 * Phases that define nothing.
	 */
	public static final FulltextAnalysisPhases UNDEFINED = new FulltextAnalysisPhases(null, null);

	/**
	 * Validates the steps and keeps immutable copies of the defined phases.
	 *
	 * @param text  steps of the text phase in execution order, or `null` when undefined
	 * @param words steps of the words phase in execution order, or `null` when undefined
	 */
	public FulltextAnalysisPhases {
		text = copy(text, "text");
		words = copy(words, "words");
	}

	/**
	 * Returns phases with the text phase replaced and the words phase kept.
	 *
	 * @param steps the new steps of the text phase in execution order
	 * @return new phases
	 */
	@Nonnull
	public FulltextAnalysisPhases withText(@Nonnull List<AnalysisStep> steps) {
		return new FulltextAnalysisPhases(steps, this.words);
	}

	/**
	 * Returns phases with the words phase replaced and the text phase kept.
	 *
	 * @param steps the new steps of the words phase in execution order
	 * @return new phases
	 */
	@Nonnull
	public FulltextAnalysisPhases withWords(@Nonnull List<AnalysisStep> steps) {
		return new FulltextAnalysisPhases(this.text, steps);
	}

	/**
	 * Returns true when neither phase is defined.
	 *
	 * @return true when both phases are `null`
	 */
	public boolean isUndefined() {
		return this.text == null && this.words == null;
	}

	/**
	 * Validates the steps of one phase and copies them into an immutable list.
	 *
	 * @param steps the steps of the phase, or `null` when the phase is undefined
	 * @param phase the name of the phase, for the error message
	 * @return an immutable copy of the steps, or `null` when the phase is undefined
	 */
	@Nullable
	private static List<AnalysisStep> copy(@Nullable List<AnalysisStep> steps, @Nonnull String phase) {
		if (steps == null) {
			return null;
		}
		for (final AnalysisStep step : steps) {
			Assert.notNull(step, "A step of the " + phase + " phase must not be null.");
		}
		return List.copyOf(steps);
	}

}
