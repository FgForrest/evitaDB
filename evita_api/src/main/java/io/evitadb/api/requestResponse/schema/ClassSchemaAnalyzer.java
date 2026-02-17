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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SchemaPostProcessor;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.SchemaClassInvalidException;
import io.evitadb.api.requestResponse.data.annotation.*;
import io.evitadb.api.requestResponse.data.annotation.ReflectedReference.InheritableBoolean;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaEditor.ReflectedReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.utils.GenericsUtils;
import one.edee.oss.proxycian.utils.GenericsUtils.GenericBundle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Analyzer is a stateful class, that traverses record / class getters or fields for annotations from `io.data.annotation`
 * package and sets up the entity / catalog schema accordingly.
 *
 * The analyzer only creates or expands existing schema and never removes anything from it. The expected form of use is
 * to define new entity properties and mark old one as deprecated. When the already deprecated properties are about to be
 * removed completely the removal should occur in an explicit way (command or API call) outside this analyzer.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @see Entity
 * @see PrimaryKey
 * @see Attribute
 * @see AssociatedData
 * @see ParentEntity
 * @see Reference
 * @see PriceForSale
 */
@NotThreadSafe
public class ClassSchemaAnalyzer {
	/**
	 * Set of annotation types that reference other schema elements and must be processed
	 * after their corresponding defining annotations. These are annotations ending with
	 * "Ref" suffix that depend on the main schema definitions being already present.
	 */
	private static final Set<Class<? extends Annotation>> DEPENDENT_ANNOTATIONS = Set.of(
		ReferenceRef.class,
		AttributeRef.class,
		AssociatedDataRef.class,
		PrimaryKeyRef.class,
		PriceForSaleRef.class
	);
	/**
	 * The model class representing the Entity model.
	 */
	private final Class<?> modelClass;
	/**
	 * This function allows to resolve the subclass of the passed class for particular reference name. This is used to
	 * resolve the proper type of the subclass when the main class returns only a supertype.
	 */
	private final BiFunction<String, Class<?>, Class<?>> subClassResolver;
	/**
	 * Reflection lookup is used to speed up reflection operation by memoizing the results for examined classes.
	 */
	private final ReflectionLookup reflectionLookup;
	/**
	 * The consumer that should be called after schemas has been altered but just before the changes has been applied.
	 */
	private final SchemaPostProcessor postProcessor;
	/**
	 * Contains all attributes that were already defined within the model class.
	 */
	private final Map<String, String> attributesDefined = new HashMap<>(32);
	/**
	 * Contains all associated data that were already defined within the model class.
	 */
	private final Map<String, String> associatedDataDefined = new HashMap<>(32);
	/**
	 * Contains all references that were already defined within the model class.
	 */
	private final Map<String, String> referencesDefined = new HashMap<>(32);
	/**
	 * Temporary flag - true when annotation PrimaryKey is used in the entity class.
	 */
	private boolean primaryKeyDefined = false;
	/**
	 * Temporary flag - true when annotation PriceForSale is used in the entity class.
	 */
	private boolean sellingPriceDefined = false;

	/**
	 * Extracts the entity type from a given class using reflection.
	 *
	 * @param classToAnalyse   The class to be analyzed.
	 * @param reflectionLookup The reflection lookup utility.
	 * @return An Optional containing the entity type if found, or an empty Optional otherwise.
	 */
	@Nonnull
	public static Optional<String> extractEntityTypeFromClass(
		@Nonnull Class<?> classToAnalyse, @Nonnull ReflectionLookup reflectionLookup) {
		return Objects.requireNonNull(
			reflectionLookup.extractFromClass(
				classToAnalyse, EntityRef.class,
				clazz -> {
					final EntityRef entityRef = reflectionLookup.getClassAnnotation(clazz, EntityRef.class);
					if (entityRef != null) {
						return ofNullable(entityRef.value())
							.filter(it -> !it.isBlank())
							.or(() -> of(reflectionLookup.findOriginClass(clazz, entityRef).getSimpleName()));
					}
					final Entity entity = reflectionLookup.getClassAnnotation(clazz, Entity.class);
					if (entity != null) {
						return ofNullable(entity.name())
							.filter(it -> !it.isBlank())
							.or(() -> of(reflectionLookup.findOriginClass(clazz, entity).getSimpleName()));
					}
					return empty();
				}
			)
		);
	}

	/**
	 * Attempts to retrieve default value from a getter with default implementation.
	 */
	@Nullable
	public static Serializable extractDefaultValue(@Nonnull Class<?> definingClass, @Nonnull Method getter) {
		try {

			final Constructor<Lookup> constructor = Lookup.class.getDeclaredConstructor(Class.class);
			constructor.setAccessible(true);

			final Class<?> declaringClass = getter.getDeclaringClass();
			final MethodHandle methodHandle = constructor.newInstance(declaringClass).unreflectSpecial(
				getter, declaringClass);
			final Object proxy = Proxy.newProxyInstance(
				definingClass.getClassLoader(), new Class<?>[]{definingClass},
				(theProxy, method, args) -> {
					throw new UnsupportedOperationException();
				}
			);
			return (Serializable) methodHandle.bindTo(proxy).invokeWithArguments();

		} catch (Error e) {
			// never swallow JVM errors (OutOfMemoryError, StackOverflowError, etc.)
			throw e;
		} catch (Throwable ex) {
			return null;
		}
	}

	/**
	 * Method resolves the proper type from the method return type unwrapping the optional and collection types.
	 */
	@Nonnull
	static Class<?> extractReturnType(@Nonnull Class<?> modelClass, @Nonnull Method getter) {
		final Class<?> theType;
		if (Optional.class.isAssignableFrom(getter.getReturnType())) {
			final List<GenericBundle> genericTypes = GenericsUtils.getNestedMethodReturnTypes(modelClass, getter);
			final Class<?> secondType = genericTypes.size() > 1 ?
				genericTypes.get(1).getResolvedType() :
				genericTypes.get(0).getResolvedType();
			if (Collection.class.isAssignableFrom(secondType) && genericTypes.size() > 2) {
				theType = Array.newInstance(genericTypes.get(2).getResolvedType(), 0).getClass();
			} else {
				theType = secondType;
			}
		} else if (OptionalInt.class.isAssignableFrom(getter.getReturnType())) {
			theType = int.class;
		} else if (OptionalLong.class.isAssignableFrom(getter.getReturnType())) {
			theType = long.class;
		} else if (Collection.class.isAssignableFrom(getter.getReturnType())) {
			return Array.newInstance(
					GenericsUtils.getGenericTypeFromCollection(modelClass, getter.getGenericReturnType()), 0)
				.getClass();
		} else {
			theType = getter.getReturnType();
		}
		return theType;
	}

	/**
	 * Method resolves the proper type from the field return type unwrapping the optional and collection types.
	 */
	@Nonnull
	static Class<?> extractFieldType(@Nonnull Class<?> modelClass, @Nonnull Field field) {
		final Class<?> theType;
		if (Optional.class.isAssignableFrom(field.getType())) {
			final List<GenericBundle> genericTypes = GenericsUtils.getNestedFieldTypes(modelClass, field);
			final Class<?> secondType = genericTypes.size() > 1 ?
				genericTypes.get(1).getResolvedType() :
				genericTypes.get(0).getResolvedType();
			if (Collection.class.isAssignableFrom(secondType) && genericTypes.size() > 2) {
				theType = Array.newInstance(genericTypes.get(2).getResolvedType(), 0).getClass();
			} else {
				theType = secondType;
			}
		} else if (OptionalInt.class.isAssignableFrom(field.getType())) {
			theType = int.class;
		} else if (OptionalLong.class.isAssignableFrom(field.getType())) {
			theType = long.class;
		} else if (Collection.class.isAssignableFrom(field.getType())) {
			return Array.newInstance(GenericsUtils.getGenericTypeFromCollection(modelClass, field.getGenericType()), 0)
				.getClass();
		} else {
			theType = field.getType();
		}
		return theType;
	}

	/**
	 * Method resolves the proper type from the recordComponent return type unwrapping the optional and collection types.
	 */
	@Nonnull
	static Class<?> extractRecordComponentType(@Nonnull Class<?> modelClass, @Nonnull RecordComponent recordComponent) {
		final Class<?> theType;
		if (Optional.class.isAssignableFrom(recordComponent.getType())) {
			final List<GenericBundle> genericTypes = GenericsUtils.getNestedRecordComponentType(
				modelClass, recordComponent);
			final Class<?> secondType = genericTypes.size() > 1 ?
				genericTypes.get(1).getResolvedType() :
				genericTypes.get(0).getResolvedType();
			if (Collection.class.isAssignableFrom(secondType) && genericTypes.size() > 2) {
				theType = Array.newInstance(genericTypes.get(2).getResolvedType(), 0).getClass();
			} else {
				theType = secondType;
			}
		} else if (OptionalInt.class.isAssignableFrom(recordComponent.getType())) {
			theType = int.class;
		} else if (OptionalLong.class.isAssignableFrom(recordComponent.getType())) {
			theType = long.class;
		} else if (Collection.class.isAssignableFrom(recordComponent.getType())) {
			return Array.newInstance(
					GenericsUtils.getGenericTypeFromCollection(modelClass, recordComponent.getGenericType()), 0)
				.getClass();
		} else {
			theType = recordComponent.getType();
		}
		return theType;
	}

	/**
	 * Verifies the data type of a given class.
	 *
	 * @param theType the class representing the data type
	 * @param <T>     the generic type of the data type
	 * @return the verified class representing the data type
	 */
	@Nonnull
	private static <T> Class<T> verifyDataType(@Nonnull Class<T> theType) {
		// user enums are always represented as String, which is naturally supported
		if (theType.isEnum()) {
			//noinspection unchecked
			return (Class<T>) String.class;
		}
		Assert.isTrue(
			EvitaDataTypes.isSupportedTypeOrItsArray(theType),
			"Default value type `" + theType + "` must implement Serializable or must be an enum type!"
		);
		return theType;
	}

