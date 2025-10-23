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

import io.evitadb.api.requestResponse.schema.Cardinality;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown when a reference cannot be uniquely identified because multiple references exist
 * and no specific reference name or identifier was provided to distinguish between them.
 * This typically occurs when trying to access a reference on an entity that has multiple references
 * of the same type without specifying which one is intended.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class AmbiguousReferenceException extends InvalidMutationException {
	@Serial private static final long serialVersionUID = -8644814260214013001L;

	public AmbiguousReferenceException(@Nonnull String referenceName, @Nonnull Cardinality cardinality) {
		super(
			"Cannot resolve reference `" + referenceName + "` without a referenced entity id - reference cardinality is " +
			cardinality + ". Please specify which reference to retrieve by providing the referenced entity id."
		);
	}

}
