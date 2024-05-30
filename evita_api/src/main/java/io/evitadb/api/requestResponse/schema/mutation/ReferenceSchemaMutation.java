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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.schema.mutation;

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * This interface marks all implementations that alter the {@link EntitySchemaContract#getReferences()}.
 * The implementations can either modify the entire {@link EntitySchemaContract} or partially only a single
 * {@link ReferenceSchemaContract} of it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Immutable
@ThreadSafe
public interface ReferenceSchemaMutation extends SchemaMutation {

	/**
	 * Returns the name of the reference the mutation is targeting.
	 */
	@Nonnull
	String getName();

	/**
	 * Method applies the mutation operation on the reference schema in the input and returns modified version
	 * as its return value. The create operation works with NULL input value and produces non-NULL result, the remove
	 * operation produces the opposite. Modification operations always accept and produce non-NULL values.
	 *
	 * @param entitySchema owner entity schema that could be used in validations and error messages
	 * @param referenceSchema current version of the schema as an input to mutate
	 */
	@Nullable
	ReferenceSchemaContract mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema
	);

}
