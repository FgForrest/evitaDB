/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.mutation.conflict.CollectionConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.NamedSchemaMutation;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Base class for schema mutations that target a single reference schema of an entity collection.
 *
 * Responsibilities
 * - expose the reference `name` and implement `containerName()` from `NamedSchemaMutation` so the
 *   mutation is routed to the correct reference container
 * - provide a default `collectConflictKeys(...)` that scopes conflicts to the owning entity type via
 *   `CollectionConflictKey` (all reference-schema mutations of the same entity share the same domain)
 *
 * Concurrency and lifecycle
 * - instances are immutable and thread-safe
 * - intended to be extended by concrete reference schema mutations (e.g. rename reference, change
 *   cardinality, configure attributes)
 *
 * Context
 * Reference-schema mutations are evaluated within the entity type taken from the
 * `ConflictGenerationContext`; conflicts are detected at the collection level rather than for
 * individual references.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ThreadSafe
@Immutable
@RequiredArgsConstructor
@EqualsAndHashCode
abstract class AbstractReferenceDataSchemaMutation implements NamedSchemaMutation {
	@Serial private static final long serialVersionUID = -6143626432682048663L;
	@Getter @Nonnull protected final String name;

	@Nonnull
	@Override
	public String containerName() {
		return this.name;
	}

	@Nonnull
	@Override
	public Stream<ConflictKey> collectConflictKeys(
		@Nonnull ConflictGenerationContext context,
		@Nonnull Set<ConflictPolicy> conflictPolicies
	) {
		return Stream.of(new CollectionConflictKey(context.getEntityType()));
	}

	/**
	 * Inserts a new reference schema into the entity schema, returning an updated copy with the
	 * reference added to the references map.
	 *
	 * If the reference already exists and equals `referenceToCompare`, returns the unchanged entity
	 * schema (the mutation was already applied). If the reference exists but differs, throws
	 * {@link InvalidSchemaMutationException}.
	 *
	 * @param entitySchema      current entity schema to modify
	 * @param referenceName     name of the reference being created
	 * @param referenceToInsert reference schema instance to add to the schema's reference map
	 * @param referenceToCompare reference schema used for equality check against existing references
	 *                           (same as `referenceToInsert` for regular references; raw unresolved
	 *                           version for reflected references)
	 * @return updated entity schema with the new reference, or unchanged schema if already present
	 * @throws InvalidSchemaMutationException if reference with the same name but different definition
	 *                                        already exists
	 */
	@Nonnull
	protected static EntitySchemaContract insertNewReference(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String referenceName,
		@Nonnull ReferenceSchemaContract referenceToInsert,
		@Nonnull ReferenceSchemaContract referenceToCompare
	) {
		final Optional<ReferenceSchemaContract> existingReferenceSchema = entitySchema.getReference(referenceName);
		if (existingReferenceSchema.isEmpty()) {
			return EntitySchema._internalBuild(
				entitySchema.version() + 1,
				entitySchema.getName(),
				entitySchema.getNameVariants(),
				entitySchema.getDescription(),
				entitySchema.getDeprecationNotice(),
				entitySchema.isWithGeneratedPrimaryKey(),
				entitySchema.isWithHierarchy(),
				entitySchema.getHierarchyIndexedInScopes(),
				entitySchema.isWithPrice(),
				entitySchema.getPriceIndexedInScopes(),
				entitySchema.getIndexedPricePlaces(),
				entitySchema.getLocales(),
				entitySchema.getCurrencies(),
				entitySchema.getAttributes(),
				entitySchema.getAssociatedData(),
				Stream.concat(
						entitySchema.getReferences().values().stream(),
						Stream.of(referenceToInsert)
					)
					.collect(
						Collectors.toMap(
							ReferenceSchemaContract::getName,
							Function.identity()
						)
					),
				entitySchema.getEvolutionMode(),
				entitySchema.getSortableAttributeCompounds()
			);
		} else if (existingReferenceSchema.get().equals(referenceToCompare)) {
			// the mutation must have been applied previously - return the schema we don't need to alter
			return entitySchema;
		} else {
			// there is a conflict in reference settings
			throw new InvalidSchemaMutationException(
				"The reference `" + referenceName + "` already exists in entity `" +
					entitySchema.getName() + "` schema and has different definition. " +
					"To alter existing reference schema you need to use different mutations."
			);
		}
	}
}
