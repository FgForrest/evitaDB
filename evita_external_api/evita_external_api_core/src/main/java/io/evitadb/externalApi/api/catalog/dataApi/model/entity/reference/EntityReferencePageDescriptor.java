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

package io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference;

import io.evitadb.externalApi.api.model.ObjectDescriptor;

/**
 * Represents a paginated list of {@link EntityReferenceDescriptor#THIS_INTERFACE}.
 *
 * Note: this descriptor has a dynamic structure based on the referenced entity type.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public interface EntityReferencePageDescriptor extends ReferenceDefinitionPageDescriptor {

	ObjectDescriptor THIS_INTERFACE = ObjectDescriptor.implementing(ReferenceDefinitionPageDescriptor.THIS_INTERFACE)
		.name("$$ReferencePage") // the name consists of the source entity type and the reference name
		.description("Page of references %s entity%s according to pagination rules in input query.") // the rest of the description should be dynamically constructed based on present data
		.build();
}