	/**
	 * Method defines that entity will have the attribute according to {@link Attribute} annotation. Method checks
	 * whether there are no duplicate definitions of the same attribute in single model class.
	 */
	private static void defineAttribute(
		@Nullable CatalogSchemaBuilder catalogBuilder,
		@Nonnull AttributeProviderSchemaEditor<?, ?, ?> attributeSchemaEditor,
		@Nonnull Attribute attributeAnnotation,
		@Nonnull String attributeName,
		@Nonnull String definer,
		@Nonnull Class<? extends Serializable> attributeType,
		@Nullable Serializable defaultValue,
		@Nonnull Map<String, String> attributesAlreadyDefined
	) {
		if (attributesAlreadyDefined.containsKey(attributeName)) {
			throw new InvalidSchemaMutationException(
				"Entity model already defines attribute `" + attributeName + "` in `" + attributesAlreadyDefined.get(
					attributeName) + "`!"
			);
		} else {
			attributesAlreadyDefined.put(attributeName, definer);
		}
		final Consumer<AttributeSchemaEditor<?>> attributeBuilder = editor -> {
			// default value - only set if different (using deep comparison for arrays)
			ofNullable(defaultValue)
				.map(it -> EvitaDataTypes.toTargetType(it, editor.getType()))
				.filter(targetValue -> !isDefaultValueEqual(editor.getDefaultValue(), targetValue))
				.ifPresent(editor::withDefaultValue);

			// description - only set if different
			if (!attributeAnnotation.description().isBlank() &&
				isStringNotEqual(editor.getDescription(), attributeAnnotation.description())) {
				editor.withDescription(attributeAnnotation.description());
			}
			// deprecation - only set if different
			if (!attributeAnnotation.deprecated().isBlank() &&
				isStringNotEqual(editor.getDeprecationNotice(), attributeAnnotation.deprecated())) {
				editor.deprecated(attributeAnnotation.deprecated());
			}
			// nullable - only set if not already nullable
			if (attributeAnnotation.nullable() && !editor.isNullable()) {
				editor.nullable();
			}

			final ScopeAttributeSettings[] scopedDefinition = attributeAnnotation.scope();
			if (ArrayUtils.isEmptyOrItsValuesNull(scopedDefinition)) {
				// unique - only set if not already unique in default scope
				if (attributeAnnotation.unique() == AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION &&
					!editor.isUniqueInScope(Scope.DEFAULT_SCOPE)) {
					editor.unique();
				}
				// unique within locale - only set if not already unique within locale in default scope
				if (attributeAnnotation.unique() == AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE &&
					!editor.isUniqueWithinLocaleInScope(Scope.DEFAULT_SCOPE)) {
					editor.uniqueWithinLocale();
				}
				// filterable - only set if not already filterable in default scope
				if (attributeAnnotation.filterable() && !editor.isFilterableInScope(Scope.DEFAULT_SCOPE)) {
					editor.filterable();
				}
				// sortable - only set if not already sortable in default scope
				if (attributeAnnotation.sortable() && !editor.isSortableInScope(Scope.DEFAULT_SCOPE)) {
					editor.sortable();
				}
			} else {
				Assert.isTrue(
					attributeAnnotation.unique() == AttributeUniquenessType.NOT_UNIQUE,
					"When `scope` is defined in `@Attribute` annotation, " +
						"the value of `unique` property is not taken into an account " +
						"(and thus it doesn't make sense to set it to any value)!"
				);
				// unique in scopes - only set for scopes not already unique
				final Scope[] uniqueInScopes = Arrays.stream(scopedDefinition)
					.filter(it -> it.unique() == AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION)
					.map(ScopeAttributeSettings::scope)
					.filter(scope -> !editor.isUniqueInScope(scope))
					.toArray(Scope[]::new);
				if (!ArrayUtils.isEmptyOrItsValuesNull(uniqueInScopes)) {
					editor.uniqueInScope(uniqueInScopes);
				}
				// unique within locale in scopes - only set for scopes not already unique within locale
				final Scope[] uniqueWithinLocaleInScopes = Arrays.stream(scopedDefinition)
					.filter(it -> it.unique() == AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE)
					.map(ScopeAttributeSettings::scope)
					.filter(scope -> !editor.isUniqueWithinLocaleInScope(scope))
					.toArray(Scope[]::new);
				if (!ArrayUtils.isEmptyOrItsValuesNull(uniqueWithinLocaleInScopes)) {
					editor.uniqueWithinLocaleInScope(uniqueWithinLocaleInScopes);
				}

				Assert.isTrue(
					!attributeAnnotation.filterable(),
					"When `scope` is defined in `@Attribute` annotation, " +
						"the value of `filterable` property is not taken into an account " +
						"(and thus it doesn't make sense to set it to true)!"
				);
				// filterable in scopes - only set for scopes not already filterable
				final Scope[] filterableInScopes = Arrays.stream(scopedDefinition)
					.filter(ScopeAttributeSettings::filterable)
					.map(ScopeAttributeSettings::scope)
					.filter(scope -> !editor.isFilterableInScope(scope))
					.toArray(Scope[]::new);
				if (!ArrayUtils.isEmptyOrItsValuesNull(filterableInScopes)) {
					editor.filterableInScope(filterableInScopes);
				}
				Assert.isTrue(
					!attributeAnnotation.sortable(),
					"When `scope` is defined in `@Attribute` annotation, " +
						"the value of `sortable` property is not taken into an account " +
						"(and thus it doesn't make sense to set it to true)!"
				);
				// sortable in scopes - only set for scopes not already sortable
				final Scope[] sortableInScopes = Arrays.stream(scopedDefinition)
					.filter(ScopeAttributeSettings::sortable)
					.map(ScopeAttributeSettings::scope)
					.filter(scope -> !editor.isSortableInScope(scope))
					.toArray(Scope[]::new);
				if (!ArrayUtils.isEmptyOrItsValuesNull(sortableInScopes)) {
					editor.sortableInScope(sortableInScopes);
				}
			}
			// representative - only set if not already representative
			if (attributeAnnotation.representative() && !editor.isRepresentative()) {
				editor.representative();
			}
			// localized - only set if not already localized
			if (attributeAnnotation.localized() && !editor.isLocalized()) {
				editor.localized();
			}
			// indexed decimal places - only set if different
			if (BigDecimal.class.equals(attributeType) &&
				editor.getIndexedDecimalPlaces() != attributeAnnotation.indexedDecimalPlaces()) {
				editor.indexDecimalPlaces(attributeAnnotation.indexedDecimalPlaces());
			}
		};

		final ScopeAttributeSettings[] scopedDefinition = attributeAnnotation.scope();
		if (attributeAnnotation.global() ||
			attributeAnnotation.uniqueGlobally() != GlobalAttributeUniquenessType.NOT_UNIQUE ||
			Arrays.stream(attributeAnnotation.scope()).anyMatch(
				it -> it.uniqueGlobally() != GlobalAttributeUniquenessType.NOT_UNIQUE) ||
			(!ArrayUtils.isEmptyOrItsValuesNull(scopedDefinition) && Arrays.stream(scopedDefinition).anyMatch(
				it -> it.unique() != AttributeUniquenessType.NOT_UNIQUE || it.uniqueGlobally() != GlobalAttributeUniquenessType.NOT_UNIQUE))
		) {
			Assert.notNull(
				catalogBuilder,
				"Cannot configure global attribute on reference!"
			);
			catalogBuilder.withAttribute(
				attributeName, attributeType,
				whichIs -> {
					attributeBuilder.accept(whichIs);

					if (attributeAnnotation.representative() && !whichIs.isRepresentative()) {
						whichIs.representative();
					}

					if (ArrayUtils.isEmptyOrItsValuesNull(scopedDefinition)) {
						// unique globally - only set if not already unique globally in default scope
						if (attributeAnnotation.uniqueGlobally() == GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG &&
							!whichIs.isUniqueGloballyInScope(Scope.DEFAULT_SCOPE)) {
							whichIs.uniqueGlobally();
						}
						// unique globally within locale - only set if not already unique globally within locale in default scope
						if (attributeAnnotation.uniqueGlobally() == GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE &&
							!whichIs.isUniqueGloballyWithinLocaleInScope(Scope.DEFAULT_SCOPE)) {
							whichIs.uniqueGloballyWithinLocale();
						}
					} else {
						Assert.isTrue(
							attributeAnnotation.uniqueGlobally() == GlobalAttributeUniquenessType.NOT_UNIQUE,
							"When `scope` is defined in `@Attribute` annotation, " +
								"the value of `uniqueGlobally` property is not taken into an account " +
								"(and thus it doesn't make sense to set it to any value)!"
						);
						// unique globally in scopes - only set for scopes not already unique globally
						final Scope[] uniqueGloballyInScopes = Arrays.stream(scopedDefinition)
							.filter(it -> it.uniqueGlobally() == GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG)
							.map(ScopeAttributeSettings::scope)
							.filter(scope -> !whichIs.isUniqueGloballyInScope(scope))
							.toArray(Scope[]::new);
						if (!ArrayUtils.isEmptyOrItsValuesNull(uniqueGloballyInScopes)) {
							whichIs.uniqueGloballyInScope(uniqueGloballyInScopes);
						}
						// unique globally within locale in scopes - only set for scopes not already unique globally within locale
						final Scope[] uniqueGloballyWithinLocaleInScopes = Arrays.stream(scopedDefinition)
							.filter(
								it -> it.uniqueGlobally() == GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE)
							.map(ScopeAttributeSettings::scope)
							.filter(scope -> !whichIs.isUniqueGloballyWithinLocaleInScope(scope))
							.toArray(Scope[]::new);
						if (!ArrayUtils.isEmptyOrItsValuesNull(uniqueGloballyWithinLocaleInScopes)) {
							whichIs.uniqueGloballyWithinLocaleInScope(uniqueGloballyWithinLocaleInScopes);
						}
					}
				}
			);
			if (attributeSchemaEditor instanceof EntitySchemaBuilder entitySchemaBuilder) {
				final Boolean globalAttributeAlreadyDefined = entitySchemaBuilder.getAttribute(attributeName)
					.map(GlobalAttributeSchemaContract.class::isInstance)
					.orElse(false);
				if (!globalAttributeAlreadyDefined) {
					entitySchemaBuilder.withGlobalAttribute(attributeName);
				}
			}
		} else {
			if (attributeSchemaEditor instanceof EntitySchemaBuilder entitySchemaBuilder) {
				Assert.isPremiseValid(
					catalogBuilder != null,
					"Expected CatalogSchemaBuilder instance!"
				);
				final Optional<GlobalAttributeSchemaContract> catalogAttribute = catalogBuilder.getAttribute(
					attributeName);
				if (catalogAttribute.isPresent()) {
					final GlobalAttributeSchemaContract theAttribute = catalogAttribute.get();
					Assert.isTrue(
						theAttribute.getType().equals(attributeType),
						"The attribute `" + attributeName + "` has definition in catalog schema, which needs to be shared, " +
							"but the defined types doesn't match - required `" + theAttribute.getType() + "`, but found `" + attributeType + "`!"
					);
					entitySchemaBuilder.withGlobalAttribute(attributeName);
					return;
				}
			}

			attributeSchemaEditor.withAttribute(
				attributeName,
				EvitaDataTypes.toWrappedForm(attributeType),
				attributeBuilder::accept
			);
		}
	}

