/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.query.sort;


import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.core.query.sort.reference.translator.ReferencePropertyTranslator;

import javax.annotation.Nonnull;

/**
 * This interface extends {@link EntityComparator} and allows to bind comparison with some specific referenced entity
 * id. This context is used in {@link ReferencePropertyTranslator} when multiple references are traversed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface EntityReferenceSensitiveComparator extends EntityComparator {

	/**
	 * Executes the provided {@code lambda} within the context of a specific referenced entity ID.
	 *
	 * @param referenceKey The identifier of the reference to be used as the context for the lambda execution.
	 * @param lambda        The executable task to be performed within the context of the referenced entity ID.
	 */
	void withReferencedEntityId(@Nonnull ReferenceKey referenceKey, @Nonnull Runnable lambda);

}
