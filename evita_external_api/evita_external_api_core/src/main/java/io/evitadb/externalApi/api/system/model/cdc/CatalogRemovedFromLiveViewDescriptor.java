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

import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Static descriptor for {@link HostSystemEvent.CatalogRemovedFromLiveView}.
 *
 * Fires when a catalog is fully removed from the live view on this host (after the
 * `BEING_DELETED` transition completes and the entry is gone from the engine state map).
 * The catalog is no longer addressable on this host. Carries a snapshot of the engine
 * version for correlation only — receiving this event does not advance the engine version
 * counter.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface CatalogRemovedFromLiveViewDescriptor {

	PropertyDescriptor CATALOG_NAME = PropertyDescriptor.builder()
		.name("catalogName")
		.description("""
			Name of the catalog that was removed from the live view on this host.
			""")
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor CURRENT_ENGINE_VERSION = PropertyDescriptor.builder()
		.name("currentEngineVersion")
		.description("""
			Snapshot of the engine version at the moment this event was emitted. Provided for
			correlation with the preceding engine mutation only — host events never advance
			the engine version counter.
			""")
		.type(nonNull(Long.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("CatalogRemovedFromLiveView")
		.description("""
			Host-local event signalling that a catalog is fully removed from the live view on
			this host. The catalog is no longer addressable; subscribers should drop any
			cached endpoints or schemas associated with the named catalog.
			""")
		.staticProperty(CATALOG_NAME)
		.staticProperty(CURRENT_ENGINE_VERSION)
		.representedClass(HostSystemEvent.CatalogRemovedFromLiveView.class)
		.build();
}
