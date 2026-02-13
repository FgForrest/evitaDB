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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.reference;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * Defines a unique key for a single {@link io.evitadb.api.requestResponse.schema.AttributeSchemaContract reference attribute}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public record ReferenceAttributeKey(@Nonnull String name, @Nonnull Class<? extends Serializable> dataType) implements Comparable<ReferenceAttributeKey> {

	@Override
	public int compareTo(ReferenceAttributeKey o) {
		return this.name.compareTo(o.name);
	}
}
