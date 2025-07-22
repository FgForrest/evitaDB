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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Mutation is responsible for setting value to a {@link ReferenceSchemaContract#isIndexed()}
 * in {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link ReferenceSchemaContract} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class SetReferenceSchemaIndexedMutation
	extends AbstractModifyReferenceDataSchemaMutation implements CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = -5386807849414938326L;
	@Getter private final ScopedReferenceIndexType[] indexedInScopes;

	public SetReferenceSchemaIndexedMutation(
		@Nonnull String name,
		@Nullable Boolean indexed
	) {
		this(
			name,
			indexed == null ? null : (indexed ? Scope.DEFAULT_SCOPES : Scope.NO_SCOPE)
		);
	}

	public SetReferenceSchemaIndexedMutation(
		@Nonnull String name,
		@Nullable Scope[] indexedInScopes
	) {
		super(name);
		this.indexedInScopes = indexedInScopes == null ?
			null :
			Arrays.stream(indexedInScopes)
				.map(scope -> new ScopedReferenceIndexType(scope, ReferenceIndexType.FOR_FILTERING))
				.toArray(ScopedReferenceIndexType[]::new);
	}

	public SetReferenceSchemaIndexedMutation(
		@Nonnull String name,
		@Nullable ScopedReferenceIndexType[] indexedInScopes
	) {
		super(name);
		this.indexedInScopes = indexedInScopes;
	}

	@Nullable
	public Boolean getIndexed() {
		if (this.indexedInScopes == null) {
			return null;
		} else {
			return Arrays.stream(this.indexedInScopes)
				.anyMatch(it -> it.scope() == Scope.DEFAULT_SCOPE && it.indexType() != ReferenceIndexType.NONE);
		}
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (existingMutation instanceof SetReferenceSchemaIndexedMutation theExistingMutation && this.name.equals(theExistingMutation.getName())) {
			if (theExistingMutation.indexedInScopes == null && this.indexedInScopes == null) {
				// both mutations are not indexed, so we can skip the combination
				return new MutationCombinationResult<>(null, this);
			} else {
				final Map<Scope, ReferenceIndexType> existingIndexedScopes = theExistingMutation.indexedInScopes == null ?
					CollectionUtils.createHashMap(Scope.values().length) :
					Arrays.stream(theExistingMutation.indexedInScopes)
						.collect(
							() -> new EnumMap<>(Scope.class),
							(map, scopedIndexType) -> map.put(scopedIndexType.scope(), scopedIndexType.indexType()),
							EnumMap::putAll
						);
				for (ScopedReferenceIndexType indexedInScope : this.indexedInScopes) {
					existingIndexedScopes.put(indexedInScope.scope(), indexedInScope.indexType());
				}

				SetReferenceSchemaIndexedMutation combinedMutation = new SetReferenceSchemaIndexedMutation(
					this.name,
					existingIndexedScopes
						.entrySet()
						.stream()
						.map(entry -> new ScopedReferenceIndexType(entry.getKey(), entry.getValue()))
						.toArray(ScopedReferenceIndexType[]::new)
				);
				return new MutationCombinationResult<>(null, combinedMutation);
			}
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public ReferenceSchemaContract mutate(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema, @Nonnull ConsistencyChecks consistencyChecks) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		final EnumMap<Scope, ReferenceIndexType> indexedScopes = this.indexedInScopes == null ?
			new EnumMap<>(Scope.class) :
			Arrays.stream(this.indexedInScopes)
				.collect(
					() -> new EnumMap<>(Scope.class),
					(map, scopedIndexType) -> map.put(scopedIndexType.scope(), scopedIndexType.indexType()),
					EnumMap::putAll
				);
		if (referenceSchema instanceof ReflectedReferenceSchema reflectedReferenceSchema) {
			if ((reflectedReferenceSchema.isIndexedInherited() && this.indexedInScopes == null) ||
				(!reflectedReferenceSchema.isIndexedInherited() && reflectedReferenceSchema.getIndexedInScopes().equals(indexedScopes))) {
				return reflectedReferenceSchema;
			} else {
				return reflectedReferenceSchema.withIndexed(this.indexedInScopes);
			}
		} else {
			if (indexedScopes.equals(referenceSchema.getReferenceIndexTypeInScopes())) {
				// schema is already indexed
				return referenceSchema;
			} else {
				if (consistencyChecks == ConsistencyChecks.APPLY) {
					verifyAttributeIndexRequirements(entitySchema, referenceSchema);
				}

				// Convert EnumSet<Scope> to ScopedReferenceIndexType[] for the new API
				final ScopedReferenceIndexType[] scopedIndexTypes = indexedScopes.entrySet().stream()
					.map(entry -> new ScopedReferenceIndexType(entry.getKey(), entry.getValue()))
					.toArray(ScopedReferenceIndexType[]::new);

				return ReferenceSchema._internalBuild(
					this.name,
					referenceSchema.getNameVariants(),
					referenceSchema.getDescription(),
					referenceSchema.getDeprecationNotice(),
					referenceSchema.getReferencedEntityType(),
					referenceSchema.isReferencedEntityTypeManaged() ? Collections.emptyMap() : referenceSchema.getEntityTypeNameVariants(s -> null),
					referenceSchema.isReferencedEntityTypeManaged(),
					referenceSchema.getCardinality(),
					referenceSchema.getReferencedGroupType(),
					referenceSchema.isReferencedGroupTypeManaged() ? Collections.emptyMap() : referenceSchema.getGroupTypeNameVariants(s -> null),
					referenceSchema.isReferencedGroupTypeManaged(),
					scopedIndexTypes,
					Arrays.stream(Scope.values()).filter(referenceSchema::isFacetedInScope).toArray(Scope[]::new),
					referenceSchema.getAttributes(),
					referenceSchema.getSortableAttributeCompounds()
				);
			}
		}
	}

	private static void verifyAttributeIndexRequirements(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema
	) {
		for (Scope scope : Scope.values()) {
			for (AttributeSchemaContract attributeSchema : referenceSchema.getAttributes().values()) {
				if (attributeSchema.isFilterableInScope(scope) || attributeSchema.isUniqueInScope(scope) || attributeSchema.isSortableInScope(scope)) {
					final String type;
					if (attributeSchema.isFilterableInScope(scope)) {
						type = "filterable";
					} else if (attributeSchema.isUniqueInScope(scope)) {
						type = "unique";
					} else {
						type = "sortable";
					}
					if (referenceSchema.getReferenceIndexType(scope) == ReferenceIndexType.NONE) {
						// reference schema is not indexed in the scope, but attribute is indexed
						// this is not allowed, because it would prevent filtering / sorting by the attribute
						throw new InvalidSchemaMutationException(
							"Cannot make reference schema `" + referenceSchema.getName() + "` of entity `" + entitySchema.getName() + "` " +
								"non-indexed if there is a single " + type + " attribute in scope `" + scope + "`! Found " + type + " attribute " +
								"definition `" + attributeSchema.getName() + "`."
						);
					}
				}
			}
		}
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final Optional<ReferenceSchemaContract> existingReferenceSchema = entitySchema.getReference(this.name);
		if (existingReferenceSchema.isEmpty()) {
			// ups, the reference schema is missing
			throw new InvalidSchemaMutationException(
				"The reference `" + this.name + "` is not defined in entity `" + entitySchema.getName() + "` schema!"
			);
		} else {
			final ReferenceSchemaContract theSchema = existingReferenceSchema.get();
			final ReferenceSchemaContract updatedReferenceSchema = mutate(entitySchema, theSchema, ConsistencyChecks.SKIP);
			return replaceReferenceSchema(
				entitySchema, theSchema, updatedReferenceSchema
			);
		}
	}

	@Override
	public String toString() {
		return "Set entity reference `" + this.name + "` schema: " +
			"indexed=" + (this.indexedInScopes == null ? "(inherited)" : (ArrayUtils.isEmpty(this.indexedInScopes) ? "(not indexed)" : "(" + Arrays.stream(this.indexedInScopes).map(it -> it.scope().name() + ": " + it.indexType().name()).collect(Collectors.joining(", ")) + ")"));
	}
}
