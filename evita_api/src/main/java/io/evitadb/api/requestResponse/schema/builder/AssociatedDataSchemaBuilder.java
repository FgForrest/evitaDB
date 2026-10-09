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
import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.mutation.AssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.ModifyAssociatedDataSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaConflictResolutionOverrideMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaLocalizedMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaNullableMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaSearchableMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.exception.GenericEvitaInternalError;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Internal {@link AssociatedDataSchemaContract} builder used solely from within {@link EntitySchemaEditor}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public final class AssociatedDataSchemaBuilder implements AssociatedDataSchemaEditor, InternalSchemaBuilderHelper {
	@Serial private static final long serialVersionUID = 4286814145998208599L;

	private final CatalogSchemaContract catalogSchema;
	private final EntitySchemaContract entitySchema;
	private final AssociatedDataSchemaContract baseSchema;
	private final List<LocalEntitySchemaMutation> mutations = new LinkedList<>();
	private MutationImpact updatedSchemaDirty = MutationImpact.NO_IMPACT;
	private int lastMutationReflectedInSchema = 0;
	private AssociatedDataSchemaContract updatedSchema;
	/** Whether {@link AssociatedDataSchemaContract#validate()} has already accepted the currently assembled schema. */
	private boolean updatedSchemaValidated;

	AssociatedDataSchemaBuilder(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull AssociatedDataSchemaContract existingSchema
	) {
		this.catalogSchema = catalogSchema;
		this.entitySchema = entitySchema;
		this.baseSchema = existingSchema;
	}

	AssociatedDataSchemaBuilder(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String name,
		@Nonnull Class<? extends Serializable> ofType
	) {
		this.catalogSchema = catalogSchema;
		this.entitySchema = entitySchema;
		this.baseSchema = AssociatedDataSchema._internalBuild(
			name, ofType, ConflictResolutionOverride.INHERITED
		);
		this.mutations.add(
			new CreateAssociatedDataSchemaMutation(
				this.baseSchema.getName(),
				this.baseSchema.getDescription(),
				this.baseSchema.getDeprecationNotice(),
				this.baseSchema.getType(),
				this.baseSchema.isLocalized(),
				this.baseSchema.isNullable()
			)
		);
	}

	@Override
	@Nonnull
	public AssociatedDataSchemaBuilder localized() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetAssociatedDataSchemaLocalizedMutation(
					this.baseSchema.getName(),
					true
				)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public AssociatedDataSchemaBuilder localized(@Nonnull BooleanSupplier decider) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetAssociatedDataSchemaLocalizedMutation(
					this.baseSchema.getName(),
					decider.getAsBoolean()
				)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public AssociatedDataSchemaBuilder nullable() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetAssociatedDataSchemaNullableMutation(
					this.baseSchema.getName(),
					true
				)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public AssociatedDataSchemaEditor nullable(@Nonnull BooleanSupplier decider) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetAssociatedDataSchemaNullableMutation(
					this.baseSchema.getName(),
					decider.getAsBoolean()
				)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public AssociatedDataSchemaBuilder searchableInScope(@Nonnull Scope... inScope) {
		return setSearchability(inScope, assembleInstance().getTextExpression());
	}

	@Nonnull
	@Override
	public AssociatedDataSchemaBuilder searchableInScope(
		@Nonnull Expression textExpression,
		@Nonnull Scope... inScope
	) {
		return setSearchability(inScope, textExpression);
	}

	@Nonnull
	@Override
	public AssociatedDataSchemaBuilder nonSearchableInScope(@Nonnull Scope... inScope) {
		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
		final AssociatedDataSchemaContract currentSchema = assembleInstance();
		final Scope[] remainingScopes = Arrays.stream(Scope.values())
			.filter(it -> currentSchema.isSearchableInScope(it) && !excludedScopes.contains(it))
			.toArray(Scope[]::new);
		// the expression belongs to searchability - once the associated data is searchable nowhere, it goes with it
		return setSearchability(
			remainingScopes, remainingScopes.length == 0 ? null : currentSchema.getTextExpression()
		);
	}

	@Override
	@Nonnull
	public AssociatedDataSchemaBuilder withConflictResolutionOverride(@Nonnull ConflictResolutionOverride conflictResolutionOverride) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetAssociatedDataSchemaConflictResolutionOverrideMutation(
					this.baseSchema.getName(),
					conflictResolutionOverride
				)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public AssociatedDataSchemaBuilder withDescription(@Nullable String description) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyAssociatedDataSchemaDescriptionMutation(
					this.baseSchema.getName(),
					description
				)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public AssociatedDataSchemaBuilder deprecated(@Nonnull String deprecationNotice) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyAssociatedDataSchemaDeprecationNoticeMutation(
					this.baseSchema.getName(),
					deprecationNotice
				)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public AssociatedDataSchemaBuilder notDeprecatedAnymore() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyAssociatedDataSchemaDeprecationNoticeMutation(
					this.baseSchema.getName(),
					null
				)
			)
		);
		return this;
	}

	/**
	 * Builds new instance of immutable {@link AssociatedDataSchema} filled with updated configuration.
	 */
	/**
	 * Records a full statement of the searchability axis - the scopes and the text expression together.
	 *
	 * @param searchableInScopes the scopes the associated data is searchable in
	 * @param textExpression     the expression selecting the text of a complex data object value, or `null`
	 * @return this builder
	 */
	@Nonnull
	private AssociatedDataSchemaBuilder setSearchability(
		@Nonnull Scope[] searchableInScopes,
		@Nullable Expression textExpression
	) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetAssociatedDataSchemaSearchableMutation(
					this.baseSchema.getName(), searchableInScopes, textExpression
				)
			)
		);
		return this;
	}

	/**
	 * Creates the associated data schema instance - the call that validates: the schema is assembled by
	 * {@link #assembleInstance()} and checked once per assembly by {@link AssociatedDataSchemaContract#validate()},
	 * so this is where associated data whose declarations do not compose is refused - as for an attribute, after the
	 * whole declaration was applied, so the order of its parts does not matter.
	 *
	 * @return the assembled schema
	 * @throws InvalidSchemaMutationException when the assembled schema is not consistent
	 */
	@Delegate(types = AssociatedDataSchemaContract.class)
	@Nonnull
	public AssociatedDataSchemaContract toInstance() {
		final AssociatedDataSchemaContract instance = assembleInstance();
		if (!this.updatedSchemaValidated) {
			instance.validate()
				.findFirst()
				.ifPresent(error -> {
					throw new InvalidSchemaMutationException(error);
				});
			this.updatedSchemaValidated = true;
		}
		return instance;
	}

	/**
	 * Assembles the associated data schema from the base schema and the mutations recorded so far, without validating
	 * it - the builder's own methods read the half-declared state through it.
	 *
	 * @return the assembled schema
	 */
	@Nonnull
	private AssociatedDataSchemaContract assembleInstance() {
		if (this.updatedSchema == null || this.updatedSchemaDirty != MutationImpact.NO_IMPACT) {
			// if the dirty flag is set to modified previous we need to start from the base schema again
			// and reapply all mutations
			if (this.updatedSchemaDirty == MutationImpact.MODIFIED_PREVIOUS) {
				this.lastMutationReflectedInSchema = 0;
			}
			// if the last mutation reflected in the schema is zero we need to start from the base schema
			// else we can continue modification last known updated schema by adding additional mutations
			AssociatedDataSchemaContract currentSchema = this.lastMutationReflectedInSchema == 0 ?
				this.baseSchema : this.updatedSchema;

			// apply the mutations not reflected in the schema
			for (int i = this.lastMutationReflectedInSchema; i < this.mutations.size(); i++) {
				final EntitySchemaMutation mutation = this.mutations.get(i);
				currentSchema = ((AssociatedDataSchemaMutation) mutation).mutate(currentSchema);
				if (currentSchema == null) {
					throw new GenericEvitaInternalError("Associated data unexpectedly removed from inside!");
				}
			}
			this.updatedSchema = currentSchema;
			this.updatedSchemaDirty = MutationImpact.NO_IMPACT;
			this.lastMutationReflectedInSchema = this.mutations.size();
			// a freshly assembled schema has not been through validate() yet - toInstance() will run it
			this.updatedSchemaValidated = false;
		}
		return this.updatedSchema;
	}

	/**
	 * Returns collection of {@link EntitySchemaMutation} instances describing what changes occurred in the builder
	 * and which should be applied on the existing parent schema in particular version.
	 * Each mutation increases {@link Versioned#version()} of the modified object and allows to detect race
	 * conditions based on "optimistic locking" mechanism in very granular way.
	 */
	@Nonnull
	public Collection<LocalEntitySchemaMutation> toMutation() {
		return this.mutations;
	}

}
