/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.externalApi.api.system.model.progress;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import java.util.List;
import java.util.UUID;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;
import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Descriptor of one message of the progress stream of an engine operation.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public interface EngineMutationProgressChangeDescriptor {

	PropertyDescriptor PROGRESS_ID = PropertyDescriptor.builder()
		.name("progressId")
		.description("""
			Id of the observed operation.
			""")
		.type(nonNull(UUID.class))
		.build();
	PropertyDescriptor PERCENT_COMPLETED = PropertyDescriptor.builder()
		.name("percentCompleted")
		.description("""
			Percentage of completion of the operation, from 0 to 100. Intermediate values may be skipped when the
			client consumes the stream slower than the operation progresses.
			""")
		.type(nonNull(Integer.class))
		.build();
	PropertyDescriptor FINISHED = PropertyDescriptor.builder()
		.name("finished")
		.description("""
			Whether the operation has finished successfully. It is the last message of the stream, the stream is
			completed right after it. A failed operation does not produce such a message - the stream ends with an
			error carrying the failure instead.
			""")
		.type(nonNull(Boolean.class))
		.build();
	PropertyDescriptor CATALOG_VERSION = PropertyDescriptor.builder()
		.name("catalogVersion")
		.description("""
			Version of the catalog after the operation has finished, for operations that report it (making
			a catalog alive, renaming or replacing it). Null otherwise.
			""")
		.type(nullable(Long.class))
		.build();
	PropertyDescriptor CATALOG_SCHEMA_VERSION = PropertyDescriptor.builder()
		.name("catalogSchemaVersion")
		.description("""
			Version of the catalog schema after the operation has finished, for operations that report it (making
			a catalog alive, renaming or replacing it). Null otherwise.
			""")
		.type(nullable(Integer.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("EngineMutationProgressChange")
		.description("""
			Progress of an engine operation (e.g. activation or renaming of a catalog). The first message carries the
			state of the operation at the moment of subscription, the following ones its changes. A successful
			operation ends with a message with `finished` set to `true`.
			""")
		.staticProperties(List.of(PROGRESS_ID, PERCENT_COMPLETED, FINISHED, CATALOG_VERSION, CATALOG_SCHEMA_VERSION))
		.build();
}
