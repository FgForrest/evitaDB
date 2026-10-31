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
import io.evitadb.api.requestResponse.schema.FulltextResourceSchemaContract;
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
 * Mutation removes a named list fulltext analysis steps read from the catalog schema (see
 * `CatalogSchemaContract#getFulltextResources()`). Removing a resource that does not exist changes nothing.
 *
 * Whether something still refers to the resource is checked on the schema the whole update produces, so one update
 * may re-point the references and remove the resource together.
 *
 * Mutation implements {@link CombinableCatalogSchemaMutation}: a later mutation of the same resource in one pipeline
 * absorbs the earlier one where the result is the same.
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class RemoveFulltextResourceSchemaMutation implements CombinableCatalogSchemaMutation, CatalogSchemaMutation {
	@Serial private static final long serialVersionUID = -1140572022056127465L;
	/**
	 * The name analysis steps refer to the resource by.
	 */
	@Nonnull @Getter private final String name;

	/**
	 * Creates the mutation.
	 *
	 * @param name the name analysis steps refer to the resource by
	 */
	public RemoveFulltextResourceSchemaMutation(
		@Nonnull String name
	) {
		Assert.notNull(name, "Fulltext resource name must not be null.");
		this.name = name;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull LocalCatalogSchemaMutation existingMutation
	) {
		final boolean sameTarget =
			existingMutation instanceof CreateFulltextResourceSchemaMutation creation &&
				this.name.equals(creation.getName()) ||
				existingMutation instanceof ModifyFulltextResourceSchemaMutation modification &&
					this.name.equals(modification.getName()) ||
				existingMutation instanceof RemoveFulltextResourceSchemaMutation removal &&
					this.name.equals(removal.getName());
		// the removal makes every earlier mutation of the same resource pointless
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
		final Map<String, FulltextResourceSchemaContract> current = catalogSchema.getFulltextResources();
		if (!current.containsKey(this.name)) {
			// nothing to remove - we can return the existing schema
			return new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
		}
		final Map<String, FulltextResourceSchemaContract> updated = CollectionUtils.createHashMap(current.size());
		updated.putAll(current);
		updated.remove(this.name);
		return new CatalogSchemaWithImpactOnEntitySchemas(
			CatalogSchema._internalBuildWithFulltext(
				catalogSchema,
				catalogSchema.getFulltextLanguages(),
				updated,
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
		return "Remove fulltext resource: " +
			"name=" + this.name;
	}

}
