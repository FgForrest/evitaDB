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

import io.evitadb.api.CatalogState;
import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Static descriptor for {@link HostSystemEvent.CatalogInstalledIntoLiveView}.
 *
 * Fires when a catalog's local reference settles into a non-transient state on this host.
 * Carries the catalog name, the observed (settled) state, and a snapshot of the engine
 * version for correlation only — receiving this event does not advance the engine version
 * counter.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface CatalogInstalledIntoLiveViewDescriptor {

	PropertyDescriptor CATALOG_NAME = PropertyDescriptor.builder()
		.name("catalogName")
		.description("""
			Name of the catalog whose local reference settled into a non-transient state on
			this host.
			""")
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor OBSERVED_STATE = PropertyDescriptor.builder()
		.name("observedState")
		.description("""
			The non-transient state the catalog settled into (e.g. `ALIVE`, `WARMING_UP`,
			`INACTIVE`, `OUT_OF_DATE`, `CORRUPTED`, `MISSING`). Never a `BEING_*` /
			`GOING_ALIVE` value — those are programming errors at this layer.
			""")
		.type(nonNull(CatalogState.class))
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
		.name("CatalogInstalledIntoLiveView")
		.description("""
			Host-local event signalling that a catalog's local reference settled into a
			non-transient state on this host. Subscribers can treat this as the authoritative
			"catalog X is now usable / now in state Y on this host" signal.
			""")
		.staticProperty(CATALOG_NAME)
		.staticProperty(OBSERVED_STATE)
		.staticProperty(CURRENT_ENGINE_VERSION)
		.representedClass(HostSystemEvent.CatalogInstalledIntoLiveView.class)
		.build();
}