	/**
	 * Method determines the cardinality from {@link Reference#allowEmpty()} / {@link ReflectedReference#allowEmpty()}
	 * and the return type of the method - if it returns array type the multiple cardinality is returned.
	 *
	 * @param referenceType   type of the reference
	 * @param allowEmpty      value of {@link Reference#allowEmpty()} / {@link ReflectedReference#allowEmpty()}
	 * @param allowDuplicates value of {@link Reference#allowDuplicates()}
	 * @return resolved cardinality
	 */
	@Nonnull
	private static Cardinality getCardinality(
		@Nonnull Class<?> referenceType,
		boolean allowEmpty,
		boolean allowDuplicates
	) {
		final Cardinality cardinality;
		if (referenceType.isArray()) {
			cardinality = allowEmpty ?
				(allowDuplicates ? Cardinality.ZERO_OR_MORE_WITH_DUPLICATES : Cardinality.ZERO_OR_MORE) :
				(allowDuplicates ? Cardinality.ONE_OR_MORE_WITH_DUPLICATES : Cardinality.ONE_OR_MORE);
		} else {
			Assert.isTrue(
				!allowDuplicates,
				"Cannot set `allowDuplicates` to `true` when reference is not a collection or array!"
			);
			cardinality = allowEmpty ? Cardinality.ZERO_OR_ONE : Cardinality.EXACTLY_ONE;
		}
		return cardinality;
	}

	/**
	 * Attempts to retrieve default value from a field by invoking default constructor and get the value.
	 */
	@Nullable
	private static Serializable extractValueFromField(@Nonnull Class<?> definingClass, @Nonnull Field field) {
		try {
			final Constructor<?> defaultConstructor = definingClass.getConstructor();
			final Object blankInstance = defaultConstructor.newInstance();
			field.setAccessible(true);
			return (Serializable) field.get(blankInstance);
		} catch (Exception ex) {
			return null;
		}
	}

	/**
	 * Returns passed `name` if it's not blank otherwise asks `defaultNameSupplier` for it.
	 */
	@Nonnull
	private static String getNameOrElse(@Nonnull String name, @Nonnull Supplier<String> defaultNameSupplier) {
		return name.isBlank() ? defaultNameSupplier.get() : name.trim();
	}

	/**
	 * Compares two string values treating null and blank as equivalent.
	 *
	 * @param existing the existing value from schema
	 * @param newValue the new value from annotation
	 * @return true if values are effectively equal
	 */
	private static boolean isStringNotEqual(@Nullable String existing, @Nullable String newValue) {
		final String normalizedExisting = existing == null || existing.isBlank() ? null : existing;
		final String normalizedNew = newValue == null || newValue.isBlank() ? null : newValue;
		return !Objects.equals(normalizedExisting, normalizedNew);
	}

	/**
	 * Compares two default values for equality, handling arrays properly.
	 *
	 * @param existing the existing default value from schema
	 * @param newValue the new default value from annotation
	 * @return true if values are equal
	 */
	private static boolean isDefaultValueEqual(@Nullable Serializable existing, @Nullable Serializable newValue) {
		if (existing == newValue) {
			return true;
		}
		if (existing == null || newValue == null) {
			return false;
		}
		if (existing.getClass().isArray() && newValue.getClass().isArray()) {
			return Arrays.deepEquals(new Object[]{existing}, new Object[]{newValue});
		}
		return Objects.equals(existing, newValue);
	}

	/**
	 * Method defines that entity represents hierarchical structure according to {@link ParentEntity} annotation.
	 */
	private static void defineHierarchy(@Nonnull EntitySchemaBuilder entityBuilder) {
		if (!entityBuilder.isWithHierarchy()) {
			entityBuilder.withHierarchy();
		}
	}

	/**
	 * Applies the requested reference index type to the editor if it differs from the current type.
	 *
	 * @param editor    the reference schema editor
	 * @param requested the requested index type from annotation
	 * @param current   the current index type (may be null for inherited reflected references)
	 * @param scope     the scope to apply (null means default scope)
	 */
	private static void applyReferenceIndexType(
		@Nonnull ReferenceSchemaEditor<?> editor,
		@Nonnull ReferenceIndexType requested,
		@Nullable ReferenceIndexType current,
		@Nullable Scope scope
	) {
		if (requested == current) {
			return;
		}
		if (scope == null) {
			switch (requested) {
				case FOR_FILTERING -> editor.indexedForFiltering();
				case FOR_FILTERING_AND_PARTITIONING -> editor.indexedForFilteringAndPartitioning();
				case NONE -> editor.nonIndexed();
			}
		} else {
			switch (requested) {
				case FOR_FILTERING -> editor.indexedForFilteringInScope(scope);
				case FOR_FILTERING_AND_PARTITIONING -> editor.indexedForFilteringAndPartitioningInScope(scope);
				case NONE -> editor.nonIndexed(scope);
			}
		}
	}

	/**
	 * Creates MemberAccessor instances for all record components in the target class.
	 *
	 * @param targetClass the record class to extract components from
	 * @return stream of MemberAccessor instances for each record component
	 */
	@Nonnull
	private static Stream<MemberAccessor> getRecordComponentAccessors(@Nonnull Class<?> targetClass) {
		return Arrays.stream(targetClass.getRecordComponents())
			.map(RecordComponentMemberAccessor::new);
	}

	/**
	 * Returns the appropriate type from a member, handling enums specially for attributes.
	 * Enums are represented as String type in the schema.
	 *
	 * @param modelClass the model class context
	 * @param member     the member accessor to extract type from
	 * @return the resolved type, with enums converted to String
	 */
	@Nonnull
	private static Class<?> getReturnedTypeFromMember(@Nonnull Class<?> modelClass, @Nonnull MemberAccessor member) {
		final Class<?> theType = member.getType(modelClass);
		if (theType.isEnum()) {
			return String.class;
		}
		return theType;
	}

	public ClassSchemaAnalyzer(
		@Nonnull Class<?> modelClass,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		this(
			modelClass,
			(referenceName, aClass) -> aClass,
			reflectionLookup,
			null
		);
	}

	public ClassSchemaAnalyzer(
		@Nonnull Class<?> modelClass,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull SchemaPostProcessor postProcessor
	) {
		this(modelClass, (referenceName, aClass) -> aClass, reflectionLookup, postProcessor);
	}

	public ClassSchemaAnalyzer(
		@Nonnull Class<?> modelClass,
		@Nonnull BiFunction<String, Class<?>, Class<?>> subClassResolver,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nullable SchemaPostProcessor postProcessor
	) {
		this.modelClass = modelClass;
		this.subClassResolver = subClassResolver;
		this.reflectionLookup = reflectionLookup;
		this.postProcessor = postProcessor;
	}

	/**
	 * Method analyzes the entity model class and alters the catalog and entity schema within passed write session
	 * accordingly.
	 *
	 * @param session write Evita session
	 * @throws InvalidSchemaMutationException when entity model contains errors
	 */
	@Nonnull
	public AnalysisResult analyze(@Nonnull EvitaSessionContract session) throws SchemaClassInvalidException {
		final CatalogSchemaBuilder catalogBuilder = session.getCatalogSchema().openForWrite();
		return analyze(session, catalogBuilder);
	}

	/**
	 * Method analyzes the entity model class and alters the catalog and entity schema within passed write session
	 * accordingly.
	 *
	 * @param session        write Evita session
	 * @param catalogBuilder catalog schema builder
	 * @throws InvalidSchemaMutationException when entity model contains errors
	 */
	@Nonnull
	public AnalysisResult analyze(@Nonnull EvitaSessionContract session, @Nonnull CatalogSchemaBuilder catalogBuilder) {
		final AtomicReference<String> entityName = new AtomicReference<>();
		try {
			final List<Entity> entityAnnotations = this.reflectionLookup.getClassAnnotations(
				this.modelClass, Entity.class
			);
			// use only the most specific annotation only
			if (!entityAnnotations.isEmpty()) {
				final Entity entityAnnotation = entityAnnotations.get(0);
				// locate / create the entity schema
				entityName.set(getNameOrElse(entityAnnotation.name(), this.modelClass::getSimpleName));
				final EntitySchemaBuilder entityBuilder = session.getEntitySchema(entityName.get())
					.map(SealedEntitySchema::openForWrite)
					.map(it -> it.cooperatingWith(() -> catalogBuilder))
					.orElseGet(() -> {
						final AtomicReference<EntitySchemaBuilder> capture = new AtomicReference<>();
						catalogBuilder.withEntitySchema(entityName.get(), capture::set);
						return capture.get();
					});

				if (!entityAnnotation.description().isBlank() &&
					isStringNotEqual(entityBuilder.getDescription(), entityAnnotation.description())
				) {
					entityBuilder.withDescription(entityAnnotation.description());
				}
				if (!entityAnnotation.deprecated().isBlank() &&
					isStringNotEqual(entityBuilder.getDeprecationNotice(), entityAnnotation.deprecated())
				) {
					entityBuilder.deprecated(entityAnnotation.deprecated());
				}
				if (!ArrayUtils.isEmpty(entityAnnotation.allowedLocales())) {
					final Set<Locale> existingLocales = entityBuilder.getLocales();
					final Locale[] newLocales = Arrays.stream(entityAnnotation.allowedLocales())
						.map(Locale::forLanguageTag)
						.filter(locale -> !existingLocales.contains(locale))
						.toArray(Locale[]::new);
					if (newLocales.length > 0) {
						entityBuilder.withLocale(newLocales);
					}
				}
				// analyze all members (methods, fields, or record components)
				analyzeMembers(catalogBuilder, entityBuilder);

				// define sortable attribute compounds
				defineSortableAttributeCompounds(this.modelClass, entityBuilder);

				// if the schema consumer is available invoke it
				ofNullable(this.postProcessor)
					.ifPresent(it -> it.postProcess(catalogBuilder, entityBuilder));

				// define evolution mode - if no currencies or locales definition were found - let them fill up in runtime
				final Set<EvolutionMode> existingEvolutionMode = entityBuilder.getEvolutionMode();
				final Set<EvolutionMode> newEvolutionMode = Set.of(entityAnnotation.allowedEvolution());
				if (!existingEvolutionMode.equals(newEvolutionMode)) {
					entityBuilder.verifySchemaButAllow(entityAnnotation.allowedEvolution());
				}

				// now return the mutations that needs to be done
				return new AnalysisResult(
					entityName.get(),
					catalogBuilder.toMutation()
						.stream()
						.flatMap(it -> Arrays.stream(it.getSchemaMutations()))
						.toArray(LocalCatalogSchemaMutation[]::new),
					entityBuilder.toMutation().stream().toArray(LocalCatalogSchemaMutation[]::new)
				);
			}
		} catch (RuntimeException ex) {
			throw new SchemaClassInvalidException(
				this.modelClass, ex
			);
		}
		if (entityName.get() == null) {
			throw new EvitaInvalidUsageException(
				"No entity schema name found on class. You need to declare `@Entity` annotation on the class."
			);
		} else {
			return new AnalysisResult(entityName.get());
		}
	}

