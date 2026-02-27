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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.reference;

import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.ReferenceDefinitionDescriptor;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;

/**
 * Defines a unique key for attributes of {@link ReferenceDefinitionDescriptor}.
 * Check {@link ReferenceDefinitionKey}. The key is defined also by the parent reference data because the attributes cannot be
 * used independently.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@EqualsAndHashCode(callSuper = true)
public class ReferenceDefinitionAttributesKey extends ReferenceDefinitionKey {

	public ReferenceDefinitionAttributesKey(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String templateEntityType,
		@Nonnull String templateReferenceName
	) {
		super(
			referenceSchema,
			templateEntityType,
			templateReferenceName
		);
	}
}
