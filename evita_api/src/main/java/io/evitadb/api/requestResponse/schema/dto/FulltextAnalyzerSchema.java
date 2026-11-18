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
import io.evitadb.utils.CollectionUtils;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
	 * The author's samples in declaration order, independent of the base analyzer.
	 */
	@Nonnull private final List<String> samples;

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
	 * @param samples      author samples, independent of the base analyzer; validated and deduplicated by
	 *                     {@link #_internalNormalizeSamples(List)}
	 * @return the schema
	 * @throws InvalidSchemaMutationException when {@link #_internalNormalizeSamples(List)} refuses the samples, or
	 *                                        another member is invalid
	 */
	@Nonnull
	public static FulltextAnalyzerSchema _internalBuild(
		@Nonnull String name,
		@Nullable String basedOn,
		@Nonnull FulltextAnalysisPhases allLanguages,
		@Nonnull Map<String, FulltextAnalysisPhases> languages,
		@Nonnull List<String> samples
	) {
		return new FulltextAnalyzerSchema(name, basedOn, allLanguages, languages, samples);
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
	 * @param samples      author samples, independent of the base analyzer; validated and deduplicated by
	 *                     {@link #_internalNormalizeSamples(List)}
	 */
	private FulltextAnalyzerSchema(
		@Nonnull String name,
		@Nullable String basedOn,
		@Nonnull FulltextAnalysisPhases allLanguages,
		@Nonnull Map<String, FulltextAnalysisPhases> languages,
		@Nonnull List<String> samples
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
		this.samples = _internalNormalizeSamples(samples);
	}

	/**
	 * Validates and copies author samples. The declaration, its builder and both analyzer mutations take their
	 * samples through this method, so all of them accept and refuse exactly the same lists.
	 *
	 * @param samples the samples in declaration order
	 * @return an immutable list keeping the first occurrence of each sample, in declaration order
	 * @throws InvalidSchemaMutationException when the list or a sample is `null`, a sample is blank or longer than
	 * {@link FulltextAnalyzerSchemaContract#MAX_SAMPLE_LENGTH} UTF-16 code units, or the list holds more than
	 * {@link FulltextAnalyzerSchemaContract#MAX_SAMPLES} distinct samples
	 */
	@Nonnull
	public static List<String> _internalNormalizeSamples(@Nonnull List<String> samples) {
		Assert.isTrue(
			samples != null,
			() -> new InvalidSchemaMutationException("Fulltext analyzer samples must not be null.")
		);
		final Set<String> distinct = CollectionUtils.createLinkedHashSet(MAX_SAMPLES);
		for (final String sample : samples) {
			Assert.isTrue(
				sample != null && !sample.isBlank(),
				() -> new InvalidSchemaMutationException("Fulltext analyzer samples must not be null or blank.")
			);
			Assert.isTrue(
				sample.length() <= MAX_SAMPLE_LENGTH,
				() -> new InvalidSchemaMutationException(
					"Fulltext analyzer samples must not exceed " + MAX_SAMPLE_LENGTH + " UTF-16 code units."
				)
			);
			distinct.add(sample);
			Assert.isTrue(
				distinct.size() <= MAX_SAMPLES,
				() -> new InvalidSchemaMutationException(
					"Fulltext analyzers must not have more than " + MAX_SAMPLES + " distinct samples."
				)
			);
		}
		return List.copyOf(distinct);
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

	/**
	 * {@inheritDoc}
	 *
	 * @return the immutable samples of this declaration
	 */
	@Nonnull
	@Override
	public List<String> getSamples() {
		return this.samples;
	}

	/**
	 * Describes this declaration, including its author samples.
	 *
	 * @return the declaration's member values
	 */
	@Nonnull
	@Override
	public String toString() {
		return "FulltextAnalyzerSchema{name='" + this.name + "', basedOn=" + this.basedOn +
			", allLanguages=" + this.allLanguages + ", languages=" + this.languages + ", samples=" + this.samples + '}';
	}

}
