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

package io.evitadb.api.requestResponse.data;


import io.evitadb.api.exception.ContextMissingException;

import javax.annotation.Nonnull;

/**
 * The interface contains method for checking the presence of the references in fetched data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ReferenceAvailabilityChecker {

	/**
	 * Returns true if entity references were fetched along with the entity. Calling this method before calling any
	 * other method that requires references to be fetched will allow you to avoid {@link ContextMissingException}.
	 */
	boolean referencesAvailable();

	/**
	 * Returns true if references of particular name was fetched along with the entity. Calling this method
	 * before calling any other method that requires references to be fetched will allow you to avoid
	 * {@link ContextMissingException}.
	 *
	 * @return true if at least one reference of particular name were fetched along with the entity
	 */
	boolean referencesAvailable(@Nonnull String referenceName);

}
