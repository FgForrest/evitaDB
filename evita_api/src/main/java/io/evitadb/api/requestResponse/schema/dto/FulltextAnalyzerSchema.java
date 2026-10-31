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
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Internal implementation of {@link FulltextAnalyzerSchemaContract}.
 */
@Immutable
@ThreadSafe
@EqualsAndHashCode
public final class FulltextAnalyzerSchema implements FulltextAnalyzerSchemaContract {
	@Serial private static final long serialVersionUID = 3307734689522519746L;

	/**
	 * The name searchable fields refer to the analyzer by.
	 */
	@Nonnull private final String name;
	/**
	 * The name of the analyzer this one is based on, or `null` when it is based on none.
	 */
	@Nullable private final String basedOn;
	/**
	 * The phases defined for every language.
	 */
	@Nonnull private final FulltextAnalysisPhases allLanguages;
	/**
	 * The per-language overrides keyed by ISO 639 language code, sorted by the code.
	 */
	@Nonnull private final Map<String, FulltextAnalysisPhases> languages;

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of the schema from a different
	 * package than the current one, but still internal code of the Evita ecosystem.
	 *
	 * Do not use this method from the client code!
	 *
	 * @param name         the name searchable fields refer to the analyzer by
	 * @param basedOn      the name of the analyzer this one is based on, or `null` when it is based on none
	 * @param allLanguages the phases defined for every language
	 * @param languages    the per-language overrides keyed by ISO 639 language code
	 * @return the schema
	 */
	@Nonnull
	public static FulltextAnalyzerSchema _internalBuild(
		@Nonnull String name,
		@Nullable String basedOn,
		@Nonnull FulltextAnalysisPhases allLanguages,
		@Nonnull Map<String, FulltextAnalysisPhases> languages
	) {
		return new FulltextAnalyzerSchema(name, basedOn, allLanguages, languages);
	}

	/**
	 * Creates the schema, validating what it can check on its own: the name, the language codes, and that an analyzer
	 * based on nothing defines at least one all-languages phase. References to other analyzers and to resources are
	 * checked by the catalog schema, which sees them all.
	 *
	 * @param name         the name searchable fields refer to the analyzer by, never blank
	 * @param basedOn      the name of the analyzer this one is based on, or `null` when it is based on none
	 * @param allLanguages the phases defined for every language
	 * @param languages    the per-language overrides keyed by ISO 639 language code
	 */
	private FulltextAnalyzerSchema(
		@Nonnull String name,
		@Nullable String basedOn,
		@Nonnull FulltextAnalysisPhases allLanguages,
		@Nonnull Map<String, FulltextAnalysisPhases> languages
	) {
		Assert.notNull(name, "Fulltext analyzer name must not be null.");
		Assert.isTrue(
			!name.isBlank(), () -> new InvalidSchemaMutationException("Fulltext analyzer name must not be blank.")
		);
		Assert.isTrue(
			basedOn == null || !basedOn.isBlank(),
			() -> new InvalidSchemaMutationException(
				"Fulltext analyzer `" + name + "` must not be based on a blank analyzer name."
			)
		);
		Assert.notNull(allLanguages, "All-languages phases must not be null.");
		Assert.notNull(languages, "Per-language phases must not be null.");
		Assert.isTrue(
			basedOn != null || !allLanguages.isUndefined(),
			() -> new InvalidSchemaMutationException(
				"Fulltext analyzer `" + name + "` is based on no other analyzer, so it must define its phases " +
					"for all languages - start it with `allLanguages(...)`, `text(...)` or `words(...)`."
			)
		);
		for (final String language : languages.keySet()) {
			FulltextLanguageSchema.assertLanguageCode(language);
		}
		this.name = name;
		this.basedOn = basedOn;
		this.allLanguages = allLanguages;
		this.languages = Collections.unmodifiableMap(new TreeMap<>(languages));
	}

	@Nonnull
	@Override
	public String getName() {
		return this.name;
	}

	@Nonnull
	@Override
	public Optional<String> getBasedOn() {
		return Optional.ofNullable(this.basedOn);
	}

	@Nonnull
	@Override
	public FulltextAnalysisPhases getAllLanguages() {
		return this.allLanguages;
	}

	@Nonnull
	@Override
	public Map<String, FulltextAnalysisPhases> getLanguages() {
		return this.languages;
	}

	@Nonnull
	@Override
	public String toString() {
		return "FulltextAnalyzerSchema{name='" + this.name + "', basedOn=" + this.basedOn +
			", allLanguages=" + this.allLanguages + ", languages=" + this.languages + '}';
	}

}
