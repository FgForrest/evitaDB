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

package io.evitadb.externalApi.rest.api.system.resolver.serializer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.DelegatingEngineMutationConverter;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationObjectMapper;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.externalApi.rest.io.RestHandlingContext;

import javax.annotation.Nonnull;

/**
 * Serializes {@Link ChangeSystemCapture} to JSON for REST API.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class ChangeSystemCaptureSerializer {

	@Nonnull
	private final ObjectJsonSerializer objectJsonSerializer;
	@Nonnull
	private final DelegatingEngineMutationConverter delegatingEngineMutationConverter;

	public ChangeSystemCaptureSerializer(@Nonnull RestHandlingContext restHandlingContext) {
		this.objectJsonSerializer = new ObjectJsonSerializer(restHandlingContext.getObjectMapper());
		this.delegatingEngineMutationConverter = new DelegatingEngineMutationConverter(
			new RestMutationObjectMapper(restHandlingContext.getObjectMapper()),
			RestMutationResolvingExceptionFactory.INSTANCE
		);
	}

	@Nonnull
	public ObjectNode serialize(@Nonnull ChangeSystemCapture systemCapture) {
		final ObjectNode rootNode = this.objectJsonSerializer.objectNode();

		// todo lho descriptor?
		rootNode.putIfAbsent("version", this.objectJsonSerializer.serializeObject(systemCapture.version()));
		rootNode.putIfAbsent("index", this.objectJsonSerializer.serializeObject(systemCapture.index()));
		rootNode.putIfAbsent("operation", this.objectJsonSerializer.serializeObject(systemCapture.operation()));

		if (systemCapture.body() != null) {
			rootNode.putIfAbsent("body", (JsonNode) this.delegatingEngineMutationConverter.convertToOutput(systemCapture.body()));
		}

		return rootNode;
	}
}
