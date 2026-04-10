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
 * Uniquely identifies a single attribute within a reference schema by combining its name and Java data type.
 *
 * Instances are collected and sorted by {@link ReferenceAttributesKey} to build a canonical, ordered
 * representation of all attributes belonging to a reference schema. The ordering is defined by `name` only —
 * `dataType` participates in equality (via the record's auto-generated `equals`/`hashCode`) but is not
 * considered during comparison, so two keys with the same name but different data types are equal in ordering
 * but not in identity.
 *
 * @param name     the attribute's schema name; used as the sole ordering criterion in {@link #compareTo}
 * @param dataType the attribute's Java data type; participates in equality but not in natural ordering
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
public record ReferenceAttributeKey(
	@Nonnull String name,
	@Nonnull Class<? extends Serializable> dataType
) implements Comparable<ReferenceAttributeKey> {

	/**
	 * Compares by `name` only. Two keys that differ only in `dataType` are considered equal by this ordering
	 * even though they are not equal by `equals`.
	 */
	@Override
	public int compareTo(@Nonnull ReferenceAttributeKey o) {
		return this.name.compareTo(o.name);
	}
}
