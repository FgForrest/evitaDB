/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.store.catalog.model;

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * This class maintains meta information necessary for proper deserialization of the Kryo serialized data. Contains
 * compressed key information, used class ids.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class MutableCatalogHeader {
	@Getter private final String catalogName;
	@Getter private final Map<String, MutableCatalogEntityHeader> entityTypesIndex;

	public MutableCatalogHeader(String catalogName, String... entityTypes) {
		this.catalogName = catalogName;
		this.entityTypesIndex = Arrays.stream(entityTypes)
			.sorted()
			.collect(
				Collectors.toMap(
					Function.identity(),
					entityType -> new MutableCatalogEntityHeader(
						entityType,
						0,
						Collections.emptyMap()
					),
					(header1, header2) -> {
						throw new EvitaInvalidUsageException("Duplicated entity types " + header1.getEntityType());
					},
					LinkedHashMap::new
				)
			);
	}

	public MutableCatalogHeader(String catalogName, Collection<MutableCatalogEntityHeader> entityHeaders) {
		this.catalogName = catalogName;
		this.entityTypesIndex = entityHeaders
			.stream()
			.collect(
				Collectors.toMap(
					MutableCatalogEntityHeader::getEntityType,
					it -> it,
					(header1, header2) -> {
						throw new EvitaInvalidUsageException("Duplicated entity types " + header1.getEntityType());
					},
					LinkedHashMap::new
				)
			);
	}

	/**
	 * Adds new entity type to this catalog.
	 */
	public void addEntityType(MutableCatalogEntityHeader entityHeader) {
		Assert.isTrue(
			!this.entityTypesIndex.containsKey(entityHeader.getEntityType()),
			"This catalog already contains entity of type " + entityHeader.getEntityType()
		);
		this.entityTypesIndex.put(entityHeader.getEntityType(), entityHeader);
	}

	/**
	 * Returns list of all known entity types registered in this header.
	 */
	public Collection<String> getEntityTypes() {
		return this.entityTypesIndex.keySet();
	}

	/**
	 * Returns catalog entity header for particular entity type (if present).
	 */
	@Nullable
	public MutableCatalogEntityHeader getEntityTypeHeader(@Nonnull String entityType) {
		return this.entityTypesIndex.get(entityType);
	}

	@Override
	public int hashCode() {
		int result = this.catalogName.hashCode();
		result = 31 * result + this.entityTypesIndex.hashCode();
		return result;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		MutableCatalogHeader that = (MutableCatalogHeader) o;

		if (!this.catalogName.equals(that.catalogName)) return false;
		return this.entityTypesIndex.equals(that.entityTypesIndex);
	}

}
