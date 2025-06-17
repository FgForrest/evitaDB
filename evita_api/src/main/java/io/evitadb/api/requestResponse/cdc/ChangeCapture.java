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

package io.evitadb.api.requestResponse.cdc;

import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * CDC event that is sent to the subscriber if it matches to the request he made.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public sealed interface ChangeCapture extends Serializable permits ChangeSystemCapture, ChangeCatalogCapture {

	/**
	 * Returns the target version of the data source to which this mutation advances it.
	 */
	long version();

	/**
	 * Returns the index of the event within the enclosed version. If the operation is part of the multi-step process,
	 * the index starts with 0 and increments with each such operation. Next capture with {@link #version()} + 1 always
	 * starts with index 0.
	 *
	 * This index allows client to build on the previously interrupted CDC stream even in the middle of the transaction.
	 * This is beneficial in case of very large transactions that still needs to be fully transferred to the client, but
	 * could be done so in multiple separate chunks.
	 *
	 * Combination of {@link #version()} and this index precisely identifies the position of a single operation in
	 * the CDC stream.
	 */
	int index();

	/**
	 * The operation that was performed.
	 */
	@Nonnull
	Operation operation();

	/**
	 * Optional body of the operation when it is requested initial request. Carries information about what exactly
	 * happened.
	 */
	@Nullable
	Mutation body();

	/**
	 * If the content is {@link ChangeCaptureContent#HEADER} and this instance contains non-null body, then this method
	 * returns new instance of the same type with the body set to null. If the requested content matches the current
	 * body contents, then this method returns the same instance. When the body is null, and the requested content
	 * is {@link ChangeCaptureContent#BODY}, then this method throws {@link EvitaInternalError} exception.
	 *
	 * @param content the requested content of the capture
	 * @return adapted instance result
	 * @throws EvitaInternalError if the requested content is {@link ChangeCaptureContent#BODY} and the body is null
	 */
	@Nonnull
	<T extends ChangeCapture> T as(@Nonnull ChangeCaptureContent content) throws EvitaInternalError;

}
