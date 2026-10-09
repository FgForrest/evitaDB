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

package io.evitadb.api.requestResponse.schema.mutation.associatedData;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.annotation.SerializableCreator;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AssociatedDataSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
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
import java.util.Objects;
import java.util.Optional;

import static io.evitadb.dataType.Scope.NO_SCOPE;

/**
 * Mutation is responsible for setting value to a {@link AssociatedDataSchemaContract#isSearchable()} and
 * {@link AssociatedDataSchemaContract#getTextExpression()} in {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link AssociatedDataSchemaContract} alone.
 *
 * The mutation is a **full statement of the searchability axis** - it names every scope the associated data should be
 * searchable in once it is applied (a scope it does not name ends up not searchable) together with the one text
 * expression that applies to all of them. It touches nothing else.
 *
 * The mutation refuses nothing. Whether the associated data can be searchable at all - localized, of type `String`,
 * `String[]` or a complex data object with a text expression - is checked by
 * {@link AssociatedDataSchemaContract#validate()} on the assembled schema, so that `searchable()`, `localized()`, a
 * type change and the expression may be declared in any order. It is also never refused because the collection
 * already holds entities: values stored before are not indexed retroactively, and a changed expression applies only to
 * a fulltext field built anew - see {@link AssociatedDataSchemaContract#isSearchable()}.
 *
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class SetAssociatedDataSchemaSearchableMutation
	extends AbstractModifyAssociatedDataSchemaMutation implements CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = -2554209583004408218L;

	/**
	 * The scopes the associated data should be searchable in. Never `null` after construction - an empty array states
	 * "searchable nowhere".
	 */
	@Getter @Nonnull private final Scope[] searchableInScopes;
	/**
	 * The expression selecting the text of a complex data object value, or `null` for none.
	 */
	@Getter @Nullable private final Expression textExpression;

	/**
	 * Creates a mutation making the associated data searchable in the {@link Scope#DEFAULT_SCOPE default scope} only,
	 * or searchable nowhere, without a text expression.
	 *
	 * @param name       name of the altered associated data
	 * @param searchable true to make the associated data searchable in the default scope, false to make it searchable
	 *                   nowhere
	 */
	public SetAssociatedDataSchemaSearchableMutation(@Nonnull String name, boolean searchable) {
		this(name, searchable ? Scope.DEFAULT_SCOPES : NO_SCOPE, null);
	}

	/**
	 * Creates a mutation stating the scopes the associated data should be searchable in and its text expression.
	 *
	 * @param name               name of the altered associated data
	 * @param searchableInScopes the scopes the associated data should be searchable in; a scope not named here ends up
	 *                           not searchable. May be `null`, which means "searchable nowhere"
	 * @param textExpression     the expression selecting the text of a complex data object value, or `null`
	 */
	@SerializableCreator
	public SetAssociatedDataSchemaSearchableMutation(
		@Nonnull String name,
		@Nullable Scope[] searchableInScopes,
		@Nullable Expression textExpression
	) {
		super(name);
		this.searchableInScopes = searchableInScopes == null ? NO_SCOPE : searchableInScopes;
		this.textExpression = textExpression;
	}

	/**
	 * Whether this mutation makes the associated data searchable in at least one scope.
	 *
	 * @return true when at least one scope is named
	 */
	public boolean isSearchable() {
		return !ArrayUtils.isEmptyOrItsValuesNull(this.searchableInScopes);
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (existingMutation instanceof SetAssociatedDataSchemaSearchableMutation theExistingMutation &&
			this.name.equals(theExistingMutation.getName())
		) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public AssociatedDataSchemaContract mutate(@Nullable AssociatedDataSchemaContract associatedDataSchema) {
		Assert.isPremiseValid(associatedDataSchema != null, "Associated data schema is mandatory!");
		final EnumSet<Scope> searchable = ArrayUtils.toEnumSet(Scope.class, this.searchableInScopes);
		if (
			associatedDataSchema.getSearchableInScopes().equals(searchable) &&
				Objects.equals(associatedDataSchema.getTextExpression(), this.textExpression)
		) {
			return associatedDataSchema;
		}
		return AssociatedDataSchema._internalBuild(
			this.name,
			associatedDataSchema.getNameVariants(),
			associatedDataSchema.getDescription(),
			associatedDataSchema.getDeprecationNotice(),
			associatedDataSchema.getType(),
			associatedDataSchema.isLocalized(),
			associatedDataSchema.isNullable(),
			searchable,
			this.textExpression,
			associatedDataSchema.getConflictResolutionOverride()
		);
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(
		@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final Optional<AssociatedDataSchemaContract> existingAssociatedDataSchema = entitySchema.getAssociatedData(
			this.name);
		if (existingAssociatedDataSchema.isEmpty()) {
			// ups, the associated data is missing
			throw new InvalidSchemaMutationException(
				"The associated data `" + this.name + "` is not defined in entity `" + entitySchema.getName() +
					"` schema!"
			);
		} else {
			final AssociatedDataSchemaContract theSchema = existingAssociatedDataSchema.get();
			final AssociatedDataSchemaContract updatedAssociatedDataSchema = mutate(theSchema);
			return replaceAssociatedDataIfDifferent(entitySchema, theSchema, updatedAssociatedDataSchema);
		}
	}

	@Override
	public String toString() {
		return "Set associated data `" + this.name + "` schema: " +
			"searchable=" + (isSearchable() ? "(in scopes: " + Arrays.toString(this.searchableInScopes) + ")" : "no") +
			(this.textExpression == null ? "" : ", textExpression=" + this.textExpression.toExpressionString());
	}

}
