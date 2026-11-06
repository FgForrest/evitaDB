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
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
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
 * Mutation removes a named fulltext analyzer from the catalog schema (see
 * `CatalogSchemaContract#getFulltextAnalyzers()`). Removing an analyzer that does not exist changes nothing.
 *
 * Whether something still refers to the analyzer is checked on the schema the whole update produces, so one update
 * may re-point the references and remove the analyzer together.
 *
 * Mutation implements {@link CombinableCatalogSchemaMutation}: it drops every earlier creation, modification or
 * removal of the same analyzer in one pipeline, which the removal makes pointless, and stays in the pipeline itself.
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class RemoveFulltextAnalyzerSchemaMutation implements CombinableCatalogSchemaMutation, CatalogSchemaMutation {
	@Serial private static final long serialVersionUID = -1589535769139006507L;
	/**
	 * The name searchable fields refer to the analyzer by.
	 */
	@Nonnull @Getter private final String name;

	/**
	 * Creates the mutation.
	 *
	 * @param name the name searchable fields refer to the analyzer by
	 */
	public RemoveFulltextAnalyzerSchemaMutation(
		@Nonnull String name
	) {
		Assert.notNull(name, "Fulltext analyzer name must not be null.");
		this.name = name;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull LocalCatalogSchemaMutation existingMutation
	) {
		final boolean sameTarget =
			existingMutation instanceof CreateFulltextAnalyzerSchemaMutation creation &&
				this.name.equals(creation.getName()) ||
				existingMutation instanceof ModifyFulltextAnalyzerSchemaMutation modification &&
					this.name.equals(modification.getName()) ||
				existingMutation instanceof RemoveFulltextAnalyzerSchemaMutation removal &&
					this.name.equals(removal.getName());
		// the removal makes every earlier mutation of the same analyzer pointless
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
		final Map<String, FulltextAnalyzerSchemaContract> current = catalogSchema.getFulltextAnalyzers();
		if (!current.containsKey(this.name)) {
			// nothing to remove - we can return the existing schema
			return new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
		}
		final Map<String, FulltextAnalyzerSchemaContract> updated = CollectionUtils.createHashMap(current.size());
		updated.putAll(current);
		updated.remove(this.name);
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
		return "Remove fulltext analyzer: " +
			"name=" + this.name;
	}

}
