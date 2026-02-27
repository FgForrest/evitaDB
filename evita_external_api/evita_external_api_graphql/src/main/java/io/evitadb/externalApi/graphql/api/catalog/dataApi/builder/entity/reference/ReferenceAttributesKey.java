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

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.util.Comparator;
import java.util.List;

/**
 * Handy container for holding {@link ReferenceAttributeKey attribute keys}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@EqualsAndHashCode
public class ReferenceAttributesKey {

	@Nonnull private final List<ReferenceAttributeKey> attributes;

	public ReferenceAttributesKey(@Nonnull ReferenceSchemaContract referenceSchema) {
		this.attributes = referenceSchema.getAttributes().values()
			.stream()
			.sorted(Comparator.comparing(AttributeSchemaContract::getName))
			.map(it -> new ReferenceAttributeKey(it.getName(), it.getType()))
			.toList();
	}
}