	/**
	 * Checks if a reference with the given name is already defined and throws if so.
	 * If not already defined, registers the reference as defined.
	 *
	 * @param referenceName the name of the reference to check
	 * @param definer       the string representation of the defining element (method, field, or record component)
	 * @throws InvalidSchemaMutationException if the reference is already defined
	 */
	private void checkDuplicateReference(@Nonnull String referenceName, @Nonnull String definer) {
		if (this.referencesDefined.containsKey(referenceName)) {
			throw new InvalidSchemaMutationException(
				"Entity model already defines reference `" + referenceName + "` in `" +
					this.referencesDefined.get(referenceName) + "`!"
			);
		}
		this.referencesDefined.put(referenceName, definer);
	}

	/**
	 * Determines the processing priority for a member based on its annotations.
	 * Members with "reference" annotations (ending with Ref suffix) that depend on
	 * "defining" annotations are assigned higher priority values and processed later.
	 *
	 * @param member the member to classify
	 * @return processing priority (0 = defining annotations, 1 = reference annotations)
	 */
	private static int getMemberProcessingPriority(@Nonnull MemberAccessor member) {
		return member.hasAnyOf(DEPENDENT_ANNOTATIONS) ? 1 : 0;
	}

	/**
	 * Analyzes all members of the model class for entity schema annotations.
	 * Unifies the processing of methods, fields, and record components using the MemberAccessor abstraction.
	 * Members are sorted by processing priority before iteration to ensure that defining annotations
	 * (like @Reference) are processed before reference annotations (like @ReferenceRef).
	 *
	 * @param catalogBuilder the catalog schema builder
	 * @param entityBuilder  the entity schema builder
	 */
	private void analyzeMembers(
		@Nonnull CatalogSchemaBuilder catalogBuilder,
		@Nonnull EntitySchemaBuilder entityBuilder
	) {
		// Sort members by processing priority before iterating
		// Priority 0: Defining annotations (@Reference, @Attribute, etc.) - processed first
		// Priority 1: Reference annotations (@ReferenceRef, @AttributeRef, etc.) - processed last
		getAllMemberAccessors(this.modelClass)
			.sorted(Comparator.comparingInt(ClassSchemaAnalyzer::getMemberProcessingPriority))
			.forEach(member -> {
				// Primary key
				final PrimaryKey primaryKeyAnnotation = member.getAnnotation(PrimaryKey.class);
				if (primaryKeyAnnotation != null) {
					definePrimaryKey(entityBuilder, primaryKeyAnnotation);
				}

				// Attribute
				final Attribute attributeAnnotation = member.getAnnotation(Attribute.class);
				if (attributeAnnotation != null) {
					final String attributeName = getNameOrElse(attributeAnnotation.name(), member::getName);
					final Class<?> returnedType = verifyDataType(
						getReturnedTypeFromMember(this.modelClass, member)
					);
					@SuppressWarnings("unchecked") final Class<? extends Serializable> attributeType = (Class<? extends Serializable>) returnedType;
					final Serializable defaultValue = member.getDefaultValue(this.modelClass);
					defineAttribute(
						catalogBuilder, entityBuilder, attributeAnnotation, attributeName,
						member.getDefiner(), attributeType, defaultValue, this.attributesDefined
					);
				}

				// Associated data
				final AssociatedData associatedDataAnnotation = member.getAnnotation(AssociatedData.class);
				if (associatedDataAnnotation != null) {
					final String associatedDataName = getNameOrElse(
						associatedDataAnnotation.name(), member::getName
					);
					final Class<?> associatedDataType = member.getType(this.modelClass);
					@SuppressWarnings("unchecked") final Class<? extends Serializable> typedAssociatedDataType =
						(Class<? extends Serializable>) associatedDataType;
					defineAssociatedData(
						entityBuilder, associatedDataAnnotation, associatedDataName,
						member.getDefiner(), typedAssociatedDataType
					);
				}

				// Reference
				final Reference referenceAnnotation = member.getAnnotation(Reference.class);
				if (referenceAnnotation != null) {
					final String referenceName = getNameOrElse(referenceAnnotation.name(), member::getName);
					final Class<?> referenceType = member.getType(this.modelClass);
					defineReference(
						entityBuilder, referenceAnnotation, referenceName, member.getDefiner(),
						this.subClassResolver.apply(referenceName, referenceType)
					);
				}

				// Reflected reference
				final ReflectedReference reflectedReferenceAnnotation = member.getAnnotation(ReflectedReference.class);
				if (reflectedReferenceAnnotation != null) {
					final String referenceName = getNameOrElse(
						reflectedReferenceAnnotation.name(), member::getName
					);
					final Class<?> referenceType = member.getType(this.modelClass);
					defineReflectedReference(
						entityBuilder, reflectedReferenceAnnotation, referenceName, member.getDefiner(),
						this.subClassResolver.apply(referenceName, referenceType)
					);
				}

				// Reference ref (only relevant for methods, but harmless for others)
				final ReferenceRef referenceRefAnnotation = member.getAnnotation(ReferenceRef.class);
				if (referenceRefAnnotation != null) {
					final String referenceName = getNameOrElse(referenceRefAnnotation.value(), member::getName);
					final Class<?> referenceType = member.getType(this.modelClass);
					final ReferenceSchemaContract existingReference = entityBuilder.getReferenceOrThrowException(
						referenceName
					);
					if (existingReference instanceof ReflectedReferenceSchemaContract rrsc) {
						defineReflectedReference(
							entityBuilder,
							rrsc,
							this.subClassResolver.apply(referenceName, referenceType)
						);
					} else {
						defineReference(
							entityBuilder,
							existingReference,
							this.subClassResolver.apply(referenceName, referenceType)
						);
					}
				}

				// Parent entity
				if (member.getAnnotation(ParentEntity.class) != null) {
					defineHierarchy(entityBuilder);
				}

				// Price for sale
				final PriceForSale priceForSaleAnnotation = member.getAnnotation(PriceForSale.class);
				if (priceForSaleAnnotation != null) {
					definePrice(entityBuilder, priceForSaleAnnotation);
				}
			});
	}

	/**
	 * Method defines that entity will (not) have primary key generated according to {@link PrimaryKey} annotation.
	 */
	private void definePrimaryKey(
		@Nonnull EntitySchemaBuilder entityBuilder,
		@Nonnull PrimaryKey primaryKeyAnnotation
	) {
		Assert.isTrue(
			!this.primaryKeyDefined,
			"Class `" + this.modelClass + "` contains multiple methods marked with `@PrimaryKey` annotation," +
				" which is not allowed!"
		);
		this.primaryKeyDefined = true;
		// only set if the auto-generate setting differs
		if (primaryKeyAnnotation.autoGenerate() && !entityBuilder.isWithGeneratedPrimaryKey()) {
			entityBuilder.withGeneratedPrimaryKey();
		} else if (!primaryKeyAnnotation.autoGenerate() && entityBuilder.isWithGeneratedPrimaryKey()) {
			entityBuilder.withoutGeneratedPrimaryKey();
		}
	}

	/**
	 * Method defines that entity contains price definitions according to {@link PriceForSale} annotation.
	 */
	private void definePrice(@Nonnull EntitySchemaBuilder entityBuilder, @Nonnull PriceForSale sellingPriceAnnotation) {
		Assert.isTrue(
			!this.sellingPriceDefined,
			"Class `" + this.modelClass + "` contains multiple methods marked with `@PriceForSale` annotation," +
				" which is not allowed!"
		);
		// only set if price configuration differs
		final Set<Currency> existingCurrencies = entityBuilder.getCurrencies();
		final Currency[] newCurrencies = Arrays.stream(sellingPriceAnnotation.allowedCurrencies())
			.map(Currency::getInstance)
			.filter(currency -> !existingCurrencies.contains(currency))
			.toArray(Currency[]::new);
		final boolean indexedPricePlacesDiffers = entityBuilder.getIndexedPricePlaces() != sellingPriceAnnotation.indexedPricePlaces();
		if (!entityBuilder.isWithPrice() || indexedPricePlacesDiffers || newCurrencies.length > 0) {
			entityBuilder.withPriceInCurrency(
				sellingPriceAnnotation.indexedPricePlaces(),
				newCurrencies.length > 0 ? newCurrencies : new Currency[0]
			);
		}
		this.sellingPriceDefined = true;
	}

