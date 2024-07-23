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

import io.evitadb.api.exception.ContextMissingException;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * The interface contains method for checking the presence of the associated data in fetched data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface AssociatedDataAvailabilityChecker {

	/**
	 * Returns true if entity associated data were fetched along with the entity. Calling this method before calling any
	 * other method that requires associated data to be fetched will allow you to avoid {@link ContextMissingException}.
	 */
	boolean associatedDataAvailable();

	/**
	 * Returns true if entity associated data in specified locale were fetched along with the entity. Calling this
	 * method before calling any other method that requires associated data to be fetched will allow you to avoid
	 * {@link ContextMissingException}.
	 */
	boolean associatedDataAvailable(@Nonnull Locale locale);

	/**
	 * Returns true if entity associated data of particular name was fetched along with the entity. Calling this method
	 * before calling any other method that requires associated data to be fetched will allow you to avoid
	 * {@link ContextMissingException}.
	 */
	boolean associatedDataAvailable(@Nonnull String associatedDataName);

	/**
	 * Returns true if entity associated data of particular name in particular locale was fetched along with the entity.
	 * Calling this method before calling any other method that requires associated data to be fetched will allow you to
	 * avoid {@link ContextMissingException}.
	 */
	boolean associatedDataAvailable(@Nonnull String associatedDataName, @Nonnull Locale locale);
}
