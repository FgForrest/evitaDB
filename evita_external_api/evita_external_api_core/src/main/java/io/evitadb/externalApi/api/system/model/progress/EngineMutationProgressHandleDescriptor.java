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

/**
 * Descriptor of the handle returned by an asynchronous (`...WithProgress`) engine operation. The handle identifies
 * the accepted operation; the client observes its progress and outcome by subscribing with the id.
 *
 * Note: this descriptor has static structure.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public interface EngineMutationProgressHandleDescriptor {

	PropertyDescriptor PROGRESS_ID = PropertyDescriptor.builder()
		.name("progressId")
		.description("""
			Id under which the engine tracks the accepted operation. Use it to subscribe to the progress of the
			operation. The outcome of a finished operation is retained only for a limited time and in memory of the
			server only - after a restart the id is unknown and the state of the catalog has to be read instead.
			""")
		.type(nonNull(UUID.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("EngineMutationProgressHandle")
		.description("""
			Handle of an accepted engine operation (e.g. activation or renaming of a catalog) that runs in the
			background.
			""")
		.staticProperties(List.of(PROGRESS_ID))
		.build();
}
