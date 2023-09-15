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

package io.evitadb.api.requestResponse.schema.builder;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Internal implementation of the {@link CatalogSchemaBuilder} builder returned by {@link SealedCatalogSchema} when
 * {@link SealedCatalogSchema#openForWrite()} is called. This implementation does the heavy lifting of this particular
 * interface. The class should never be instantiated by the client code.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@NotThreadSafe
public final class InternalCatalogSchemaBuilder implements CatalogSchemaBuilder, InternalSchemaBuilderHelper {
	@Serial private static final long serialVersionUID = -6465608379445518033L;
	private static final LocalCatalogSchemaMutation[] EMPTY_ARRAY = new LocalCatalogSchemaMutation[0];

	private final CatalogSchemaContract baseSchema;
	private final List<LocalCatalogSchemaMutation> mutations = new LinkedList<>();
	private boolean updatedSchemaDirty;
	private CatalogSchemaContract updatedSchema;

	public InternalCatalogSchemaBuilder(@Nonnull CatalogSchemaContract baseSchema, @Nonnull Collection<LocalCatalogSchemaMutation> schemaMutations) {
		this.baseSchema = baseSchema;
		this.updatedSchemaDirty = addMutations(
			this.baseSchema, this.mutations,
			schemaMutations.toArray(EMPTY_ARRAY)
		);
	}

	public InternalCatalogSchemaBuilder(@Nonnull CatalogSchemaContract baseSchema) {
		this(baseSchema, Collections.emptyList());
	}

	@Nonnull
	@Override
	public CatalogSchemaBuilder withDescription(@Nullable String description) {
		this.updatedSchemaDirty = addMutations(
			this.baseSchema, this.mutations,
			new ModifyCatalogSchemaDescriptionMutation(description)
		);
		return this;
	}

	@Override
	@Nonnull
	public CatalogSchemaBuilder verifyCatalogSchemaStrictly() {
		this.updatedSchemaDirty = addMutations(
			this.baseSchema, this.mutations,
			new DisallowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.values())
		);
		return this;
	}

	@Override
	@Nonnull
	public CatalogSchemaBuilder verifyCatalogSchemaButCreateOnTheFly() {
		this.updatedSchemaDirty = addMutations(
			this.baseSchema, this.mutations,
			new AllowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.values())
		);
		return this;
	}

	@Nonnull
	@Override
	public CatalogSchemaBuilder withEntitySchema(@Nonnull String entityType, @Nullable Consumer<EntitySchemaBuilder> whichIs) {
		this.baseSchema.getEntitySchema(entityType)
			.ifPresentOrElse(
				existingSchema -> ofNullable(whichIs)
					.ifPresent(schemaBuilder -> {
							final EntitySchemaBuilder builder = new InternalEntitySchemaBuilder(toInstance(), existingSchema).cooperatingWith(() -> this);
							whichIs.accept(builder);
							builder.toMutation()
								.ifPresent(
									mutations -> this.updatedSchemaDirty = addMutations(
										this.baseSchema, this.mutations,
										mutations
									)
								);
						}
					),
				() -> {
					final Stream<ModifyEntitySchemaMutation> entityMutation = ofNullable(whichIs)
						.map(schemaBuilder -> {
							final EntitySchemaBuilder builder = new InternalEntitySchemaBuilder(toInstance(), EntitySchema._internalBuild(entityType)).cooperatingWith(() -> this);
							whichIs.accept(builder);
							return builder.toMutation().stream();
						})
						.orElse(Stream.empty());

					this.updatedSchemaDirty = addMutations(
						this.baseSchema, this.mutations,
						Stream.concat(
							Stream.of(new CreateEntitySchemaMutation(entityType)),
							entityMutation.map(it -> (LocalCatalogSchemaMutation)it)
						).toArray(LocalCatalogSchemaMutation[]::new)
					);
				}
			);
		return this;
	}

	@Override
	@Nonnull
	public CatalogSchemaBuilder withAttribute(@Nonnull String attributeName, @Nonnull Class<? extends Serializable> ofType) {
		return withAttribute(attributeName, ofType, null);
	}

	@Nonnull
	@Override
	public CatalogSchemaBuilder withAttribute(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> ofType,
		@Nullable Consumer<GlobalAttributeSchemaEditor.GlobalAttributeSchemaBuilder> whichIs
	) {
		final Optional<GlobalAttributeSchemaContract> existingAttribute = getAttribute(attributeName);
		final Class<? extends Serializable> requestedType = EvitaDataTypes.toWrappedForm(ofType);
		final GlobalAttributeSchemaBuilder attributeSchemaBuilder =
			existingAttribute
				.map(it -> {
					Assert.isTrue(
						requestedType.equals(it.getType()),
						() -> new InvalidSchemaMutationException(
							"Attribute " + attributeName + " has already assigned type " + it.getType() +
								", cannot change this type to: " + requestedType + "!"
						)
					);
					return new GlobalAttributeSchemaBuilder(baseSchema, it);
				})
				.orElseGet(() -> new GlobalAttributeSchemaBuilder(baseSchema, attributeName, requestedType));

		ofNullable(whichIs).ifPresent(it -> it.accept(attributeSchemaBuilder));
		final GlobalAttributeSchemaContract attributeSchema = attributeSchemaBuilder.toInstance();
		checkSortableTraits(attributeName, attributeSchema);

		// check the names in all naming conventions are unique in the catalog schema
		checkNamesAreUniqueInAllNamingConventions(
			this.getAttributes().values(),
			Collections.emptyList(),
			attributeSchema
		);

		if (existingAttribute.map(it -> !it.equals(attributeSchema)).orElse(true)) {
			this.updatedSchemaDirty = addMutations(
				this.baseSchema, this.mutations,
				attributeSchemaBuilder.toMutation().toArray(EMPTY_ARRAY)
			);
		}
		return this;
	}

	@Override
	@Nonnull
	public CatalogSchemaBuilder withoutAttribute(@Nonnull String attributeName) {
		this.updatedSchemaDirty = addMutations(
			this.baseSchema, this.mutations,
			new RemoveAttributeSchemaMutation(attributeName)
		);
		return this;
	}

	@Nonnull
	@Override
	public Optional<ModifyCatalogSchemaMutation> toMutation() {
		return this.mutations.isEmpty() ?
			Optional.empty() :
			Optional.of(new ModifyCatalogSchemaMutation(getName(), this.mutations.toArray(EMPTY_ARRAY)));
	}

	@Delegate(types = CatalogSchemaContract.class)
	@Nonnull
	@Override
	public CatalogSchemaContract toInstance() {
		if (this.updatedSchema == null || this.updatedSchemaDirty) {
			CatalogSchemaContract currentSchema = this.baseSchema;
			for (CatalogSchemaMutation mutation : this.mutations) {
				currentSchema = mutation.mutate(currentSchema);
				if (currentSchema == null) {
					throw new EvitaInternalError("Catalog schema unexpectedly removed from inside!");
				}
			}
			this.updatedSchema = currentSchema;
			this.updatedSchemaDirty = false;
		}
		return this.updatedSchema;
	}

}