	/**
	 * Method defines that entity will have the associated data according to {@link AssociatedData} annotation. Method
	 * checks whether there are no duplicate definitions of the same associated data in single model class.
	 */
	private void defineAssociatedData(
		@Nonnull EntitySchemaBuilder entityBuilder,
		@Nonnull AssociatedData associatedDataAnnotation,
		@Nonnull String associatedDataName,
		@Nonnull String definer,
		@Nonnull Class<? extends Serializable> associatedDataType
	) {
		if (this.associatedDataDefined.containsKey(associatedDataName)) {
			throw new InvalidSchemaMutationException(
				"Entity model already defines associated data `" + associatedDataName + "` in `" + this.associatedDataDefined.get(
					associatedDataName) + "`!"
			);
		} else {
			this.associatedDataDefined.put(associatedDataName, definer);
		}
		final boolean supportedTypeOrItsArray = EvitaDataTypes.isSupportedTypeOrItsArray(associatedDataType);
		final boolean serializable = Serializable.class.isAssignableFrom(associatedDataType);

		if (!supportedTypeOrItsArray && !serializable) {
			throw new InvalidSchemaMutationException(
				"The type `" + associatedDataType + "` cannot be used for associated data `" + associatedDataName + "`. " +
					"It is not a directly supported evitaDB type and cannot be converted to a ComplexDataObject, because " +
					"it doesn't implement `Serializable` interface."
			);
		}

		entityBuilder.withAssociatedData(
			associatedDataName,
			supportedTypeOrItsArray ? associatedDataType : ComplexDataObject.class,
			whichIs -> {
				// description - only set if different
				if (!associatedDataAnnotation.description().isBlank() &&
					isStringNotEqual(whichIs.getDescription(), associatedDataAnnotation.description())
				) {
					whichIs.withDescription(associatedDataAnnotation.description());
				}
				// deprecation - only set if different
				if (!associatedDataAnnotation.deprecated().isBlank() &&
					isStringNotEqual(whichIs.getDeprecationNotice(), associatedDataAnnotation.deprecated())
				) {
					whichIs.deprecated(associatedDataAnnotation.deprecated());
				}
				// nullable - only set if not already nullable
				if (associatedDataAnnotation.nullable() && !whichIs.isNullable()) {
					whichIs.nullable();
				}
				// localized - only set if not already localized
				if (associatedDataAnnotation.localized() && !whichIs.isLocalized()) {
					whichIs.localized();
				}
			}
		);
	}

	/**
	 * Method defines that entity will have the reference according to {@link Reference} annotation. Method checks
	 * whether there are no duplicate definitions of the same reference in single model class.
	 */
	private void defineReference(
		@Nonnull EntitySchemaBuilder entityBuilder,
		@Nonnull Reference reference,
		@Nonnull String referenceName,
		@Nonnull String definer,
		@Nonnull Class<?> referenceType
	) {
		checkDuplicateReference(referenceName, definer);

		final Cardinality cardinality = getCardinality(
			referenceType, reference.allowEmpty(), reference.allowDuplicates());
		final Class<?> examinedReferenceType = referenceType.isArray() ?
			referenceType.getComponentType() :
			referenceType;
		final TargetEntity targetEntity;
		final TargetEntity targetEntityGroup;

		final List<Entity> directEntity = this.reflectionLookup.getClassAnnotations(
			examinedReferenceType, Entity.class
		);
		if (directEntity.isEmpty()) {
			targetEntity = getTargetEntity(
				definer, ReferencedEntity.class, new TargetEntity(reference.entity(), reference.managed()),
				examinedReferenceType
			);
			targetEntityGroup = getTargetEntity(
				definer, ReferencedEntityGroup.class, new TargetEntity(
					reference.groupEntity(),
					reference.groupEntityManaged()
				), examinedReferenceType
			);
		} else {
			targetEntity = new TargetEntity(directEntity.get(0).name(), true);
			targetEntityGroup = new TargetEntity(reference.groupEntity(), reference.groupEntityManaged());
		}

		Assert.isTrue(
			!targetEntity.entityType().isBlank(),
			"Target entity type needs to be specified either by `@Reference` entity attribute or by `@ReferencedEntity` " +
				" and `@Entity` annotation on target class!"
		);

		final Map<String, String> relationAttributes = new HashMap<>(32);
		final Consumer<ReferenceSchemaBuilder> referenceBuilder = editor -> {
			// group type - only set if different
			if (!targetEntityGroup.entityType().isBlank()) {
				final String existingGroupType = editor.getReferencedGroupType();
				if (!targetEntityGroup.entityType().equals(existingGroupType)) {
					if (targetEntityGroup.managed()) {
						editor.withGroupTypeRelatedToEntity(targetEntityGroup.entityType());
					} else {
						editor.withGroupType(targetEntityGroup.entityType());
					}
				}
			}

			// description - only set if different
			if (!reference.description().isBlank() &&
				isStringNotEqual(editor.getDescription(), reference.description())
			) {
				editor.withDescription(reference.description());
			}
			// deprecation - only set if different
			if (!reference.deprecated().isBlank() &&
				isStringNotEqual(editor.getDeprecationNotice(), reference.deprecated())
			) {
				editor.deprecated(reference.deprecated());
			}

			final ScopeReferenceSettings[] scopedDefinition = reference.scope();
			if (ArrayUtils.isEmptyOrItsValuesNull(scopedDefinition)) {
				// indexed - only set if index type differs in default scope
				applyReferenceIndexType(
					editor,
					reference.indexed(),
					editor.getReferenceIndexType(Scope.DEFAULT_SCOPE),
					null
				);
				// faceted - only set if not already faceted in default scope
				if (!reference.faceted().value().isEmpty() && !editor.isFacetedInScope(Scope.DEFAULT_SCOPE)) {
					editor.faceted();
				}
			} else {
				Assert.isTrue(
					reference.indexed() == ReferenceIndexType.NONE,
					"When `scope` is defined in `@Reference` annotation, " +
						"the value of `indexed` property is not taken into an account " +
						"(and thus it doesn't make sense to set it to true)!"
				);

				// indexed in scopes - only set for scopes where index type differs
				for (ScopeReferenceSettings scopeReferenceSettings : scopedDefinition) {
					applyReferenceIndexType(
						editor,
						scopeReferenceSettings.indexed(),
						editor.getReferenceIndexType(scopeReferenceSettings.scope()),
						scopeReferenceSettings.scope()
					);
				}

				Assert.isTrue(
					reference.faceted().value().isEmpty(),
					"When `scope` is defined in `@Reference` annotation, " +
						"the value of `faceted` property is not taken into an account " +
						"(and thus it doesn't make sense to set it to true)!"
				);
				// faceted in scopes - only set for scopes not already faceted
				final Scope[] facetedInScopes = Arrays.stream(scopedDefinition)
					.filter(s -> !s.faceted().value().isEmpty())
					.map(ScopeReferenceSettings::scope)
					.filter(scope -> !editor.isFacetedInScope(scope))
					.toArray(Scope[]::new);
				if (!ArrayUtils.isEmptyOrItsValuesNull(facetedInScopes)) {
					editor.facetedInScope(facetedInScopes);
				}
			}

			defineReferenceAttributes(referenceType, editor, examinedReferenceType, relationAttributes);
		};

		Assert.isTrue(
			reference.managed() == targetEntity.managed(),
			"@Reference annotation managed flag needs to be consistent with @ReferencedEntity / @Entity annotations! " +
				"Managed flag is set to `" + reference.managed() + "` on target entity, " +
				"but `" + targetEntity.managed() + "` on reference definition in `" + definer + "`."
		);

		if (targetEntity.managed()) {
			entityBuilder.withReferenceToEntity(
				referenceName, targetEntity.entityType(), cardinality,
				referenceBuilder
			);
		} else {
			entityBuilder.withReferenceTo(
				referenceName, targetEntity.entityType(), cardinality,
				referenceBuilder
			);
		}
	}

	/**
	 * Method defines that entity will have the reference according to {@link Reference} annotation. Method checks
	 * whether there are no duplicate definitions of the same reference in single model class.
	 */
	private void defineReference(
		@Nonnull EntitySchemaBuilder entityBuilder,
		@Nonnull ReferenceSchemaContract reference,
		@Nonnull Class<?> referenceType
	) {
		final Class<?> examinedReferenceType = referenceType.isArray() ?
			referenceType.getComponentType() :
			referenceType;

		final Map<String, String> relationAttributes = new HashMap<>(32);
		final Consumer<ReferenceSchemaBuilder> referenceBuilder = editor ->
			defineReferenceAttributes(referenceType, editor, examinedReferenceType, relationAttributes);

		if (reference.isReferencedEntityTypeManaged()) {
			entityBuilder.withReferenceToEntity(
				reference.getName(),
				reference.getReferencedEntityType(),
				reference.getCardinality(),
				referenceBuilder
			);
		} else {
			entityBuilder.withReferenceTo(
				reference.getName(),
				reference.getReferencedEntityType(),
				reference.getCardinality(),
				referenceBuilder
			);
		}
	}

	/**
	 * Analyzes reference type members for attribute annotations.
	 * Unifies the processing of methods, fields, and record components using the MemberAccessor abstraction.
	 *
	 * @param referenceType             the class type being analyzed
	 * @param relationAttributesDefined map tracking already defined attributes to detect duplicates
	 * @param editor                    the reference schema editor
	 */
	private void analyzeReferenceMembers(
		@Nonnull Class<?> referenceType,
		@Nonnull Map<String, String> relationAttributesDefined,
		@Nonnull ReferenceSchemaEditor<?> editor
	) {
		getAllMemberAccessors(referenceType)
			.forEach(member -> {
				final Attribute attributeAnnotation = member.getAnnotation(Attribute.class);
				if (attributeAnnotation != null) {
					final String attributeName = getNameOrElse(attributeAnnotation.name(), member::getName);
					final Class<?> memberType = verifyDataType(member.getType(referenceType));
					@SuppressWarnings("unchecked") final Class<? extends Serializable> attributeType = (Class<? extends Serializable>) memberType;
					final Serializable defaultValue = member.getDefaultValue(referenceType);
					defineAttribute(
						null, editor, attributeAnnotation, attributeName,
						member.getDefiner(), attributeType, defaultValue, relationAttributesDefined
					);
				}
			});
	}

