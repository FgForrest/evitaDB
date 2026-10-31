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
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Mutation adds a named fulltext analyzer to the catalog schema (see
 * `CatalogSchemaContract#getFulltextAnalyzers()`). Applying it again with the same definition changes nothing; an
 * analyzer of the same name with a different definition is refused - use
 * {@link ModifyFulltextAnalyzerSchemaMutation} to change it.
 *
 * Mutation implements {@link CombinableCatalogSchemaMutation}, but absorbs no earlier mutation: a removal of the
 * same analyzer before it must still run first. A later {@link RemoveFulltextAnalyzerSchemaMutation} of the analyzer
 * absorbs it, and so does a later {@link ModifyFulltextAnalyzerSchemaMutation} when the base schema does not hold the
 * analyzer.
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class CreateFulltextAnalyzerSchemaMutation implements CombinableCatalogSchemaMutation, CatalogSchemaMutation {
	@Serial private static final long serialVersionUID = -705613773270998989L;
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
	 * Creates the mutation.
	 *
	 * @param name the name searchable fields refer to the analyzer by
	 * @param basedOn the name of the analyzer this one is based on, or `null` when it is based on none
	 * @param allLanguages the phases defined for every language
	 * @param languages the per-language overrides keyed by ISO 639 language code
	 */
	public CreateFulltextAnalyzerSchemaMutation(
		@Nonnull String name,
		@Nullable String basedOn,
		@Nonnull FulltextAnalysisPhases allLanguages,
		@Nonnull Map<String, FulltextAnalysisPhases> languages
	) {
		Assert.notNull(name, "Fulltext analyzer name must not be null.");
		this.name = name;
		Assert.notNull(allLanguages, "All-languages phases must not be null.");
		Assert.notNull(languages, "Per-language phases must not be null.");
		this.basedOn = basedOn;
		this.allLanguages = allLanguages;
		this.languages = Collections.unmodifiableMap(new TreeMap<>(languages));
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull LocalCatalogSchemaMutation existingMutation
	) {
		// a creation follows nothing it could absorb: a removal before it must still run first
		return null;
	}

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
		Assert.isTrue(
			!FulltextAnalyzerSchemaContract.BUILT_IN_ANALYZERS.contains(this.name),
			() -> new InvalidSchemaMutationException(
				"Fulltext analyzer `" + this.name + "` cannot be declared: the name belongs to a built-in analyzer."
			)
		);
		final FulltextAnalyzerSchemaContract newSchema =
			FulltextAnalyzerSchema._internalBuild(this.name, this.basedOn, this.allLanguages, this.languages);
		final Map<String, FulltextAnalyzerSchemaContract> current = catalogSchema.getFulltextAnalyzers();
		final FulltextAnalyzerSchemaContract existing = current.get(this.name);
		if (existing != null) {
			Assert.isTrue(
				existing.equals(newSchema),
				() -> new InvalidSchemaMutationException(
					"Fulltext analyzer `" + this.name + "` already exists in catalog `" + catalogSchema.getName() +
						"` with a different definition - modify it instead."
				)
			);
			// the same definition - we can return the existing schema
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

	@Nonnull
	@Override
	public String toString() {
		return "Create fulltext analyzer: " +
			"name=" + this.name + ", basedOn=" + this.basedOn +
				", allLanguages=" + this.allLanguages + ", languages=" + this.languages;
	}

}
