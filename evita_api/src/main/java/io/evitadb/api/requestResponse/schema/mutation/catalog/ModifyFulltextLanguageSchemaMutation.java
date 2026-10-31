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
import io.evitadb.api.requestResponse.schema.FulltextLanguageSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.FulltextLanguageSchema;
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
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Mutation replaces the whole definition of how text in one language is split into words in the catalog schema (see
 * `CatalogSchemaContract#getFulltextLanguages()`). The language must exist; to add one use
 * {@link CreateFulltextLanguageSchemaMutation}.
 *
 * Mutation implements {@link CombinableCatalogSchemaMutation}: in one pipeline it replaces an earlier modification
 * of the same language. It folds into an earlier {@link CreateFulltextLanguageSchemaMutation} of the language only when
 * the base schema does not hold the language - the creation then carries the final definition. When the base schema
 * holds it, that creation restates the existing definition and changes nothing, while folding the change into it
 * would turn it into a conflicting creation, so both stay.
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class ModifyFulltextLanguageSchemaMutation implements CombinableCatalogSchemaMutation, CatalogSchemaMutation {
	@Serial private static final long serialVersionUID = -958419184294091463L;
	/**
	 * The ISO 639 code of the language, as `Locale#getLanguage()` returns it.
	 */
	@Nonnull @Getter private final String language;
	/**
	 * The word splitting steps in execution order, the tokenizer first.
	 */
	@Nonnull @Getter private final AnalysisStep[] wordSplitting;

	/**
	 * Creates the mutation.
	 *
	 * @param language the ISO 639 code of the language, as `Locale#getLanguage()` returns it
	 * @param wordSplitting the word splitting steps in execution order, the tokenizer first
	 */
	public ModifyFulltextLanguageSchemaMutation(
		@Nonnull String language,
		@Nonnull AnalysisStep[] wordSplitting
	) {
		FulltextLanguageSchema.assertLanguageCode(language);
		this.language = language;
		Assert.notNull(wordSplitting, "Word splitting steps must not be null.");
		this.wordSplitting = wordSplitting.clone();
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull LocalCatalogSchemaMutation existingMutation
	) {
		if (existingMutation instanceof CreateFulltextLanguageSchemaMutation creation &&
			this.language.equals(creation.getLanguage())) {
			if (currentCatalogSchema.getFulltextLanguages().containsKey(this.language)) {
				// the creation of a language the base schema holds is a no-op when equal - folding this change
				// into it would turn it into a conflicting creation, so both stay
				return null;
			}
			// the language did not exist before the pipeline - it is created with the final definition
			return new MutationCombinationResult<>(
				new CreateFulltextLanguageSchemaMutation(this.language, this.wordSplitting)
			);
		} else if (existingMutation instanceof ModifyFulltextLanguageSchemaMutation modification &&
			this.language.equals(modification.getLanguage())) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
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
		final FulltextLanguageSchemaContract newSchema =
			FulltextLanguageSchema._internalBuild(this.language, Arrays.asList(this.wordSplitting));
		final Map<String, FulltextLanguageSchemaContract> current = catalogSchema.getFulltextLanguages();
		final FulltextLanguageSchemaContract existing = current.get(this.language);
		Assert.isTrue(
			existing != null,
			() -> new InvalidSchemaMutationException(
				"Fulltext language `" + this.language + "` does not exist in catalog `" + catalogSchema.getName() +
					"` - create it instead."
			)
		);
		if (existing.equals(newSchema)) {
			// nothing has changed - we can return the existing schema
			return new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
		}
		final Map<String, FulltextLanguageSchemaContract> updated = CollectionUtils.createHashMap(current.size() + 1);
		updated.putAll(current);
		updated.put(this.language, newSchema);
		return new CatalogSchemaWithImpactOnEntitySchemas(
			CatalogSchema._internalBuildWithFulltext(
				catalogSchema,
				updated,
				catalogSchema.getFulltextResources(),
				catalogSchema.getFulltextAnalyzers(),
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
		return "Modify fulltext language: " +
			"language=" + this.language + ", wordSplitting=" + Arrays.toString(this.wordSplitting);
	}

}
