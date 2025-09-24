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


import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.exception.ReferenceAllowsDuplicatesException;
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.dataType.DataChunk;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * This interface prescribes a set of methods that must be implemented by the object, that maintains set of references.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ReferencesContract extends Serializable, ReferenceAvailabilityChecker {

	/**
	 * Returns collection of {@link Reference} of this entity. The references represent relations to other evitaDB
	 * entities or external entities in different systems.
	 *
	 * @return collection of all the fetched references of the entity
	 * @throws ContextMissingException when {@link ReferenceContent} is not part of the query requirements
	 */
	@Nonnull
	Collection<ReferenceContract> getReferences()
		throws ContextMissingException;

	/**
	 * Returns set of unique names of references of this entity.
	 *
	 * @return set of all names of the fetched references of the entity
	 * @throws ContextMissingException when {@link ReferenceContent} is not part of the query requirements
	 */
	@Nonnull
	Set<String> getReferenceNames()
		throws ContextMissingException;

	/**
	 * Returns collection of {@link Reference} to certain type of other entities. References represent relations to
	 * other evitaDB entities or external entities in different systems.
	 *
	 * @param referenceName name of the reference
	 * @return collection of references from reference of given name
	 *
	 * @throws ReferenceNotFoundException when reference with given name is not defined in the schema
	 * @throws ContextMissingException    when {@link ReferenceContent} is not part of the query requirements
	 */
	@Nonnull
	Collection<ReferenceContract> getReferences(@Nonnull String referenceName)
		throws ContextMissingException, ReferenceNotFoundException;

	/**
	 * Returns single {@link Reference} instance that is referencing passed entity type with certain primary key.
	 * The references represent relations to other evitaDB entities or external entities in different systems.
	 *
	 * @param referenceName name of the reference
	 * @param referencedEntityId primary key of the entity that is referenced
	 *
	 * @return reference to the entity or empty if the entity is not referenced
	 * @throws ReferenceNotFoundException when reference with given name is not defined in the schema
	 * @throws ContextMissingException    when {@link ReferenceContent} is not part of the query requirements
	 */
	@Nonnull
	Optional<ReferenceContract> getReference(@Nonnull String referenceName, int referencedEntityId)
		throws ContextMissingException, ReferenceNotFoundException;

	/**
	 * Returns single {@link Reference} instance that is referencing passed entity type with certain primary key.
	 * The references represent relations to other evitaDB entities or external entities in different systems.
	 *
	 * @param referenceKey reference key combining both reference name and referenced entity id information
	 *
	 * @return reference to the entity or empty if the entity is not referenced
	 * @throws ContextMissingException    when {@link ReferenceContent} is not part of the query requirements
	 * @throws ReferenceNotFoundException when reference with given name is not defined in the schema
	 * @throws ReferenceAllowsDuplicatesException when reference schema allows duplicates, in such case you need to use
	 * {@link #getReferences(ReferenceKey)} method
	 */
	@Nonnull
	Optional<ReferenceContract> getReference(@Nonnull ReferenceKey referenceKey)
		throws ContextMissingException, ReferenceNotFoundException, ReferenceAllowsDuplicatesException;

	/**
	 * Returns one or more {@link Reference} instances that is referencing passed entity type with certain primary key.
	 * The references represent relations to other evitaDB entities or external entities in different systems.
	 *
	 * @param referenceName name of the reference
	 * @param referencedEntityId primary key of the entity that is referenced
	 *
	 * @return reference to the entity or empty if the entity is not referenced
	 * @throws ContextMissingException    when {@link ReferenceContent} is not part of the query requirements
	 * @throws ReferenceNotFoundException when reference with given name is not defined in the schema
	 */
	@Nonnull
	List<ReferenceContract> getReferences(@Nonnull String referenceName, int referencedEntityId)
		throws ContextMissingException, ReferenceNotFoundException;

	/**
	 * Returns one or more {@link Reference} instances that is referencing passed entity type with certain primary key.
	 * The references represent relations to other evitaDB entities or external entities in different systems.
	 *
	 * @param referenceKey reference key combining both reference name and referenced entity id information
	 *
	 * @return reference to the entity or empty if the entity is not referenced
	 * @throws ContextMissingException    when {@link ReferenceContent} is not part of the query requirements
	 * @throws ReferenceNotFoundException when reference with given name is not defined in the schema
	 */
	@Nonnull
	List<ReferenceContract> getReferences(@Nonnull ReferenceKey referenceKey)
		throws ContextMissingException, ReferenceNotFoundException;

	/**
	 * Returns collection of {@link Reference} to certain type of other entities. References represent relations to
	 * other evitaDB entities or external entities in different systems.
	 *
	 * @param referenceName name of the reference
	 *
	 * @return page or strip of references with additional information about total number of references and other
	 *         information about the possibly incomplete chunk of data
	 * @throws ContextMissingException    when {@link ReferenceContent} is not part of the query requirements
	 */
	@Nonnull
	DataChunk<ReferenceContract> getReferenceChunk(@Nonnull String referenceName)
		throws ContextMissingException;
}
