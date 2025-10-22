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

package io.evitadb.api.exception;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown when there is an attempt to access a single reference by {@link ReferenceKey}, but such reference
 * is know to allow / have duplicates and method returning collection of references by {@link ReferenceKey} needs to be
 * used.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferenceAllowsDuplicatesException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -5464745624918378010L;

	public ReferenceAllowsDuplicatesException(@Nonnull String referenceName, @Nonnull EntitySchemaContract entitySchema, @Nonnull Operation operation) {
		super(
			switch (operation) {
				case CREATE -> "Reference with name `" + referenceName + "` of `" + entitySchema.getName() + "` doesn't allow duplicates, but there is already one present - this is not expected at this moment!";
				case READ -> "Reference with name `" + referenceName + "` of `" + entitySchema.getName() + "` entity allows duplicates, you need to use a method returning a collection of references.";
				case WRITE -> "Reference with name `" + referenceName + "` of `" + entitySchema.getName() + "` entity allows duplicates, you need to use a method accepting predicate to select the correct instance.";
				case WRITE_MULTIPLE_MATCHES -> "Reference with name `" + referenceName + "` of `" + entitySchema.getName() + "` entity allows duplicates and there are multiple ones matching your predicate. Please narrow the predicate logic to match exactly one reference.";
				case MUTATE -> "Reference with name `" + referenceName + "` of `" + entitySchema.getName() + "` entity allows duplicates, you need to exactly specify reference using its internal primary key.";
			}
		);
	}

	/**
	 * Type of operation that was attempted on the reference.
	 */
	public enum Operation {
		READ,
		WRITE,
		WRITE_MULTIPLE_MATCHES,
		CREATE,
		MUTATE
	}

}
