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

package io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference;

import io.evitadb.externalApi.api.model.ObjectDescriptor;
import io.evitadb.externalApi.api.model.PropertyDescriptor;

/**
 * Represents a reference to a specific entity. Extension of the fully generic {@link ReferenceDescriptor} that is generic
 * for references targeting the specified entity type.
 *
 * Note: this descriptor has a dynamic structure based on the referenced entity type.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public interface ReferenceWithReferencedEntityDescriptor extends ReferenceDescriptor {

	PropertyDescriptor REFERENCED_ENTITY = PropertyDescriptor.builder()
		.name("referencedEntity")
		.description("""
			Returns body of the referenced entity in case its fetching was requested via entity_fetch constraint.
			""")
		// type is expected to be a sealed entity
		.build();

	ObjectDescriptor THIS_INTERFACE = ObjectDescriptor.implementing(ReferenceDescriptor.THIS_INTERFACE)
		.name("$Reference") // name should contain the referenced entity type
		.description("Represents a reference to %s entity.")
		.build();
}
