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
import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.api.requestResponse.cdc.SystemCaptureBody;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.api.system.model.cdc.CatalogInstalledIntoLiveViewDescriptor;
import io.evitadb.externalApi.api.system.model.cdc.CatalogRemovedFromLiveViewDescriptor;
import io.evitadb.externalApi.api.system.model.cdc.ChangeSystemCaptureDescriptor;
import io.evitadb.externalApi.api.system.resolver.mutation.DelegatingEngineMutationConverter;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationObjectMapper;
import io.evitadb.externalApi.rest.api.catalog.resolver.mutation.RestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.rest.api.resolver.serializer.ObjectJsonSerializer;
import io.evitadb.externalApi.rest.io.RestHandlingContext;

import javax.annotation.Nonnull;

/**
 * Serializes {@link ChangeSystemCapture} to JSON for REST API.
 *
 * The body field is polymorphic at runtime: it carries either an
 * {@link EngineMutation} (durable, WAL-replicated, default-area `ENGINE` events) or a
 * {@link HostSystemEvent} (host-local, non-replicable, opt-in `HOST` events).
 * Engine mutations are delegated to the existing
 * {@link DelegatingEngineMutationConverter}; host events are serialized inline with a
 * `type` discriminator naming the variant (`CatalogInstalledIntoLiveView`,
 * `CatalogRemovedFromLiveView`).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public class ChangeSystemCaptureSerializer {

	/**
	 * Discriminator field name used on serialized {@link HostSystemEvent} JSON to identify
	 * the concrete variant. Mirrors the descriptor `name` of each host-event type.
	 */
	private static final String HOST_EVENT_TYPE_FIELD = "type";

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

		rootNode.putIfAbsent(
			ChangeSystemCaptureDescriptor.VERSION.name(),
			this.objectJsonSerializer.serializeObject(systemCapture.version())
		);
		rootNode.putIfAbsent(
			ChangeSystemCaptureDescriptor.INDEX.name(),
			this.objectJsonSerializer.serializeObject(systemCapture.index())
		);
		rootNode.putIfAbsent(
			ChangeSystemCaptureDescriptor.OPERATION.name(),
			this.objectJsonSerializer.serializeObject(systemCapture.operation())
		);

		final SystemCaptureBody body = systemCapture.body();
		if (body == null) {
			// HEADER content (or body intentionally omitted) — no body field on the wire
			return rootNode;
		}
		if (body instanceof EngineMutation<?> engineMutation) {
			rootNode.putIfAbsent(
				ChangeSystemCaptureDescriptor.BODY.name(),
				(JsonNode) this.delegatingEngineMutationConverter.convertToOutput(engineMutation)
			);
		} else if (body instanceof HostSystemEvent.CatalogInstalledIntoLiveView installed) {
			rootNode.putIfAbsent(
				ChangeSystemCaptureDescriptor.BODY.name(),
				serializeCatalogInstalled(installed)
			);
		} else if (body instanceof HostSystemEvent.CatalogRemovedFromLiveView removed) {
			rootNode.putIfAbsent(
				ChangeSystemCaptureDescriptor.BODY.name(),
				serializeCatalogRemoved(removed)
			);
		} else {
			throw new GenericEvitaInternalError(
				"Unsupported `ChangeSystemCapture` body kind: " + body.getClass().getName()
			);
		}

		return rootNode;
	}

	@Nonnull
	private ObjectNode serializeCatalogInstalled(
		@Nonnull HostSystemEvent.CatalogInstalledIntoLiveView installed
	) {
		final ObjectNode node = this.objectJsonSerializer.objectNode();
		node.put(HOST_EVENT_TYPE_FIELD, CatalogInstalledIntoLiveViewDescriptor.THIS.name());
		node.putIfAbsent(
			CatalogInstalledIntoLiveViewDescriptor.CATALOG_NAME.name(),
			this.objectJsonSerializer.serializeObject(installed.catalogName())
		);
		node.putIfAbsent(
			CatalogInstalledIntoLiveViewDescriptor.OBSERVED_STATE.name(),
			this.objectJsonSerializer.serializeObject(installed.observedState())
		);
		node.putIfAbsent(
			CatalogInstalledIntoLiveViewDescriptor.CURRENT_ENGINE_VERSION.name(),
			this.objectJsonSerializer.serializeObject(installed.currentEngineVersion())
		);
		return node;
	}

	@Nonnull
	private ObjectNode serializeCatalogRemoved(
		@Nonnull HostSystemEvent.CatalogRemovedFromLiveView removed
	) {
		final ObjectNode node = this.objectJsonSerializer.objectNode();
		node.put(HOST_EVENT_TYPE_FIELD, CatalogRemovedFromLiveViewDescriptor.THIS.name());
		node.putIfAbsent(
			CatalogRemovedFromLiveViewDescriptor.CATALOG_NAME.name(),
			this.objectJsonSerializer.serializeObject(removed.catalogName())
		);
		node.putIfAbsent(
			CatalogRemovedFromLiveViewDescriptor.CURRENT_ENGINE_VERSION.name(),
			this.objectJsonSerializer.serializeObject(removed.currentEngineVersion())
		);
		return node;
	}
}
