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

package io.evitadb.api.requestResponse.cdc;

import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Record represents a CDC event that is sent to the subscriber if it matches to the request he made.
 *
 * @param version       the version of the catalog where the operation was performed
 * @param index         the index of the event within the enclosed transaction, index 0 is the transaction lead event
 * @param area          the area of the operation
 * @param entityType    the {@link EntitySchema#getName()} of the entity or its schema that was affected by the operation
 *                      (if the operation is executed on catalog schema this field is null)
 * @param entityVersion the current version of the entity or its schema that was affected by the operation
 * @param operation     the operation that was performed
 * @param body          optional body of the operation when it is requested by the {@link ChangeSystemCaptureRequest#content()}
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public record ChangeCatalogCapture(
	long version,
	int index,
	@Nonnull CaptureArea area,
	@Nullable String entityType,
	@Nullable Integer entityVersion,
	@Nonnull Operation operation,
	@Nullable Mutation body
) implements ChangeCapture {
}
