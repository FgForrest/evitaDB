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


package io.evitadb.api.requestResponse.schema.mutation.catalog;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.CatalogConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.FulltextAnalyzerSchema;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Mutation replaces the whole definition of a named fulltext analyzer in the catalog schema (see
 * `CatalogSchemaContract#getFulltextAnalyzers()`). The analyzer must exist; to add one use
 * {@link CreateFulltextAnalyzerSchemaMutation}.
 *
 * Mutation implements {@link CombinableCatalogSchemaMutation}: in one pipeline it replaces an earlier modification
 * of the same analyzer. It folds into an earlier {@link CreateFulltextAnalyzerSchemaMutation} of the analyzer only when
 * the base schema does not hold the analyzer - the creation then carries the final definition. When the base schema
 * holds it, that creation restates the existing definition and changes nothing, while folding the change into it
 * would turn it into a conflicting creation, so both stay.
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class ModifyFulltextAnalyzerSchemaMutation implements CombinableCatalogSchemaMutation, CatalogSchemaMutation {
	@Serial private static final long serialVersionUID = -3168850905111019401L;
	/**
	 * The name searchable fields refer to the analyzer by.
	 */
	@Nonnull @Getter private final String name;
	/**
	 * The name of the analyzer this one is based on, or `null` when it is based on none.
	 */
	@Nullable @Getter private final String basedOn;
	/**
	 * The phases defined for every language.
	 */
	@Nonnull @Getter private final FulltextAnalysisPhases allLanguages;
	/**
	 * The per-language overrides keyed by ISO 639 language code.
	 */
	@Nonnull @Getter private final Map<String, FulltextAnalysisPhases> languages;

	/**
	 * The author's samples, independent of the base analyzer, in declaration order.
	 */
	@Nonnull @Getter private final List<String> samples;

	/**
	 * Creates the mutation.
	 *
	 * @param name the name searchable fields refer to the analyzer by
	 * @param basedOn the name of the analyzer this one is based on, or `null` when it is based on none
	 * @param allLanguages the phases defined for every language
	 * @param languages the per-language overrides keyed by ISO 639 language code
	 * @param samples author samples in declaration order; duplicates keep their first occurrence
	 * @throws InvalidSchemaMutationException when the list or a sample is `null`, a sample is blank or longer than
	 * {@link FulltextAnalyzerSchemaContract#MAX_SAMPLE_LENGTH} UTF-16 code units, or there are more than
	 * {@link FulltextAnalyzerSchemaContract#MAX_SAMPLES} distinct samples
	 */
	public ModifyFulltextAnalyzerSchemaMutation(
		@Nonnull String name,
		@Nullable String basedOn,
		@Nonnull FulltextAnalysisPhases allLanguages,
		@Nonnull Map<String, FulltextAnalysisPhases> languages,
		@Nonnull List<String> samples
	) {
		Assert.notNull(name, "Fulltext analyzer name must not be null.");
		this.name = name;
		Assert.notNull(allLanguages, "All-languages phases must not be null.");
		Assert.notNull(languages, "Per-language phases must not be null.");
		this.basedOn = basedOn;
		this.allLanguages = allLanguages;
		this.languages = Collections.unmodifiableMap(new TreeMap<>(languages));
		this.samples = FulltextAnalyzerSchema._internalNormalizeSamples(samples);
	}

	/**
	 * Absorbs an earlier modification of this analyzer, and folds this complete definition, samples included, into an
	 * earlier creation of the analyzer when the base catalog does not hold it - see the class documentation.
	 *
	 * @param currentCatalogSchema the base catalog before the mutation pipeline
	 * @param existingMutation the preceding mutation to consider absorbing
	 * @return the combination, or `null` when both mutations stay
	 */
	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull LocalCatalogSchemaMutation existingMutation
	) {
		if (existingMutation instanceof CreateFulltextAnalyzerSchemaMutation creation &&
			this.name.equals(creation.getName())) {
			if (currentCatalogSchema.getFulltextAnalyzers().containsKey(this.name)) {
				// the creation of an analyzer the base schema holds is a no-op when equal - folding this change
				// into it would turn it into a conflicting creation, so both stay
				return null;
			}
			// the analyzer did not exist before the pipeline - it is created with the final definition
			return new MutationCombinationResult<>(
				new CreateFulltextAnalyzerSchemaMutation(
					this.name, this.basedOn, this.allLanguages, this.languages, this.samples
				)
			);
		} else if (existingMutation instanceof ModifyFulltextAnalyzerSchemaMutation modification &&
			this.name.equals(modification.getName())) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
	}

	/**
	 * Applies the complete analyzer declaration, including its samples.
	 *
	 * @param catalogSchema the catalog being changed
	 * @param entitySchemaAccessor access to the catalog's entity schemas
	 * @return the changed catalog, or the existing instance when the declaration is unchanged
	 * @throws InvalidSchemaMutationException when the catalog does not declare the analyzer
	 */
	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaProvider entitySchemaAccessor
	) {
		Assert.notNull(
			catalogSchema,
			() -> new InvalidSchemaMutationException("Catalog doesn't exist!")
		);
		final FulltextAnalyzerSchemaContract newSchema =
			FulltextAnalyzerSchema._internalBuild(
				this.name, this.basedOn, this.allLanguages, this.languages, this.samples
			);
		final Map<String, FulltextAnalyzerSchemaContract> current = catalogSchema.getFulltextAnalyzers();
		final FulltextAnalyzerSchemaContract existing = current.get(this.name);
		Assert.isTrue(
			existing != null,
			() -> new InvalidSchemaMutationException(
				"Fulltext analyzer `" + this.name + "` does not exist in catalog `" + catalogSchema.getName() +
					"` - create it instead."
			)
		);
		if (existing.equals(newSchema)) {
			// nothing has changed - we can return the existing schema
			return new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
		}
		final Map<String, FulltextAnalyzerSchemaContract> updated = CollectionUtils.createHashMap(current.size() + 1);
		updated.putAll(current);
		updated.put(this.name, newSchema);
		return new CatalogSchemaWithImpactOnEntitySchemas(
			CatalogSchema._internalBuildWithFulltext(
				catalogSchema,
				catalogSchema.getFulltextLanguages(),
				catalogSchema.getFulltextResources(),
				updated,
				entitySchemaAccessor
			)
		);
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Nonnull
	@Override
	public Stream<ConflictKey> collectConflictKeys(@Nonnull ConflictGenerationContext context) {
		return Stream.of(new CatalogConflictKey(context.getCatalogName()));
	}

	/**
	 * Describes the complete declaration carried by this mutation.
	 *
	 * @return the analyzer members, including its ordered author samples
	 */
	@Nonnull
	@Override
	public String toString() {
		return "Modify fulltext analyzer: " +
			"name=" + this.name + ", basedOn=" + this.basedOn +
				", allLanguages=" + this.allLanguages + ", languages=" + this.languages + ", samples=" + this.samples;
	}

}
