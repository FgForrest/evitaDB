/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.api.exception.ReferenceNotKnownException;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.function.Consumer;

import static io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;

/**
 * Contract for classes that allow creating / updating or removing information in {@link Entity} instance.
 * Interface follows the <a href="https://en.wikipedia.org/wiki/Builder_pattern">builder pattern</a> allowing to alter
 * the data that are available on the read-only {@link EntityContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface EntityEditor<W extends EntityEditor<W>> extends EntityContract, AttributesEditor<W, EntityAttributeSchemaContract>, AssociatedDataEditor<W>, PricesEditor<W> {

	/**
	 * Sets hierarchy information of the entity. Hierarchy information allows to compose hierarchy tree composed of
	 * entities of the same type. Referenced entity is always entity of the same type. Referenced entity must be already
	 * present in the evitaDB and must also have hierarchy placement set.
	 */
	W setParent(int parentPrimaryKey);

	/**
	 * Removes existing parent of the entity. If there are other entities, that refer transitively via
	 * {@link EntityContract#getParentEntity()} this entity their will become "orphans" and their parent needs to be
	 * removed as well, or it must be "rewired" to another parent.
	 */
	W removeParent();

	/**
	 * Method creates or updates reference of the entity. Reference represents relation to another Evita entity or may
	 * be also any external source. The exact target entity is defined in {@link ReferenceSchemaContract#getReferencedEntityType()} and
	 * {@link ReferenceSchemaContract#isReferencedEntityTypeManaged()}.
	 *
	 * This method expects that the {@link ReferenceSchemaContract} of passed `referenceName` is already present in
	 * the {@link EntitySchemaContract}.
	 *
	 * @throws ReferenceNotKnownException when reference doesn't exist in entity schema
	 */
	W setReference(@Nonnull String referenceName, int referencedPrimaryKey) throws ReferenceNotKnownException;

	/**
	 * Method creates or updates reference of the entity. Reference represents relation to another Evita entity or may
	 * be also any external source. The exact target entity is defined in {@link ReferenceSchemaContract#getReferencedEntityType()} and
	 * {@link ReferenceSchemaContract#isReferencedEntityTypeManaged()}. Third argument accepts consumer, that allows to set
	 * additional information on the reference such as its {@link ReferenceContract#getAttributeValues()} or grouping information.
	 *
	 * This method expects that the {@link ReferenceSchemaContract} of passed `referenceName` is already present in
	 * the {@link EntitySchemaContract}.
	 *
	 * @throws ReferenceNotKnownException when reference doesn't exist in entity schema
	 */
	W setReference(
		@Nonnull String referenceName,
		int referencedPrimaryKey,
		@Nullable Consumer<ReferenceBuilder> whichIs
	) throws ReferenceNotKnownException;

	/**
	 * Method creates or updates reference of the entity. The reference represents relation to another Evita entity or may
	 * be also any external source. The exact target entity is defined in {@link ReferenceSchemaContract#getReferencedEntityType()} and
	 * {@link ReferenceSchemaContract#isReferencedEntityTypeManaged()}.
	 *
	 * If no {@link ReferenceSchemaContract} exists yet - new one is created. New reference will have these properties
	 * automatically set up:
	 *
	 * - {@link ReferenceSchemaContract#isIndexed()} TRUE - you'll be able to filter by presence of this reference
	 * (but this setting also consumes more memory)
	 * - {@link ReferenceSchemaContract#isFaceted()} FALSE - reference data will not be part of the {@link FacetSummary}
	 * - {@link ReferenceSchemaContract#isReferencedEntityTypeManaged()} TRUE if there already is entity with matching
	 * `referencedEntityType` in current catalog, otherwise FALSE
	 * - {@link ReferenceSchemaContract#getReferencedGroupType()} - not defined
	 * - {@link ReferenceSchemaContract#isReferencedGroupTypeManaged()} FALSE
	 *
	 * If you need to change this defaults you need to fetch the reference schema by calling
	 * {@link CatalogContract#getEntitySchema(String)} and accessing it by
	 * {@link EntitySchemaContract#getReference(String)}, open it for write and update it in evita DB instance via
	 * {@link EntitySchemaBuilder#updateVia(EvitaSessionContract)}.
	 */
	W setReference(
		@Nonnull String referenceName,
		@Nonnull String referencedEntityType,
		@Nonnull Cardinality cardinality,
		int referencedPrimaryKey
	);

	/**
	 * Method creates or updates reference of the entity. Reference represents relation to another Evita entity or may
	 * be also any external source. The exact target entity is defined in {@link ReferenceSchemaContract#getReferencedEntityType()} and
	 * {@link ReferenceSchemaContract#isReferencedEntityTypeManaged()}. Third argument accepts consumer, that allows to set
	 * additional information on the reference such as its {@link ReferenceContract#getAttributeValues()} or grouping information.
	 *
	 * If no {@link ReferenceSchemaContract} exists yet - new one is created. New reference will have these properties
	 * automatically set up:
	 *
	 * - {@link ReferenceSchemaContract#isIndexed()} TRUE - you'll be able to filter by presence of this reference
	 * (but this setting also consumes more memory)
	 * - {@link ReferenceSchemaContract#isFaceted()} FALSE - reference data will not be part of the {@link FacetSummary}
	 * - {@link ReferenceSchemaContract#isReferencedEntityTypeManaged()} TRUE if there already is entity with matching
	 * `referencedEntityType` in current catalog, otherwise FALSE
	 * - {@link ReferenceSchemaContract#getReferencedGroupType()} - as defined in `whichIs` lambda
	 * - {@link ReferenceSchemaContract#isReferencedGroupTypeManaged()} TRUE if there already is entity with matching
	 * {@link ReferenceContract#getGroup()} in current catalog, otherwise FALSE
	 *
	 * If you need to change this defaults you need to fetch the reference schema by calling
	 * {@link CatalogContract#getEntitySchema(String)} and accessing it by
	 * {@link EntitySchemaContract#getReference(String)}, open it for write and update it in evita DB instance via
	 * {@link EntitySchemaBuilder#updateVia(EvitaSessionContract)}.
	 */
	W setReference(
		@Nonnull String referenceName,
		@Nonnull String referencedEntityType,
		@Nonnull Cardinality cardinality,
		int referencedPrimaryKey,
		@Nullable Consumer<ReferenceBuilder> whichIs
	);

	/**
	 * Adds new set of reference mutations for particular `referenceKey`, if some set is already present for that key,
	 * it is replaced by a new set.
	 *
	 * This method is considered to be a part of private API.
	 *
	 * @param referenceBuilder reference builder wrapping the changes in the reference contract
	 */
	void addOrReplaceReferenceMutations(@Nonnull ReferenceBuilder referenceBuilder);

	/**
	 * Removes existing reference of specified name and primary key.
	 *
	 * @throws ReferenceNotKnownException when reference doesn't exist in entity schema
	 */
	W removeReference(@Nonnull String referenceName, int referencedPrimaryKey) throws ReferenceNotKnownException;

	/**
	 * Interface that simply combines {@link EntitySchemaEditor} and {@link EntitySchemaContract} entity contracts together.
	 * Builder produces either {@link EntityMutation} that describes all changes to be made on {@link EntityContract} instance
	 * to get it to "up-to-date" state or can provide already built {@link EntityContract} that may not represent globally
	 * "up-to-date" state because it is based on the version of the entity known when builder was created.
	 *
	 * Mutation allows Evita to perform surgical updates on the latest version of the {@link EntityContract} object that
	 * is in the database at the time update request arrives.
	 */
	@NotThreadSafe
	interface EntityBuilder extends EntityEditor<EntityBuilder>, InstanceEditor<SealedEntity> {

		/**
		 * {@inheritDoc}
		 */
		@Override
		@Nonnull
		default Class<SealedEntity> getContract() {
			return SealedEntity.class;
		}

	}

}
