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

package io.evitadb.externalApi.api.system.model.cdc;

import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nonNull;

/**
 * Static descriptor for {@link HostSystemEvent.CatalogSchemaUpdated}.
 *
 * Fires when a catalog's schema version advanced on this host. Coalesced once per non-ALIVE
 * session close whose schema advanced and once per `Evita#replaceCatalogReference` invocation
 * whose schema advanced (covering ALIVE transaction commits, boot load, post-upgrade replace,
 * post-activation, deactivation). Replaces the per-mutation refresh storm previously observed
 * by GraphQL / REST managers reacting to every `ModifyCatalogSchemaMutation` /
 * `SetCatalogMutabilityMutation`. Carries the catalog name, the new (current) schema version
 * on this host, and a snapshot of the engine version for correlation only — receiving this
 * event does not advance the engine version counter.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface CatalogSchemaUpdatedDescriptor {

	PropertyDescriptor CATALOG_NAME = PropertyDescriptor.builder()
		.name("catalogName")
		.description("""
			Name of the catalog whose schema version increased on this host.
			""")
		.type(nonNull(String.class))
		.build();
	PropertyDescriptor NEW_SCHEMA_VERSION = PropertyDescriptor.builder()
		.name("newSchemaVersion")
		.description("""
			The new (current) catalog schema version on this host. Coalesced once per
			session/transaction whose schema version actually advanced.
			""")
		.type(nonNull(Integer.class))
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
		.name("CatalogSchemaUpdated")
		.description("""
			Host-local event signalling that a catalog's schema version advanced on this host.
			Coalesced once per non-ALIVE session close whose schema advanced and once per
			`replaceCatalogReference` invocation whose schema advanced (covers ALIVE transaction
			commits, boot load, post-upgrade replace, post-activation, deactivation). Subscribers
			should treat this as the single 'rebuild your view of the catalog schema' signal.
			""")
		.staticProperty(CATALOG_NAME)
		.staticProperty(NEW_SCHEMA_VERSION)
		.staticProperty(CURRENT_ENGINE_VERSION)
		.representedClass(HostSystemEvent.CatalogSchemaUpdated.class)
		.build();
}
