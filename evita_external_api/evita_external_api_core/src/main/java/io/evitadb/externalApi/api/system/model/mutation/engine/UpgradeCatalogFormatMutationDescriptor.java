/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.externalApi.api.system.model.mutation.engine;

import io.evitadb.api.requestResponse.schema.mutation.engine.UpgradeCatalogFormatMutation;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Descriptor for {@link UpgradeCatalogFormatMutation}.
 *
 * Carries the catalog name plus the `fromProtocolVersion` / `toProtocolVersion` pair captured for observability. The
 * engine drives the `OUT_OF_DATE → BEING_UPGRADED → <prior operational state>` transition and delegates the actual
 * upgrade work to an injected executor.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface UpgradeCatalogFormatMutationDescriptor extends EngineMutationDescriptor {

	PropertyDescriptor FROM_PROTOCOL_VERSION = PropertyDescriptor.builder()
		.name("fromProtocolVersion")
		.description("""
			Storage protocol version currently present on disk. Captured for observability and for CDC consumers that
			want to correlate schema or data shape changes with the protocol bump.
			""")
		.type(nonNull(Integer.class))
		.build();
	PropertyDescriptor TO_PROTOCOL_VERSION = PropertyDescriptor.builder()
		.name("toProtocolVersion")
		.description("""
			Storage protocol version the catalog is being upgraded to (typically the engine's current supported
			storage protocol version). Captured for observability only — the upgrade executor is the component that
			branches on this value.
			""")
		.type(nonNull(Integer.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
		.representedClass(UpgradeCatalogFormatMutation.class)
		.description("""
			Mutation that upgrades a catalog's on-disk storage protocol from `fromProtocolVersion` to
			`toProtocolVersion`. While the upgrade is in flight the catalog transitions to BEING_UPGRADED; the
			completion phase of the mutation returns the catalog to its prior operational state (typically ALIVE).
			Sessions issued against the catalog while the upgrade is running receive the transient
			`CatalogBeingUpgradedException`; catalogs that need an upgrade but haven't been upgraded yet surface the
			fatal `CatalogRequiresUpgradeException` on every access attempt.
			""")
		.staticProperty(CATALOG_NAME)
		.staticProperty(FROM_PROTOCOL_VERSION)
		.staticProperty(TO_PROTOCOL_VERSION)
		.build();
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS, INPUT_OBJECT_PROPERTIES_FILTER)
		.name("UpgradeCatalogFormatMutationInput")
		.build();
}
