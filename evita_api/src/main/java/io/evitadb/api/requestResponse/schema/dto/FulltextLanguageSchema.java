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


package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.FulltextLanguageSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.List;
import java.util.Locale;

/**
 * Internal implementation of {@link FulltextLanguageSchemaContract}.
 */
@Immutable
@ThreadSafe
@EqualsAndHashCode
public final class FulltextLanguageSchema implements FulltextLanguageSchemaContract {
	@Serial private static final long serialVersionUID = 6084214263580925371L;

	/**
	 * The ISO 639 code of the language.
	 */
	@Nonnull private final String language;
	/**
	 * The splitting steps in execution order, the tokenizer first.
	 */
	@Nonnull private final List<AnalysisStep> wordSplitting;

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of the schema from a different
	 * package than the current one, but still internal code of the Evita ecosystem.
	 *
	 * Do not use this method from the client code!
	 *
	 * @param language      the ISO 639 code of the language, as {@link Locale#getLanguage()} returns it
	 * @param wordSplitting the splitting steps in execution order, the tokenizer first; never empty
	 * @return the schema
	 */
	@Nonnull
	public static FulltextLanguageSchema _internalBuild(
		@Nonnull String language,
		@Nonnull List<AnalysisStep> wordSplitting
	) {
		return new FulltextLanguageSchema(language, wordSplitting);
	}

	/**
	 * Checks that the code is a bare ISO 639 language code, as {@link Locale#getLanguage()} returns it - not a full
	 * language tag such as `cs-CZ`, and not an upper-case or blank one.
	 *
	 * @param language the code to check
	 * @throws InvalidSchemaMutationException when the code is not a bare language code
	 */
	public static void assertLanguageCode(@Nonnull String language) {
		Assert.notNull(language, "Fulltext language must not be null.");
		Assert.isTrue(
			!language.isBlank() && language.equals(Locale.forLanguageTag(language).getLanguage()),
			() -> new InvalidSchemaMutationException(
				"Fulltext language `" + language + "` must be a bare ISO 639 language code such as `cs`, " +
					"as `Locale#getLanguage()` returns it."
			)
		);
	}

	/**
	 * Creates the schema, validating it.
	 *
	 * @param language      the ISO 639 code of the language
	 * @param wordSplitting the splitting steps in execution order, the tokenizer first; never empty
	 */
	private FulltextLanguageSchema(@Nonnull String language, @Nonnull List<AnalysisStep> wordSplitting) {
		assertLanguageCode(language);
		Assert.notNull(wordSplitting, "Word splitting steps must not be null.");
		Assert.isTrue(
			!wordSplitting.isEmpty(),
			() -> new InvalidSchemaMutationException(
				"Fulltext language `" + language + "` must declare at least its tokenizer."
			)
		);
		this.language = language;
		this.wordSplitting = List.copyOf(wordSplitting);
	}

	@Nonnull
	@Override
	public String getLanguage() {
		return this.language;
	}

	@Nonnull
	@Override
	public List<AnalysisStep> getWordSplitting() {
		return this.wordSplitting;
	}

	@Nonnull
	@Override
	public String toString() {
		return "FulltextLanguageSchema{language='" + this.language + "', wordSplitting=" + this.wordSplitting + '}';
	}

}
