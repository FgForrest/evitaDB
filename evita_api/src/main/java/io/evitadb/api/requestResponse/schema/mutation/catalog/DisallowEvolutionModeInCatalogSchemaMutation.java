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

package io.evitadb.api.requestResponse.schema.mutation.catalog;

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.annotation.SerializableCreator;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mutation is responsible for adding one or more evolution modes to
 * a {@link CatalogSchemaContract#getCatalogEvolutionMode()} in {@link CatalogSchemaContract}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class DisallowEvolutionModeInCatalogSchemaMutation implements LocalCatalogSchemaMutation, CatalogSchemaMutation {
	@Serial private static final long serialVersionUID = 4074000557741311141L;
	@Getter private final Set<CatalogEvolutionMode> evolutionModes;

	public DisallowEvolutionModeInCatalogSchemaMutation(@Nonnull Set<CatalogEvolutionMode> evolutionModes) {
		this.evolutionModes = EnumSet.noneOf(CatalogEvolutionMode.class);
		this.evolutionModes.addAll(evolutionModes);
	}

	@SerializableCreator
	public DisallowEvolutionModeInCatalogSchemaMutation(@Nonnull CatalogEvolutionMode... evolutionModes) {
		this.evolutionModes = EnumSet.noneOf(CatalogEvolutionMode.class);
		this.evolutionModes.addAll(Arrays.asList(evolutionModes));
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor) {
		Assert.isPremiseValid(catalogSchema != null, "Catalog schema is mandatory!");
		if (catalogSchema.getCatalogEvolutionMode().stream().noneMatch(this.evolutionModes::contains)) {
			// no need to change the schema
			return new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
		} else {
			return new CatalogSchemaWithImpactOnEntitySchemas(
				CatalogSchema._internalBuild(
					catalogSchema.version() + 1,
					catalogSchema.getName(),
					catalogSchema.getNameVariants(),
					catalogSchema.getDescription(),
					catalogSchema.getCatalogEvolutionMode()
						.stream()
						.filter(it -> !this.evolutionModes.contains(it))
						.collect(Collectors.toSet()),
					catalogSchema.getAttributes(),
					entitySchemaAccessor
				)
			);
		}
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "Disallow: evolutionModes=" + this.evolutionModes;
	}
}
