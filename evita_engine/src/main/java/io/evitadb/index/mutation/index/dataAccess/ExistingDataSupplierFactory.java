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

package io.evitadb.index.mutation.index.dataAccess;


import io.evitadb.index.RepresentativeReferenceKey;

import javax.annotation.Nonnull;

/**
 * The factory interface for creating {@link ExistingAttributeValueSupplier} instances.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public sealed interface ExistingDataSupplierFactory
	permits EntityExistingDataFactory, EntityStoragePartExistingDataFactory {

	/**
	 * Provides an {@link ExistingAttributeValueSupplier} instance that supplies existing attribute values from an entity.
	 *
	 * @return an instance of {@link ExistingAttributeValueSupplier} which can be used to retrieve attribute values of an entity.
	 */
	@Nonnull
	ExistingAttributeValueSupplier getEntityAttributeValueSupplier();

	/**
	 * Provides an instance of {@link ReferenceSupplier} which can be used to retrieve reference-related data.
	 *
	 * @return an instance of {@link ReferenceSupplier} for accessing reference data.
	 */
	@Nonnull
	ReferenceSupplier getReferenceSupplier();

	/**
	 * Provides an {@link ExistingAttributeValueSupplier} instance for retrieving attribute values defined on
	 * a particular reference.
	 *
	 * @param referenceKey the unique identifier of the reference from which attribute values are to be retrieved.
	 * @return an instance of {@link ExistingAttributeValueSupplier} which can be used to retrieve
	 *         attribute values of the reference.
	 */
	@Nonnull
	ExistingAttributeValueSupplier getReferenceAttributeValueSupplier(@Nonnull RepresentativeReferenceKey referenceKey);

	/**
	 * Provides an {@link ExistingPriceSupplier} instance for retrieving prices on a particular reference.
	 *
	 * @return an instance of {@link ExistingPriceSupplier} which can be used to retrieve existing prices.
	 */
	@Nonnull
	ExistingPriceSupplier getPriceSupplier();

}
