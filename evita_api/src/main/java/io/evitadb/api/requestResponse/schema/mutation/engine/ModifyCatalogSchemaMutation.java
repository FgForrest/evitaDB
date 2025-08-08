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
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.mutation.conflict.CatalogConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mutation is a holder for a set of {@link LocalCatalogSchemaMutation} that affect a internal contents of the catalog
 * schema itself.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class ModifyCatalogSchemaMutation implements TopLevelCatalogSchemaMutation<CommitVersions> {
	@Serial private static final long serialVersionUID = -5779012919587623154L;
	@Nonnull @Getter private final String catalogName;
	@Nullable @Getter private final UUID sessionId;
	@Nonnull @Getter private final LocalCatalogSchemaMutation[] schemaMutations;

	public ModifyCatalogSchemaMutation(
		@Nonnull String catalogName,
		@Nullable UUID sessionId,
		@Nonnull LocalCatalogSchemaMutation... schemaMutations
	) {
		this.catalogName = catalogName;
		this.sessionId = sessionId;
		this.schemaMutations = schemaMutations;
	}

	@Override
	public void verifyApplicability(@Nonnull EvitaContract evita) throws InvalidMutationException {
		if (!evita.getCatalogNames().contains(this.catalogName)) {
			throw new InvalidMutationException("Catalog `" + this.catalogName + "` doesn't exist!");
		}
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

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nullable CatalogSchemaContract catalogSchema) {
		Assert.isTrue(
			catalogSchema != null,
			() -> new InvalidMutationException("Catalog `" + this.catalogName + "` doesn't exist!")
		);
		CatalogSchemaWithImpactOnEntitySchemas alteredSchema = new CatalogSchemaWithImpactOnEntitySchemas(
			catalogSchema
		);
		ModifyEntitySchemaMutation[] aggregatedMutations = null;
		for (LocalCatalogSchemaMutation schemaMutation : this.schemaMutations) {
			alteredSchema = Objects.requireNonNull(schemaMutation.mutate(alteredSchema.updatedCatalogSchema(), catalogSchema));
			if (alteredSchema.entitySchemaMutations() != null) {
				aggregatedMutations = aggregatedMutations == null ?
					alteredSchema.entitySchemaMutations() :
					ArrayUtils.mergeArrays(aggregatedMutations, alteredSchema.entitySchemaMutations());
			}
		}
		return new CatalogSchemaWithImpactOnEntitySchemas(alteredSchema.updatedCatalogSchema(), aggregatedMutations);
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	@Nonnull
	public Stream<ChangeCatalogCapture> toChangeCatalogCapture(
		@Nonnull MutationPredicate predicate,
		@Nonnull ChangeCaptureContent content
	) {
		final Stream<ChangeCatalogCapture> catalogMutation = TopLevelCatalogSchemaMutation.super.toChangeCatalogCapture(predicate, content);

		final MutationPredicateContext context = predicate.getContext();
		if (context.getDirection() == StreamDirection.FORWARD) {
			return Stream.concat(
				catalogMutation,
				Arrays.stream(this.schemaMutations)
				      .filter(predicate)
				      .flatMap(m -> m.toChangeCatalogCapture(predicate, content))
			);
		} else {
			final AtomicInteger index = new AtomicInteger(this.schemaMutations.length);
			return Stream.concat(
				Stream.generate(() -> null)
				      .takeWhile(x -> index.get() > 0)
				      .map(x -> this.schemaMutations[index.decrementAndGet()])
				      .filter(predicate)
				      .flatMap(x -> x.toChangeCatalogCapture(predicate, content)),
				catalogMutation
			);

		}
	}

	@Override
	public String toString() {
		return "Modify catalog `" + this.catalogName + "` schema:\n" +
			Arrays.stream(this.schemaMutations)
			      .map(Object::toString)
			      .collect(Collectors.joining(",\n"));
	}
}
