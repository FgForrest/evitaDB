/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.api.exception.AssociatedDataAlreadyPresentInEntitySchemaException;
import io.evitadb.api.exception.AttributeAlreadyPresentInCatalogSchemaException;
import io.evitadb.api.exception.AttributeAlreadyPresentInEntitySchemaException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.ReferenceAlreadyPresentInEntitySchemaException;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.AbstractReferenceSchemaBuilder.ReferenceSchemaBuilderResult;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.RemoveAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.EntityAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.UseGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.AllowCurrencyInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.AllowEvolutionModeInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.AllowLocaleInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.DisallowCurrencyInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.DisallowEvolutionModeInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.DisallowLocaleInEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaConflictResolutionMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithGeneratedPrimaryKeyMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithHierarchyMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.SetEntitySchemaWithPriceMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.RemoveReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutation;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import io.evitadb.utils.NamingConvention;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Currency;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static io.evitadb.utils.Assert.isTrue;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Internal implementation of the {@link EntitySchemaBuilder} builder returned by {@link SealedEntitySchema} when
 * {@link SealedEntitySchema#openForWrite()} is called. This implementation does the heavy lifting of this particular
 * interface. The class should never be instantiated by the client code.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public final class InternalEntitySchemaBuilder implements EntitySchemaBuilder, InternalSchemaBuilderHelper {
	@Serial private static final long serialVersionUID = -2643204562100111998L;
	private static final LocalEntitySchemaMutation[] EMPTY_ARRAY = new LocalEntitySchemaMutation[0];

	private final EntitySchemaContract baseSchema;
	/**
	 * Tells for each class of entity attribute mutation whether it applies to an entity schema the default way of
	 * {@link EntityAttributeSchemaMutation#mutate(CatalogSchemaContract, EntitySchemaContract)}, which changes
	 * nothing but its attribute - so that {@link #applyMutations(EntitySchemaContract, int)} can apply a run of such
	 * mutations to the attributes alone. A mutation overriding that method does something else as well and is applied
	 * on its own.
	 */
	private static final ClassValue<Boolean> CHANGES_ATTRIBUTE_ALONE = new ClassValue<>() {
		@Nonnull
		@Override
		protected Boolean computeValue(@Nonnull Class<?> type) {
			try {
				return type.getMethod("mutate", CatalogSchemaContract.class, EntitySchemaContract.class)
					.getDeclaringClass() == EntityAttributeSchemaMutation.class;
			} catch (NoSuchMethodException e) {
				throw new GenericEvitaInternalError(
					"Mutation `" + type.getName() + "` has no method applying it to an entity schema!", e
				);
			}
		}
	};
	private final List<LocalEntitySchemaMutation> mutations = new LinkedList<>();
	private Supplier<CatalogSchemaContract> catalogSchemaAccessor;
	private MutationImpact updatedSchemaDirty = MutationImpact.NO_IMPACT;
	private int lastMutationReflectedInSchema = 0;
	private EntitySchemaContract updatedSchema;

	public InternalEntitySchemaBuilder(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract baseSchema,
		@Nonnull Collection<LocalEntitySchemaMutation> schemaMutations
	) {
		this.catalogSchemaAccessor = () -> catalogSchema;
		this.baseSchema = baseSchema;
		this.updatedSchemaDirty = addMutations(
			this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
			schemaMutations.toArray(EMPTY_ARRAY)
		);
	}

	public InternalEntitySchemaBuilder(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract baseSchema
	) {
		this(catalogSchema, baseSchema, Collections.emptyList());
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder cooperatingWith(@Nonnull Supplier<CatalogSchemaContract> catalogSupplier) {
		this.catalogSchemaAccessor = catalogSupplier;
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder verifySchemaStrictly() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new DisallowEvolutionModeInEntitySchemaMutation(EvolutionMode.values())
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder verifySchemaButAllow(@Nonnull EvolutionMode... evolutionMode) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new DisallowEvolutionModeInEntitySchemaMutation(EvolutionMode.values()),
				new AllowEvolutionModeInEntitySchemaMutation(evolutionMode)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder verifySchemaButCreateOnTheFly() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new AllowEvolutionModeInEntitySchemaMutation(EvolutionMode.values())
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withGeneratedPrimaryKey() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithGeneratedPrimaryKeyMutation(true)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withoutGeneratedPrimaryKey() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithGeneratedPrimaryKeyMutation(false)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withHierarchyIndexedInScope(@Nonnull Scope... inScopes) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithHierarchyMutation(true, inScopes)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withoutHierarchy() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithHierarchyMutation(false, Scope.NO_SCOPE)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withoutHierarchyIndexedInScope(@Nonnull Scope... inScopes) {
		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScopes);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithHierarchyMutation(
					true,
					Arrays.stream(Scope.values())
						.filter(this::isHierarchyIndexedInScope)
						.filter(it -> !excludedScopes.contains(it))
						.toArray(Scope[]::new)
				)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withPriceIndexedInScope(@Nonnull Scope... inScopes) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithPriceMutation(true, inScopes, 2)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withPriceIndexedInScope(int indexedDecimalPlaces, @Nonnull Scope... inScopes) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithPriceMutation(true, inScopes, indexedDecimalPlaces)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withIndexedPriceInCurrency(@Nonnull Currency[] currency, @Nonnull Scope... inScopes) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithPriceMutation(true, inScopes, 2),
				new AllowCurrencyInEntitySchemaMutation(currency)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withPriceInCurrencyIndexedInScope(int indexedPricePlaces, @Nonnull Currency[] currency, @Nonnull Scope... inScopes) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithPriceMutation(true, inScopes, indexedPricePlaces),
				new AllowCurrencyInEntitySchemaMutation(currency)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withoutPrice() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithPriceMutation(false, Scope.NO_SCOPE, 0)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withoutPriceIndexedInScope(@Nonnull Scope... inScopes) {
		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScopes);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new SetEntitySchemaWithPriceMutation(
					true,
					Arrays.stream(Scope.values())
						.filter(this::isPriceIndexedInScope)
						.filter(it -> !excludedScopes.contains(it))
						.toArray(Scope[]::new),
					getIndexedPricePlaces()
				)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withoutPriceInCurrency(@Nonnull Currency currency) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new DisallowCurrencyInEntitySchemaMutation(currency)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withLocale(@Nonnull Locale... locale) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new AllowLocaleInEntitySchemaMutation(locale)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withoutLocale(@Nonnull Locale locale) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new DisallowLocaleInEntitySchemaMutation(locale)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withGlobalAttribute(@Nonnull String attributeName) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new UseGlobalAttributeSchemaMutation(attributeName)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withAssociatedData(@Nonnull String dataName, @Nonnull Class<? extends Serializable> ofType) {
		return withAssociatedData(dataName, ofType, null);
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withAssociatedData(
		@Nonnull String dataName,
		@Nonnull Class<? extends Serializable> ofType,
		@Nullable Consumer<AssociatedDataSchemaEditor> whichIs
	) {
		final Optional<AssociatedDataSchemaContract> existingAssociatedData = getAssociatedData(dataName);
		final CatalogSchemaContract catalogSchema = this.catalogSchemaAccessor.get();
		final Class<? extends Serializable> toBeAssignedType = EvitaDataTypes.isSupportedTypeOrItsArray(ofType) ?
			EvitaDataTypes.toWrappedForm(ofType) :
			ComplexDataObject.class;
		final AssociatedDataSchemaBuilder associatedDataSchemaBuilder = existingAssociatedData
			.map(it -> {
				isTrue(
					toBeAssignedType.equals(it.getType()),
					() -> new InvalidMutationException(
						"Associated data " + dataName + " has already assigned type " + it.getType() +
							", cannot change this type to: " + ofType + "!"
					)
				);
				return new AssociatedDataSchemaBuilder(catalogSchema, this.baseSchema, it);
			})
			.orElseGet(() -> new AssociatedDataSchemaBuilder(catalogSchema, this.baseSchema, dataName, ofType));

		ofNullable(whichIs).ifPresent(it -> it.accept(associatedDataSchemaBuilder));
		final AssociatedDataSchemaContract associatedDataSchema = associatedDataSchemaBuilder.toInstance();

		if (existingAssociatedData.map(it -> !it.equals(associatedDataSchema)).orElse(true)) {
			ClassifierUtils.validateClassifierFormat(ClassifierType.ASSOCIATED_DATA, dataName);
			// check the names in all naming conventions are unique in the entity schema
			getAssociatedData()
				.values()
				.stream()
				.filter(it -> !Objects.equals(it.getName(), associatedDataSchema.getName()))
				.flatMap(it -> it.getNameVariants()
					.entrySet()
					.stream()
					.filter(nameVariant -> nameVariant.getValue().equals(associatedDataSchema.getNameVariant(nameVariant.getKey())))
					.map(nameVariant -> new AssociatedDataNamingConventionConflict(it, nameVariant.getKey(), nameVariant.getValue()))
				)
				.forEach(conflict -> {
					throw new AssociatedDataAlreadyPresentInEntitySchemaException(
						conflict.conflictingSchema(), associatedDataSchema,
						conflict.convention(), conflict.conflictingName()
					);
				});
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					catalogSchema, this.baseSchema, this.mutations,
					associatedDataSchemaBuilder.toMutation().toArray(EMPTY_ARRAY)
				)
			);
		}
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withoutAssociatedData(@Nonnull String dataName) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new RemoveAssociatedDataSchemaMutation(dataName)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withReferenceTo(@Nonnull String name, @Nonnull String externalEntityType, @Nonnull Cardinality cardinality) {
		return withReferenceTo(name, externalEntityType, cardinality, null);
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withReferenceTo(
		@Nonnull String referenceName,
		@Nonnull String externalEntityType,
		@Nonnull Cardinality cardinality,
		@Nullable Consumer<ReferenceSchemaEditor.ReferenceSchemaBuilder> whichIs
	) {
		final EntitySchemaContract currentSchema = toInstance();
		final ReferenceSchemaContract existingReference = currentSchema.getReference(referenceName).orElse(null);

		Assert.isTrue(
			!(existingReference instanceof ReflectedReferenceSchemaContract),
			() -> new InvalidSchemaMutationException(
				"Reference `" + referenceName + "` is already created as reflected reference, " +
					"you need first to remove it to create a standard reference of such name."
			)
		);

		final ReferenceSchemaBuilder referenceBuilder = new ReferenceSchemaBuilder(
			this.catalogSchemaAccessor.get(),
			this.baseSchema,
			existingReference,
			referenceName,
			externalEntityType,
			false,
			cardinality,
			this.mutations,
			isCreatedAnew(existingReference, referenceName, false)
		);
		ofNullable(whichIs).ifPresent(it -> it.accept(referenceBuilder));

		final ReferenceSchemaBuilderResult result = referenceBuilder.toResult();
		redefineReferenceType(
			existingReference,
			result.schema(),
			result.mutations()
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withReferenceToEntity(@Nonnull String name, @Nonnull String entityType, @Nonnull Cardinality cardinality) {
		return withReferenceToEntity(name, entityType, cardinality, null);
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withReferenceToEntity(
		@Nonnull String referenceName,
		@Nonnull String entityType,
		@Nonnull Cardinality cardinality,
		@Nullable Consumer<ReferenceSchemaEditor.ReferenceSchemaBuilder> whichIs
	) {
		final EntitySchemaContract currentSchema = toInstance();
		final ReferenceSchemaContract existingReference = currentSchema.getReference(referenceName).orElse(null);

		Assert.isTrue(
			!(existingReference instanceof ReflectedReferenceSchemaContract),
			() -> new InvalidSchemaMutationException(
				"Reference `" + referenceName + "` is already created as reflected reference, " +
					"you need first to remove it to create a standard reference of such name."
			)
		);

		final ReferenceSchemaBuilder referenceSchemaBuilder = new ReferenceSchemaBuilder(
			this.catalogSchemaAccessor.get(),
			this.baseSchema,
			existingReference,
			referenceName,
			entityType,
			true,
			cardinality,
			this.mutations,
			isCreatedAnew(existingReference, referenceName, false)
		);
		ofNullable(whichIs).ifPresent(it -> it.accept(referenceSchemaBuilder));

		final ReferenceSchemaBuilderResult result = referenceSchemaBuilder.toResult();
		redefineReferenceType(
			existingReference,
			result.schema(),
			result.mutations()
		);
		return this;
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withReflectedReferenceToEntity(@Nonnull String name, @Nonnull String entityType, @Nonnull String reflectedReferenceName) {
		return withReflectedReferenceToEntity(name, entityType, reflectedReferenceName, null);
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withReflectedReferenceToEntity(
		@Nonnull String referenceName,
		@Nonnull String entityType,
		@Nonnull String reflectedReferenceName,
		@Nullable Consumer<ReflectedReferenceSchemaEditor.ReflectedReferenceSchemaBuilder> whichIs
	) {
		final EntitySchemaContract currentSchema = toInstance();
		final ReferenceSchemaContract existingReference = currentSchema.getReference(referenceName).orElse(null);
		Assert.isTrue(
			existingReference == null || existingReference instanceof ReflectedReferenceSchemaContract,
			() -> new InvalidSchemaMutationException(
				"Reference `" + referenceName + "` is already created as standard reference, " +
					"you need first to remove it to create a reflected reference of such name."
			)
		);
		final ReflectedReferenceSchemaBuilder referenceSchemaBuilder = new ReflectedReferenceSchemaBuilder(
			this.catalogSchemaAccessor.get(),
			this.baseSchema,
			(ReflectedReferenceSchemaContract) existingReference,
			referenceName,
			entityType,
			reflectedReferenceName,
			this.mutations,
			isCreatedAnew(existingReference, referenceName, true)
		);
		ofNullable(whichIs).ifPresent(it -> it.accept(referenceSchemaBuilder));

		final ReferenceSchemaBuilderResult result = referenceSchemaBuilder.toResult();
		redefineReferenceType(
			existingReference,
			result.schema(),
			result.mutations()
		);
		return this;
	}

	/**
	 * Decides whether the reference builder has to start with the creation of the reference. That is the case when
	 * the builder state has no such reference - because the stored schema has none of the requested kind, or because
	 * this builder removed the stored one - or when the stored reference is of the other kind (standard versus
	 * reflected), which the requested definition replaces.
	 *
	 * @param existingReference the reference as the builder state has it, null when it has none
	 * @param referenceName     the name of the reference
	 * @param reflected         true when a reflected reference is requested
	 * @return true when the reference must be created anew
	 */
	private boolean isCreatedAnew(
		@Nullable ReferenceSchemaContract existingReference,
		@Nonnull String referenceName,
		boolean reflected
	) {
		return existingReference == null ||
			this.baseSchema.getReference(referenceName)
				.map(storedReference -> (storedReference instanceof ReflectedReferenceSchemaContract) != reflected)
				.orElse(true);
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withoutReferenceTo(@Nonnull String name) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new RemoveReferenceSchemaMutation(name)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withDescription(@Nullable String description) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new ModifyEntitySchemaDescriptionMutation(description)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withConflictResolution(@Nonnull ConflictResolution conflictResolution) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new ModifyEntitySchemaConflictResolutionMutation(conflictResolution)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withoutConflictResolution() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new ModifyEntitySchemaConflictResolutionMutation(null)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder deprecated(@Nonnull String deprecationNotice) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new ModifyEntitySchemaDeprecationNoticeMutation(deprecationNotice)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder notDeprecatedAnymore() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new ModifyEntitySchemaDeprecationNoticeMutation(null)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withAttribute(@Nonnull String attributeName, @Nonnull Class<? extends Serializable> ofType) {
		return withAttribute(attributeName, ofType, null);
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withAttribute(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> ofType,
		@Nullable Consumer<EntityAttributeSchemaEditor.EntityAttributeSchemaBuilder> whichIs
	) {
		final CatalogSchemaContract catalogSchema = this.catalogSchemaAccessor.get();
		catalogSchema.getAttribute(attributeName)
			.ifPresent(it -> {
				throw new AttributeAlreadyPresentInCatalogSchemaException(
					catalogSchema.getName(), Objects.requireNonNull(it)
				);
			});
		final Optional<EntityAttributeSchemaContract> existingAttribute = getAttribute(attributeName);
		final EntityAttributeSchemaBuilder attributeSchemaBuilder =
			existingAttribute
				.map(it -> {
					final EntityAttributeSchemaBuilder builder = new EntityAttributeSchemaBuilder(this.baseSchema, it);
					isTrue(
						ofType.equals(it.getType()),
						() -> new AttributeAlreadyPresentInEntitySchemaException(
							it, builder.toInstance(), null, attributeName
						)
					);
					return builder;
				})
				.orElseGet(() -> new EntityAttributeSchemaBuilder(this.baseSchema, attributeName, ofType));

		ofNullable(whichIs).ifPresent(it -> it.accept(attributeSchemaBuilder));
		final EntityAttributeSchemaContract attributeSchema = attributeSchemaBuilder.toInstance();

		EntitySchema.assertNotReferencedEntityPredecessor(attributeName, attributeSchema.getType());
		checkSortableTraits(attributeName, attributeSchema);

		// check the names in all naming conventions are unique in the catalog schema
		checkNamesAreUniqueInAllNamingConventions(
			this.getAttributes().values(),
			this.getSortableAttributeCompounds().values(),
			attributeSchema
		);

		if (existingAttribute.map(it -> !it.equals(attributeSchema)).orElse(true)) {
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					catalogSchema, this.baseSchema, this.mutations,
					attributeSchemaBuilder.toMutation().toArray(EMPTY_ARRAY)
				)
			);
		}
		return this;
	}

	@Override
	@Nonnull
	public EntitySchemaBuilder withoutAttribute(@Nonnull String attributeName) {
		checkSortableAttributeCompoundsWithoutAttribute(
			attributeName, this.getSortableAttributeCompounds().values()
		);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new RemoveAttributeSchemaMutation(attributeName)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withSortableAttributeCompound(
		@Nonnull String name,
		@Nonnull AttributeElement... attributeElements
	) {
		return withSortableAttributeCompound(
			name, attributeElements, null
		);
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withSortableAttributeCompound(
		@Nonnull String name,
		@Nonnull AttributeElement[] attributeElements,
		@Nullable Consumer<SortableAttributeCompoundSchemaBuilder> whichIs
	) {
		final Optional<EntitySortableAttributeCompoundSchemaContract> existingCompound = getSortableAttributeCompound(name);
		final CatalogSchemaContract catalogSchema = this.catalogSchemaAccessor.get();
		final SortableAttributeCompoundSchemaBuilder builder = new SortableAttributeCompoundSchemaBuilder(
			catalogSchema,
			this,
			null,
			existingCompound.orElse(null),
			name,
			Arrays.asList(attributeElements),
			Collections.emptyList(),
			true
		);
		final SortableAttributeCompoundSchemaBuilder schemaBuilder =
			existingCompound
				.map(it -> {
					isTrue(
						it.getAttributeElements().equals(Arrays.asList(attributeElements)),
						() -> new AttributeAlreadyPresentInEntitySchemaException(
							it, builder.toInstance(), null, name
						)
					);
					return builder;
				})
				.orElse(builder);

		ofNullable(whichIs).ifPresent(it -> it.accept(schemaBuilder));
		final SortableAttributeCompoundSchemaContract compoundSchema = schemaBuilder.toInstance();
		validateSortableAttributeCompound(
			name, compoundSchema, this.getAttributes(),
			this.getSortableAttributeCompounds().values()
		);

		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				catalogSchema, this, this.mutations,
				new CreateSortableAttributeCompoundSchemaMutation(
					compoundSchema.getName(),
					compoundSchema.getDescription(),
					compoundSchema.getDeprecationNotice(),
					Arrays.stream(Scope.values())
						.filter(compoundSchema::isIndexedInScope)
						.toArray(Scope[]::new),
					attributeElements
				)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withoutSortableAttributeCompound(@Nonnull String name) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
				new RemoveSortableAttributeCompoundSchemaMutation(name)
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

	/**
	 * Returns the collected mutations, or nothing when they leave the entity schema exactly as the base schema was.
	 *
	 * The no-op check of the individual builder methods compares with the current builder state only, so a value that
	 * is set and later restored (for example by a schema post-processor) leaves mutations behind. They change nothing,
	 * but the server would still record them in the transaction and derive conflict keys from them, so the final schema
	 * is compared with the base schema by value before anything is emitted.
	 */
	@Nonnull
	@Override
	public Optional<ModifyEntitySchemaMutation> toMutation() {
		if (
			this.mutations.isEmpty() ||
				SchemaContentComparator.isEntitySchemaContentEqual(this.baseSchema, toInstance())
		) {
			return empty();
		}
		return of(new ModifyEntitySchemaMutation(getName(), this.mutations.toArray(EMPTY_ARRAY)));
	}

	@Nonnull
	@Delegate(types = EntitySchemaContract.class, excludes = NamedSchemaContract.class)
	@Override
	public EntitySchemaContract toInstance() {
		if (this.updatedSchema == null || this.updatedSchemaDirty != MutationImpact.NO_IMPACT) {
			// if the dirty flag is set to modified previous we need to start from the base schema again
			// and reapply all mutations
			if (this.updatedSchemaDirty == MutationImpact.MODIFIED_PREVIOUS) {
				this.lastMutationReflectedInSchema = 0;
			}
			// if the last mutation reflected in the schema is zero we need to start from the base schema
			// else we can continue modification last known updated schema by adding additional mutations
			final EntitySchemaContract startSchema = this.lastMutationReflectedInSchema == 0 ?
				this.baseSchema : this.updatedSchema;

			// apply the mutations not reflected in the schema
			this.updatedSchema = applyMutations(startSchema, this.lastMutationReflectedInSchema);
			this.updatedSchemaDirty = MutationImpact.NO_IMPACT;
			this.lastMutationReflectedInSchema = this.mutations.size();
		}
		return this.updatedSchema;
	}

	/**
	 * Applies the mutations from the given position on to the schema and returns the result.
	 *
	 * A run of consecutive mutations that change one attribute alone is applied to the attributes, and the entity
	 * schema is built once for the whole run. Building an entity schema costs time proportional to its attributes,
	 * and the replay of a builder whose earlier mutation was replaced starts from the base schema, so building it
	 * after each such mutation made a sequence of calls restoring a property of many attributes cubic in their
	 * number. The result is the schema the server gets by applying the mutations one by one: the same content and
	 * order of attributes, the version raised by the number of mutations that change something, and the same
	 * exception at the same mutation.
	 *
	 * @param startSchema the schema the first mutation applies to
	 * @param fromIndex   the position of the first mutation to apply
	 * @return the schema with the mutations applied
	 */
	@Nonnull
	private EntitySchemaContract applyMutations(@Nonnull EntitySchemaContract startSchema, int fromIndex) {
		EntitySchemaContract currentSchema = startSchema;
		// the attributes of the current schema with the changes of the current run, null until the run changes one
		Map<String, EntityAttributeSchemaContract> changedAttributes = null;
		int changes = 0;
		final ListIterator<LocalEntitySchemaMutation> iterator = this.mutations.listIterator(fromIndex);
		while (iterator.hasNext()) {
			final LocalEntitySchemaMutation mutation = iterator.next();
			final CatalogSchemaContract catalogSchema = this.catalogSchemaAccessor.get();
			if (
				mutation instanceof EntityAttributeSchemaMutation attributeMutation &&
					CHANGES_ATTRIBUTE_ALONE.get(mutation.getClass())
			) {
				// the steps of EntityAttributeSchemaMutation#mutate, without building the entity schema
				attributeMutation.verifyApplicableToEntity(catalogSchema, currentSchema.getName());
				final EntityAttributeSchemaContract existingAttribute = changedAttributes == null ?
					currentSchema.getAttribute(attributeMutation.getName()).orElse(null) :
					changedAttributes.get(attributeMutation.getName());
				if (existingAttribute == null) {
					throw new InvalidSchemaMutationException(
						"The attribute `" + attributeMutation.getName() + "` is not defined in entity `" +
							currentSchema.getName() + "` schema!"
					);
				}
				final EntityAttributeSchemaContract updatedAttribute = Objects.requireNonNull(
					attributeMutation.mutate(catalogSchema, existingAttribute, EntityAttributeSchemaContract.class)
				);
				if (!existingAttribute.equals(updatedAttribute)) {
					// a rename overrides the entity-level `mutate`, so it is never part of a run
					Assert.isPremiseValid(
						existingAttribute.getName().equals(updatedAttribute.getName()),
						() -> "Mutation `" + mutation + "` renamed an attribute within a run of attribute mutations!"
					);
					if (changedAttributes == null) {
						changedAttributes = new LinkedHashMap<>(currentSchema.getAttributes());
					}
					// moved to the end, as EntityAttributeSchemaMutation#replaceAttributeIfDifferent appends it
					changedAttributes.remove(updatedAttribute.getName());
					changedAttributes.put(updatedAttribute.getName(), updatedAttribute);
					changes++;
				}
			} else {
				if (changedAttributes != null) {
					currentSchema = withChangedAttributes(currentSchema, changes, changedAttributes);
					changedAttributes = null;
					changes = 0;
				}
				currentSchema = mutation.mutate(catalogSchema, currentSchema);
				if (currentSchema == null) {
					throw new GenericEvitaInternalError("Entity schema unexpectedly removed from inside!");
				}
			}
		}
		return changedAttributes == null
			? currentSchema
			: withChangedAttributes(currentSchema, changes, changedAttributes);
	}

	/**
	 * Returns the schema with the attributes a run of attribute mutations changed, and the version raised by the
	 * number of the changes.
	 *
	 * The attributes are collected the way `EntityAttributeSchemaMutation#replaceAttributeIfDifferent` collects them,
	 * which the server applies one mutation at a time: into a hash map, in the order of `changedAttributes`, which
	 * keeps the unchanged attributes in their order and appends each changed one in the order of its last change.
	 * The iteration order of a hash map depends on the order its keys were inserted in only among keys of the same
	 * bucket, and there it ends up the same both ways, so the attributes come in the same order as on the server -
	 * short of a bucket crowded enough for the map to turn it into a tree, which names with sound hashes do not fill.
	 *
	 * @param schema            the schema before the run
	 * @param changes           the number of mutations of the run that changed an attribute
	 * @param changedAttributes the attributes after the run, in the order described above
	 * @return the schema after the run
	 */
	@Nonnull
	private static EntitySchemaContract withChangedAttributes(
		@Nonnull EntitySchemaContract schema,
		int changes,
		@Nonnull Map<String, EntityAttributeSchemaContract> changedAttributes
	) {
		return EntitySchema._internalBuildWithAttributes(
			schema,
			schema.version() + changes,
			changedAttributes.values()
				.stream()
				.collect(
					Collectors.toMap(
						AttributeSchemaContract::getName,
						Function.identity()
					)
				)
		);
	}

	/**
	 * Redefines the reference type in the internal entity schema builder.
	 *
	 * @param existingReference    The existing reference to be replaced. Can be null.
	 * @param newReference         The new reference to replace the existing reference.
	 * @param newReferenceMutation The collection of new reference mutations.
	 */
	void redefineReferenceType(
		@Nullable ReferenceSchemaContract existingReference,
		@Nonnull ReferenceSchemaContract newReference,
		@Nonnull Collection<LocalEntitySchemaMutation> newReferenceMutation
	) {
		if (!Objects.equals(existingReference, newReference)) {
			// remove all existing mutations for the reference schema (it needs to be replaced)
			if (this.mutations.removeIf(it -> shouldRemoveReferenceMutation(newReference, it))) {
				this.updatedSchemaDirty = updateMutationImpact(this.updatedSchemaDirty, MutationImpact.MODIFIED_PREVIOUS);
			}
			// check the names in all naming conventions are unique in the entity schema
			toInstance()
				.getReferences()
				.values()
				.stream()
				.filter(it -> !Objects.equals(it.getName(), newReference.getName()))
				.flatMap(it -> it.getNameVariants()
					.entrySet()
					.stream()
					.filter(nameVariant -> nameVariant.getValue().equals(newReference.getNameVariant(nameVariant.getKey())))
					.map(nameVariant -> new ReferenceNamingConventionConflict(it, nameVariant.getKey(), nameVariant.getValue()))
				)
				.forEach(conflict -> {
					throw new ReferenceAlreadyPresentInEntitySchemaException(
						conflict.conflictingSchema(), newReference,
						conflict.convention(), conflict.conflictingName()
					);
				});
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					this.catalogSchemaAccessor.get(), this.baseSchema, this.mutations,
					newReferenceMutation.toArray(EMPTY_ARRAY)
				)
			);
		}
	}

	/**
	 * Determines whether a reference mutation should be removed based on provided schema mutation.
	 * Method handles the situation when stored version of the schema contains reference with particular name, and
	 * we need to keep record of its removal and replacement with completely different reference sharing the same name.
	 *
	 * @param newReference The new reference schema contract to be checked.
	 * @param mutation     The local entity schema mutation to evaluate.
	 * @return true if the reference mutation should be removed, otherwise false.
	 */
	private boolean shouldRemoveReferenceMutation(
		@Nonnull ReferenceSchemaContract newReference,
		@Nonnull LocalEntitySchemaMutation mutation
	) {
		if (!(mutation instanceof ReferenceSchemaMutation referenceSchemaMutation)) {
			return false;
		} else if (referenceSchemaMutation.getName().equals(newReference.getName())) {
			// we need to respect removal mutations targeting previous references
			return !(referenceSchemaMutation instanceof RemoveReferenceSchemaMutation) ||
				this.baseSchema.getReference(newReference.getName()).isEmpty();
		} else {
			return false;
		}
	}

	/**
	 * DTO for passing the identified conflict in attribute names for certain naming convention.
	 */
	record AttributeNamingConventionConflict(
		@Nullable AttributeSchemaContract conflictingAttributeSchema,
		@Nullable SortableAttributeCompoundSchemaContract conflictingCompoundSchema,
		@Nonnull NamingConvention convention,
		@Nonnull String conflictingName
	) {
	}

	/**
	 * DTO for passing the identified conflict in associated data names for certain naming convention.
	 */
	record AssociatedDataNamingConventionConflict(
		@Nonnull AssociatedDataSchemaContract conflictingSchema,
		@Nonnull NamingConvention convention,
		@Nonnull String conflictingName
	) {
	}

	/**
	 * DTO for passing the identified conflict in reference names for certain naming convention.
	 */
	record ReferenceNamingConventionConflict(
		@Nonnull ReferenceSchemaContract conflictingSchema,
		@Nonnull NamingConvention convention,
		@Nonnull String conflictingName
	) {
	}

}
