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
import io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.FulltextResourceSchema;
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
 * Mutation adds a fulltext resource - a named list that analysis steps read - to the catalog schema (see
 * `CatalogSchemaContract#getFulltextResources()`). Applying it again with the same definition changes nothing; a
 * resource of the same name with a different definition is refused - use
 * {@link ModifyFulltextResourceSchemaMutation} to change it.
 *
 * Mutation implements {@link CombinableCatalogSchemaMutation}, but absorbs no earlier mutation: a removal of the
 * same resource before it must still run first. A later {@link RemoveFulltextResourceSchemaMutation} of the resource
 * absorbs it, and so does a later {@link ModifyFulltextResourceSchemaMutation} when the base schema does not hold the
 * resource.
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class CreateFulltextResourceSchemaMutation implements CombinableCatalogSchemaMutation, CatalogSchemaMutation {
	@Serial private static final long serialVersionUID = 1922197230388476010L;
	/**
	 * The name analysis steps refer to the resource by.
	 */
	@Nonnull @Getter private final String name;
	/**
	 * The content of the resource.
	 */
	@Nonnull @Getter private final FulltextResourceContent content;

	/**
	 * Creates the mutation.
	 *
	 * @param name the name analysis steps refer to the resource by
	 * @param content the content of the resource
	 */
	public CreateFulltextResourceSchemaMutation(
		@Nonnull String name,
		@Nonnull FulltextResourceContent content
	) {
		Assert.notNull(name, "Fulltext resource name must not be null.");
		this.name = name;
		Assert.notNull(content, "Fulltext resource content must not be null.");
		this.content = content;
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
			!AnalysisSteps.BUILT_IN_RESOURCES.contains(this.name),
			() -> new InvalidSchemaMutationException(
				"Fulltext resource `" + this.name + "` cannot be declared: the name belongs to a built-in resource."
			)
		);
		final FulltextResourceSchemaContract newSchema =
			FulltextResourceSchema._internalBuild(this.name, this.content);
		final Map<String, FulltextResourceSchemaContract> current = catalogSchema.getFulltextResources();
		final FulltextResourceSchemaContract existing = current.get(this.name);
		if (existing != null) {
			Assert.isTrue(
				existing.equals(newSchema),
				() -> new InvalidSchemaMutationException(
					"Fulltext resource `" + this.name + "` already exists in catalog `" + catalogSchema.getName() +
						"` with a different definition - modify it instead."
				)
			);
			// the same definition - we can return the existing schema
			return new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
		}
		final Map<String, FulltextResourceSchemaContract> updated = CollectionUtils.createHashMap(current.size() + 1);
		updated.putAll(current);
		updated.put(this.name, newSchema);
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
		return "Create fulltext resource: " +
			"name=" + this.name + ", content=" + this.content;
	}

}
