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

package io.evitadb.api.requestResponse.data.structure;


import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceEditor.ReferenceBuilder;

import javax.annotation.Nonnull;

/**
 * This interface extends the {@link EntityBuilder} with methods that are considered to be a part of private API
 * and are not supposed to be used by the clients directly.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface InternalEntityBuilder extends EntityBuilder {

	/**
	 * Returns next internal id that can be used for newly created reference from an internal sequence.
	 * @return next internal reference id
	 */
	int getNextReferenceInternalId();

	/**
	 * Adds new set of reference mutations for particular `referenceKey`, if some set is already present for that key,
	 * it is replaced by a new set.
	 *
	 * This method is considered to be a part of private API.
	 *
	 * @param referenceBuilder reference builder wrapping the changes in the reference contract
	 */
	void addOrReplaceReferenceMutations(@Nonnull ReferenceBuilder referenceBuilder);

}
