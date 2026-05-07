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

import io.evitadb.api.requestResponse.cdc.SystemCaptureArea;
import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

import static io.evitadb.externalApi.api.model.PrimitivePropertyDataTypeDescriptor.nullable;

/**
 * Static descriptor for {@link io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureCriteria}.
 *
 * Note: the system CDC criteria has only a single axis (`area`) — unlike
 * {@link io.evitadb.externalApi.api.catalog.model.cdc.ChangeCatalogCaptureCriteriaDescriptor}
 * which also exposes `schemaSite` and `dataSite`. The `site` axis is intentionally
 * omitted on the system stream and reserved for future extensions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ChangeSystemCaptureCriteriaDescriptor {

	PropertyDescriptor AREA = PropertyDescriptor.builder()
		.name("area")
		.description("""
			The requested system area for the capture. `ENGINE` selects durable, WAL-replicated
			engine-level mutations. `HOST` selects host-local, non-replicable,
			live-tail-only host events. When `null` within an explicit criteria element, the
			element matches any area (OR-of-criteria semantics).
			""")
		.type(nullable(SystemCaptureArea.class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ChangeSystemCaptureCriteria")
		.description("""
			Record for the criteria of a system capture request, allowing the subscriber to
			limit the captured events to a single `SystemCaptureArea`. Multiple criteria are
			combined with OR semantics.
			""")
		.staticProperty(AREA)
		.build();
}
