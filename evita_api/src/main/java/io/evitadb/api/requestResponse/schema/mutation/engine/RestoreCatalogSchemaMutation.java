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

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.CatalogConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.stream.Stream;

/**
 * Mutation is responsible for setting up a new {@link CatalogSchemaContract} in {@link CatalogState#INACTIVE}
 * - or more precisely the catalog instance itself.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class RestoreCatalogSchemaMutation implements TopLevelCatalogSchemaMutation<Void> {
	@Serial private static final long serialVersionUID = 3313004990813564229L;
	@Nonnull @Getter private final String catalogName;

	public RestoreCatalogSchemaMutation(@Nonnull String catalogName) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.CATALOG, catalogName);
		this.catalogName = catalogName;
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
	public Class<Void> getProgressResultType() {
		return Void.class;
	}

	@Nonnull
	@Override
	public Stream<ConflictKey> getConflictKeys() {
		return Stream.of(new CatalogConflictKey(this.catalogName));
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nullable CatalogSchemaContract catalogSchema) {
		Assert.isTrue(
			catalogSchema == null,
			() -> new InvalidSchemaMutationException("Catalog `" + this.catalogName + "` already exists!")
		);
		return null;
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "Restore catalog: " +
			"catalogName='" + this.catalogName + '\'';
	}

}