	/**
	 * Collects referenced attributes from all members of the reference type.
	 * Unifies the processing of methods, fields, and record components using the MemberAccessor abstraction.
	 *
	 * @param referenceType        the class type to collect attributes from
	 * @param referencedAttributes the set to add collected attribute names to
	 * @param attributeFilter      predicate to filter which attributes to include
	 */
	private void collectReferencedAttributesFromMembers(
		@Nonnull Class<?> referenceType,
		@Nonnull Set<String> referencedAttributes,
		@Nonnull Predicate<String> attributeFilter
	) {
		getAllMemberAccessors(referenceType)
			.forEach(member -> {
				final AttributeRef attributeAnnotation = member.getAnnotation(AttributeRef.class);
				if (attributeAnnotation != null) {
					final String attributeName = attributeAnnotation.value().isBlank() ?
						member.getName() : attributeAnnotation.value();
					if (attributeFilter.test(attributeName)) {
						referencedAttributes.add(attributeName);
					}
				}
			});
	}

	/**
	 * Defines reference attributes for a given reference type by analyzing the specified class,
	 * its methods, fields, or record components based on the provided schema builder and relation attributes.
	 * This method supports both entity and non-entity reference types (e.g., relation mapping DTOs).
	 *
	 * @param referenceType         the class type of the reference for which the attributes are defined
	 * @param editor                the schema builder used for defining and modifying the schema for reference attributes
	 * @param examinedReferenceType the class type being analyzed for control annotations and attributes
	 * @param relationAttributes    a map of relationship attributes that define the linking details for the reference
	 */
	private void defineReferenceAttributes(
		@Nonnull Class<?> referenceType,
		@Nonnull ReferenceSchemaBuilder editor,
		@Nonnull Class<?> examinedReferenceType,
		@Nonnull Map<String, String> relationAttributes
	) {
		// we need also to analyze the target type for presence of control annotations in case the type is not
		// entity itself (i.e. is a relation mapping DTO)
		if (this.reflectionLookup.getClassAnnotations(examinedReferenceType, Entity.class).isEmpty()) {
			analyzeReferenceMembers(examinedReferenceType, relationAttributes, editor);
			defineSortableAttributeCompounds(referenceType, editor);
		}
	}

	/**
	 * Method defines that entity will have the reference according to {@link ReflectedReference} annotation. Method
	 * checks whether there are no duplicate definitions of the same reference in single model class.
	 */
	private void defineReflectedReference(
		@Nonnull EntitySchemaBuilder entityBuilder,
		@Nonnull ReflectedReference reference,
		@Nonnull String referenceName,
		@Nonnull String definer,
		@Nonnull Class<?> referenceType
	) {
		checkDuplicateReference(referenceName, definer);

		final Class<?> examinedReferenceType = referenceType.isArray() ?
			referenceType.getComponentType() :
			referenceType;
		final TargetEntity targetEntity;

		final List<Entity> directEntity = this.reflectionLookup.getClassAnnotations(
			examinedReferenceType, Entity.class
		);
		if (directEntity.isEmpty()) {
			targetEntity = getTargetEntity(
				definer, ReferencedEntity.class,
				new TargetEntity(reference.ofEntity(), true),
				examinedReferenceType
			);
		} else {
			targetEntity = new TargetEntity(directEntity.get(0).name(), true);
		}

		Assert.isTrue(
			!targetEntity.entityType().isBlank(),
			"Target entity of reference `" + referenceName + "` type needs to be specified either by " +
				"`@ReflectedReference` entity attribute or by `@ReferencedEntity` and `@Entity` annotation on target class!"
		);

		final Map<String, String> relationAttributes = new HashMap<>(32);
		final Consumer<ReflectedReferenceSchemaBuilder> reflectedReferenceBuilder = editor -> {

			// set cardinality first - only if different
			if (reference.allowEmpty() != InheritableBoolean.INHERITED) {
				final Cardinality newCardinality = getCardinality(
					referenceType,
					reference.allowEmpty() == InheritableBoolean.TRUE,
					false
				);
				if (editor.isCardinalityInherited() || editor.getCardinality() != newCardinality) {
					editor.withCardinality(newCardinality);
				}
			}

			// description - only set if different
			if (
				!reference.description().isBlank() && (
					editor.isDescriptionInherited() ||
						isStringNotEqual(editor.getDescription(), reference.description())
				)
			) {
				editor.withDescription(reference.description());
			}
			// deprecation - only set if different
			if (
				!reference.deprecated().isBlank() && (
					editor.isDeprecatedInherited() ||
						isStringNotEqual(editor.getDeprecationNotice(), reference.deprecated())
				)
			) {
				editor.deprecated(reference.deprecated());
			}

			final ScopeReferenceSettings[] scopedDefinition = reference.scope();
			if (ArrayUtils.isEmptyOrItsValuesNull(scopedDefinition)) {

				// indexed - only set if index type differs in default scope
				final ReferenceIndexType currentIndexType = editor.isIndexedInherited() ?
					null : editor.getReferenceIndexType(Scope.DEFAULT_SCOPE);
				applyReferenceIndexType(editor, reference.indexed(), currentIndexType, null);

				// faceted - only set if different
				final Boolean facetedInScope = editor.isFacetedInherited() ?
					null : editor.isFacetedInScope(Scope.DEFAULT_SCOPE);
				if (reference.faceted() == InheritableBoolean.TRUE && Boolean.FALSE.equals(facetedInScope)) {
					editor.faceted();
				} else if (reference.faceted() == InheritableBoolean.FALSE && Boolean.TRUE.equals(facetedInScope)) {
					editor.nonFaceted();
				}

			} else {

				// indexed in scopes - only set for scopes where index type differs
				for (ScopeReferenceSettings scopeReferenceSettings : scopedDefinition) {
					final ReferenceIndexType currentIndexType = editor.isIndexedInherited() ?
						null : editor.getReferenceIndexType(scopeReferenceSettings.scope());
					applyReferenceIndexType(
						editor,
						scopeReferenceSettings.indexed(),
						currentIndexType,
						scopeReferenceSettings.scope()
					);
				}

				Assert.isTrue(
					reference.faceted() == InheritableBoolean.FALSE,
					"When `scope` is defined in `@ReflectedReference` annotation, " +
						"the value of `faceted` property of reflected reference `" + reference + "` is not taken " +
						"into an account (and thus it doesn't make sense to set it to true)!"
				);

				// faceted in scopes - only set for scopes not already faceted
				final Scope[] facetedInScopes = Arrays.stream(scopedDefinition)
					.filter(s -> !s.faceted().value().isEmpty())
					.map(ScopeReferenceSettings::scope)
					.filter(scope -> editor.isFacetedInherited() || !editor.isFacetedInScope(scope))
					.toArray(Scope[]::new);
				if (!ArrayUtils.isEmptyOrItsValuesNull(facetedInScopes)) {
					editor.facetedInScope(facetedInScopes);
				}
			}

			final Set<String> referencedAttributes = CollectionUtils.createHashSet(32);
			// we need also to analyze the target type for presence of control annotations in case the type is not
			// entity itself (i.e. is a relation mapping DTO)
			if (this.reflectionLookup.getClassAnnotations(examinedReferenceType, Entity.class).isEmpty()) {
				// analyze reference members for attributes
				analyzeReferenceMembers(examinedReferenceType, relationAttributes, editor);

				// collect referenced attributes if inheritance behavior requires it
				if (reference.attributesInheritanceBehavior() == AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED) {
					final Set<String> allowedAttributeNames = new HashSet<>(
						Arrays.asList(reference.attributeInheritanceFilter()));
					final Predicate<String> attributeFilter = attributeName -> !allowedAttributeNames.contains(
						attributeName);
					collectReferencedAttributesFromMembers(
						examinedReferenceType, referencedAttributes, attributeFilter
					);
				}
			}

			switch (reference.attributesInheritanceBehavior()) {
				case INHERIT_ONLY_SPECIFIED -> {
					// merge specified and collected attributes
					final String[] attributeNames = Stream.concat(
						Arrays.stream(reference.attributeInheritanceFilter()),
						referencedAttributes.stream()
					).toArray(String[]::new);
					// only set if inheritance behavior or filter differs
					if (
						editor.getAttributesInheritanceBehavior() != AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED ||
							!Arrays.equals(editor.getAttributeInheritanceFilter(), attributeNames)
					) {
						editor.withAttributesInherited(attributeNames);
					}
				}
				case INHERIT_ALL_EXCEPT -> {
					// only set if inheritance behavior or filter differs
					if (editor.getAttributesInheritanceBehavior() != AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT) {
						editor.withAttributesInheritedExcept(
							reference.attributeInheritanceFilter()
						);
					}
				}
			}

			defineSortableAttributeCompounds(referenceType, editor);
		};

		entityBuilder.withReflectedReferenceToEntity(
			referenceName, targetEntity.entityType(), reference.ofName(), reflectedReferenceBuilder
		);
	}

	/**
	 * Method defines that entity will have the reflected reference based on an existing
	 * {@link ReflectedReferenceSchemaContract}. This is used when a reference is already defined
	 * (e.g., from @ReferenceRef annotation) and needs to be enhanced with attributes from the
	 * referenced type's members.
	 *
	 * @param entityBuilder the entity schema builder to add the reference to
	 * @param reference     the existing reflected reference schema contract
	 * @param referenceType the class type of the reference for analyzing attributes
	 */
	private void defineReflectedReference(
		@Nonnull EntitySchemaBuilder entityBuilder,
		@Nonnull ReflectedReferenceSchemaContract reference,
		@Nonnull Class<?> referenceType
	) {
		final Class<?> examinedReferenceType = referenceType.isArray() ?
			referenceType.getComponentType() :
			referenceType;

		final Map<String, String> relationAttributes = new HashMap<>(32);
		final Consumer<ReflectedReferenceSchemaBuilder> reflectedReferenceBuilder = editor -> {
			// we need also to analyze the target type for presence of control annotations in case the type is not
			// entity itself (i.e. is a relation mapping DTO)
			if (this.reflectionLookup.getClassAnnotations(examinedReferenceType, Entity.class).isEmpty()) {
				analyzeReferenceMembers(examinedReferenceType, relationAttributes, editor);
				defineSortableAttributeCompounds(referenceType, editor);
			}
		};

		entityBuilder.withReflectedReferenceToEntity(
			reference.getName(),
			reference.getReferencedEntityType(),
			reference.getReflectedReferenceName(),
			reflectedReferenceBuilder
		);
	}

