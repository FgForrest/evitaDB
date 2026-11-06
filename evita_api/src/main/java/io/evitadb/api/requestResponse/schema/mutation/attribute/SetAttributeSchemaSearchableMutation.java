/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.annotation.SerializableCreator;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
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
import java.util.EnumSet;

import static io.evitadb.dataType.Scope.NO_SCOPE;

/**
 * Mutation is responsible for setting value to a {@link AttributeSchemaContract#isSearchable()}
 * in {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link AttributeSchemaContract} or
 * {@link GlobalAttributeSchemaContract} alone, and a reference attribute through
 * {@link ReferenceAttributeSchemaMutation}.
 *
 * The mutation is a **full statement of the searchability axis** - it names every scope the attribute should be
 * searchable in once it is applied, and a scope it does not name ends up not searchable. The fulltext analyzer and the
 * length pivot of the field travel with it but are not part of that statement: `null` keeps the current value, so a
 * client unaware of them never resets them. It touches nothing else: searchability is independent of filterability,
 * so the filterability and the accelerators of the attribute are carried through unchanged.
 *
 * The mutation refuses nothing. Whether the attribute can be searchable at all - a localized `String` or `String[]`
 * - is checked by {@link AttributeSchemaContract#validate()} on the assembled schema, so that `searchable()`,
 * `localized()` and a type change may be declared in any order. It is also never refused because the collection
 * already holds entities: values stored before the attribute became searchable are not indexed retroactively, and
 * a search over them returns incomplete results until they are written again.
 *
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class SetAttributeSchemaSearchableMutation
	extends AbstractAttributeSchemaMutation
	implements EntityAttributeSchemaMutation, GlobalAttributeSchemaMutation, ReferenceAttributeSchemaMutation,
	CombinableLocalEntitySchemaMutation, CombinableCatalogSchemaMutation {
	@Serial private static final long serialVersionUID = 2918246155260731470L;

	/**
	 * The scopes the attribute should be searchable in. Never `null` after construction - an empty array states
	 * "searchable nowhere".
	 */
	@Getter @Nonnull private final Scope[] searchableInScopes;
	/**
	 * The name of the fulltext analyzer to set, see {@link AttributeSchemaContract#getAnalyzer()}, or `null` to keep
	 * the current one.
	 */
	@Getter @Nullable private final String analyzer;
	/**
	 * The length pivot to set, see {@link AttributeSchemaContract#getLengthPivot()}, or `null` to keep the current one.
	 */
	@Getter @Nullable private final Integer lengthPivot;

	/**
	 * Creates a mutation making the attribute searchable in the {@link Scope#DEFAULT_SCOPE default scope} only, or
	 * searchable nowhere.
	 *
	 * @param name       name of the altered attribute
	 * @param searchable true to make the attribute searchable in the default scope, false to make it searchable nowhere
	 */
	public SetAttributeSchemaSearchableMutation(@Nonnull String name, boolean searchable) {
		this(
			name,
			searchable ? Scope.DEFAULT_SCOPES : NO_SCOPE
		);
	}

	/**
	 * Creates a mutation stating the scopes the attribute should be searchable in.
	 *
	 * @param name               name of the altered attribute
	 * @param searchableInScopes the scopes the attribute should be searchable in; a scope not named here ends up not
	 *                           searchable. May be `null`, which means "searchable nowhere"
	 */
	public SetAttributeSchemaSearchableMutation(
		@Nonnull String name,
		@Nullable Scope[] searchableInScopes
	) {
		this(name, searchableInScopes, null, null);
	}

	/**
	 * Creates a mutation stating the scopes the attribute should be searchable in and setting the fulltext analyzer and
	 * the length pivot of its field.
	 *
	 * @param name               name of the altered attribute
	 * @param searchableInScopes the scopes the attribute should be searchable in; a scope not named here ends up not
	 *                           searchable. May be `null`, which means "searchable nowhere"
	 * @param analyzer           the name of the fulltext analyzer, or `null` to keep the current one
	 * @param lengthPivot        the length pivot, or `null` to keep the current one
	 */
	@SerializableCreator
	public SetAttributeSchemaSearchableMutation(
		@Nonnull String name,
		@Nullable Scope[] searchableInScopes,
		@Nullable String analyzer,
		@Nullable Integer lengthPivot
	) {
		super(name);
		this.searchableInScopes = searchableInScopes == null ? NO_SCOPE : searchableInScopes;
		this.analyzer = analyzer;
		this.lengthPivot = lengthPivot;
	}

	/**
	 * Whether this mutation makes the attribute searchable in at least one scope.
	 *
	 * @return true when at least one scope is named
	 */
	public boolean isSearchable() {
		return !ArrayUtils.isEmptyOrItsValuesNull(this.searchableInScopes);
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull LocalCatalogSchemaMutation existingMutation
	) {
		if (existingMutation instanceof SetAttributeSchemaSearchableMutation theExistingMutation &&
			this.name.equals(theExistingMutation.getName())
		) {
			return new MutationCombinationResult<>(null, mergeWith(theExistingMutation));
		} else {
			return null;
		}
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (existingMutation instanceof SetAttributeSchemaSearchableMutation theExistingMutation &&
			this.name.equals(theExistingMutation.getName())
		) {
			return new MutationCombinationResult<>(null, mergeWith(theExistingMutation));
		} else {
			return null;
		}
	}

	/**
	 * Merges this mutation over an earlier one of the same attribute: the scopes are a full statement and replace the
	 * earlier ones, the analyzer and the pivot are kept from the earlier mutation unless this one restates them.
	 *
	 * @param existingMutation the earlier mutation of the same attribute
	 * @return the mutation standing for both
	 */
	@Nonnull
	private SetAttributeSchemaSearchableMutation mergeWith(
		@Nonnull SetAttributeSchemaSearchableMutation existingMutation
	) {
		if ((this.analyzer != null || existingMutation.getAnalyzer() == null) &&
			(this.lengthPivot != null || existingMutation.getLengthPivot() == null)) {
			// the earlier mutation carries nothing this one does not restate
			return this;
		}
		return new SetAttributeSchemaSearchableMutation(
			this.name, this.searchableInScopes,
			this.analyzer == null ? existingMutation.getAnalyzer() : this.analyzer,
			this.lengthPivot == null ? existingMutation.getLengthPivot() : this.lengthPivot
		);
	}

	@Nonnull
	@Override
	public <S extends AttributeSchemaContract> S mutate(
		@Nullable CatalogSchemaContract catalogSchema, @Nullable S attributeSchema, @Nonnull Class<S> schemaType
	) {
		Assert.isPremiseValid(attributeSchema != null, "Attribute schema is mandatory!");
		final EnumSet<Scope> searchable = ArrayUtils.toEnumSet(Scope.class, this.searchableInScopes);
		final String theAnalyzer = this.analyzer == null ? attributeSchema.getAnalyzer() : this.analyzer;
		final int theLengthPivot = this.lengthPivot == null ? attributeSchema.getLengthPivot() : this.lengthPivot;
		if (
			attributeSchema.getSearchableInScopes().equals(searchable) &&
				attributeSchema.getAnalyzer().equals(theAnalyzer) &&
				attributeSchema.getLengthPivot() == theLengthPivot
		) {
			return attributeSchema;
		} else if (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema) {
			//noinspection unchecked,rawtypes
			return (S) GlobalAttributeSchema._internalBuild(
				this.name,
				globalAttributeSchema.getNameVariants(),
				globalAttributeSchema.getDescription(),
				globalAttributeSchema.getDeprecationNotice(),
				globalAttributeSchema.getUniquenessTypeInScopes(),
				globalAttributeSchema.getGlobalUniquenessTypeInScopes(),
				globalAttributeSchema.getFilterableInScopes(),
				globalAttributeSchema.getAcceleratorsInScopes(),
				searchable,
				globalAttributeSchema.getSortableInScopes(),
				globalAttributeSchema.isLocalized(),
				globalAttributeSchema.isNullable(),
				globalAttributeSchema.isRepresentative(),
				(Class) globalAttributeSchema.getType(),
				globalAttributeSchema.getDefaultValue(),
				globalAttributeSchema.getIndexedDecimalPlaces(),
				globalAttributeSchema.getConflictResolutionOverride(),
				globalAttributeSchema.getNormalization(),
				theAnalyzer, theLengthPivot
			);
		} else if (attributeSchema instanceof EntityAttributeSchemaContract entityAttributeSchema) {
			//noinspection unchecked,rawtypes
			return (S) EntityAttributeSchema._internalBuild(
				this.name,
				entityAttributeSchema.getNameVariants(),
				entityAttributeSchema.getDescription(),
				entityAttributeSchema.getDeprecationNotice(),
				entityAttributeSchema.getUniquenessTypeInScopes(),
				entityAttributeSchema.getFilterableInScopes(),
				entityAttributeSchema.getAcceleratorsInScopes(),
				searchable,
				entityAttributeSchema.getSortableInScopes(),
				entityAttributeSchema.isLocalized(),
				entityAttributeSchema.isNullable(),
				entityAttributeSchema.isRepresentative(),
				(Class) entityAttributeSchema.getType(),
				entityAttributeSchema.getDefaultValue(),
				entityAttributeSchema.getIndexedDecimalPlaces(),
				entityAttributeSchema.getConflictResolutionOverride(),
				entityAttributeSchema.getNormalization(),
				theAnalyzer, theLengthPivot
			);
		} else {
			//noinspection unchecked,rawtypes
			return (S) AttributeSchema._internalBuild(
				this.name,
				attributeSchema.getNameVariants(),
				attributeSchema.getDescription(),
				attributeSchema.getDeprecationNotice(),
				attributeSchema.getUniquenessTypeInScopes(),
				attributeSchema.getFilterableInScopes(),
				attributeSchema.getAcceleratorsInScopes(),
				searchable,
				attributeSchema.getSortableInScopes(),
				attributeSchema.isLocalized(),
				attributeSchema.isNullable(),
				attributeSchema.isRepresentative(),
				(Class) attributeSchema.getType(),
				attributeSchema.getDefaultValue(),
				attributeSchema.getIndexedDecimalPlaces(),
				attributeSchema.getConflictResolutionOverride(),
				attributeSchema.getNormalization(),
				theAnalyzer, theLengthPivot
			);
		}
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(
		@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor
	) {
		return mutateGlobalAttributeSchema(catalogSchema, entitySchemaAccessor, this);
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "Set attribute `" + this.name + "` schema: " +
			"searchable=" + (isSearchable() ? "(in scopes: " + Arrays.toString(this.searchableInScopes) + ")" : "no") +
			(this.analyzer == null ? "" : ", analyzer=" + this.analyzer) +
			(this.lengthPivot == null ? "" : ", lengthPivot=" + this.lengthPivot);
	}

}
