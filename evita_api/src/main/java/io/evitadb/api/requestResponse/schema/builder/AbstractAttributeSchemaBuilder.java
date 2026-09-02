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
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaDefaultValueMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaTypeMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaConflictResolutionOverrideMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaAcceleratedMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaFilterableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaLocalizedMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaNullableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaRepresentativeMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaSortableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaUniqueMutation;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Abstract parent for builders that produce {@link AttributeSchemaContract} or its extensions.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@SuppressWarnings("unchecked")
public abstract sealed class AbstractAttributeSchemaBuilder<T extends AttributeSchemaEditor<T>, S extends AttributeSchemaContract>
	implements AttributeSchemaEditor<T>, InternalSchemaBuilderHelper
	permits AttributeSchemaBuilder, EntityAttributeSchemaBuilder, GlobalAttributeSchemaBuilder {
	@Serial private static final long serialVersionUID = -1519084392486171781L;
	protected final S baseSchema;
	protected final CatalogSchemaContract catalogSchema;
	protected final EntitySchemaContract entitySchema;
	protected MutationImpact updatedSchemaDirty = MutationImpact.NO_IMPACT;
	protected S updatedSchema;
	private int lastMutationReflectedInSchema = 0;
	/** Whether {@link #validate(AttributeSchemaContract)} has already accepted the currently assembled schema. */
	private boolean updatedSchemaValidated;

	AbstractAttributeSchemaBuilder(
		@Nullable CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema,
		@Nonnull S existingSchema
	) {
		Assert.isTrue(
			EvitaDataTypes.isSupportedTypeOrItsArray(existingSchema.getType()),
			"Data type " + existingSchema.getType().getName() + " is not supported."
		);
		Assert.isTrue(catalogSchema != null || entitySchema != null, "Either catalog name or entity type must be present!");
		Assert.isTrue(!(catalogSchema != null && entitySchema != null), "Either catalog name or entity type must be present, but not both!");
		this.catalogSchema = catalogSchema;
		this.entitySchema = entitySchema;
		this.baseSchema = existingSchema;
	}

	@Override
	@Nonnull
	public T withDefaultValue(
		@Nullable Serializable defaultValue) {
		if (defaultValue != null) {
			final Class<? extends Serializable> wrappedForm = EvitaDataTypes.toWrappedForm(defaultValue.getClass());
			final S currentSchema = assembleInstance();
			final Class<? extends Serializable> expectedType = currentSchema.getType();
			Assert.isTrue(
				expectedType.equals(wrappedForm),
				"Passed default value doesn't match the type `" + expectedType + "`!"
			);

			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					new ModifyAttributeSchemaDefaultValueMutation(
						this.baseSchema.getName(),
						EvitaDataTypes.toTargetType(defaultValue, wrappedForm, currentSchema.getIndexedDecimalPlaces())
					)
				)
			);
		} else {
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					new ModifyAttributeSchemaDefaultValueMutation(
						this.baseSchema.getName(),
						null
					)
				)
			);
		}
		return (T) this;
	}

	@Override
	@Nonnull
	public T filterableInScope(@Nonnull Scope... inScope) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaFilterableMutation(
					this.baseSchema.getName(),
					inScope
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T nonFilterableInScope(@Nonnull Scope... inScope) {
		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaFilterableMutation(
					this.baseSchema.getName(),
					Arrays.stream(Scope.values())
						.filter(it -> isFilterableInScope(it) && !excludedScopes.contains(it))
						.toArray(Scope[]::new)
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T acceleratedForInScope(
		@Nonnull Scope scope,
		@Nonnull AttributeFilterAccelerator... accelerators
	) {
		return setAcceleratorsInScope(scope, accelerators, true);
	}

	@Nonnull
	@Override
	public T nonAcceleratedForInScope(
		@Nonnull Scope scope,
		@Nonnull AttributeFilterAccelerator... accelerators
	) {
		return setAcceleratorsInScope(scope, accelerators, false);
	}

	/**
	 * Emits a {@link SetAttributeSchemaAcceleratedMutation} stating the **whole** accelerator axis - every scope, not
	 * only the one being changed - after adding or removing the given accelerators in one scope.
	 *
	 * The mutation is a full statement by design, because that is what makes combining two of them trivially
	 * last-one-wins. The delta therefore has to be resolved here, against the axis the builder currently declares, so
	 * that two calls naming different scopes accumulate instead of the second erasing the first. That read goes
	 * through {@link #assembleInstance()} rather than {@link #toInstance()} - see there for why it must not validate.
	 *
	 * @param scope        the scope whose accelerators change
	 * @param accelerators the accelerators to add or to remove; an empty array leaves the schema as it is
	 * @param add          true to declare the accelerators, false to withdraw them
	 * @return this builder, for fluent chaining
	 */
	@Nonnull
	private T setAcceleratorsInScope(
		@Nonnull Scope scope,
		@Nonnull AttributeFilterAccelerator[] accelerators,
		boolean add
	) {
		final Scope[] allScopes = Scope.values();
		final ScopedAttributeFilterAccelerators[] carriers = new ScopedAttributeFilterAccelerators[allScopes.length];
		int index = 0;
		for (final Scope theScope : allScopes) {
			final Set<AttributeFilterAccelerator> declared = getAcceleratorsInScope(theScope);
			final EnumSet<AttributeFilterAccelerator> updated = declared.isEmpty() ?
				EnumSet.noneOf(AttributeFilterAccelerator.class) : EnumSet.copyOf(declared);
			if (theScope == scope) {
				if (add) {
					Collections.addAll(updated, accelerators);
				} else {
					Arrays.asList(accelerators).forEach(updated::remove);
				}
			}
			if (!updated.isEmpty()) {
				carriers[index++] = new ScopedAttributeFilterAccelerators(
					theScope, updated.toArray(AttributeFilterAccelerator[]::new)
				);
			}
		}
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaAcceleratedMutation(
					this.baseSchema.getName(),
					index == carriers.length ? carriers : Arrays.copyOf(carriers, index)
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T uniqueInScope(@Nonnull Scope... inScope) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaUniqueMutation(
					this.baseSchema.getName(),
					Arrays.stream(inScope)
						.map(it -> new ScopedAttributeUniquenessType(it, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION))
						.toArray(ScopedAttributeUniquenessType[]::new)
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T nonUniqueInScope(@Nonnull Scope... inScope) {
		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaUniqueMutation(
					this.baseSchema.getName(),
					Arrays.stream(Scope.values())
						.filter(it -> isUniqueInScope(it) && !excludedScopes.contains(it))
						.map(it -> new ScopedAttributeUniquenessType(it, getUniquenessType(it)))
						.toArray(ScopedAttributeUniquenessType[]::new)
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T uniqueWithinLocaleInScope(@Nonnull Scope... inScope) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaUniqueMutation(
					this.baseSchema.getName(),
					Arrays.stream(inScope)
						.map(it -> new ScopedAttributeUniquenessType(it, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE))
						.toArray(ScopedAttributeUniquenessType[]::new)
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T nonUniqueWithinLocaleInScope(@Nonnull Scope... inScope) {
		nonUniqueInScope(inScope);
		return (T) this;
	}

	@Nonnull
	@Override
	public T sortableInScope(@Nonnull Scope... inScope) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaSortableMutation(
					this.baseSchema.getName(),
					inScope
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T nonSortableInScope(@Nonnull Scope... inScope) {
		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaSortableMutation(
					this.baseSchema.getName(),
					Arrays.stream(Scope.values())
						.filter(it -> isSortableInScope(it) && !excludedScopes.contains(it))
						.toArray(Scope[]::new)
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T sortable(@Nonnull BooleanSupplier decider) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaSortableMutation(
					this.baseSchema.getName(),
					decider.getAsBoolean() ? Scope.DEFAULT_SCOPES : Scope.NO_SCOPE
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T localized() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaLocalizedMutation(
					this.baseSchema.getName(),
					true
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T localized(@Nonnull BooleanSupplier decider) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaLocalizedMutation(
					this.baseSchema.getName(),
					decider.getAsBoolean()
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T nonLocalized() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaLocalizedMutation(
					this.baseSchema.getName(),
					false
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T nullable() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaNullableMutation(
					this.baseSchema.getName(),
					true
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T nullable(@Nonnull BooleanSupplier decider) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaNullableMutation(
					this.baseSchema.getName(),
					decider.getAsBoolean()
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T nonNullable() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaNullableMutation(
					this.baseSchema.getName(),
					false
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T indexDecimalPlaces(int indexedDecimalPlaces) {
		//noinspection rawtypes
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new ModifyAttributeSchemaTypeMutation(
					this.baseSchema.getName(),
					toAttributeMutation().stream()
						.filter(ModifyAttributeSchemaTypeMutation.class::isInstance)
						.map(it -> ((ModifyAttributeSchemaTypeMutation) it).getType())
						.findFirst()
						.orElseGet(() -> (Class) this.baseSchema.getType()),
					indexedDecimalPlaces
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T representative() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaRepresentativeMutation(
					this.baseSchema.getName(),
					true
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T representative(@Nonnull BooleanSupplier decider) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaRepresentativeMutation(
					this.baseSchema.getName(),
					decider.getAsBoolean()
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T withConflictResolutionOverride(@Nonnull ConflictResolutionOverride conflictResolutionOverride) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaConflictResolutionOverrideMutation(
					this.baseSchema.getName(),
					conflictResolutionOverride
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T withDescription(@Nullable String description) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new ModifyAttributeSchemaDescriptionMutation(
					this.baseSchema.getName(),
					description
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T deprecated(@Nonnull String deprecationNotice) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new ModifyAttributeSchemaDeprecationNoticeMutation(
					this.baseSchema.getName(),
					deprecationNotice
				)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T notDeprecatedAnymore() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new ModifyAttributeSchemaDeprecationNoticeMutation(
					this.baseSchema.getName(),
					null
				)
			)
		);
		return (T) this;
	}

	/**
	 * Creates attribute schema instance.
	 *
	 * This is the call that validates: the attribute is assembled by {@link #assembleInstance()} and then checked
	 * once per assembly, so this is where an attribute whose declarations do not compose is refused.
	 */
	@Nonnull
	public S toInstance() {
		final S instance = assembleInstance();
		if (!this.updatedSchemaValidated) {
			validate(instance);
			this.updatedSchemaValidated = true;
		}
		return instance;
	}

	/**
	 * Assembles the attribute schema out of the base schema and the mutations recorded so far, WITHOUT validating it.
	 *
	 * Validation is a property of the finished attribute, never of a half-written one. The rules are cross-field - an
	 * accelerator needs a filter index in the same scope, a sortable attribute needs a comparable type - so checking
	 * them against an intermediate state would make declaration order significant, and `acceleratedFor(...)` followed
	 * by the `filterable()` that licenses it would be refused before the licence was ever given.
	 *
	 * Every builder method that has to read the current state in order to compute its own mutation therefore reads it
	 * through this method, and the {@link AttributeSchemaContract} getters this builder exposes are delegated to it
	 * for the same reason: a getter reports what is currently declared, while {@link #toInstance()} is what hands out
	 * a validated schema.
	 *
	 * @return the attribute schema as currently declared, unvalidated
	 */
	@Nonnull
	protected S assembleInstance() {
		if (this.updatedSchema == null || this.updatedSchemaDirty != MutationImpact.NO_IMPACT) {
			// if the dirty flag is set to modified previous we need to start from the base schema again
			// and reapply all mutations
			if (this.updatedSchemaDirty == MutationImpact.MODIFIED_PREVIOUS) {
				this.lastMutationReflectedInSchema = 0;
			}
			// if the last mutation reflected in the schema is zero we need to start from the base schema
			// else we can continue modification last known updated schema by adding additional mutations
			S currentSchema = this.lastMutationReflectedInSchema == 0 ?
				this.baseSchema : this.updatedSchema;

			final List<AttributeSchemaMutation> attributeMutations = toAttributeMutation();
			// apply the mutations not reflected in the schema
			for (int i = this.lastMutationReflectedInSchema; i < attributeMutations.size(); i++) {
				final AttributeSchemaMutation mutation = attributeMutations.get(i);
				currentSchema = mutation.mutate(null, currentSchema, getAttributeSchemaType());
				if (currentSchema == null) {
					throw new GenericEvitaInternalError("Attribute unexpectedly removed from inside!");
				}
			}
			this.updatedSchema = currentSchema;
			this.updatedSchemaDirty = MutationImpact.NO_IMPACT;
			this.lastMutationReflectedInSchema = attributeMutations.size();
			// a freshly assembled schema has not been through validate() yet - toInstance() will run it
			this.updatedSchemaValidated = false;
		}
		return this.updatedSchema;
	}

	/**
	 * Returns the type of the attribute this builder builds.
	 */
	@Nonnull
	protected abstract Class<S> getAttributeSchemaType();

	/**
	 * Method allows adding specific mutation on the fly.
	 */
	@Nonnull
	protected abstract MutationImpact addMutations(@Nonnull AttributeSchemaMutation mutation);

	/**
	 * Returns collection of {@link AttributeSchemaMutation} instances describing what changes occurred in the builder
	 * and which should be applied on the existing parent schema in particular version.
	 * Each mutation increases {@link Versioned#version()} of the modified object and allows to detect race
	 * conditions based on "optimistic locking" mechanism in very granular way.
	 */
	@Nonnull
	protected abstract List<AttributeSchemaMutation> toAttributeMutation();

	/**
	 * Method validates the consistency of an attribute schema.
	 * It basically checks the compatibility of the data type for filter/unique/sort index purposes.
	 */
	private void validate(@Nonnull S currentSchema) {
		final Class<?> plainType = ReflectionLookup.getSimpleType(currentSchema.getType());
		Assert.isTrue(
			!currentSchema.isSortableInAnyScope() ||
				plainType.isPrimitive() ||
				Comparable.class.isAssignableFrom(plainType) ||
				Currency.class.isAssignableFrom(plainType) ||
				Locale.class.isAssignableFrom(plainType) ||
				Predecessor.class.isAssignableFrom(plainType) ||
				ReferencedEntityPredecessor.class.isAssignableFrom(plainType),
			() -> new InvalidSchemaMutationException("Data type `" + currentSchema.getType() + "` in attribute schema `" + currentSchema.getName() + "` must implement Comparable (or must be Predecessor/ReferencedEntityPredecessor) in order to be usable for sort index!")
		);
		Assert.isTrue(
			!(currentSchema.isSortableInAnyScope() && currentSchema.getType().isArray()),
			() -> new InvalidSchemaMutationException("Attribute `" + currentSchema.getName() + "` is sortable but also an array. Arrays cannot be handled by sorting algorithm!")
		);
		Assert.isTrue(
			!(currentSchema.isFilterableInAnyScope() || currentSchema.isUniqueInAnyScope()) ||
				plainType.isPrimitive() ||
				Comparable.class.isAssignableFrom(plainType) ||
				Currency.class.isAssignableFrom(plainType) ||
				Locale.class.isAssignableFrom(plainType),
			() -> new InvalidSchemaMutationException("Data type `" + currentSchema.getType() + "` in attribute schema `" + currentSchema.getName() + "` must implement Comparable in order to be usable for filter / unique index!")
		);
		Assert.isTrue(
			!(currentSchema.isFilterableInAnyScope() && currentSchema.isUniqueInAnyScope()),
			() -> new InvalidSchemaMutationException("Attribute `" + currentSchema.getName() + "` cannot be both unique and filterable. Unique attributes are implicitly filterable!")
		);
		// the remaining rules are self-contained invariants of the assembled attribute, so they are stated once on
		// AttributeSchemaContract#validate() and are merely turned into an exception here - the schema-level
		// validation reads the very same definition and collects the messages instead of throwing them. They compose
		// with the assertions above rather than contradicting them: the accelerator rule is checked per scope, so an
		// attribute unique in LIVE does not license an accelerator declared in ARCHIVED, and it accepts either flag
		// as the filter index an accelerator speeds up, while the assertion right above forbids declaring both
		currentSchema.validate()
			.findFirst()
			.ifPresent(error -> {
				throw new InvalidSchemaMutationException(error);
			});
	}

}
