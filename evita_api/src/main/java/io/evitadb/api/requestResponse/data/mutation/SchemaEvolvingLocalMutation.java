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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.data.mutation;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serializable;

/**
 * Mutations implementing of this interface needs to check their changes whether they comply with current entity schema.
 * They can also automatically evolve the schema if it is allowed to do so.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Immutable
@ThreadSafe
public interface SchemaEvolvingLocalMutation<T, S extends Comparable<S>> extends LocalMutation<T, S> {

	/**
	 * Verifies mutation change against current entity schema known to evitaDB.
	 * If schema can be automatically adapted to the mutation change, new schema is returned as a result of this method.
	 * If no change in schema is required, the returned instance is the SAME as input schema.
	 *
	 * @param catalogSchema allowing to access catalog wide settings and global attributes
	 * @param entitySchemaBuilder  builder accepting changes to {@link EntitySchemaContract}
	 * @throws InvalidMutationException when mutation violates the entity schema
	 */
	void verifyOrEvolveSchema(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaBuilder entitySchemaBuilder
	) throws InvalidMutationException;

	/**
	 * Skip token is used to quickly skip analogous local mutations to speed verification / evolution process up.
	 * For example when there are several mutations to prices we need to check only first mutation because
	 * {@link EntitySchemaContract} defines only {@link EntitySchemaContract#isWithPrice()} behaviour. Other price
	 * mutations don't need to check this information again.
	 */
	@Nonnull
	Serializable getSkipToken(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract entitySchema
	);

}
