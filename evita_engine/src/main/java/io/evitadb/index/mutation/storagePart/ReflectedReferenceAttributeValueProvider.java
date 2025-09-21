/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.index.mutation.storagePart;


import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Interface is used to abstract the logic of providing data for default and inherited attributes in case an reflected
 * reference is created. It also handles reverse logic, when the reflected reference already exists and the reference
 * itself is created on the referenced entity creation.
 *
 * @param <T> the type of the reference carrier
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
interface ReflectedReferenceAttributeValueProvider<T> {

	/**
	 * Retrieves a concatenated stream of attribute schemas based on the provided local reference schema,
	 * referenced entity reference schema, and a set of inherited attributes. Method is responsible to provide
	 * non-duplicated stream of attribute schemas related to the (reflected) reference being updated.
	 *
	 * @param localReferenceSchema The schema of the local reference.
	 * @param referencedEntityReferenceSchema The schema of the referenced entity reference.
	 * @param inheritedAttributes A set of attribute names that are inherited.
	 * @return A stream of attribute schema contracts that match the provided criteria.
	 */
	@Nonnull
	Stream<? extends AttributeSchemaContract> getAttributeSchemas(
		@Nonnull ReferenceSchema localReferenceSchema,
		@Nonnull ReferenceSchema referencedEntityReferenceSchema,
		@Nonnull Set<String> inheritedAttributes
	);

	/**
	 * Retrieves the appropriate reference carrier of type T based on the provided reference key.
	 * The reference carrier contains key information about the corresponding reference.
	 *
	 * @param referenceKey The unique key identifying the reference for which the carrier is to be retrieved.
	 * @return An Optional containing the reference carrier if it exists, or an empty Optional if no carrier is found.
	 */
	@Nonnull
	Optional<T> getReferenceCarrier(@Nonnull ReferenceKey referenceKey);

	/**
	 * Retrieves a stream of reference carriers of type T. The type depends on the implementation and should provide
	 * performance optimal way of accessing the key information about all the inserted (reflected) references.
	 *
	 * @return A non-null stream of reference holders.
	 */
	@Nonnull
	Stream<T> getReferenceCarriers();

	/**
	 * Retrieves a stream of reference carriers of type T that match the provided generic reference key. The type depends
	 * on the implementation and should provide performance optimal way of accessing the key information
	 * about all the inserted (reflected) references that match the provided reference key.
	 *
	 * @param genericReferenceKey The generic reference key used to filter the reference carriers.
	 * @return A non-null stream of reference holders that match the provided reference key.
	 */
	@Nonnull
	Stream<T> getReferenceCarriers(@Nonnull ReferenceKey genericReferenceKey);

	/**
	 * Retrieves the primary key of the referenced entity associated with the given reference carrier.
	 *
	 * @param referenceCarrier The carrier containing the reference information from which to retrieve the primary key.
	 * @return The primary key of the referenced entity.
	 */
	int getReferencedEntityPrimaryKey(
		@Nonnull T referenceCarrier
	);

	/**
	 * Retrieves the reference key associated with the given reference schema and reference carrier.
	 *
	 * @param referenceSchema The schema of the reference that provides the structural information of the reference.
	 * @param referenceCarrier The carrier containing the reference information from which to retrieve the reference key.
	 * @return The unique reference key derived from the provided reference schema and carrier.
	 */
	@Nonnull
	ReferenceKey getReferenceKey(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull T referenceCarrier
	);

	/**
	 * Retrieves an array of representative attribute values associated with the provided reference schema
	 * and reference carrier. This method ensures that the values extracted are representative of the
	 * defined schema and reference carrier relationship.
	 *
	 * @param referenceSchema The schema of the reference that defines the structural and attribute information
	 *                        pertaining to the reference.
	 * @param referenceCarrier The carrier containing reference information from which the attribute values
	 *                         are extracted.
	 * @return An array of serializable objects representing the attribute values for the given reference schema
	 *         and reference carrier.
	 */
	@Nonnull
	Serializable[] getRepresentativeAttributeValues(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull T referenceCarrier
	);

	/**
	 * Retrieves a collection of attribute values for a given reference schema, reference carrier,
	 * and attribute name. Method is used to retrieve values of existing attributes ready to be inherited.
	 *
	 * @param referenceSchema The schema of the reference that provides the structural information of the reference.
	 * @param referenceCarrier The carrier containing the reference information from which to retrieve attribute values.
	 * @param attributeName The name of the attribute for which values are being retrieved.
	 * @return A collection of attribute values corresponding to the provided reference schema and attribute name.
	 */
	@Nonnull
	Collection<AttributeValue> getAttributeValues(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull T referenceCarrier,
		@Nonnull String attributeName
	);

}
