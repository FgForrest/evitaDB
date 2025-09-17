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

package io.evitadb.api.requestResponse.data;


import io.evitadb.api.CatalogContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.ReferenceNotKnownException;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.References;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Contract for classes that allow creating / updating or removing information in {@link References} instance.
 * Interface follows the <a href="https://en.wikipedia.org/wiki/Builder_pattern">builder pattern</a> allowing to alter
 * the data that are available on the read-only {@link ReferencesContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ReferencesEditor<W extends ReferencesEditor<W>> extends ReferencesContract {
	/**
	 * Iterates over all references of the entity, filters them by the predicate, and applies the consumer
	 * to update all matching references. If none match the predicate, no action is performed.
	 *
	 * @param filter  predicate used to select references that should be updated
	 * @param whichIs consumer that mutates each matching reference; may be {@code null} for no-op
	 * @return this editor instance for fluent chaining
	 */
	@Nonnull
	W updateReferences(
		@Nonnull Predicate<ReferenceContract> filter,
		@Nonnull Consumer<ReferenceBuilder> whichIs
	);

	/**
	 * Creates or updates a reference of the entity. A reference represents a relation to another evitaDB entity or
	 * to an external source. The exact target entity is defined in
	 * {@link ReferenceSchemaContract#getReferencedEntityType()} and
	 * {@link ReferenceSchemaContract#isReferencedEntityTypeManaged()}.
	 *
	 * This method expects that a {@link ReferenceSchemaContract} for the given {@code referenceName} already exists
	 * in the {@link EntitySchemaContract}.
	 *
	 * @param referenceName        the name of the reference as defined in the entity schema
	 * @param referencedPrimaryKey the primary key of the referenced entity (or the external identifier)
	 * @return this editor instance for fluent chaining
	 * @throws ReferenceNotKnownException when the reference doesn't exist in the entity schema
	 */
	@Nonnull
	W setReference(
		@Nonnull String referenceName,
		int referencedPrimaryKey
	) throws ReferenceNotKnownException;

	/**
	 * Creates or updates a reference of the entity. A reference represents a relation to another
	 * evitaDB entity or to an external source. The exact target entity is defined in
	 * {@link ReferenceSchemaContract#getReferencedEntityType()} and
	 * {@link ReferenceSchemaContract#isReferencedEntityTypeManaged()}. The third argument accepts
	 * a consumer that allows setting additional information on the reference, such as its
	 * {@link ReferenceContract#getAttributeValues()} or grouping.
	 *
	 * This method expects that a {@link ReferenceSchemaContract} for the given {@code referenceName}
	 * already exists in the {@link EntitySchemaContract}.
	 *
	 * @param referenceName        the name of the reference as defined in the entity schema
	 * @param referencedPrimaryKey the primary key of the referenced entity (or the external identifier)
	 * @param whichIs              a mutator that initializes or updates the reference attributes/grouping; may be {@code null}
	 * @return this editor instance for fluent chaining
	 * @throws ReferenceNotKnownException when the reference doesn't exist in the entity schema
	 */
	@Nonnull
	W setReference(
		@Nonnull String referenceName,
		int referencedPrimaryKey,
		@Nullable Consumer<ReferenceBuilder> whichIs
	) throws ReferenceNotKnownException;

	/**
	 * Creates or updates a reference of the entity. A reference represents a relation to another evitaDB entity or to
	 * an external source. The exact target entity is defined in
	 * {@link ReferenceSchemaContract#getReferencedEntityType()} and
	 * {@link ReferenceSchemaContract#isReferencedEntityTypeManaged()}.
	 *
	 * The third argument accepts a predicate that identifies existing references to be updated among potentially
	 * multiple references of the same {@code referenceName} targeting the specified {@code referencedPrimaryKey}.
	 * If the predicate matches multiple references, all are updated. If none match, a new reference is created and
	 * passed to the consumer for initialization.
	 *
	 * The fourth argument accepts a consumer that allows setting additional information on the reference such as its
	 * {@link ReferenceContract#getAttributeValues()} or grouping information.
	 *
	 * This method expects that a {@link ReferenceSchemaContract} for the given {@code referenceName} already exists
	 * in the {@link EntitySchemaContract}.
	 *
	 * @param referenceName        the name of the reference as defined in the entity schema
	 * @param referencedPrimaryKey the primary key of the referenced entity (or the external identifier)
	 * @param filter               predicate used to select existing references to update
	 * @param whichIs              a mutator that initializes or updates the reference attributes/grouping; may be {@code null}
	 * @return this editor instance for fluent chaining
	 */
	@Nonnull
	W setReference(
		@Nonnull String referenceName,
		int referencedPrimaryKey,
		@Nonnull Predicate<ReferenceContract> filter,
		@Nonnull Consumer<ReferenceBuilder> whichIs
	);

	/**
	 * Creates or updates a reference of the entity. The reference represents a relation to another evitaDB entity
	 * or to an external source. The exact target entity is defined in
	 * {@link ReferenceSchemaContract#getReferencedEntityType()} and
	 * {@link ReferenceSchemaContract#isReferencedEntityTypeManaged()}.
	 *
	 * If no {@link ReferenceSchemaContract} exists yet, a new one is created. The new reference will have these
	 * properties automatically set up:
	 * - {@link ReferenceSchemaContract#isIndexed()} TRUE – you will be able to filter by presence of this reference
	 * (at the cost of higher memory usage)
	 * - {@link ReferenceSchemaContract#isFaceted()} FALSE – reference data will not be part of the {@link FacetSummary}
	 * - {@link ReferenceSchemaContract#isReferencedEntityTypeManaged()} TRUE if there already is an entity with matching
	 * {@code referencedEntityType} in the current catalog, otherwise FALSE
	 * - {@link ReferenceSchemaContract#getReferencedGroupType()} – not defined
	 * - {@link ReferenceSchemaContract#isReferencedGroupTypeManaged()} FALSE
	 *
	 * If you need to change these defaults, fetch the reference schema by calling
	 * {@link CatalogContract#getEntitySchema(String)}, access it via
	 * {@link EntitySchemaContract#getReference(String)}, open it for write, and update it in the evitaDB instance via
	 * {@link EntitySchemaBuilder#updateVia(EvitaSessionContract)}.
	 *
	 * @param referenceName        the name of the reference being created or updated
	 * @param referencedEntityType the type of the referenced entity
	 * @param cardinality          expected cardinality as defined by the schema
	 * @param referencedPrimaryKey the primary key of the referenced entity (or the external identifier)
	 * @return this editor instance for fluent chaining
	 */
	@Nonnull
	W setReference(
		@Nonnull String referenceName,
		@Nonnull String referencedEntityType,
		@Nonnull Cardinality cardinality,
		int referencedPrimaryKey
	);

	/**
	 * Creates or updates a reference of the entity. A reference represents a relation to another evitaDB entity or to
	 * an external source. The exact target entity is defined in
	 * {@link ReferenceSchemaContract#getReferencedEntityType()} and
	 * {@link ReferenceSchemaContract#isReferencedEntityTypeManaged()}. The fifth argument accepts a consumer that
	 * allows setting additional information on the reference such as its
	 * {@link ReferenceContract#getAttributeValues()} or grouping information.
	 *
	 * If no {@link ReferenceSchemaContract} exists yet, a new one is created. The new reference will have these
	 * properties automatically set up:
	 * - {@link ReferenceSchemaContract#isIndexed()} TRUE – you will be able to filter by presence of this reference
	 * (at the cost of higher memory usage)
	 * - {@link ReferenceSchemaContract#isFaceted()} FALSE – reference data will not be part of the {@link FacetSummary}
	 * - {@link ReferenceSchemaContract#isReferencedEntityTypeManaged()} TRUE if there already is an entity with matching
	 * {@code referencedEntityType} in the current catalog, otherwise FALSE
	 * - {@link ReferenceSchemaContract#getReferencedGroupType()} – as defined in {@code whichIs} lambda
	 * - {@link ReferenceSchemaContract#isReferencedGroupTypeManaged()} TRUE if there already is an entity with matching
	 * {@link ReferenceContract#getGroup()} in the current catalog, otherwise FALSE
	 *
	 * If you need to change these defaults, fetch the reference schema by calling
	 * {@link CatalogContract#getEntitySchema(String)}, access it via
	 * {@link EntitySchemaContract#getReference(String)}, open it for write, and update it in the evitaDB instance via
	 * {@link EntitySchemaBuilder#updateVia(EvitaSessionContract)}.
	 *
	 * @param referenceName        the name of the reference being created or updated
	 * @param referencedEntityType the type of the referenced entity
	 * @param cardinality          expected cardinality as defined by the schema
	 * @param referencedPrimaryKey the primary key of the referenced entity (or the external identifier)
	 * @param whichIs              a mutator that initializes or updates the reference attributes/grouping; may be {@code null}
	 * @return this editor instance for fluent chaining
	 */
	@Nonnull
	W setReference(
		@Nonnull String referenceName,
		@Nonnull String referencedEntityType,
		@Nonnull Cardinality cardinality,
		int referencedPrimaryKey,
		@Nullable Consumer<ReferenceBuilder> whichIs
	);

	/**
	 * Creates or updates a reference of the entity. A reference represents a relation to another evitaDB entity or to
	 * an external source. The exact target entity is defined in
	 * {@link ReferenceSchemaContract#getReferencedEntityType()} and
	 * {@link ReferenceSchemaContract#isReferencedEntityTypeManaged()}.
	 *
	 * The fifth argument accepts a predicate that identifies existing references to be updated among potentially
	 * multiple references of the same {@code referenceName} targeting the specified {@code referencedPrimaryKey}.
	 * If the predicate matches multiple references, all are updated. If none match, a new reference is created and
	 * passed to the consumer for initialization.
	 *
	 * The sixth argument accepts a consumer that allows setting additional information on the reference such as its
	 * {@link ReferenceContract#getAttributeValues()} or grouping information.
	 *
	 * If no {@link ReferenceSchemaContract} exists yet, a new one is created. The new reference will have these
	 * properties automatically set up:
	 * - {@link ReferenceSchemaContract#isIndexed()} TRUE – you will be able to filter by presence of this reference
	 * (at the cost of higher memory usage)
	 * - {@link ReferenceSchemaContract#isFaceted()} FALSE – reference data will not be part of the {@link FacetSummary}
	 * - {@link ReferenceSchemaContract#isReferencedEntityTypeManaged()} TRUE if there already is an entity with matching
	 * {@code referencedEntityType} in the current catalog, otherwise FALSE
	 * - {@link ReferenceSchemaContract#getReferencedGroupType()} – as defined in {@code whichIs} lambda
	 * - {@link ReferenceSchemaContract#isReferencedGroupTypeManaged()} TRUE if there already is an entity with matching
	 * {@link ReferenceContract#getGroup()} in the current catalog, otherwise FALSE
	 *
	 * If you need to change these defaults, fetch the reference schema by calling
	 * {@link CatalogContract#getEntitySchema(String)}, access it via
	 * {@link EntitySchemaContract#getReference(String)}, open it for write, and update it in the evitaDB instance via
	 * {@link EntitySchemaBuilder#updateVia(EvitaSessionContract)}.
	 *
	 * @param referenceName        the name of the reference being created or updated
	 * @param referencedEntityType the type of the referenced entity
	 * @param cardinality          expected cardinality as defined by the schema
	 * @param referencedPrimaryKey the primary key of the referenced entity (or the external identifier)
	 * @param filter               predicate used to select existing references to update
	 * @param whichIs              a mutator that initializes or updates the reference attributes/grouping; may be {@code null}
	 * @return this editor instance for fluent chaining
	 */
	@Nonnull
	W setReference(
		@Nonnull String referenceName,
		@Nonnull String referencedEntityType,
		@Nonnull Cardinality cardinality,
		int referencedPrimaryKey,
		@Nonnull Predicate<ReferenceContract> filter,
		@Nonnull Consumer<ReferenceBuilder> whichIs
	);

	/**
	 * Removes an existing reference with the specified name and primary key.
	 *
	 * @param referenceName        the name of the reference to remove
	 * @param referencedPrimaryKey the primary key of the referenced entity to remove the link to
	 * @return this editor instance for fluent chaining
	 * @throws ReferenceNotKnownException when the reference doesn't exist in the entity schema
	 */
	@Nonnull
	W removeReference(@Nonnull String referenceName, int referencedPrimaryKey) throws ReferenceNotKnownException;

	/**
	 * Removes an existing reference with the specified name and primary key.
	 *
	 * @param referenceKey the key of the reference to remove
	 * @return this editor instance for fluent chaining
	 * @throws ReferenceNotKnownException when the reference doesn't exist in the entity schema
	 */
	@Nonnull
	W removeReference(@Nonnull ReferenceKey referenceKey) throws ReferenceNotKnownException;

	/**
	 * Removes all existing references with the specified name that target the specified primary key. This method may
	 * remove multiple references if the reference schema allows multiple (duplicate) references to the same entity.
	 * If duplicates are not allowed by the schema, at most one reference will be removed.
	 *
	 * @param referenceName        the name of the reference to remove
	 * @param referencedPrimaryKey the primary key of the referenced entity whose links should be removed
	 * @return this editor instance for fluent chaining
	 */
	@Nonnull
	W removeReferences(@Nonnull String referenceName, int referencedPrimaryKey);

	/**
	 * Removes all existing references with the specified name. This method may remove multiple references at once if
	 * the reference schema allows higher {@link Cardinality}.
	 *
	 * @param referenceName the name of the references to remove
	 * @return this editor instance for fluent chaining
	 */
	@Nonnull
	W removeReferences(@Nonnull String referenceName);

	/**
	 * Removes matching references with the specified name. This method may remove multiple references at once if
	 * the reference schema allows higher {@link Cardinality} and the predicate matches multiple references.
	 *
	 * @param referenceName the name of the references to remove
	 * @param filter        predicate used to select references to remove
	 * @return this editor instance for fluent chaining
	 */
	@Nonnull
	W removeReferences(@Nonnull String referenceName, @Nonnull Predicate<ReferenceContract> filter);

	/**
	 * Removes matching references from the entity. This method may remove multiple references if the
	 * predicate matches multiple ones.
	 *
	 * @param filter predicate used to select references to remove
	 * @return this editor instance for fluent chaining
	 */
	@Nonnull
	W removeReferences(@Nonnull Predicate<ReferenceContract> filter);

	/**
	 * Interface that simply combines writer and builder contracts together.
	 */
	interface ReferencesBuilder extends ReferencesEditor<ReferencesEditor.ReferencesBuilder>, BuilderContract<References> {

		@Nonnull
		@Override
		Stream<? extends ReferenceMutation<?>> buildChangeSet();

		/**
		 * Returns next internal id that can be used for newly created reference from an internal sequence.
		 * @return next internal reference id
		 */
		int getNextReferenceInternalId();

		/**
		 * Adds new set of reference mutations for particular `referenceKey`, if some set is already present for that
		 * key, it is replaced by a new set.
		 *
		 * This method is considered to be a part of private API and is used only from generated Proxies.
		 *
		 * @param referenceBuilder reference builder wrapping the changes in the reference contract
		 * @param methodAllowsDuplicates true when called in context, where duplicates are expected and reference in
		 *                               the reference builder should be respected as it is, otherwise its internal
		 *                               primary key might be aligned to already assigned internal primary key of this
		 *                               editor
		 */
		void addOrReplaceReferenceMutations(
			@Nonnull ReferenceBuilder referenceBuilder,
			boolean methodAllowsDuplicates
		);

		/**
		 * Creates new reference with given parameters. This method is used only as a temporal schema until it's created.
		 */
		@Nonnull
		static ReferenceSchema createImplicitSchema(
			@Nonnull EntitySchemaContract entitySchema,
			@Nonnull String referenceName,
			@Nonnull String referencedEntityType,
			@Nonnull Cardinality cardinality,
			@Nullable GroupEntityReference group
		) {
			Assert.isTrue(
				entitySchema.allows(EvolutionMode.ADDING_REFERENCES),
				() -> new InvalidMutationException(
					"Entity schema `" + entitySchema.getName() + "` doesn't allow adding new references on the fly! " +
						"You need to update the schema first before you can add new references with name `" + referenceName + "`."
				)
			);
			return ReferenceSchema._internalBuild(
				referenceName, referencedEntityType, false, cardinality,
				ofNullable(group).map(GroupEntityReference::getType).orElse(null), false,
				null, null
			);
		}

	}

}
