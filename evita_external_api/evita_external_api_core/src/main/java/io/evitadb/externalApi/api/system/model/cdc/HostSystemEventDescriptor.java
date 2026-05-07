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

package io.evitadb.externalApi.api.system.model.cdc;

import io.evitadb.externalApi.api.model.UnionDescriptor;

/**
 * Union descriptor for the polymorphic {@link io.evitadb.api.requestResponse.cdc.HostSystemEvent}
 * sealed interface. Both record variants
 * ({@link io.evitadb.api.requestResponse.cdc.HostSystemEvent.CatalogInstalledIntoLiveView},
 * {@link io.evitadb.api.requestResponse.cdc.HostSystemEvent.CatalogRemovedFromLiveView}) are
 * registered as union members.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface HostSystemEventDescriptor {

	UnionDescriptor THIS = UnionDescriptor.builder()
		.name("HostSystemEvent")
		.description("""
			Lists all possible host events delivered on the system CDC
			stream when the subscriber has explicitly opted into the `HOST` area.
			""")
		.type(CatalogInstalledIntoLiveViewDescriptor.THIS)
		.type(CatalogRemovedFromLiveViewDescriptor.THIS)
		.build();
}
