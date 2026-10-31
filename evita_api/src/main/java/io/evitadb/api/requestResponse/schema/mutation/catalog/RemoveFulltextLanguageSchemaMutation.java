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
import java.util.Map;
import java.util.stream.Stream;

/**
 * Mutation removes how text in one language is split into words from the catalog schema (see
 * `CatalogSchemaContract#getFulltextLanguages()`). Removing a language that does not exist changes nothing.
 *
 * Mutation implements {@link CombinableCatalogSchemaMutation}: it drops every earlier creation, modification or
 * removal of the same language in one pipeline, which the removal makes pointless, and stays in the pipeline itself.
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class RemoveFulltextLanguageSchemaMutation implements CombinableCatalogSchemaMutation, CatalogSchemaMutation {
	@Serial private static final long serialVersionUID = -4601237455671198494L;
	/**
	 * The ISO 639 code of the language, as `Locale#getLanguage()` returns it.
	 */
	@Nonnull @Getter private final String language;

	/**
	 * Creates the mutation.
	 *
	 * @param language the ISO 639 code of the language, as `Locale#getLanguage()` returns it
	 */
	public RemoveFulltextLanguageSchemaMutation(
		@Nonnull String language
	) {
		FulltextLanguageSchema.assertLanguageCode(language);
		this.language = language;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull LocalCatalogSchemaMutation existingMutation
	) {
		final boolean sameTarget =
			existingMutation instanceof CreateFulltextLanguageSchemaMutation creation &&
				this.language.equals(creation.getLanguage()) ||
				existingMutation instanceof ModifyFulltextLanguageSchemaMutation modification &&
					this.language.equals(modification.getLanguage()) ||
				existingMutation instanceof RemoveFulltextLanguageSchemaMutation removal &&
					this.language.equals(removal.getLanguage());
		// the removal makes every earlier mutation of the same language pointless
		return sameTarget ? new MutationCombinationResult<>(true, null, this) : null;
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
		final Map<String, FulltextLanguageSchemaContract> current = catalogSchema.getFulltextLanguages();
		if (!current.containsKey(this.language)) {
			// nothing to remove - we can return the existing schema
			return new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
		}
		final Map<String, FulltextLanguageSchemaContract> updated = CollectionUtils.createHashMap(current.size());
		updated.putAll(current);
		updated.remove(this.language);
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
		return Operation.REMOVE;
	}

	@Nonnull
	@Override
	public Stream<ConflictKey> collectConflictKeys(@Nonnull ConflictGenerationContext context) {
		return Stream.of(new CatalogConflictKey(context.getCatalogName()));
	}

	@Nonnull
	@Override
	public String toString() {
		return "Remove fulltext language: " +
			"language=" + this.language;
	}

}
