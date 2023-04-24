/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.schema.mutation.catalog;

import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.utils.ClassifierUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;

/**
 * Mutation is responsible for setting up a new {@link EntitySchemaContract} - or more precisely
 * the {@link EntityCollectionContract} instance within {@link io.evitadb.api.CatalogContract} instance.
 * The mutation is used by {@link io.evitadb.api.CatalogContract#getOrCreateCollectionForEntity(String, EvitaSessionContract)} method
 * internally.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class CreateEntitySchemaMutation implements LocalCatalogSchemaMutation {
	@Serial private static final long serialVersionUID = 5167037327442001715L;
	@Nonnull @Getter private final String name;

	public CreateEntitySchemaMutation(@Nonnull String name) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, name);
		this.name = name;
	}

	@Nullable
	@Override
	public CatalogSchemaContract mutate(@Nullable CatalogSchemaContract catalogSchema) {
		// do nothing - the mutation is handled differently
		return catalogSchema;
	}

	@Override
	public String toString() {
		return "Create entity schema: " +
			"name='" + name + '\'';
	}
}
