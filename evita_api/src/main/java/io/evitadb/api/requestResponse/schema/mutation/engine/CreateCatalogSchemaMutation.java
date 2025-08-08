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

package io.evitadb.api.requestResponse.schema.mutation.engine;

import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.CatalogConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.MutationEntitySchemaAccessor;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import io.evitadb.utils.NamingConvention;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.EnumSet;
import java.util.stream.Stream;

/**
 * Mutation is responsible for setting up a new {@link CatalogSchemaContract} - or more precisely the catalog instance
 * itself. The mutation is used by {@link EvitaContract#defineCatalog(String)} method internally.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class CreateCatalogSchemaMutation implements TopLevelCatalogSchemaMutation<CommitVersions> {
	@Serial private static final long serialVersionUID = 6996920692477020274L;
	@Nonnull @Getter private final String catalogName;

	public CreateCatalogSchemaMutation(@Nonnull String catalogName) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.CATALOG, catalogName);
		this.catalogName = catalogName;
	}

	@Nonnull
	@Override
	public Class<CommitVersions> getProgressResultType() {
		return CommitVersions.class;
	}

	@Nonnull
	@Override
	public Stream<ConflictKey> getConflictKeys() {
		return Stream.of(new CatalogConflictKey(this.catalogName));
	}

	@Override
	public void verifyApplicability(@Nonnull EvitaContract evita) throws InvalidMutationException {
		if (evita.getCatalogNames().contains(this.catalogName)) {
			throw new InvalidSchemaMutationException("Catalog `" + this.catalogName + "` already exists!");
		}
		// check the names in all naming conventions are unique in the entity schema
		CatalogSchema.checkCatalogNameIsAvailable(evita, this.catalogName);
	}

	@Nonnull
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nullable CatalogSchemaContract catalogSchema) {
		Assert.isTrue(
			catalogSchema == null,
			() -> new InvalidSchemaMutationException("Catalog `" + this.catalogName + "` already exists!")
		);
		return new CatalogSchemaWithImpactOnEntitySchemas(
			CatalogSchema._internalBuild(
				this.catalogName,
				NamingConvention.generate(this.catalogName),
				EnumSet.allOf(CatalogEvolutionMode.class),
				MutationEntitySchemaAccessor.INSTANCE
			)
		);
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "Create catalog: " +
			"catalogName='" + this.catalogName + '\'';
	}

}
