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
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
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
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.AllowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.DisallowEvolutionModeInCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaConflictResolutionMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyCatalogSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.MutationEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NamingConvention;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
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
	 * It reflects the mutations up to {@link #lastMutationReflectedInSchema} and is replaced by a fresh one whenever
	 * {@link #toInstance()} replays the mutations from the base schema.
	 */
	private MutationEntitySchemaAccessor updatedEntitySchemaAccessor;
	/**
	 * Whether a replayed catalog mutation cascaded into an entity schema. Such an entity schema changes in the middle
	 * of the sequence, so its own modifications cannot be judged against its base schema alone.
	 */
	private boolean cascadedIntoEntitySchemas;

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

	@Nonnull
	@Override
	public CatalogSchemaBuilder withConflictResolution(@Nonnull ConflictResolution conflictResolution) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.baseSchema, this.mutations,
				new ModifyCatalogSchemaConflictResolutionMutation(conflictResolution)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public CatalogSchemaBuilder withoutConflictResolution() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.baseSchema, this.mutations,
				new ModifyCatalogSchemaConflictResolutionMutation(null)
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
							// the entity builder starts from the entity as this builder holds it: started from the stored
							// one, it would judge a call restoring a value an earlier call changed as no change and drop it
							final EntitySchemaContract currentSchema = getEntitySchema(entityType).orElse(existingSchema);
							final EntitySchemaBuilder builder = new InternalEntitySchemaBuilder(toInstance(), currentSchema).cooperatingWith(() -> this);
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
						.orElseGet(() -> Stream.of(new CreateEntitySchemaMutation(entityType)));

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
		// materialize first, so that the accessor reflects every collected mutation
		toInstance();
		return this.updatedEntitySchemaAccessor.getEntitySchemas();
	}

	@Nonnull
	@Override
	public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
		// materialize first, so that the accessor reflects every collected mutation
		toInstance();
		return this.updatedEntitySchemaAccessor.getEntitySchema(entityType);
	}

	/**
	 * Returns the entity schema as the collected mutations leave it, like {@link #getEntitySchema(String)}. The method
	 * is declared here because the delegation of {@link CatalogSchemaContract} methods to {@link #toInstance()} would
	 * otherwise answer it from the catalog schema instance, which does not carry the modified entity schemas.
	 *
	 * @param entityType the name of the entity schema
	 * @return the entity schema
	 * @throws EvitaInvalidUsageException when the entity schema does not exist
	 */
	@Nonnull
	@Override
	public EntitySchemaContract getEntitySchemaOrThrowException(@Nonnull String entityType) {
		return getEntitySchema(entityType)
			.orElseThrow(() -> new EvitaInvalidUsageException("Schema for entity with name `" + entityType + "` was not found!"));
	}

	/**
	 * Returns the collected mutations without those that leave their part of the schema exactly as it was in the base
	 * schema, or nothing when no mutation remains.
	 *
	 * The no-op check of the individual builder methods compares with the current builder state only, so a value that
	 * is set and later restored (for example by a schema post-processor) leaves mutations behind. They change nothing,
	 * but the server would still record them in the transaction and derive conflict keys from them - a mutation of
	 * a global attribute even takes a catalog-wide one. The check is done per level, see
	 * {@link #withoutNoOpMutations()}.
	 */
	@Nonnull
	@Override
	public Optional<ModifyCatalogSchemaMutation> toMutation() {
		if (this.mutations.isEmpty()) {
			return Optional.empty();
		}
		final List<LocalCatalogSchemaMutation> effectiveMutations = withoutNoOpMutations();
		return effectiveMutations.isEmpty() ?
			Optional.empty() :
			Optional.of(new ModifyCatalogSchemaMutation(getName(), null, effectiveMutations.toArray(EMPTY_ARRAY)));
	}

	@Delegate(types = CatalogSchemaContract.class, excludes = {NamedSchemaContract.class, EntitySchemaProvider.class})
	@Nonnull
	@Override
	public CatalogSchemaContract toInstance() {
		if (this.updatedSchema == null || this.updatedSchemaDirty != MutationImpact.NO_IMPACT) {
			// if the dirty flag is set to modified previous we need to start from the base schema again
			// and reapply all mutations
			if (this.updatedSchemaDirty == MutationImpact.MODIFIED_PREVIOUS) {
				this.lastMutationReflectedInSchema = -1;
				this.updatedSchema = null;
				this.cascadedIntoEntitySchemas = false;
				// the entity schemas start from the base too, otherwise the replay applies their modifications twice
				this.updatedEntitySchemaAccessor = new MutationEntitySchemaAccessor(this.baseSchema);
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
				// apply what the mutation cascades into the entity schemas - for example a removed global attribute
				// leaving every entity schema that uses it - right away, before the mutations that follow, as the
				// server does
				final ModifyEntitySchemaMutation[] cascadedMutations = mutationImpact.entitySchemaMutations();
				if (cascadedMutations != null) {
					for (ModifyEntitySchemaMutation cascadedMutation : cascadedMutations) {
						cascadedMutation.mutate(currentSchema, this.updatedEntitySchemaAccessor);
						this.cascadedIntoEntitySchemas = true;
					}
				}
			}
			this.updatedSchema = currentSchema;
			this.updatedSchemaDirty = MutationImpact.NO_IMPACT;
			this.lastMutationReflectedInSchema = this.mutations.size() - 1;
		}
		return this.updatedSchema;
	}

	/**
	 * Filters out the collected mutations that leave their level of the schema as it was in the base schema. Each
	 * level is judged on its own:
	 *
	 * - the catalog level (global attributes, description, conflict resolution, evolution modes): when its final content
	 *   equals the base one, every mutation of this level is dropped,
	 * - each existing entity schema modified by {@link ModifyEntitySchemaMutation}: when applying all its modifications
	 *   to the base entity schema yields the same content, the modifications of that entity schema are dropped.
	 *
	 * Mutations that create, remove or rename an entity schema carry intent a value comparison cannot judge, so they
	 * are always kept, together with all modifications of the entity schemas they touch. A level is judged as a whole:
	 * when it contains a genuine change, its no-op mutations are kept along with it. They carry the same conflict key as
	 * the genuine change, so they do not widen the conflict surface of the transaction.
	 *
	 * A catalog mutation that cascades into an entity schema changes it at its own position in the sequence, so a
	 * builder holding one emits every collected mutation: a modification after the cascade that restores the base
	 * value overrides it, and it may depend on the catalog mutations around the cascade.
	 *
	 * The final state of a modified entity schema is computed by applying only its own modifications to the base
	 * entity schema, rather than read from {@link #updatedEntitySchemaAccessor}, so that it judges exactly the
	 * mutations that may be dropped.
	 *
	 * The modifications are applied against the final catalog schema, not against the catalog state at their own
	 * position in the sequence. When they cannot be applied that way - an entity adopts a global attribute that the
	 * catalog removes later - the levels depend on each other, and every collected mutation is emitted unchanged.
	 * The same fallback covers any replay of the builder's own state that rejects a sequence: the server is the one to
	 * judge it. A modification of an entity schema that does not exist at its position in the sequence is emitted with
	 * everything else as well: the server creates such an entity schema on the fly, while the replay has nothing to
	 * apply it to.
	 *
	 * @return the mutations to emit, in their original order
	 */
	@Nonnull
	private List<LocalCatalogSchemaMutation> withoutNoOpMutations() {
		// collect the entity schemas whose existence or name changes, and the modifications of each entity schema
		final Set<String> structurallyChangedEntities = new HashSet<>(8);
		final Map<String, List<ModifyEntitySchemaMutation>> entityModifications = new HashMap<>(8);
		// whether an entity schema exists at the current position of the sequence, for those the sequence touches
		final Map<String, Boolean> entityExistence = new HashMap<>(8);
		for (LocalCatalogSchemaMutation mutation : this.mutations) {
			if (mutation instanceof CreateEntitySchemaMutation createMutation) {
				structurallyChangedEntities.add(createMutation.getName());
				entityExistence.put(createMutation.getName(), Boolean.TRUE);
			} else if (mutation instanceof RemoveEntitySchemaMutation removeMutation) {
				structurallyChangedEntities.add(removeMutation.getName());
				entityExistence.put(removeMutation.getName(), Boolean.FALSE);
			} else if (mutation instanceof ModifyEntitySchemaNameMutation renameMutation) {
				structurallyChangedEntities.add(renameMutation.getName());
				structurallyChangedEntities.add(renameMutation.getNewName());
				entityExistence.put(renameMutation.getName(), Boolean.FALSE);
				entityExistence.put(renameMutation.getNewName(), Boolean.TRUE);
			} else if (mutation instanceof ModifyEntitySchemaMutation modifyMutation) {
				final String entityType = modifyMutation.getName();
				final boolean exists = entityExistence.computeIfAbsent(
					entityType, theEntityType -> this.baseSchema.getEntitySchema(theEntityType).isPresent()
				);
				if (!exists) {
					// the server creates a missing entity schema a modification targets (when the catalog evolution
					// mode allows it), but the builder's own replay has no entity schema to apply the modification
					// to - the mutations are emitted as collected and the server judges them
					return this.mutations;
				}
				entityModifications
					.computeIfAbsent(entityType, theEntityType -> new ArrayList<>(2))
					.add(modifyMutation);
			}
		}
		final boolean catalogLevelUnchanged;
		final Set<String> unchangedEntities = new HashSet<>(entityModifications.size());
		try {
			final CatalogSchemaContract updatedCatalogSchema = toInstance();
			if (this.cascadedIntoEntitySchemas) {
				// a catalog mutation cascading into an entity schema changes it at its own position in the sequence:
				// a modification after it that restores the base value overrides the cascade, and the modifications
				// of such an entity schema may depend on catalog mutations around the cascade - the levels cannot be
				// judged separately, so the mutations are emitted as collected
				return this.mutations;
			}
			catalogLevelUnchanged = SchemaContentComparator.isCatalogSchemaContentEqual(
				this.baseSchema, updatedCatalogSchema
			);
			for (Entry<String, List<ModifyEntitySchemaMutation>> entry : entityModifications.entrySet()) {
				final String entityType = entry.getKey();
				if (!structurallyChangedEntities.contains(entityType)) {
					this.baseSchema.getEntitySchema(entityType)
						.filter(baseEntitySchema -> isEntitySchemaUnchanged(updatedCatalogSchema, baseEntitySchema, entry.getValue()))
						.ifPresent(baseEntitySchema -> unchangedEntities.add(entityType));
				}
			}
		} catch (EvitaInvalidUsageException ex) {
			// the levels cannot be judged separately, so the mutations are emitted as collected, to be applied in
			// their order and judged by the server; this happens when
			// - the replay of the builder's own state rejects the sequence,
			// - the entity modifications cannot be applied against the final catalog schema, because they depend on
			//   a catalog state that exists only in the middle of the sequence - for example a global attribute
			//   adopted by an entity and removed from the catalog afterwards
			// the second case is the one that occurs in practice, guarded by a test - this catch must stay even when
			// no other known sequence reaches it
			return this.mutations;
		}

		final List<LocalCatalogSchemaMutation> effectiveMutations = new ArrayList<>(this.mutations.size());
		for (LocalCatalogSchemaMutation mutation : this.mutations) {
			final boolean noOp;
			if (mutation instanceof ModifyEntitySchemaMutation modifyMutation) {
				noOp = unchangedEntities.contains(modifyMutation.getName());
			} else if (
				mutation instanceof CreateEntitySchemaMutation ||
					mutation instanceof RemoveEntitySchemaMutation ||
					mutation instanceof ModifyEntitySchemaNameMutation
			) {
				noOp = false;
			} else {
				noOp = catalogLevelUnchanged;
			}
			if (!noOp) {
				effectiveMutations.add(mutation);
			}
		}
		return effectiveMutations;
	}

	/**
	 * Applies all modifications of a single entity schema to its base schema and checks whether the result has the same
	 * content as the base schema.
	 *
	 * @param catalogSchema    the final catalog schema the modifications are applied against
	 * @param baseEntitySchema the entity schema as it is in the base catalog schema
	 * @param modifications    all modifications of the entity schema, in their original order
	 * @return true when the modifications leave the entity schema as it was
	 * @throws EvitaInvalidUsageException when a modification cannot be applied against the given catalog schema
	 */
	private static boolean isEntitySchemaUnchanged(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract baseEntitySchema,
		@Nonnull List<ModifyEntitySchemaMutation> modifications
	) {
		EntitySchemaContract entitySchema = baseEntitySchema;
		for (ModifyEntitySchemaMutation modification : modifications) {
			for (LocalEntitySchemaMutation entityMutation : modification.getSchemaMutations()) {
				entitySchema = entityMutation.mutate(catalogSchema, entitySchema);
				if (entitySchema == null) {
					// the modification removes the entity schema, which is a change by definition
					return false;
				}
			}
		}
		return SchemaContentComparator.isEntitySchemaContentEqual(baseEntitySchema, entitySchema);
	}

}
