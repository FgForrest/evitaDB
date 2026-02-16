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

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import java.io.Serial;

/**
 * Exception thrown when a client attempts to create a new reference on an entity without
 * explicitly specifying the target entity type and cardinality, and no reference schema with
 * the given name exists in the entity schema.
 *
 * evitaDB supports automatic schema evolution, allowing new reference schemas to be created
 * implicitly when references are added to entities. However, this automatic schema creation
 * requires explicit specification of:
 *
 * - **Target entity type**: The type of entities this reference points to (or `null` for
 * external entity references)
 * - **Cardinality**: The cardinality constraint (ZERO_OR_ONE, EXACTLY_ONE, ZERO_OR_MORE,
 * ONE_OR_MORE)
 *
 * When a reference is set using only a reference name (without these parameters), evitaDB
 * attempts to look up the reference schema. If no schema exists, this exception is thrown
 * because automatic schema creation cannot proceed without the required metadata.
 *
 * **Resolution**: Use a method variant that explicitly specifies the target entity type and
 * cardinality, such as
 * {@link io.evitadb.api.requestResponse.data.ReferenceEditor#setReference(String, String, io.evitadb.api.requestResponse.schema.Cardinality, int)}.
 *
 * This exception is typically raised during entity building operations when using simplified
 * reference-setting methods that assume schema pre-existence.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferenceNotKnownException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -7527988031906578241L;
	/**
	 * The name of the reference that could not be resolved in the schema.
	 */
	@Getter private final String referenceName;

	/**
	 * Constructs a new exception indicating that a reference schema does not exist and cannot be
	 * automatically created without additional metadata.
	 *
	 * @param referenceName the name of the reference schema that does not exist
	 */
	public ReferenceNotKnownException(String referenceName) {
		super("Reference schema for name `" + referenceName + "` doesn't exist." +
			      " Use method that specifies target entity type and cardinality instead!");
		this.referenceName = referenceName;
	}
}
