/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Contract for classes that allow creating, updating, or removing information in an {@link Entity}
 * instance. The interface follows the [builder pattern](https://en.wikipedia.org/wiki/Builder_pattern)
 * allowing you to alter the data available on the read-only {@link EntityContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface EntityEditor<W extends EntityEditor<W>>
	extends EntityContract,
	AttributesEditor<W, EntityAttributeSchemaContract>,
	AssociatedDataEditor<W>,
	PricesEditor<W>,
	ReferencesEditor<W>
{

	/**
	 * Sets the scope of the entity. When {@link Scope#ARCHIVED} is set and the entity is saved, the entity is
	 * moved to archive indexes and will no longer be accessible in the live data set. This is equivalent to
	 * calling {@link EvitaSessionContract#archiveEntity(String, int)}.
	 * When {@link Scope#LIVE} is set and the entity is saved, the entity is moved back to the live data set.
	 * This is equivalent to calling {@link EvitaSessionContract#restoreEntity(String, int)}.
	 *
	 * @param scope the scope to set for the entity
	 * @return this editor instance for fluent chaining
	 */
	@Nonnull
	W setScope(@Nonnull Scope scope);

	/**
	 * Sets hierarchy information of the entity. Hierarchy information allows composing a hierarchy tree of
	 * entities of the same type. The referenced entity is always of the same type. The referenced entity must
	 * already be present in evitaDB and must also have a hierarchy placement set.
	 *
	 * @param parentPrimaryKey primary key of the parent entity in the same hierarchy
	 * @return this editor instance for fluent chaining
	 */
	@Nonnull
	W setParent(int parentPrimaryKey);

	/**
	 * Removes the existing parent of the entity. If there are other entities that refer transitively via
	 * {@link EntityContract#getParentEntity()} to this entity, they will become "orphans" and their parent needs to be
	 * removed as well, or they must be rewired to another parent.
	 *
	 * @return this editor instance for fluent chaining
	 */
	@Nonnull
	W removeParent();

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
