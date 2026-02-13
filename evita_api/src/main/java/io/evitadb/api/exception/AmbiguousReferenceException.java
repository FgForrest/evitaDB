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
 * Thrown when attempting to retrieve or manipulate a reference without providing sufficient information
 * to uniquely identify which reference is intended, in cases where the reference schema allows multiple
 * references of the same type.
 *
 * This exception occurs when working with references that have {@link Cardinality#ZERO_OR_MORE} or
 * {@link Cardinality#ONE_OR_MORE} cardinality. In these cases, an entity can have multiple references
 * with the same reference name but different referenced entity IDs. Operations that don't specify the
 * referenced entity ID cannot determine which specific reference to operate on.
 *
 * **When this is thrown:**
 * - Calling proxy interface methods that retrieve or set references without providing referenced entity ID
 * - Using reference mutation methods on multi-cardinality references without specifying which one
 * - Thrown by `GetReferenceMethodClassifier` and `SetReferenceMethodClassifier` during proxy method dispatch
 *
 * **Resolution:**
 * - Provide the referenced entity ID to disambiguate which reference you want
 * - Use methods that accept both reference name and referenced entity ID
 * - Iterate over all references with the same name if you need to process them all
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class AmbiguousReferenceException extends InvalidMutationException {
	@Serial private static final long serialVersionUID = -8644814260214013001L;

	/**
	 * Creates exception indicating that a reference lookup requires the referenced entity ID due to
	 * the reference's cardinality allowing multiple instances.
	 */
	public AmbiguousReferenceException(@Nonnull String referenceName, @Nonnull Cardinality cardinality) {
		super(
			"Cannot resolve reference `" + referenceName + "` without a referenced entity id - reference cardinality is " +
			cardinality + ". Please specify which reference to retrieve by providing the referenced entity id."
		);
	}

}
