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

import io.evitadb.api.exception.PrimaryKeyNotAssignedException;
import io.evitadb.api.query.filter.EntityPrimaryKeyInSet;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Common ancestor for contracts that either directly represent {@link EntityContract} or reference to it
 * - i.e. {@link EntityReferenceContract}. We don't use sealed interface here because there are multiple implementations
 * of those interfaces but only these two aforementioned extending interfaces could extend from this one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface EntityClassifier extends Serializable {

	/**
	 * Reference to {@link EntitySchemaContract#getName()} of the entity. Might be also anything {@link Serializable}
	 * that identifies type some external resource not maintained by Evita.
	 */
	@Nonnull
	String getType();

	/**
	 * Unique Integer positive number (max. 2<sup>63</sup>-1) representing the entity. Can be used for fast lookup for
	 * entity (entities). Primary key must be unique within the same entity type.
	 * May be left empty if it should be auto generated by the database.
	 * Entities can be looked up by primary key by using query {@link EntityPrimaryKeyInSet}
	 */
	@Nullable
	Integer getPrimaryKey();

	/**
	 * Retrieves the primary key of the entity. If the primary key is not assigned, throws a PrimaryKeyNotAssignedException.
	 *
	 * @return the primary key of the entity.
	 * @throws PrimaryKeyNotAssignedException if the primary key is not assigned.
	 */
	default int getPrimaryKeyOrThrowException() {
		final Integer pk = getPrimaryKey();
		if (pk == null) {
			throw new PrimaryKeyNotAssignedException(getType());
		} else {
			return pk;
		}
	}

}
