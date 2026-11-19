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

package io.evitadb.externalApi.rest.api.system.resolver.serializer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.core.transaction.engine.EngineMutationProgressStatus;
import io.evitadb.externalApi.api.system.model.progress.EngineMutationProgressChangeDescriptor;
import io.evitadb.externalApi.rest.io.RestHandlingContext;

import javax.annotation.Nonnull;

/**
 * Serializes {@link EngineMutationProgressStatus} into the JSON message of the engine mutation progress stream, as
 * described by {@link EngineMutationProgressChangeDescriptor}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class EngineMutationProgressChangeSerializer {

	@Nonnull private final RestHandlingContext restHandlingContext;

	public EngineMutationProgressChangeSerializer(@Nonnull RestHandlingContext restHandlingContext) {
		this.restHandlingContext = restHandlingContext;
	}

	/**
	 * Serializes one progress status.
	 *
	 * @param status the status
	 * @return JSON message
	 */
	@Nonnull
	public ObjectNode serialize(@Nonnull EngineMutationProgressStatus status) {
		final ObjectNode node = this.restHandlingContext.getObjectMapper().createObjectNode();
		node.put(EngineMutationProgressChangeDescriptor.PROGRESS_ID.name(), status.progressId().toString());
		node.put(EngineMutationProgressChangeDescriptor.PERCENT_COMPLETED.name(), status.percentCompleted());
		node.put(EngineMutationProgressChangeDescriptor.FINISHED.name(), status.finished());
		if (status.catalogVersion() != null) {
			node.put(EngineMutationProgressChangeDescriptor.CATALOG_VERSION.name(), status.catalogVersion());
		}
		if (status.catalogSchemaVersion() != null) {
			node.put(
				EngineMutationProgressChangeDescriptor.CATALOG_SCHEMA_VERSION.name(), status.catalogSchemaVersion()
			);
		}
		return node;
	}
}
