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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collection;
import java.util.function.BooleanSupplier;

/**
 * Interface follows the <a href="https://en.wikipedia.org/wiki/Builder_pattern">builder pattern</a> allowing to alter
 * the data that are available on the read-only {@link AttributeSchemaContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface AttributeSchemaEditor<T extends AttributeSchemaEditor<T>> extends
	NamedSchemaWithDeprecationEditor<T>,
	AttributeSchemaContract,
	ConflictResolutionOverrideAwareSchemaEditor<T>
{
	/**
	 * Default value is used when the entity is created without this attribute specified. Default values allow to pass
	 * non-null checks even if no attributes of such name are specified.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T withDefaultValue(@Nullable Serializable defaultValue);

	/**
	 * When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
	 * as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
	 * (memory/disk) space in the form of index.
	 *
	 * The attribute will be filtered / looked up for by its {@link AttributeSchemaContract#getType() type}
	 * {@link Comparable} contract. If the type is not {@link Comparable} the {@link String#compareTo(String)}
	 * comparison on its {@link Object#toString()} will be used
	 *
	 * This method makes attribute filterable only in the {@link Scope#DEFAULT_SCOPE} scope, archived entities will not be
	 * filterable by this attribute unless explicitly set via {@link #uniqueInScope(Scope...)}.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default T filterable() {
		return filterableInScope(Scope.DEFAULT_SCOPE);
	}

	/**
	 * When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
	 * as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
	 * (memory/disk) space in the form of index.
	 *
	 * The attribute will be filtered / looked up for by its {@link AttributeSchemaContract#getType() type}
	 * {@link Comparable} contract. If the type is not {@link Comparable} the {@link String#compareTo(String)}
	 * comparison on its {@link Object#toString()} will be used
	 *
	 * This call is a **full statement** of the attribute's filterability, exactly as `unique(...)` is of its
	 * uniqueness. It does **not** touch the {@link AttributeFilterAccelerator accelerator} axis, which is declared
	 * separately via {@link #acceleratedFor(AttributeFilterAccelerator...)}: dropping filterability from a scope that
	 * is still `unique()` leaves its accelerators standing, because the filter index they accelerate is still there.
	 *
	 * @param inScope one or more scopes in which the attribute should be filterable
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T filterableInScope(@Nonnull Scope... inScope);

	/**
	 * When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
	 * as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
	 * (memory/disk) space in the form of index.
	 *
	 * The attribute will be filtered / looked up for by its {@link AttributeSchemaContract#getType() type}
	 * {@link Comparable} contract. If the type is not {@link Comparable} the {@link String#compareTo(String)}
	 * comparison on its {@link Object#toString()} will be used
	 *
	 * @param decider returns true when attribute should be filtered
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default T filterable(@Nonnull BooleanSupplier decider) {
		return decider.getAsBoolean() ? filterable() : nonFilterable();
	}

	/**
	 * Makes attribute not filterable in all scopes. This means it will not be possible to filter entities by this
	 * attribute anymore.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default T nonFilterable() {
		return nonFilterableInScope(Scope.values());
	}

	/**
	 * Makes attribute not filterable in specified scope(s). This means it will not be possible to filter entities by
	 * this attribute in that scope anymore.
	 *
	 * @param inScope one or more scopes in which the attribute should not be filterable
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T nonFilterableInScope(@Nonnull Scope... inScope);

	/**
	 * Asks the attribute's filter index to maintain the listed optional {@link AttributeFilterAccelerator accelerators}
	 * in the {@link Scope#DEFAULT_SCOPE} scope.
	 *
	 * This is a **sibling axis** of `filterable()` / `unique()`, not part of either: it says nothing about *whether*
	 * the attribute can be filtered by, only about how fast one particular shape of filter is answered. It does
	 * however require the index it accelerates to exist - the attribute must be
	 * {@link #filterable() filterable} or {@link #unique() unique} in the scope, or the mutation is refused. Each
	 * accelerator costs additional memory and additional write-path work, so read its documentation before declaring
	 * it; declaring none at all is the default.
	 *
	 * **Declaration order is insignificant.** The requirement is checked on the *assembled* attribute rather than on
	 * the half-written chain, so `filterable().acceleratedFor(...)` and `acceleratedFor(...).filterable()` are equally
	 * accepted, whatever else the chain does between the two calls. It is checked **per scope**, and either flag
	 * satisfies it there - but only there: being {@link #uniqueInScope(Scope...) unique} in {@link Scope#LIVE}
	 * licenses no accelerator declared in {@link Scope#ARCHIVED}.
	 *
	 * @param accelerators the accelerators to maintain in the default scope
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default T acceleratedFor(@Nonnull AttributeFilterAccelerator... accelerators) {
		return acceleratedForInScope(Scope.DEFAULT_SCOPE, accelerators);
	}

	/**
	 * Conditional counterpart of {@link #acceleratedFor(AttributeFilterAccelerator...)} - declares the accelerators
	 * when the decider says so and withdraws exactly those same accelerators when it does not, mirroring
	 * {@link #filterable(BooleanSupplier)}.
	 *
	 * The negative branch withdraws only the accelerators named here, never the whole axis, so a conditional
	 * declaration cannot silently drop an accelerator some other call declared.
	 *
	 * @param decider      returns true when the accelerators should be maintained
	 * @param accelerators the accelerators to declare or withdraw
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default T acceleratedFor(@Nonnull BooleanSupplier decider, @Nonnull AttributeFilterAccelerator... accelerators) {
		return decider.getAsBoolean() ? acceleratedFor(accelerators) : nonAcceleratedFor(accelerators);
	}

	/**
	 * Asks the attribute's filter index to maintain the listed optional {@link AttributeFilterAccelerator accelerators}
	 * in one particular scope - the scoped counterpart of {@link #acceleratedFor(AttributeFilterAccelerator...)},
	 * allowing a different set per scope.
	 *
	 * The accelerators are **added** to whatever the scope already declares, and other scopes are left untouched, so
	 * two calls naming different scopes accumulate rather than overwrite each other. Use
	 * {@link #nonAcceleratedForInScope(Scope, AttributeFilterAccelerator...)} to withdraw one again.
	 *
	 * @param scope        the scope in which the accelerators should be maintained
	 * @param accelerators the accelerators to maintain in that scope
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T acceleratedForInScope(@Nonnull Scope scope, @Nonnull AttributeFilterAccelerator... accelerators);

	/**
	 * Withdraws the listed {@link AttributeFilterAccelerator accelerators} from **every** scope, leaving the
	 * attribute's filterability and uniqueness exactly as they were.
	 *
	 * Accelerators the attribute does not declare are silently ignored - the call states the desired end state rather
	 * than a delta that has to match.
	 *
	 * @param accelerators the accelerators to withdraw
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default T nonAcceleratedFor(@Nonnull AttributeFilterAccelerator... accelerators) {
		T result = null;
		for (final Scope scope : Scope.values()) {
			result = nonAcceleratedForInScope(scope, accelerators);
		}
		//noinspection DataFlowIssue - Scope always declares at least one constant
		return result;
	}

	/**
	 * Withdraws the listed {@link AttributeFilterAccelerator accelerators} from one particular scope, leaving the
	 * other scopes and the attribute's filterability and uniqueness exactly as they were.
	 *
	 * @param scope        the scope to withdraw the accelerators from
	 * @param accelerators the accelerators to withdraw
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T nonAcceleratedForInScope(@Nonnull Scope scope, @Nonnull AttributeFilterAccelerator... accelerators);

	/**
	 * When attribute value is unique it is automatically filterable, and it is ensured there is exactly one single entity
	 * having certain value of this attribute.
	 *
	 * The attribute will be filtered / looked up for by its {@link AttributeSchemaContract#getType() type}
	 * {@link Comparable} contract. If the type is not {@link Comparable} the {@link String#compareTo(String)}
	 * comparison on its {@link Object#toString()} will be used
	 *
	 * As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
	 * better to have this ensured by the database engine.
	 *
	 * This method makes attribute unique only in the {@link Scope#DEFAULT_SCOPE} scope, archived entities will not be unique
	 * by this attribute unless explicitly set via {@link #uniqueInScope(Scope...)}.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default T unique() {
		return uniqueInScope(Scope.DEFAULT_SCOPE);
	}

	/**
	 * When attribute value is unique it is automatically filterable, and it is ensured there is exactly one single entity
	 * having certain value of this attribute.
	 *
	 * The attribute will be filtered / looked up for by its {@link AttributeSchemaContract#getType() type}
	 * {@link Comparable} contract. If the type is not {@link Comparable} the {@link String#compareTo(String)}
	 * comparison on its {@link Object#toString()} will be used
	 *
	 * As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
	 * better to have this ensured by the database engine.
	 *
	 * @param inScope one or more scopes where the attribute should be unique
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T uniqueInScope(@Nonnull Scope... inScope);

	/**
	 * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
	 * having certain value of this attribute among other entities in the same collection.
	 *
	 *
	 * The attribute values will be filtered / looked up for by its {@link AttributeSchemaContract#getType() type}
	 * {@link Comparable} contract. If the type is not {@link Comparable} the {@link String#compareTo(String)}
	 * comparison on its {@link Object#toString()} will be used
	 *
	 * As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
	 * better to have this ensured by the database engine.
	 *
	 * @param decider returns true when attribute should be unique
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default T unique(@Nonnull BooleanSupplier decider) {
		return decider.getAsBoolean() ? unique() : nonUnique();
	}

	/**
	 * Makes attribute values not unique among other attributes in all scopes. This method resets all unique constraints
	 * on the attribute, no matter whether they are global or locale specific. This means there might be duplicate values
	 * for this type of attribute.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default T nonUnique() {
		return nonUniqueInScope(Scope.values());
	}

	/**
	 * Makes attribute values not unique in specified scope(s). This method resets all unique constraints on
	 * the attribute, no matter whether they are global or locale specific. This means there might be duplicate values
	 * for this type of attribute.
	 *
	 * @param inScope one or more scopes in which the attribute should not be unique
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T nonUniqueInScope(@Nonnull Scope... inScope);

	/**
	 * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
	 * having certain value of this attribute.
	 *
	 * The attribute will be filtered / looked up for by its {@link AttributeSchemaContract#getType() type}
	 * {@link Comparable} contract. If the type is not {@link Comparable} the {@link String#compareTo(String)}
	 * comparison on its {@link Object#toString()} will be used
	 *
	 * As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
	 * better to have this ensured by the database engine.
	 *
	 * This method differs from {@link #unique()} in that it is possible to have multiple entities with same value
	 * of this attribute as long as the attribute is {@link #isLocalized()} and the values relate to different locales.
	 *
	 * This method makes attribute unique within locale only in the {@link Scope#DEFAULT_SCOPE} scope, archived entities will
	 * not be unique by this attribute unless explicitly set via {@link #uniqueWithinLocaleInScope(Scope...)}.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default T uniqueWithinLocale() {
		return uniqueWithinLocaleInScope(Scope.DEFAULT_SCOPE);
	}

	/**
	 * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
	 * having certain value of this attribute.
	 *
	 * The attribute will be filtered / looked up for by its {@link AttributeSchemaContract#getType() type}
	 * {@link Comparable} contract. If the type is not {@link Comparable} the {@link String#compareTo(String)}
	 * comparison on its {@link Object#toString()} will be used
	 *
	 * As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
	 * better to have this ensured by the database engine.
	 *
	 * This method differs from {@link #unique()} in that it is possible to have multiple entities with same value
	 * of this attribute as long as the attribute is {@link #isLocalized()} and the values relate to different locales.
	 *
	 * @param inScope one or more scopes where the attribute should be unique within particular locale
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T uniqueWithinLocaleInScope(@Nonnull Scope... inScope);

	/**
	 * Makes attribute values not unique among other values in particular locale in all scopes. This method resets all
	 * unique constraints on the attribute, no matter whether they are global or locale specific. This means there might
	 * be duplicate values for this type of attribute.
	 *
	 * Use `nonUniqueInScope(Scope...)` instead. This method will be removed in future versions.
	 *
	 * @return builder to continue with configuration
	 */
	@Deprecated(since = "2025.6", forRemoval = true)
	@Nonnull
	default T nonUniqueWithinLocale() {
		return nonUniqueWithinLocaleInScope(Scope.values());
	}

	/**
	 * Makes attribute values not unique among other values in particular locale in particular scope(s). This method
	 * resets all unique constraints on the attribute, no matter whether they are global or locale specific. This means
	 * there might be duplicate values for this type of attribute.
	 *
	 * Use `nonUniqueInScope(Scope...)` instead. This method will be removed in future versions.
	 *
	 * @param inScope one or more scopes in which the attribute should not be unique within particular locale
	 * @return builder to continue with configuration
	 */
	@Deprecated(since = "2025.6", forRemoval = true)
	@Nonnull
	T nonUniqueWithinLocaleInScope(@Nonnull Scope... inScope);

	/**
	 * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
	 * having certain value of this attribute among other entities in the same collection.
	 *
	 *
	 * The attribute will be filtered / looked up for by its {@link AttributeSchemaContract#getType() type}
	 * {@link Comparable} contract. If the type is not {@link Comparable} the {@link String#compareTo(String)}
	 * comparison on its {@link Object#toString()} will be used
	 *
	 * As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
	 * better to have this ensured by the database engine.
	 *
	 * This method differs from {@link #unique(BooleanSupplier)} in that it is possible to have multiple entities with
	 * same value of this attribute as long as the attribute is {@link #isLocalized()} and the values relate
	 * to different locales.
	 *
	 * @param decider returns true when attribute should be unique
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default T uniqueWithinLocale(@Nonnull BooleanSupplier decider) {
		return decider.getAsBoolean() ?
			uniqueWithinLocale() : nonUniqueWithinLocale();
	}

	/**
	 * When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
	 * as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
	 * (memory/disk) space in the form of index. {@link AttributeSchemaContract#getType() Type} of the sortable
	 * attribute must implement {@link Comparable} interface.
	 *
	 * This method makes attribute sortable only in the {@link Scope#DEFAULT_SCOPE} scope, archived entities will not be
	 * sortable by this attribute unless explicitly set via {@link #sortableInScope(Scope...)}.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default T sortable() {
		return sortableInScope(Scope.DEFAULT_SCOPE);
	}

	/**
	 * When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
	 * as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
	 * (memory/disk) space in the form of index. {@link AttributeSchemaContract#getType() Type} of the sortable
	 * attribute must implement {@link Comparable} interface.
	 *
	 * @param inScope one or more scopes where the attribute should be sortable
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T sortableInScope(@Nonnull Scope... inScope);

	/**
	 * Makes attribute not sortable in all scopes. This means it will not be possible to sort entities by this
	 * attribute anymore.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default T nonSortable() {
		return nonSortableInScope(Scope.values());
	}

	/**
	 * Makes attribute not sortable in specified scope(s). This means it will not be possible to sort entities by
	 * this attribute in that scope anymore.
	 *
	 * @param inScope one or more scopes in which the attribute should not be sortable
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T nonSortableInScope(@Nonnull Scope... inScope);

	/**
	 * When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
	 * as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
	 * (memory/disk) space in the form of index. {@link AttributeSchemaContract#getType() Type} of the sortable attribute must
	 * implement {@link Comparable} interface.
	 *
	 * @param decider returns true when attribute should be sortable
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T sortable(@Nonnull BooleanSupplier decider);

	/**
	 * Localized attribute has to be ALWAYS used in connection with specific {@link java.util.Locale}. In other
	 * words - it cannot be stored unless associated locale is also provided.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T localized();

	/**
	 * Localized attribute has to be ALWAYS used in connection with specific {@link java.util.Locale}. In other
	 * words - it cannot be stored unless associated locale is also provided.
	 *
	 * @param decider returns true when attribute should be localized
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T localized(@Nonnull BooleanSupplier decider);

	/**
	 * Makes attribute not localized. This method is opposite to {@link #localized()} and shares the value among all
	 * locales of the entity.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T nonLocalized();

	/**
	 * When attribute is nullable, its values may be missing in the entities. Otherwise, the system will enforce
	 * non-null checks upon upserting of the entity.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T nullable();

	/**
	 * When attribute is nullable, its values may be missing in the entities. Otherwise, the system will enforce
	 * non-null checks upon upserting of the entity.
	 *
	 * @param decider returns true when attribute should be nullable
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T nullable(@Nonnull BooleanSupplier decider);

	/**
	 * When attribute is non-nullable, its value is mandatory. If no value is provided, and the {@link #getDefaultValue()}
	 * is null, the system will throw an error.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T nonNullable();

	/**
	 * Determines how many fractional places are important when entities are compared during filtering or sorting. It is
	 * essential to know that all values of this attribute will be converted to {@link Integer}, so the attribute
	 * number must not ever exceed maximum limits of {@link Integer} type when scaling the number by the power
	 * of ten using `indexDecimalPlaces` as exponent.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T indexDecimalPlaces(int indexedDecimalPlaces);

	/**
	 * If an attribute is flagged as representative, it should be used in developer tools along with the entity's
	 * primary key to describe the entity or reference to that entity. The flag is completely optional and doesn't
	 * affect the core functionality of the database in any way. However, if it's used correctly, it can be very
	 * helpful to developers in quickly finding their way around the data. There should be very few representative
	 * attributes in the entity type, and the unique ones are usually the best to choose.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T representative();

	/**
	 * If an attribute is flagged as representative, it should be used in developer tools along with the entity's
	 * primary key to describe the entity or reference to that entity. The flag is completely optional and doesn't
	 * affect the core functionality of the database in any way. However, if it's used correctly, it can be very
	 * helpful to developers in quickly finding their way around the data. There should be very few representative
	 * attributes in the entity type, and the unique ones are usually the best to choose.
	 *
	 * @param decider returns true when attribute should be representative
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T representative(@Nonnull BooleanSupplier decider);

	/**
	 * Interface that simply combines {@link AttributeSchemaEditor} and {@link AttributeSchemaContract} entity contracts
	 * together. Builder produces either {@link EntitySchemaMutation} that describes all changes to be made on
	 * {@link EntitySchemaContract} instance to get it to "up-to-date" state or can provide already built
	 * {@link EntitySchemaContract} that may not represent globally "up-to-date" state because it is based on
	 * the version of the entity known when builder was created.
	 *
	 * Mutation allows Evita to perform surgical updates on the latest version of the {@link EntitySchemaContract}
	 * object that is in the database at the time update request arrives.
	 */
	interface AttributeSchemaBuilder extends AttributeSchemaEditor<AttributeSchemaBuilder> {

		/**
		 * Returns collection of {@link EntitySchemaMutation} instances describing what changes occurred in the builder
		 * and which should be applied on the existing parent schema in particular version.
		 * Each mutation increases {@link Versioned#version()} of the modified object and allows to detect race
		 * conditions based on "optimistic locking" mechanism in very granular way.
		 *
		 * All mutations need and will also to implement {@link AttributeSchemaMutation} and can be retrieved by calling
		 * {@link #toAttributeMutation()} identically.
		 */
		@Nonnull
		Collection<LocalEntitySchemaMutation> toMutation();

		/**
		 * Returns collection of {@link AttributeSchemaMutation} instances describing what changes occurred in the builder
		 * and which should be applied on the existing parent schema in particular version.
		 * Each mutation increases {@link Versioned#version()} of the modified object and allows to detect race
		 * conditions based on "optimistic locking" mechanism in very granular way.
		 *
		 * All mutations need and will also to implement {@link EntitySchemaMutation} and can be retrieved by calling
		 * {@link #toMutation()} identically.
		 */
		@Nonnull
		Collection<AttributeSchemaMutation> toAttributeMutation();

		/**
		 * Returns collection of {@link ReferenceSchemaMutation} instances describing what changes occurred in the builder
		 * and which should be applied on the existing {@link ReferenceSchemaContract} in particular version.
		 * Each mutation increases {@link Versioned#version()} of the modified object and allows to detect race
		 * conditions based on "optimistic locking" mechanism in very granular way.
		 *
		 * All mutations need and will also to implement {@link AttributeSchemaMutation} and can be retrieved by calling
		 * {@link #toAttributeMutation()} identically.
		 */
		@Nonnull
		Collection<ReferenceSchemaMutation> toReferenceMutation(@Nonnull String referenceName);

		/**
		 * Returns built "local up-to-date" {@link AttributeSchemaContract} instance that may not represent globally
		 * "up-to-date" state because it is based on the version of the entity known when builder was created.
		 *
		 * This method is particularly useful for tests.
		 */
		@Nonnull
		AttributeSchemaContract toInstance();

	}
}