	/**
	 * Defines sortable attribute compounds for a given type using the provided schema editor.
	 * This method collects all annotations of type {@link SortableAttributeCompounds} or
	 * {@link SortableAttributeCompound} present on the specified class and processes them to
	 * configure sortable attribute compounds within the provided editor.
	 *
	 * @param type   the class type whose annotations are to be processed; must not be null
	 * @param editor the schema editor responsible for handling sortable attribute compounds; must not be null
	 */
	private void defineSortableAttributeCompounds(
		@Nonnull Class<?> type,
		@Nonnull SortableAttributeCompoundSchemaProviderEditor<?, ?, ?> editor
	) {
		// Note: getClassAnnotations already handles repeatable annotations by extracting
		// individual @SortableAttributeCompound annotations from @SortableAttributeCompounds container
		this.reflectionLookup.getClassAnnotations(type, SortableAttributeCompound.class)
			.forEach(sortableAttributeCompound -> {
				final String compoundName = sortableAttributeCompound.name();
				final List<AttributeElement> newElements = Arrays.stream(sortableAttributeCompound.attributeElements())
					.map(it -> new AttributeElement(it.attributeName(), it.orderDirection(), it.orderBehaviour()))
					.toList();

				// check if compound already exists with same configuration
				final Optional<? extends SortableAttributeCompoundSchemaContract> existingCompound =
					editor.getSortableAttributeCompound(compoundName);

				if (existingCompound.isPresent()) {
					final SortableAttributeCompoundSchemaContract existing = existingCompound.get();
					// check if all properties match
					final boolean elementsMatch = existing.getAttributeElements().equals(newElements);
					final boolean descriptionMatch = !isStringNotEqual(
						existing.getDescription(), sortableAttributeCompound.description());
					final boolean deprecationMatch = !isStringNotEqual(
						existing.getDeprecationNotice(), sortableAttributeCompound.deprecated());
					final Set<Scope> existingScopes = existing.getIndexedInScopes();
					final Set<Scope> newScopes = sortableAttributeCompound.scope().length == 0
						? Collections.emptySet()
						: Set.of(sortableAttributeCompound.scope());
					final boolean scopesMatch = existingScopes.equals(newScopes);

					// if everything matches, skip creating mutation
					if (elementsMatch && descriptionMatch && deprecationMatch && scopesMatch) {
						return;
					}
				}

				// only call builder if compound doesn't exist or has different configuration
				editor.withSortableAttributeCompound(
					compoundName,
					newElements.toArray(AttributeElement[]::new),
					whichIs -> {
						// description - only set if different
						if (isStringNotEqual(whichIs.getDescription(), sortableAttributeCompound.description())) {
							whichIs.withDescription(sortableAttributeCompound.description());
						}
						// deprecation - only change if different
						if (sortableAttributeCompound.deprecated().isBlank()) {
							if (whichIs.getDeprecationNotice() != null) {
								whichIs.notDeprecatedAnymore();
							}
						} else if (isStringNotEqual(
							whichIs.getDeprecationNotice(), sortableAttributeCompound.deprecated())) {
							whichIs.deprecated(sortableAttributeCompound.deprecated());
						}
						// indexed scopes - only set if different
						final Set<Scope> existingScopes = whichIs.getIndexedInScopes();
						if (sortableAttributeCompound.scope().length == 0) {
							if (!existingScopes.isEmpty()) {
								whichIs.nonIndexed();
							}
						} else {
							final Set<Scope> newScopes = Set.of(sortableAttributeCompound.scope());
							if (!existingScopes.equals(newScopes)) {
								whichIs.indexedInScope(sortableAttributeCompound.scope());
							}
						}
					}
				);
			});
	}

	/**
	 * Method identifies the referenced entity by referenced class contents and transforms that information into an
	 * enveloping DTO {@link TargetEntity}.
	 *
	 * @param caller              String representation of the place that contains the reference
	 * @param lookedUpAnnotation  annotation to analyze
	 * @param defaultTargetEntity default value if no other is found within this method
	 * @param referenceType       the referenced class to analyze
	 * @return fabricated {@link TargetEntity} or `defaultTargetEntity`
	 */
	@Nonnull
	private TargetEntity getTargetEntity(
		@Nonnull String caller,
		@Nonnull Class<? extends Annotation> lookedUpAnnotation,
		@Nonnull TargetEntity defaultTargetEntity,
		@Nonnull Class<?> referenceType
	) {
		final TargetEntity targetEntity;
		if (defaultTargetEntity.entityType().isBlank() && !EvitaDataTypes.isSupportedTypeOrItsArray(referenceType)) {
			final Class<?> targetType = referenceType.isArray() ? referenceType.getComponentType() : referenceType;
			if (targetType.isRecord()) {
				final List<RecordComponent> recordComponents = Arrays.stream(targetType.getRecordComponents())
					.filter(it -> it.getAnnotation(lookedUpAnnotation) != null)
					.toList();
				final List<RecordComponent> nonPrimaryKeyComponents = recordComponents
					.stream()
					.filter(it -> !EvitaDataTypes.isSupportedTypeOrItsArray(it.getType()))
					.toList();
				if (recordComponents.isEmpty()) {
					return defaultTargetEntity;
				} else if (nonPrimaryKeyComponents.size() == 1) {
					final RecordComponent recordComponent = nonPrimaryKeyComponents.get(0);
					final Class<?> targetEntityType = recordComponent.getType();
					targetEntity = getTargetEntity(targetEntityType, caller, lookedUpAnnotation, referenceType);
				} else if (recordComponents.size() == 1) {
					final RecordComponent recordComponent = recordComponents.get(0);
					final Class<?> targetEntityType = recordComponent.getType();
					if (int.class.isAssignableFrom(targetEntityType) || Integer.class.isAssignableFrom(
						targetEntityType)) {
						targetEntity = new TargetEntity(recordComponent.getName(), false);
					} else {
						throw new InvalidSchemaMutationException(
							"Record component `" + recordComponent + "` on class `" + referenceType + "` returns " +
								"a type that cannot be used for referenced entity!"
						);
					}
				} else {
					throw new InvalidSchemaMutationException(
						"Multiple (" + recordComponents.stream()
							.map(RecordComponent::toString)
							.collect(Collectors.joining(
								", ")) + ") record components have annotation `@" + lookedUpAnnotation.getSimpleName() + "` specified. " +
							"We need to select one in order to infer target entity type, but we don't know which one."
					);
				}
			} else {
				final List<Method> getters = this.reflectionLookup.findAllGettersHavingAnnotation(
					targetType, lookedUpAnnotation);
				final List<Method> nonPrimaryKeyGetters = getters
					.stream()
					.filter(it -> !EvitaDataTypes.isSupportedTypeOrItsArray(extractReturnType(this.modelClass, it)))
					.toList();
				if (getters.isEmpty()) {
					final Map<Field, ? extends List<? extends Annotation>> fields = this.reflectionLookup.getFields(
						targetType, lookedUpAnnotation);
					final Map<Field, ? extends List<? extends Annotation>> nonPrimaryKeyFields = fields
						.entrySet()
						.stream()
						.filter(it -> !EvitaDataTypes.isSupportedTypeOrItsArray(it.getKey().getType()))
						.collect(
							Collectors.toMap(
								Entry::getKey,
								Entry::getValue
							)
						);
					if (fields.isEmpty()) {
						return defaultTargetEntity;
					} else if (nonPrimaryKeyFields.size() == 1) {
						final Field field = nonPrimaryKeyFields.keySet().iterator().next();
						final Class<?> targetEntityType = field.getType();
						targetEntity = getTargetEntity(targetEntityType, caller, lookedUpAnnotation, referenceType);
					} else if (fields.size() == 1) {
						final Field field = fields.keySet().iterator().next();
						final Class<?> targetEntityType = field.getType();
						if (int.class.isAssignableFrom(targetEntityType) || Integer.class.isAssignableFrom(
							targetEntityType)) {
							targetEntity = new TargetEntity(field.getName(), false);
						} else {
							throw new InvalidSchemaMutationException(
								"Field `" + field.toGenericString() + "` on class `" + referenceType + "` returns " +
									"a type that cannot be used for referenced entity!"
							);
						}
					} else {
						throw new InvalidSchemaMutationException(
							"Multiple (" + fields.keySet().stream()
								.map(Field::toGenericString)
								.collect(Collectors.joining(
									", ")) + ") fields have annotation `@" + lookedUpAnnotation.getSimpleName() + "` specified. " +
								"We need to select one in order to infer target entity type, but we don't know which one."
						);
					}
				} else if (nonPrimaryKeyGetters.size() == 1 || nonPrimaryKeyGetters.stream().map(
					it -> extractReturnType(this.modelClass, it)).distinct().count() == 1L) {
					final Method getter = nonPrimaryKeyGetters.get(0);
					final Class<?> targetEntityType = extractReturnType(this.modelClass, getter);
					targetEntity = getTargetEntity(targetEntityType, caller, lookedUpAnnotation, referenceType);
				} else if (getters.size() == 1) {
					final Method getter = getters.get(0);
					final Class<?> targetEntityType = extractReturnType(this.modelClass, getter);
					if (int.class.isAssignableFrom(targetEntityType) || Integer.class.isAssignableFrom(
						targetEntityType)) {
						targetEntity = new TargetEntity(
							ReflectionLookup.getPropertyNameFromMethodName(getter.getName()), false
						);
					} else {
						throw new InvalidSchemaMutationException(
							"Method `" + getter.toGenericString() + "` on class `" + referenceType + "` returns " +
								"a type that cannot be used for referenced entity!"
						);
					}
				} else {
					throw new InvalidSchemaMutationException(
						"Multiple (" + getters.stream()
							.map(Method::toGenericString)
							.collect(Collectors.joining(
								", ")) + ") getters have annotation `@" + lookedUpAnnotation.getSimpleName() + "` specified and have different cardinality. " +
							"We need to select one in order to infer target entity type, but we don't know which one."
					);
				}
			}
		} else {
			targetEntity = defaultTargetEntity;
		}

		return targetEntity;
	}

