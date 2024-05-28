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

package io.evitadb.core.exception;

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown when there is attempt to create facet summary for a reference that is not marked as `faceted`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class ReferenceNotFacetedException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -7827455529865183711L;

	public ReferenceNotFacetedException(@Nonnull String referenceName, @Nonnull EntitySchemaContract entitySchema) {
		super(
			"Reference with name `" + referenceName + "` in entity `" + entitySchema.getName() + "` is not " +
				"faceted and cannot be part of the facet summary."
		);
	}

}
