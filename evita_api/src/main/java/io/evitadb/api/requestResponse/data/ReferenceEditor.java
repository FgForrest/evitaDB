/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.stream.Stream;

/**
 * Contract for classes that allow creating / updating or removing information in {@link Reference} instance.
 * Interface follows the <a href="https://en.wikipedia.org/wiki/Builder_pattern">builder pattern</a> allowing to alter
 * the data that are available on the read-only {@link ReferenceContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface ReferenceEditor<W extends ReferenceEditor<W>> extends ReferenceContract, AttributesEditor<W, AttributeSchemaContract> {

	/**
	 * Sets group id to the reference. The group type must be already known by the entity schema.
	 */
	@Nonnull
	W setGroup(int primaryKey);

	/**
	 * Sets group to the reference. Group is composed of entity type and primary key of the referenced group entity.
	 * Group may or may not be Evita entity. If the group is not known by the entity schema it's automatically set up.
	 */
	@Nonnull
	W setGroup(@Nullable String referencedEntity, int primaryKey);

	/**
	 * Removes existing reference group.
	 */
	@Nonnull
	W removeGroup();

	/**
	 * Interface that simply combines writer and builder contracts together.
	 */
	interface ReferenceBuilder extends ReferenceEditor<ReferenceBuilder>, BuilderContract<ReferenceContract> {

		/**
		 * Returns stream of all changes that were made to the reference.
		 * @return stream of changes
		 */
		@Nonnull
		Stream<? extends ReferenceMutation<?>> buildChangeSet();

		/**
		 * Returns true if there are any changes made to the reference.
		 * @return true if there are any changes
		 */
		boolean hasChanges();

	}

}
