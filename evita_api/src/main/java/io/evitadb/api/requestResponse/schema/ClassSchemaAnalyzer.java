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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SchemaPostProcessor;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.SchemaClassInvalidException;
import io.evitadb.api.requestResponse.data.annotation.*;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.EntityAttributeSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.GlobalAttributeSchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Analyzer is a stateful class, that traverses record / class getters or fields for annotations from `io.data.annotation`
 * package and sets up the entity / catalog schema accordingly.
 *
 * The analyzer only creates or expands existing schema and never removes anything from it. The expected form of use is
 * to define new entity properties and mark old one as deprecated. Whe the already deprecated properties are about to be
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
	public static Optional<String> extractEntityTypeFromClass(@Nonnull Class<?> classToAnalyse, @Nonnull ReflectionLookup reflectionLookup) {
		return reflectionLookup.extractFromClass(
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
		);
	}

	/**
	 * Verifies the data type of a given class.
	 *
	 * @param theType the class representing the data type
	 * @return the verified class representing the data type
	 * @param <T> the generic type of the data type
	 */
	@Nonnull
	private static <T> Class<T> verifyDataType(@Nonnull Class<T> theType) {
		Assert.isTrue(
			EvitaDataTypes.isSupportedTypeOrItsArray(theType),
			"Default value type `" + theType + "` must implement Serializable!"
		);
		return theType;
	}

	/**
	 * Method defines that entity will have the attribute according to {@link Attribute} annotation. Method checks
	 * whether there are no duplicate definitions of the same attribute in single model class.
	 */
	private static void defineAttribute(
		@Nonnull CatalogSchemaBuilder catalogBuilder,
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
				"Entity model already defines attribute `" + attributeName + "` in `" + attributesAlreadyDefined.get(attributeName) + "`!"
			);
		} else {
			attributesAlreadyDefined.put(attributeName, definer);
		}
		final Consumer<AttributeSchemaEditor<?>> attributeBuilder = whichIs -> {
			ofNullable(defaultValue)
				.map(it -> EvitaDataTypes.toTargetType(it, whichIs.getType()))
				.ifPresent(whichIs::withDefaultValue);

			if (attributeAnnotation.unique() == AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION) {
				whichIs.unique();
			}
			if (attributeAnnotation.unique() == AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE) {
				whichIs.uniqueWithinLocale();
			}
			if (!attributeAnnotation.description().isBlank()) {
				whichIs.withDescription(attributeAnnotation.description());
			}
			if (!attributeAnnotation.deprecated().isBlank()) {
				whichIs.deprecated(attributeAnnotation.deprecated());
			}
			if (attributeAnnotation.nullable()) {
				whichIs.nullable();
			}
			if (attributeAnnotation.filterable()) {
				whichIs.filterable();
			}
			if (attributeAnnotation.sortable()) {
				whichIs.sortable();
			}
			if (attributeAnnotation.representative()) {
				if (whichIs instanceof EntityAttributeSchemaBuilder entityBuilder) {
					entityBuilder.representative();
				} else if (whichIs instanceof GlobalAttributeSchemaBuilder entityBuilder) {
					entityBuilder.representative();
				} else {
					throw new IllegalArgumentException("Reference attribute cannot be made representative!");
				}
			}
			if (attributeAnnotation.localized()) {
				whichIs.localized();
			}
			if (BigDecimal.class.equals(attributeType)) {
				whichIs.indexDecimalPlaces(attributeAnnotation.indexedDecimalPlaces());
			}
		};
		if (attributeAnnotation.global() || attributeAnnotation.uniqueGlobally() != GlobalAttributeUniquenessType.NOT_UNIQUE) {
			catalogBuilder.withAttribute(
				attributeName, attributeType,
				whichIs -> {
					attributeBuilder.accept(whichIs);
					if (attributeAnnotation.uniqueGlobally() == GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG) {
						whichIs.uniqueGlobally();
					}
					if (attributeAnnotation.uniqueGlobally() == GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG) {
						whichIs.uniqueGloballyWithinLocale();
					}
				}
			);
			if (attributeSchemaEditor instanceof EntitySchemaBuilder entitySchemaBuilder) {
				entitySchemaBuilder.withGlobalAttribute(attributeName);
			}
		} else {
			if (attributeSchemaEditor instanceof EntitySchemaBuilder entitySchemaBuilder) {
				final Optional<GlobalAttributeSchemaContract> catalogAttribute = catalogBuilder.getAttribute(attributeName);
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
	 * Method analyzes reference class record components for presence of control annotations.
	 */
	private static void analyzeReferenceRecordComponents(@Nonnull CatalogSchemaBuilder catalogBuilder, @Nonnull Class<?> referenceType, @Nonnull Map<String, String> relationAttributesDefined, @Nonnull ReferenceSchemaBuilder whichIs) {
		for (RecordComponent recordComponent : referenceType.getRecordComponents()) {
			final Attribute attributeAnnotation = recordComponent.getAnnotation(Attribute.class);
			if (attributeAnnotation != null) {
				final String attributeName = getNameOrElse(attributeAnnotation.name(), recordComponent::getName);
				final Class<?> recordComponentType = verifyDataType(extractRecordComponentType(referenceType, recordComponent));
				@SuppressWarnings("unchecked") final Class<? extends Serializable> attributeType = (Class<? extends Serializable>) recordComponentType;
				defineAttribute(
					catalogBuilder, whichIs, attributeAnnotation, attributeName,
					recordComponent.getName(), attributeType, null, relationAttributesDefined
				);
			}
		}
	}

	/**
	 * Method resolves the proper type from the method return type unwrapping the optional and collection types.
	 *
	 * @param modelClass
	 * @param getter
	 * @return
	 */
	@Nonnull
	static Class<?> extractReturnType(@Nonnull Class<?> modelClass, @Nonnull Method getter) {
		final Class<?> theType;
		if (Optional.class.isAssignableFrom(getter.getReturnType())) {
			final List<GenericBundle> genericTypes = GenericsUtils.getNestedMethodReturnTypes(modelClass, getter);
			final Class<?> secondType = genericTypes.size() > 1 ? genericTypes.get(1).getResolvedType() : genericTypes.get(0).getResolvedType();
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
			return Array.newInstance(GenericsUtils.getGenericTypeFromCollection(modelClass, getter.getGenericReturnType()), 0)
				.getClass();
		} else {
			theType = getter.getReturnType();
		}
		return theType;
	}

	/**
	 * Method resolves the proper type from the field return type unwrapping the optional and collection types.
	 *
	 * @param modelClass
	 * @param field
	 * @return
	 */
	@Nonnull
	static Class<?> extractFieldType(@Nonnull Class<?> modelClass, @Nonnull Field field) {
		final Class<?> theType;
		if (Optional.class.isAssignableFrom(field.getType())) {
			final List<GenericBundle> genericTypes = GenericsUtils.getNestedFieldTypes(modelClass, field);
			final Class<?> secondType = genericTypes.size() > 1 ? genericTypes.get(1).getResolvedType() : genericTypes.get(0).getResolvedType();
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
			return Array.newInstance(GenericsUtils.getGenericTypeFromCollection(modelClass, field.getType()), 0)
				.getClass();
		} else {
			theType = field.getType();
		}
		return theType;
	}

	/**
	 * Method resolves the proper type from the recordComponent return type unwrapping the optional and collection types.
	 *
	 * @param modelClass
	 * @param recordComponent
	 * @return
	 */
	@Nonnull
	static Class<?> extractRecordComponentType(@Nonnull Class<?> modelClass, @Nonnull RecordComponent recordComponent) {
		final Class<?> theType;
		if (Optional.class.isAssignableFrom(recordComponent.getType())) {
			final List<GenericBundle> genericTypes = GenericsUtils.getNestedRecordComponentType(modelClass, recordComponent);
			final Class<?> secondType = genericTypes.size() > 1 ? genericTypes.get(1).getResolvedType() : genericTypes.get(0).getResolvedType();
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
			return Array.newInstance(GenericsUtils.getGenericTypeFromCollection(modelClass, recordComponent.getType()), 0)
				.getClass();
		} else {
			theType = recordComponent.getType();
		}
		return theType;
	}

	/**
	 * Method returns the return type of the passed getter but only if it matches any of the supported Evita data types.
	 */
	@Nonnull
	private static Class<?> getReturnedType(@Nonnull Class<?> modelClass, @Nonnull Method getter) {
		final Class<?> theType = extractReturnType(modelClass, getter);
		if (theType.isEnum()) {
			// the enums are represented by their full name
			return String.class;
		} else {
			return theType;
		}
	}

	/**
	 * Method determines the cardinality from {@link Reference#allowEmpty()} and the return type of the method - if it
	 * returns array type the multiple cardinality is returned.
	 */
	@Nonnull
	private static Cardinality getCardinality(@Nonnull Reference reference, @Nonnull Class<?> referenceType) {
		final Cardinality cardinality;
		if (referenceType.isArray()) {
			cardinality = reference.allowEmpty() ? Cardinality.ZERO_OR_MORE : Cardinality.ONE_OR_MORE;
		} else {
			cardinality = reference.allowEmpty() ? Cardinality.ZERO_OR_ONE : Cardinality.EXACTLY_ONE;
		}
		return cardinality;
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
			final MethodHandle methodHandle = constructor.newInstance(declaringClass).unreflectSpecial(getter, declaringClass);
			final Object proxy = Proxy.newProxyInstance(
				definingClass.getClassLoader(), new Class<?>[]{definingClass},
				(theProxy, method, args) -> {
					throw new UnsupportedOperationException();
				}
			);
			return (Serializable) methodHandle.bindTo(proxy).invokeWithArguments();

		} catch (Throwable ex) {

			return null;
		}
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

		} catch (Throwable ex) {
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

	public ClassSchemaAnalyzer(@Nonnull Class<?> modelClass, @Nonnull ReflectionLookup reflectionLookup) {
		this(modelClass, (referenceName, aClass) -> aClass, reflectionLookup, null);
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
		@Nonnull SchemaPostProcessor postProcessor
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
	 * @param session write Evita session
	 * @param catalogBuilder catalog schema builder
	 * @throws InvalidSchemaMutationException when entity model contains errors
	 */
	@Nonnull
	public AnalysisResult analyze(@Nonnull EvitaSessionContract session, @Nonnull CatalogSchemaBuilder catalogBuilder) {
		AtomicReference<String> entityName = new AtomicReference<>();
		try {
			final List<Entity> entityAnnotations = reflectionLookup.getClassAnnotations(modelClass, Entity.class);
			// use only the most specific annotation only
			if (!entityAnnotations.isEmpty()) {
				final Entity entityAnnotation = entityAnnotations.get(0);
				// locate / create the entity schema
				entityName.set(getNameOrElse(entityAnnotation.name(), modelClass::getSimpleName));
				final EntitySchemaBuilder entityBuilder = session.getEntitySchema(entityName.get())
					.map(SealedEntitySchema::openForWrite)
					.map(it -> it.cooperatingWith(() -> catalogBuilder))
					.orElseGet(() -> {
						final AtomicReference<EntitySchemaBuilder> capture = new AtomicReference<>();
						catalogBuilder.withEntitySchema(entityName.get(), capture::set);
						return capture.get();
					});

				if (!entityAnnotation.description().isBlank()) {
					entityBuilder.withDescription(entityAnnotation.description());
				}
				if (!entityAnnotation.deprecated().isBlank()) {
					entityBuilder.deprecated(entityAnnotation.deprecated());
				}
				if (!ArrayUtils.isEmpty(entityAnnotation.allowedLocales())) {
					entityBuilder.withLocale(
						Arrays.stream(entityAnnotation.allowedLocales())
							.map(Locale::forLanguageTag)
							.toArray(Locale[]::new)
					);
				}
				if (!ArrayUtils.isEmpty(entityAnnotation.allowedLocales())) {
					entityBuilder.withLocale(
						Arrays.stream(entityAnnotation.allowedLocales())
							.map(Locale::forLanguageTag)
							.toArray(Locale[]::new)
					);
				}
				// is the passed model class record?
				if (modelClass.isRecord()) {
					// analyze record components
					analyzeRecordComponents(catalogBuilder, entityBuilder);
				} else {
					// analyze field annotations
					analyzeFields(catalogBuilder, entityBuilder);
					// then analyze getter methods
					analyzeGetterMethods(catalogBuilder, entityBuilder);
				}

				// if the schema consumer is available invoke it
				ofNullable(postProcessor)
					.ifPresent(it -> it.postProcess(catalogBuilder, entityBuilder));

				// define evolution mode - if no currencies or locales definition were found - let them fill up in runtime
				entityBuilder.verifySchemaButAllow(entityAnnotation.allowedEvolution());

				// now return the mutations that needs to be done
				return new AnalysisResult(
					entityName.get(),
					catalogBuilder.toMutation().stream().flatMap(it -> Arrays.stream(it.getSchemaMutations())).toArray(LocalCatalogSchemaMutation[]::new),
					entityBuilder.toMutation().stream().toArray(LocalCatalogSchemaMutation[]::new)
				);
			}
		} catch (RuntimeException ex) {
			throw new SchemaClassInvalidException(
				modelClass, ex
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
	 * Method analyzes getter methods for presence of control annotations.
	 */
	private void analyzeGetterMethods(@Nonnull CatalogSchemaBuilder catalogBuilder, @Nonnull EntitySchemaBuilder entityBuilder) {
		final Collection<Method> getters = reflectionLookup.findAllGetters(modelClass);
		for (Method getter : getters) {
			final PrimaryKey primaryKeyAnnotation = reflectionLookup.getAnnotationInstance(getter, PrimaryKey.class);
			if (primaryKeyAnnotation != null) {
				definePrimaryKey(entityBuilder, primaryKeyAnnotation);
			}
			final Attribute attributeAnnotation = reflectionLookup.getAnnotationInstance(getter, Attribute.class);
			if (attributeAnnotation != null) {
				final String attributeName = getNameOrElse(
					attributeAnnotation.name(),
					() -> ReflectionLookup.getPropertyNameFromMethodName(getter.getName())
				);
				final Class<?> returnedType = verifyDataType(getReturnedType(modelClass, getter));
				@SuppressWarnings("unchecked") final Class<? extends Serializable> attributeType = (Class<? extends Serializable>) returnedType;
				final Serializable defaultValue = getter.isDefault() ? extractDefaultValue(modelClass, getter) : null;
				defineAttribute(catalogBuilder, entityBuilder, attributeAnnotation, attributeName, getter.toGenericString(), attributeType, defaultValue, attributesDefined);
			}
			final AssociatedData associatedDataAnnotation = reflectionLookup.getAnnotationInstance(getter, AssociatedData.class);
			if (associatedDataAnnotation != null) {
				final String associatedDataName = getNameOrElse(
					associatedDataAnnotation.name(),
					() -> ReflectionLookup.getPropertyNameFromMethodName(getter.getName())
				);
				final Class<?> associatedDataType = extractReturnType(modelClass, getter);
				//noinspection unchecked
				defineAssociatedData(
					entityBuilder, associatedDataAnnotation, associatedDataName,
					getter.toGenericString(), (Class<? extends Serializable>) associatedDataType
				);
			}
			final Reference referenceAnnotation = reflectionLookup.getAnnotationInstance(getter, Reference.class);
			if (referenceAnnotation != null) {
				final String referenceName = getNameOrElse(
					referenceAnnotation.name(),
					() -> ReflectionLookup.getPropertyNameFromMethodName(getter.getName())
				);
				final Class<?> referenceType = extractReturnType(modelClass, getter);
				defineReference(
					catalogBuilder, entityBuilder, referenceAnnotation, referenceName, getter.toGenericString(),
					subClassResolver.apply(referenceName, referenceType)
				);
			}
			ofNullable(reflectionLookup.getAnnotationInstance(getter, ParentEntity.class))
				.ifPresent(it -> defineHierarchy(entityBuilder));

			ofNullable(reflectionLookup.getAnnotationInstance(getter, PriceForSale.class))
				.ifPresent(it -> definePrice(entityBuilder, it));
		}
	}

	/**
	 * Method analyzes record components for presence of control annotations.
	 */
	private void analyzeRecordComponents(@Nonnull CatalogSchemaBuilder catalogBuilder, @Nonnull EntitySchemaBuilder entityBuilder) {
		final RecordComponent[] recordComponents = modelClass.getRecordComponents();
		for (RecordComponent recordComponent : recordComponents) {
			final PrimaryKey primaryKeyAnnotation = recordComponent.getAnnotation(PrimaryKey.class);
			if (primaryKeyAnnotation != null) {
				definePrimaryKey(entityBuilder, primaryKeyAnnotation);
			}
			final Attribute attributeAnnotation = recordComponent.getAnnotation(Attribute.class);
			if (attributeAnnotation != null) {
				final String attributeName = getNameOrElse(attributeAnnotation.name(), recordComponent::getName);
				final Class<?> recordComponentType = verifyDataType(extractRecordComponentType(modelClass, recordComponent));
				@SuppressWarnings("unchecked") final Class<? extends Serializable> attributeType = (Class<? extends Serializable>) recordComponentType;
				defineAttribute(
					catalogBuilder, entityBuilder, attributeAnnotation, attributeName,
					recordComponent.toString(), attributeType, null, attributesDefined
				);
			}
			final AssociatedData associatedDataAnnotation = recordComponent.getAnnotation(AssociatedData.class);
			if (associatedDataAnnotation != null) {
				final String associatedDataName = getNameOrElse(
					associatedDataAnnotation.name(),
					recordComponent::getName
				);
				final Class<?> associatedDataType = extractRecordComponentType(modelClass, recordComponent);
				//noinspection unchecked
				defineAssociatedData(
					entityBuilder, associatedDataAnnotation, associatedDataName,
					recordComponent.toString(), (Class<? extends Serializable>) associatedDataType
				);
			}
			final Reference referenceAnnotation = recordComponent.getAnnotation(Reference.class);
			if (referenceAnnotation != null) {
				final String referenceName = getNameOrElse(referenceAnnotation.name(), recordComponent::getName);
				final Class<?> referenceType = extractRecordComponentType(modelClass, recordComponent);
				defineReference(
					catalogBuilder, entityBuilder, referenceAnnotation, referenceName,
					recordComponent.toString(),
					referenceType
				);
			}

			ofNullable(recordComponent.getAnnotation(ParentEntity.class))
				.ifPresent(it -> defineHierarchy(entityBuilder));

			ofNullable(recordComponent.getAnnotation(PriceForSale.class))
				.ifPresent(it -> definePrice(entityBuilder, it));
		}
	}

	/**
	 * Method analyzes class fields for presence of control annotations.
	 */
	private void analyzeFields(@Nonnull CatalogSchemaBuilder catalogBuilder, @Nonnull EntitySchemaBuilder entityBuilder) {
		final Map<Field, List<Annotation>> fields = reflectionLookup.getFields(modelClass);
		for (final Entry<Field, List<Annotation>> fieldEntry : fields.entrySet()) {
			for (final Annotation annotation : fieldEntry.getValue()) {
				if (annotation instanceof PrimaryKey primaryKeyAnnotation) {
					definePrimaryKey(entityBuilder, primaryKeyAnnotation);
				}
				if (annotation instanceof Attribute attributeAnnotation) {
					final String attributeName = getNameOrElse(
						attributeAnnotation.name(),
						() -> fieldEntry.getKey().getName()
					);
					final Class<?> fieldType = verifyDataType(extractFieldType(modelClass, fieldEntry.getKey()));
					@SuppressWarnings("unchecked") final Class<? extends Serializable> attributeType = (Class<? extends Serializable>) fieldType;
					final Serializable defaultValue = extractValueFromField(modelClass, fieldEntry.getKey());
					defineAttribute(
						catalogBuilder, entityBuilder, attributeAnnotation, attributeName,
						fieldEntry.getKey().toGenericString(), attributeType, defaultValue, attributesDefined
					);
				}
				if (annotation instanceof AssociatedData associatedDataAnnotation) {
					final String attributeName = getNameOrElse(
						associatedDataAnnotation.name(),
						() -> fieldEntry.getKey().getName()
					);
					final Class<?> associatedDataType = extractFieldType(modelClass, fieldEntry.getKey());
					//noinspection unchecked
					defineAssociatedData(
						entityBuilder, associatedDataAnnotation, attributeName,
						fieldEntry.getKey().toGenericString(), (Class<? extends Serializable>) associatedDataType
					);
				}
				if (annotation instanceof Reference referenceAnnotation) {
					final String referenceName = getNameOrElse(
						referenceAnnotation.name(),
						() -> fieldEntry.getKey().getName()
					);
					final Class<?> referenceType = extractFieldType(modelClass, fieldEntry.getKey());
					defineReference(
						catalogBuilder, entityBuilder,
						referenceAnnotation, referenceName,
						fieldEntry.getKey().toGenericString(),
						subClassResolver.apply(referenceName, referenceType)
					);
				}
				if (annotation instanceof ParentEntity) {
					defineHierarchy(entityBuilder);
				}
				if (annotation instanceof PriceForSale sellingPriceAnnotation) {
					definePrice(entityBuilder, sellingPriceAnnotation);
				}
			}
		}
	}

	/**
	 * Method defines that entity will (not) have primary key generated according to {@link PrimaryKey} annotation.
	 */
	private void definePrimaryKey(
		@Nonnull EntitySchemaBuilder entityBuilder,
		@Nonnull PrimaryKey primaryKeyAnnotation
	) {
		Assert.isTrue(
			!primaryKeyDefined,
			"Class `" + modelClass + "` contains multiple methods marked with `@PrimaryKey` annotation," +
				" which is not allowed!"
		);
		primaryKeyDefined = true;
		if (primaryKeyAnnotation.autoGenerate()) {
			entityBuilder.withGeneratedPrimaryKey();
		} else {
			entityBuilder.withoutGeneratedPrimaryKey();
		}
	}

	/**
	 * Method defines that entity represents hierarchical structure according to {@link ParentEntity} annotation.
	 */
	private void defineHierarchy(@Nonnull EntitySchemaBuilder entityBuilder) {
		entityBuilder.withHierarchy();
	}

	/**
	 * Method defines that entity contains price definitions according to {@link PriceForSale} annotation.
	 */
	private void definePrice(@Nonnull EntitySchemaBuilder entityBuilder, @Nonnull PriceForSale sellingPriceAnnotation) {
		Assert.isTrue(
			!sellingPriceDefined,
			"Class `" + modelClass + "` contains multiple methods marked with `@PriceForSale` annotation," +
				" which is not allowed!"
		);
		entityBuilder.withPriceInCurrency(
			sellingPriceAnnotation.indexedPricePlaces(),
			Arrays.stream(sellingPriceAnnotation.allowedCurrencies())
				.map(Currency::getInstance)
				.toArray(Currency[]::new)
		);
		sellingPriceDefined = true;
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
		if (associatedDataDefined.containsKey(associatedDataName)) {
			throw new InvalidSchemaMutationException(
				"Entity model already defines associated data `" + associatedDataName + "` in `" + associatedDataDefined.get(associatedDataName) + "`!"
			);
		} else {
			associatedDataDefined.put(associatedDataName, definer);
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
				if (!associatedDataAnnotation.description().isBlank()) {
					whichIs.withDescription(associatedDataAnnotation.description());
				}
				if (!associatedDataAnnotation.deprecated().isBlank()) {
					whichIs.deprecated(associatedDataAnnotation.deprecated());
				}
				if (associatedDataAnnotation.nullable()) {
					whichIs.nullable();
				}
				if (associatedDataAnnotation.localized()) {
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
		@Nonnull CatalogSchemaBuilder catalogBuilder,
		@Nonnull EntitySchemaBuilder entityBuilder,
		@Nonnull Reference reference,
		@Nonnull String referenceName,
		@Nonnull String definer,
		@Nonnull Class<?> referenceType
	) {
		if (referencesDefined.containsKey(referenceName)) {
			throw new InvalidSchemaMutationException(
				"Entity model already defines reference `" + referenceName + "` in `" + referencesDefined.get(referenceName) + "`!"
			);
		} else {
			referencesDefined.put(referenceName, definer);
		}

		final Cardinality cardinality = getCardinality(reference, referenceType);
		final Class<?> examinedReferenceType = referenceType.isArray() ? referenceType.getComponentType() : referenceType;
		final TargetEntity targetEntity;
		final TargetEntity targetEntityGroup;

		final List<Entity> directEntity = reflectionLookup.getClassAnnotations(examinedReferenceType, Entity.class);
		if (directEntity.isEmpty()) {
			targetEntity = getTargetEntity(definer, ReferencedEntity.class, new TargetEntity(reference.entity(), reference.managed()), examinedReferenceType);
			targetEntityGroup = getTargetEntity(definer, ReferencedEntityGroup.class, new TargetEntity(reference.groupEntity(), reference.groupEntityManaged()), examinedReferenceType);
		} else {
			targetEntity = new TargetEntity(directEntity.get(0).name(), true);
			targetEntityGroup = new TargetEntity(reference.groupEntity(), reference.groupEntityManaged());
		}

		Assert.isTrue(
			!targetEntity.entityType().isBlank(),
			"Target entity type needs to be specified either by `@Reference` entity attribute or by `@ReferencedEntity` " +
				" and `@Entity` annotation on target class!"
		);

		final Map<String, String> relationAttributesDefined = new HashMap<>(32);
		final Consumer<ReferenceSchemaBuilder> referenceBuilder = whichIs -> {
			if (!targetEntityGroup.entityType().isBlank()) {
				if (targetEntityGroup.managed()) {
					whichIs.withGroupTypeRelatedToEntity(targetEntityGroup.entityType());
				} else {
					whichIs.withGroupType(targetEntityGroup.entityType());
				}
			}

			if (!reference.description().isBlank()) {
				whichIs.withDescription(reference.description());
			}
			if (!reference.deprecated().isBlank()) {
				whichIs.deprecated(reference.deprecated());
			}
			if (reference.indexed()) {
				whichIs.indexed();
			}
			if (reference.faceted()) {
				whichIs.faceted();
			}

			// we need also to analyze the target type for presence of control annotations in case the type is not
			// entity itself (i.e. is a relation mapping DTO)
			if (reflectionLookup.getClassAnnotations(examinedReferenceType, Entity.class).isEmpty()) {
				// if the target type is record
				if (examinedReferenceType.isRecord()) {
					// we need to scan it differently
					analyzeReferenceRecordComponents(catalogBuilder, examinedReferenceType, relationAttributesDefined, whichIs);
				} else {
					// otherwise we check methods and fields
					analyzeReferenceMethods(catalogBuilder, examinedReferenceType, relationAttributesDefined, whichIs);
					analyzeReferenceFields(catalogBuilder, examinedReferenceType, relationAttributesDefined, whichIs);
				}
			}
		};

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
	 * Method analyzes reference class methods for presence of control annotations.
	 */
	private void analyzeReferenceMethods(@Nonnull CatalogSchemaBuilder catalogBuilder, @Nonnull Class<?> referenceType, @Nonnull Map<String, String> relationAttributesDefined, @Nonnull ReferenceSchemaBuilder whichIs) {
		final Collection<Method> allGetters = reflectionLookup.findAllGetters(referenceType);
		for (Method getter : allGetters) {
			final Attribute attributeAnnotation = reflectionLookup.getAnnotationInstance(getter, Attribute.class);
			if (attributeAnnotation != null) {
				final String attributeName = getNameOrElse(
					attributeAnnotation.name(),
					() -> ReflectionLookup.getPropertyNameFromMethodName(getter.getName())
				);
				final Class<?> returnedType = verifyDataType(getReturnedType(modelClass, getter));
				@SuppressWarnings("unchecked") final Class<? extends Serializable> attributeType = (Class<? extends Serializable>) returnedType;
				final Serializable defaultValue = getter.isDefault() ? extractDefaultValue(referenceType, getter) : null;
				defineAttribute(
					catalogBuilder, whichIs, attributeAnnotation, attributeName,
					getter.toGenericString(), attributeType, defaultValue, relationAttributesDefined
				);
			}
		}
	}

	/**
	 * Method analyzes reference class fields for presence of control annotations.
	 */
	private void analyzeReferenceFields(@Nonnull CatalogSchemaBuilder catalogBuilder, @Nonnull Class<?> referenceType, @Nonnull Map<String, String> relationAttributesDefined, @Nonnull ReferenceSchemaBuilder whichIs) {
		final Map<Field, List<Annotation>> fields = reflectionLookup.getFields(referenceType);
		for (final Entry<Field, List<Annotation>> fieldEntry : fields.entrySet()) {
			for (final Annotation annotation : fieldEntry.getValue()) {
				if (annotation instanceof Attribute attributeAnnotation) {
					final String attributeName = getNameOrElse(
						attributeAnnotation.name(),
						() -> fieldEntry.getKey().getName()
					);
					final Class<?> fieldType = verifyDataType(extractFieldType(modelClass, fieldEntry.getKey()));
					@SuppressWarnings("unchecked") final Class<? extends Serializable> attributeType = (Class<? extends Serializable>) fieldType;
					final Serializable defaultValue = extractValueFromField(referenceType, fieldEntry.getKey());
					defineAttribute(
						catalogBuilder, whichIs, attributeAnnotation, attributeName,
						fieldEntry.getKey().toGenericString(), attributeType, defaultValue, relationAttributesDefined
					);
				}
			}
		}
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
					if (int.class.isAssignableFrom(targetEntityType) || Integer.class.isAssignableFrom(targetEntityType)) {
						targetEntity = new TargetEntity(recordComponent.getName(), false);
					} else {
						throw new InvalidSchemaMutationException(
							"Record component `" + recordComponent + "` on class `" + referenceType + "` returns " +
								"a type that cannot be used for referenced entity!"
						);
					}
				} else {
					throw new InvalidSchemaMutationException(
						"Multiple (" + recordComponents.stream().map(RecordComponent::toString).collect(Collectors.joining(", ")) + ") record components have annotation `@" + lookedUpAnnotation.getSimpleName() + "` specified. " +
							"We need to select one in order to infer target entity type, but we don't know which one."
					);
				}
			} else {
				final List<Method> getters = reflectionLookup.findAllGettersHavingAnnotation(targetType, lookedUpAnnotation);
				final List<Method> nonPrimaryKeyGetters = getters
					.stream()
					.filter(it -> !EvitaDataTypes.isSupportedTypeOrItsArray(extractReturnType(modelClass, it)))
					.toList();
				if (getters.isEmpty()) {
					final Map<Field, ? extends List<? extends Annotation>> fields = reflectionLookup.getFields(targetType, lookedUpAnnotation);
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
						if (int.class.isAssignableFrom(targetEntityType) || Integer.class.isAssignableFrom(targetEntityType)) {
							targetEntity = new TargetEntity(field.getName(), false);
						} else {
							throw new InvalidSchemaMutationException(
								"Field `" + field.toGenericString() + "` on class `" + referenceType + "` returns " +
									"a type that cannot be used for referenced entity!"
							);
						}
					} else {
						throw new InvalidSchemaMutationException(
							"Multiple (" + getters.stream().map(Method::toGenericString).collect(Collectors.joining(", ")) + ") fields have annotation `@" + lookedUpAnnotation.getSimpleName() + "` specified. " +
								"We need to select one in order to infer target entity type, but we don't know which one."
						);
					}
				} else if (nonPrimaryKeyGetters.size() == 1 || nonPrimaryKeyGetters.stream().map(it -> extractReturnType(modelClass, it)).distinct().count() == 1L) {
					final Method getter = nonPrimaryKeyGetters.get(0);
					final Class<?> targetEntityType = extractReturnType(modelClass, getter);
					targetEntity = getTargetEntity(targetEntityType, caller, lookedUpAnnotation, referenceType);
				} else if (getters.size() == 1 || nonPrimaryKeyGetters.stream().map(it -> extractReturnType(modelClass, it)).distinct().count() == 1L) {
					final Method getter = getters.get(0);
					final Class<?> targetEntityType = extractReturnType(modelClass, getter);
					if (int.class.isAssignableFrom(targetEntityType) || Integer.class.isAssignableFrom(targetEntityType)) {
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
						"Multiple (" + getters.stream().map(Method::toGenericString).collect(Collectors.joining(", ")) + ") getters have annotation `@" + lookedUpAnnotation.getSimpleName() + "` specified and have different cardinality. " +
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
		final List<Entity> targetEntityAnnotations = reflectionLookup.getClassAnnotations(targetEntityType, Entity.class);
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
			return ArrayUtils.mergeArrays(catalogMutations, entityMutations);
		}
	}

}
