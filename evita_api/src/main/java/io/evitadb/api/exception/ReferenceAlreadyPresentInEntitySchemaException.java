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

import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.NamingConvention;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown when client code tries to define the reference schema with same name as existing entity
 * reference schema. This is not allowed and client must choose different name or reuse the already defined reference.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferenceAlreadyPresentInEntitySchemaException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 4058501830732065207L;
	@Getter private final ReferenceSchemaContract existingSchema;

	public ReferenceAlreadyPresentInEntitySchemaException(
		@Nonnull ReferenceSchemaContract existingReferenceSchema,
		@Nonnull ReferenceSchemaContract updatedReferenceSchema,
		@Nonnull NamingConvention convention,
		@Nonnull String conflictingName) {
		super(
			"Reference schema `" + updatedReferenceSchema.getName() + "` and existing " +
				"reference schema `" + existingReferenceSchema.getName() + "` produce the same " +
				"name `" + conflictingName + "` in `" + convention + "` convention! " +
				"Please choose different reference schema name."
		);
		this.existingSchema = existingReferenceSchema;
	}
}