	/**
	 * Retrieves entity information from {@link Entity} attribute placed on referenced entity type.
	 *
	 * @param targetEntityType   the class to introspect for {@link Entity} annotation
	 * @param caller             for the sake of error message
	 * @param lookedUpAnnotation for the sake of error message
	 * @param referenceType      for the sake of error message
	 * @return fabricated {@link TargetEntity}
	 */
	@Nonnull
	private TargetEntity getTargetEntity(
		@Nonnull Class<?> targetEntityType,
		@Nonnull String caller,
		@Nonnull Class<? extends Annotation> lookedUpAnnotation,
		@Nonnull Class<?> referenceType
	) {
		final TargetEntity targetEntity;
		final List<Entity> targetEntityAnnotations = this.reflectionLookup.getClassAnnotations(
			targetEntityType, Entity.class
		);
		if (targetEntityAnnotations.isEmpty()) {
			throw new InvalidSchemaMutationException(
				"There is no `@Entity` annotation available on class `" + targetEntityType + "` referenced " +
					"by `@" + lookedUpAnnotation.getSimpleName() + "` in class `" + referenceType + "` and no " +
					"entity is defined on annotated method `" + caller + "`!"
			);
		} else {
			final Entity targetEntityAnnotation = targetEntityAnnotations.get(0);
			final String targetEntityName = getNameOrElse(
				targetEntityAnnotation.name(),
				targetEntityType::getSimpleName
			);
			targetEntity = new TargetEntity(targetEntityName, true);
		}
		return targetEntity;
	}

	/**
	 * Creates MemberAccessor instances for all getters in the target class.
	 *
	 * @param targetClass the class to extract getters from
	 * @return stream of MemberAccessor instances for each getter
	 */
	@Nonnull
	private Stream<MemberAccessor> getMethodAccessors(@Nonnull Class<?> targetClass) {
		return this.reflectionLookup.findAllGetters(targetClass).stream()
			.map(it -> new MethodMemberAccessor(it, this.reflectionLookup));
	}

	/**
	 * Creates MemberAccessor instances for all annotated fields in the target class.
	 *
	 * @param targetClass the class to extract fields from
	 * @return stream of MemberAccessor instances for each annotated field
	 */
	@Nonnull
	private Stream<MemberAccessor> getFieldAccessors(@Nonnull Class<?> targetClass) {
		return this.reflectionLookup.getFields(targetClass).entrySet().stream()
			.map(entry -> new FieldMemberAccessor(entry.getKey(), entry.getValue()));
	}

	/**
	 * Creates MemberAccessor instances for all members based on class type.
	 * For records, returns record components. For regular classes, returns fields and methods.
	 *
	 * @param targetClass the class to extract members from
	 * @return stream of MemberAccessor instances for all relevant members
	 */
	@Nonnull
	private Stream<MemberAccessor> getAllMemberAccessors(@Nonnull Class<?> targetClass) {
		if (targetClass.isRecord()) {
			return getRecordComponentAccessors(targetClass);
		} else {
			return Stream.concat(
				getFieldAccessors(targetClass),
				getMethodAccessors(targetClass)
			);
		}
	}

	/**
	 * Unified accessor for class members (methods, fields, record components).
	 * Provides consistent access to annotations, names, types, and default values,
	 * enabling unified processing of different member types.
	 */
	private interface MemberAccessor {

		/**
		 * Returns the annotation of the specified type, or null if not present.
		 *
		 * @param annotationType the annotation class to look for
		 * @param <A>            the annotation type
		 * @return the annotation instance or null
		 */
		@Nullable
		<A extends Annotation> A getAnnotation(@Nonnull Class<A> annotationType);

		/**
		 * Returns the property name derived from this member.
		 *
		 * @return the property name
		 */
		@Nonnull
		String getName();

		/**
		 * Returns the resolved type of this member (unwrapping Optional, Collection, etc.).
		 *
		 * @param modelClass the model class context for type resolution
		 * @return the resolved type
		 */
		@Nonnull
		Class<?> getType(@Nonnull Class<?> modelClass);

		/**
		 * Returns the default value if available, null otherwise.
		 *
		 * @param modelClass the model class context for default value extraction
		 * @return the default value or null
		 */
		@Nullable
		Serializable getDefaultValue(@Nonnull Class<?> modelClass);

		/**
		 * Returns a string representation for error messages.
		 *
		 * @return string identifying this member
		 */
		@Nonnull
		String getDefiner();

		/**
		 * Checks if this member has any annotation whose type is present in the given set.
		 *
		 * @param annotationTypes set of annotation types to check for
		 * @return true if member has at least one annotation from the set
		 */
		boolean hasAnyOf(@Nonnull Set<Class<? extends Annotation>> annotationTypes);

	}

	/**
	 * DTO for passing information about the referenced entity.
	 *
	 * @param entityType name of the entity
	 * @param managed    true if the entity is expected to be managed by Evita
	 */
	private record TargetEntity(@Nonnull String entityType, boolean managed) {
	}

	/**
	 * Record contains the analysis result.
	 */
	public record AnalysisResult(
		@Nonnull String entityType,
		@Nonnull LocalCatalogSchemaMutation[] catalogMutations,
		@Nonnull LocalCatalogSchemaMutation[] entityMutations
	) {
		private final static LocalCatalogSchemaMutation[] EMPTY_MUTATIONS = new LocalCatalogSchemaMutation[0];

		public AnalysisResult(@Nonnull String entityType) {
			this(entityType, EMPTY_MUTATIONS, EMPTY_MUTATIONS);
		}


		@Nonnull
		public LocalCatalogSchemaMutation[] mutations() {
			return ArrayUtils.mergeArrays(this.catalogMutations, this.entityMutations);
		}
	}

	/**
	 * MemberAccessor implementation for class fields.
	 * Extracts annotations from the provided annotation list, uses field name directly,
	 * and extracts default values by instantiating the class with default constructor.
	 */
	private record FieldMemberAccessor(
		@Nonnull Field field,
		@Nonnull List<Annotation> annotations
	) implements MemberAccessor {

		@Override
		@Nullable
		@SuppressWarnings("unchecked")
		public <A extends Annotation> A getAnnotation(@Nonnull Class<A> annotationType) {
			return (A) this.annotations.stream()
				.filter(annotationType::isInstance)
				.findFirst()
				.orElse(null);
		}

		@Override
		@Nonnull
		public String getName() {
			return this.field.getName();
		}

		@Override
		@Nonnull
		public Class<?> getType(@Nonnull Class<?> modelClass) {
			return extractFieldType(modelClass, this.field);
		}

		@Override
		@Nullable
		public Serializable getDefaultValue(@Nonnull Class<?> modelClass) {
			return extractValueFromField(modelClass, this.field);
		}

		@Override
		@Nonnull
		public String getDefiner() {
			return this.field.toGenericString();
		}

		@Override
		public boolean hasAnyOf(@Nonnull Set<Class<? extends Annotation>> annotationTypes) {
			for (Annotation annotation : this.annotations) {
				if (annotationTypes.contains(annotation.annotationType())) {
					return true;
				}
			}
			return false;
		}

	}

	/**
	 * MemberAccessor implementation for record components.
	 * Extracts annotations directly from the component, uses component name,
	 * and returns null for default values (records don't support default values).
	 */
	private record RecordComponentMemberAccessor(
		@Nonnull RecordComponent recordComponent
	) implements MemberAccessor {

		@Override
		@Nullable
		public <A extends Annotation> A getAnnotation(@Nonnull Class<A> annotationType) {
			return this.recordComponent.getAnnotation(annotationType);
		}

		@Override
		@Nonnull
		public String getName() {
			return this.recordComponent.getName();
		}

		@Override
		@Nonnull
		public Class<?> getType(@Nonnull Class<?> modelClass) {
			return extractRecordComponentType(modelClass, this.recordComponent);
		}

		@Override
		@Nullable
		public Serializable getDefaultValue(@Nonnull Class<?> modelClass) {
			// Record components don't have default values
			return null;
		}

		@Override
		@Nonnull
		public String getDefiner() {
			return this.recordComponent.toString();
		}

		@Override
		public boolean hasAnyOf(@Nonnull Set<Class<? extends Annotation>> annotationTypes) {
			for (Annotation annotation : this.recordComponent.getAnnotations()) {
				if (annotationTypes.contains(annotation.annotationType())) {
					return true;
				}
			}
			return false;
		}

	}

	/**
	 * MemberAccessor implementation for getter methods.
	 * Extracts annotations via ReflectionLookup, property names from method names,
	 * types from return types, and default values from default method implementations.
	 */
	private record MethodMemberAccessor(
		@Nonnull Method method,
		@Nonnull ReflectionLookup reflectionLookup
	) implements MemberAccessor {

		@Override
		@Nullable
		public <A extends Annotation> A getAnnotation(@Nonnull Class<A> annotationType) {
			return this.reflectionLookup.getAnnotationInstance(this.method, annotationType);
		}

		@Override
		@Nonnull
		public String getName() {
			return ReflectionLookup.getPropertyNameFromMethodName(this.method.getName());
		}

		@Override
		@Nonnull
		public Class<?> getType(@Nonnull Class<?> modelClass) {
			return extractReturnType(modelClass, this.method);
		}

		@Override
		@Nullable
		public Serializable getDefaultValue(@Nonnull Class<?> modelClass) {
			return this.method.isDefault()
				? ClassSchemaAnalyzer.extractDefaultValue(modelClass, this.method)
				: null;
		}

		@Override
		@Nonnull
		public String getDefiner() {
			return this.method.toGenericString();
		}

		@Override
		public boolean hasAnyOf(@Nonnull Set<Class<? extends Annotation>> annotationTypes) {
			for (Annotation annotation : this.method.getAnnotations()) {
				if (annotationTypes.contains(annotation.annotationType())) {
					return true;
				}
			}
			return false;
		}

	}

}
