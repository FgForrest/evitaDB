/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.api.requestResponse.cdc.CaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.utils.ArrayUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Arrays;
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
public class ModifyCatalogSchemaMutation implements TopLevelCatalogSchemaMutation {
	@Serial private static final long serialVersionUID = -5779012919587623154L;
	@Nonnull @Getter private final String catalogName;
	@Nonnull @Getter private final LocalCatalogSchemaMutation[] schemaMutations;

	public ModifyCatalogSchemaMutation(@Nonnull String catalogName, @Nonnull LocalCatalogSchemaMutation... schemaMutations) {
		this.catalogName = catalogName;
		this.schemaMutations = schemaMutations;
	}

	@Nonnull
	@Override
	public Operation getOperation() {
		return Operation.UPDATE;
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nullable CatalogSchemaContract catalogSchema) {
		CatalogSchemaWithImpactOnEntitySchemas alteredSchema = new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
		ModifyEntitySchemaMutation[] aggregatedMutations = null;
		for (LocalCatalogSchemaMutation schemaMutation : schemaMutations) {
			alteredSchema = schemaMutation.mutate(alteredSchema.updatedCatalogSchema(), catalogSchema);
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

	@Nonnull
	@Override
	public Stream<ChangeCatalogCapture> toChangeCatalogCapture(
		@Nonnull MutationPredicate predicate,
		@Nonnull CaptureContent content) {
		final MutationPredicateContext context = predicate.getContext();
		context.advance();

		final Stream<ChangeCatalogCapture> catalogMutation;
		if (predicate.test(this)) {
			catalogMutation = Stream.of(ChangeCatalogCapture.schemaCapture(
					context,
					operation(),
					content == CaptureContent.BODY ? this : null
				)
			);
		} else {
			catalogMutation = Stream.empty();
		}
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
		return "Modify catalog `" + catalogName + "` schema:\n" +
			Arrays.stream(schemaMutations)
				.map(Object::toString)
				.collect(Collectors.joining(",\n"));
	}
}
