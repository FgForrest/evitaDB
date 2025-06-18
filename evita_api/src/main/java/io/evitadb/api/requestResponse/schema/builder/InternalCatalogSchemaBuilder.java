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

package io.evitadb.api.requestResponse.schema.builder;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.MutationEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NamingConvention;
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
import java.util.Map;
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

	/**
	 * The baseSchema variable represents the origin contract of the schema.
	 */
	private final CatalogSchemaContract baseSchema;
	/**
	 * List of LocalCatalogSchemaMutation objects representing a collection of variable mutations.
	 *
	 * The mutations list is used to store LocalCatalogSchemaMutation objects, which represent individual mutations.
	 * Each mutation represents a change or modification to a specific property in a catalog schema.
	 */
	private final List<LocalCatalogSchemaMutation> mutations = new LinkedList<>();
	/**
	 * Represents the status of the updated schema.
	 */
	private MutationImpact updatedSchemaDirty;
	/**
	 * Contains index of the last mutation reflected in the schema from {@link #mutations} field.
	 */
	private int lastMutationReflectedInSchema = -1;
	/**
	 * Represents an updated schema for a catalog.
	 */
	@Nullable private CatalogSchemaContract updatedSchema;
	/**
	 * This variable represents the accessor object for the updated entity schema.
	 * It provides access to the current state of all the entity schemas altered by the mutations of this builder.
	 */
	private MutationEntitySchemaAccessor updatedEntitySchemaAccessor;

	public InternalCatalogSchemaBuilder(
		@Nonnull CatalogSchemaContract baseSchema,
		@Nonnull Collection<LocalCatalogSchemaMutation> schemaMutations
	) {
		this.baseSchema = baseSchema;
		this.updatedEntitySchemaAccessor = new MutationEntitySchemaAccessor(baseSchema);
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
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.baseSchema, this.mutations,
				new ModifyCatalogSchemaDescriptionMutation(description)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public CatalogSchemaBuilder verifyCatalogSchemaStrictly() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.baseSchema, this.mutations,
				new DisallowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.values())
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public CatalogSchemaBuilder verifyCatalogSchemaButCreateOnTheFly() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.baseSchema, this.mutations,
				new AllowEvolutionModeInCatalogSchemaMutation(CatalogEvolutionMode.values())
			)
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
									mutations -> this.updatedSchemaDirty = updateMutationImpact(
										this.updatedSchemaDirty,
										addMutations(
											this.baseSchema, this.mutations,
											mutations
										)
									)
								);
						}
					),
				() -> {
					final Stream<LocalCatalogSchemaMutation> entityMutation = ofNullable(whichIs)
						.map(schemaBuilder -> {
							final EntitySchemaBuilder builder = new InternalEntitySchemaBuilder(toInstance(), EntitySchema._internalBuild(entityType)).cooperatingWith(() -> this);
							whichIs.accept(builder);
							return Stream.concat(
								Stream.<LocalCatalogSchemaMutation>of(new CreateEntitySchemaMutation(entityType)),
								builder.toMutation().stream().map(LocalCatalogSchemaMutation.class::cast)
							);
						})
						.orElse(Stream.empty());

					this.updatedSchemaDirty = updateMutationImpact(
						this.updatedSchemaDirty,
						addMutations(
							this.baseSchema, this.mutations,
							entityMutation.toArray(LocalCatalogSchemaMutation[]::new)
						)
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
					return new GlobalAttributeSchemaBuilder(this.baseSchema, it);
				})
				.orElseGet(() -> new GlobalAttributeSchemaBuilder(this.baseSchema, attributeName, requestedType));

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
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					this.baseSchema, this.mutations,
					attributeSchemaBuilder.toMutation().toArray(EMPTY_ARRAY)
				)
			);
		}
		return this;
	}

	@Override
	@Nonnull
	public CatalogSchemaBuilder withoutAttribute(@Nonnull String attributeName) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.baseSchema, this.mutations,
				new RemoveAttributeSchemaMutation(attributeName)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public String getName() {
		return this.baseSchema.getName();
	}

	@Nullable
	@Override
	public String getDescription() {
		return toInstance().getDescription();
	}

	@Nonnull
	@Override
	public Map<NamingConvention, String> getNameVariants() {
		return this.baseSchema.getNameVariants();
	}

	@Nonnull
	@Override
	public String getNameVariant(@Nonnull NamingConvention namingConvention) {
		return this.baseSchema.getNameVariant(namingConvention);
	}

	@Nonnull
	@Override
	public Collection<EntitySchemaContract> getEntitySchemas() {
		return this.updatedEntitySchemaAccessor.getEntitySchemas();
	}

	@Nonnull
	@Override
	public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
		return this.updatedEntitySchemaAccessor.getEntitySchema(entityType);
	}

	@Nonnull
	@Override
	public Optional<ModifyCatalogSchemaMutation> toMutation() {
		return this.mutations.isEmpty() ?
			Optional.empty() :
			Optional.of(new ModifyCatalogSchemaMutation(getName(), null, this.mutations.toArray(EMPTY_ARRAY)));
	}

	@Delegate(types = CatalogSchemaContract.class, excludes = {NamedSchemaContract.class, EntitySchemaProvider.class})
	@Nonnull
	@Override
	public CatalogSchemaContract toInstance() {
		if (this.updatedSchema == null || this.updatedSchemaDirty != MutationImpact.NO_IMPACT) {
			// if the dirty flat is set to modified previous we need to start from the base schema again
			// and reapply all mutations
			if (this.updatedSchemaDirty == MutationImpact.MODIFIED_PREVIOUS) {
				this.lastMutationReflectedInSchema = -1;
				this.updatedSchema = null;
			}
			// if the last mutation reflected in the schema is zero we need to start from the base schema
			// else we can continue modification last known updated schema by adding additional mutations
			CatalogSchemaContract currentSchema = this.updatedSchema == null ?
				this.baseSchema : this.updatedSchema;

			// apply the mutations not reflected in the schema
			for (int i = this.lastMutationReflectedInSchema + 1; i < this.mutations.size(); i++) {
				final LocalCatalogSchemaMutation mutation = this.mutations.get(i);
				final CatalogSchemaWithImpactOnEntitySchemas mutationImpact = mutation.mutate(currentSchema, this.updatedEntitySchemaAccessor);
				if (mutationImpact == null || mutationImpact.updatedCatalogSchema() == null) {
					throw new GenericEvitaInternalError("Catalog schema unexpectedly removed from inside!");
				}
				currentSchema = mutationImpact.updatedCatalogSchema();
			}
			this.updatedSchema = currentSchema;
			this.updatedSchemaDirty = MutationImpact.NO_IMPACT;
			this.lastMutationReflectedInSchema = this.mutations.size() - 1;
		}
		return this.updatedSchema;
	}

}
