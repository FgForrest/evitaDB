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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NamingConvention;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Collections;
import java.util.Optional;

/**
 * Mutation is responsible for setting value to a {@link ReferenceSchemaContract#getReferencedGroupType()}
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
public class ModifyReferenceSchemaRelatedEntityGroupMutation
	extends AbstractModifyReferenceDataSchemaMutation implements CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = 5652064385493788515L;
	@Nullable @Getter private final String referencedGroupType;
	@Getter private final boolean referencedGroupTypeManaged;

	public ModifyReferenceSchemaRelatedEntityGroupMutation(@Nonnull String name, @Nullable String referencedGroupType, boolean referencedGroupTypeManaged) {
		super(name);
		this.referencedGroupType = referencedGroupType;
		this.referencedGroupTypeManaged = referencedGroupType != null && referencedGroupTypeManaged;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (existingMutation instanceof ModifyReferenceSchemaRelatedEntityGroupMutation theExistingMutation && name.equals(theExistingMutation.getName())) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public ReferenceSchemaContract mutate(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema, @Nonnull ConsistencyChecks consistencyChecks) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		Assert.isPremiseValid(
			!(referenceSchema instanceof ReflectedReferenceSchema),
			() -> "Group cannot be changed on reflected reference. This mutation can be applied only on original reference!"
		);
		if (referenceSchema instanceof ReferenceSchema theReferenceSchema) {
			return ReferenceSchema._internalBuild(
				this.name,
				theReferenceSchema.getNameVariants(),
				theReferenceSchema.getDescription(),
				theReferenceSchema.getDeprecationNotice(),
				theReferenceSchema.getCardinality(),
				theReferenceSchema.getReferencedEntityType(),
				theReferenceSchema.isReferencedEntityTypeManaged() ? Collections.emptyMap() : theReferenceSchema.getEntityTypeNameVariants(s -> null),
				theReferenceSchema.isReferencedEntityTypeManaged(),
				this.referencedGroupType,
				this.referencedGroupTypeManaged || this.referencedGroupType == null ?
					Collections.emptyMap() : NamingConvention.generate(this.referencedGroupType),
				this.referencedGroupTypeManaged,
				theReferenceSchema.getIndexedInScopes(),
				theReferenceSchema.getFacetedInScopes(),
				theReferenceSchema.getAttributes(),
				theReferenceSchema.getSortableAttributeCompounds()
			);
		} else {
			throw new InvalidSchemaMutationException(
				"Reference schema `" + referenceSchema.getName() + "` is not a valid reference schema!"
			);
		}
	}

	@Nullable
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final Optional<ReferenceSchemaContract> existingReferenceSchema = entitySchema.getReference(name);
		if (existingReferenceSchema.isEmpty()) {
			// ups, the reference is missing
			throw new InvalidSchemaMutationException(
				"The reference `" + this.name + "` is not defined in entity `" + entitySchema.getName() + "` schema!"
			);
		} else {
			final ReferenceSchemaContract theSchema = existingReferenceSchema.get();
			final ReferenceSchemaContract updatedReferenceSchema = mutate(entitySchema, theSchema);
			return replaceReferenceSchema(entitySchema, theSchema, updatedReferenceSchema);
		}
	}

	@Override
	public String toString() {
		return "Modify entity reference `" + this.name + "` schema: " +
			"referencedGroupType='" + this.referencedGroupType + '\'' +
			", relatesToEntity=" + this.referencedGroupTypeManaged;
	}
}
