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

package io.evitadb.dataType.data;

import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.exception.IncompleteDeserializationException;
import io.evitadb.dataType.exception.InconvertibleDataTypeException;
import io.evitadb.dataType.exception.InvalidDataObjectException;
import io.evitadb.dataType.exception.SerializableClassRequiredException;
import io.evitadb.dataType.exception.SerializationFailedException;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.function.TriFunction;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ReflectionLookup;
import io.evitadb.utils.ReflectionLookup.ArgumentKey;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static java.util.Optional.ofNullable;

/**
 * This class converts unknown POJO class data to Evita serialized form. This serialized form can be then converted
 * back again to the object of the original class. Deserialization is tolerant to minor change in the original class
 * definition - such as adding fields. Renaming or removals must be covered with annotations {@link DiscardedData} or
 * {@link RenamedData}. You can also use annotation {@link NonSerializedData} for marking getters that should not
 * be serialized by the automatic process.
 *
 * {@link IncompleteDeserializationException} will occur when there is data value that cannot be stored to the passed
 * POJO class to avoid data loss in case that entity gets read and then written again.
 *
 * Client code should ALWAYS contain test that serializes fully filled POJO and verifies that after deserialization
 * new POJO instance equals to the original one. Deserialization is controlled by the system, but there is no way
 * how to verify that serialization didn't omit some data values.
 *
 * BEWARE only non-null properties are persisted and recovered. Also, NULL values in the lists / arrays are not serialized
 * so you cannot rely on collection indexes when they may allow nulls inside them. All {@link EvitaDataTypes} are supported
 * and also List, Set, Map and array are supported for the sake of (de)serialization. Map key may be any of {@link EvitaDataTypes}
 * but nothing else, value can be arbitrary object.
 *
 * The entire conversion process works by:
 * 1. For serialization: Analyzing an object's properties using reflection and converting them to a generic structure
 * 2. For deserialization: Recreating the original object structure using reflection
 * 3. Handling various data types including primitives, collections, arrays, and nested objects
 * 4. Supporting class evolution through annotation-based property renaming and discarding
 *
 * @param <T> The type of the object being converted, which must be Serializable
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ComplexDataObjectConverter<T extends Serializable> {
	/**
	 * Constant for empty data items array used to represent empty collections in serialized form.
	 */
	private static final DataItem[] EMPTY_DATA_ITEMS = new DataItem[0];
	/**
	 * Utility for accessing object properties, methods, and constructors through reflection.
	 * Used for both serialization and deserialization processes.
	 */
	private final ReflectionLookup reflectionLookup;
	/**
	 * The source object being converted to serializable form.
	 * Will be null when this converter is used for deserialization.
	 */
	private final T container;
	/**
	 * The class of the object being converted.
	 * Used for both serialization (from container) and deserialization (to create new instances).
	 */
	private final Class<T> containerClass;

	/**
	 * Converts any serializable object into a form that Evita can store internally.
	 *
	 * This method guarantees returning one of the supported {@link EvitaDataTypes}:
	 * - If the container is already a {@link ComplexDataObject} or its array, it is returned as is
	 * - If the container is a supported Evita data type or its array, it is returned as is
	 * - Otherwise, it is converted to a {@link ComplexDataObject} automatically
	 *
	 * @param container The object to be converted to a serializable form
	 * @return A serialized representation of the container that Evita can store
	 * @throws SerializationFailedException If the conversion process fails
	 */
	public static Serializable getSerializableForm(@Nonnull Serializable container) {
		if (container instanceof ComplexDataObject || container instanceof ComplexDataObject[]) {
			return container;
		} else if (EvitaDataTypes.isSupportedTypeOrItsArray(container.getClass())) {
			return container;
		} else {
			return new ComplexDataObjectConverter<>(container).getSerializableForm();
		}
	}

	/**
	 * Deserializes a stored object back to its original form.
	 *
	 * This method handles the deserialization process:
	 * - If the container is already an instance of the requested class, it is returned as is
	 * - If the container is a supported Evita data type, it is cast to the requested type
	 * - Otherwise, a new converter is created to handle complex deserialization
	 *
	 * @param <T>              The type to convert the object to
	 * @param container        The serialized object to be converted
	 * @param requestedClass   The class of the target type
	 * @param reflectionLookup The reflection utility to use for deserialization
	 * @return The deserialized object of the requested type
	 * @throws InvalidDataObjectException         When the deserialization process fails
	 * @throws IncompleteDeserializationException When some data cannot be deserialized
	 */
	@Nonnull
	public static <T extends Serializable> T getOriginalForm(
		@Nonnull Serializable container,
		@Nonnull Class<T> requestedClass,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		if (requestedClass.isInstance(container)) {
			//noinspection unchecked
			return (T) container;
		} else {
			//noinspection unchecked
			return EvitaDataTypes.isSupportedType(container.getClass()) ?
				(T) container :
				new ComplexDataObjectConverter<>(requestedClass, reflectionLookup).getOriginalForm(container);
		}
	}

	/**
	 * Instantiates a container object using a constructor that best matches the available properties.
	 * This method implements a multi-stage constructor resolution process that handles:
	 * 1. Finding a constructor that directly matches property names
	 * 2. Handling discarded and renamed properties through annotations
	 * 3. Finding a partial match constructor if exact match isn't possible
	 * 4. Falling back to default constructor as a last resort
	 *
	 * @param <X>                        The type of container to instantiate
	 * @param containerClass             The class to instantiate
	 * @param reflectionLookup           Reflection utility for finding constructors and annotations
	 * @param extractionCtx              Context tracking the extraction process
	 * @param propertyNamesWithoutSetter Properties needing constructor initialization (not settable via methods)
	 * @param allPropertyNames           All available property names in the serialized form
	 * @param argumentFetcher            Function to fetch and convert values for constructor arguments
	 * @return A new instance of the container
	 * @throws InvalidDataObjectException If no suitable constructor can be found or instantiation fails
	 */
	@Nonnull
	private static <X> X instantiateContainerByMatchingConstructor(
		@Nonnull Class<X> containerClass,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull ExtractionContext extractionCtx,
		@Nonnull Set<ArgumentKey> propertyNamesWithoutSetter,
		@Nonnull Set<ArgumentKey> allPropertyNames,
		@Nonnull TriFunction<String, Class<?>, Type, Object> argumentFetcher
	) {
		// Step 1: Check for properties that should be discarded (marked with @DiscardedData)
		final Set<String> discards = identifyPropertyDiscard(
			containerClass, reflectionLookup, extractionCtx, propertyNamesWithoutSetter
		);

		Map<String, String> renames = null;
		Constructor<X> appropriateConstructor = null;

		try {
			// Step 2: Try to find a constructor that directly matches available properties
			appropriateConstructor = reflectionLookup.findConstructor(containerClass, propertyNamesWithoutSetter);
		} catch (IllegalArgumentException ex) {
			// Step 3: If exact match fails, look for renamed properties (via @RenamedData)
			// We do this in catch block for performance - most classes won't need renames
			renames = identifyPropertyReplacement(containerClass, reflectionLookup, propertyNamesWithoutSetter);

			// Step 4: If we found renamed or discarded properties, try again with updated property set
			if (!(renames.isEmpty() && discards.isEmpty())) {
				appropriateConstructor = instantiateContainerByFallbackConstructorWithRenamedAndDiscardedArguments(
					containerClass, reflectionLookup, propertyNamesWithoutSetter
				);
			}

			// Step 5: If still no match, try to find any constructor that accepts a subset of available properties
			if (appropriateConstructor == null) {
				appropriateConstructor = reflectionLookup.findAnyMatchingConstructor(containerClass, allPropertyNames);

				// Step 6: Last resort, use default no-arg constructor if available
				if (appropriateConstructor == null) {
					appropriateConstructor = reflectionLookup.findConstructor(containerClass, Collections.emptySet());
				}
			}
		}

		// Step 7: Create the actual instance with appropriate constructor arguments
		final X resultContainer;
		try {
			// Prepare arguments for the selected constructor
			final Object[] initArgs = getInitArgs(argumentFetcher, renames, appropriateConstructor);

			// Create the instance
			resultContainer = appropriateConstructor.newInstance(initArgs);
		} catch (IllegalArgumentException | InstantiationException | IllegalAccessException |
		         InvocationTargetException e) {
			throw new InvalidDataObjectException(
				"Error invoking constructor " + appropriateConstructor.toGenericString() +
					" on class: " + containerClass
			);
		}
		return resultContainer;
	}

	/**
	 * Identifies properties that should be discarded during deserialization based on @DiscardedData annotation.
	 * Removed properties are tracked in the extraction context and removed from the set of properties
	 * needing constructor initialization.
	 *
	 * @param <X>                        The type of container class
	 * @param containerClass             The class to check for @DiscardedData annotation
	 * @param reflectionLookup           Reflection utility for finding annotations
	 * @param extractionCtx              Context tracking the extraction process
	 * @param propertyNamesWithoutSetter Properties needing constructor initialization (modified by this method)
	 * @return Set of property names marked for discard
	 */
	@Nonnull
	private static <X> Set<String> identifyPropertyDiscard(
		@Nonnull Class<X> containerClass,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull ExtractionContext extractionCtx,
		@Nonnull Set<ArgumentKey> propertyNamesWithoutSetter
	) {
		// Look for @DiscardedData annotation on the class
		final DiscardedData discardedData = reflectionLookup.getClassAnnotation(containerClass, DiscardedData.class);

		// Return empty set if no discard information is present
		if (discardedData == null) {
			return Collections.emptySet();
		} else {
			// Process each discarded property
			final Set<String> discardedProperties = new HashSet<>(discardedData.value().length);
			for (String discardedProperty : discardedData.value()) {
				// Add to the set of discarded properties
				discardedProperties.add(discardedProperty);

				// Register in the extraction context to prevent "data loss" warnings
				extractionCtx.addDiscardedProperty(discardedProperty);

				// Remove from the set of properties to consider for constructor matching
				propertyNamesWithoutSetter.remove(new ArgumentKey(discardedProperty, Object.class));
			}
			return discardedProperties;
		}
	}

	/**
	 * Attempts to find a constructor that matches the property set after handling renames and discards.
	 * This is a fallback mechanism when the initial constructor search fails.
	 *
	 * @param <X>                        The type of container class
	 * @param containerClass             The class to find a constructor for
	 * @param reflectionLookup           Reflection utility for finding constructors
	 * @param propertyNamesWithoutSetter Properties needing constructor initialization (now with renames applied)
	 * @return A matching constructor if found, null otherwise
	 */
	@Nullable
	private static <X> Constructor<X> instantiateContainerByFallbackConstructorWithRenamedAndDiscardedArguments(
		@Nonnull Class<X> containerClass,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Set<ArgumentKey> propertyNamesWithoutSetter
	) {
		try {
			// Try to find constructor with the updated property names (after renames/discards)
			return reflectionLookup.findConstructor(
				containerClass,
				propertyNamesWithoutSetter
			);
		} catch (IllegalArgumentException ex) {
			// Return null if no matching constructor found
			return null;
		}
	}

	/**
	 * Creates an array of constructor argument values by applying the argument fetcher to each parameter.
	 * Handles property renaming by remapping parameter names based on the renames map.
	 *
	 * @param <X>                    The type of container class
	 * @param argumentFetcher        Function to fetch values for constructor arguments
	 * @param renames                Map of renamed properties (new name → original name in serialized data)
	 * @param appropriateConstructor The constructor to prepare arguments for
	 * @return Array of constructor argument values
	 */
	@Nonnull
	private static <X> Object[] getInitArgs(
		@Nonnull TriFunction<String, Class<?>, Type, Object> argumentFetcher,
		@Nullable Map<String, String> renames,
		@Nonnull Constructor<X> appropriateConstructor
	) {
		final Map<String, String> finalRenames = renames;

		// Process each constructor parameter
		return Arrays.stream(appropriateConstructor.getParameters())
			.map(it -> getConstructorArgument(argumentFetcher, it, finalRenames))
			.toArray();
	}


	/**
	 * Retrieves the constructor argument value by applying the provided argument fetcher.
	 * Handles property renaming by remapping parameter names based on the renames map.
	 *
	 * @param argumentFetcher A function to fetch and convert values for constructor arguments.
	 * @param parameter       The constructor parameter for which the argument value is being retrieved.
	 * @param renames         A map of renamed properties (new name → original name in serialized data), or null if no renames exist.
	 * @return The fetched and converted argument value, or null if the value cannot be retrieved.
	 */
	@Nullable
	private static Object getConstructorArgument(
		@Nonnull TriFunction<String, Class<?>, Type, Object> argumentFetcher,
		@Nonnull Parameter parameter,
		@Nullable Map<String, String> renames
	) {
		// Apply rename mapping if available, otherwise use parameter name directly
		String lookupName = renames == null ?
			parameter.getName() :
			renames.getOrDefault(parameter.getName(), parameter.getName());

		// Fetch and convert the argument value
		return argumentFetcher.apply(
			lookupName,
			parameter.getType(),
			parameter.getParameterizedType()
		);
	}

	/**
	 * Identifies properties that have been renamed through @RenamedData annotations.
	 * This allows for class evolution by mapping old property names in serialized data
	 * to new property names in the current class definition.
	 *
	 * @param <X>                        The type of container class
	 * @param containerClass             The class to check for @RenamedData annotations
	 * @param reflectionLookup           Reflection utility for finding annotations
	 * @param propertyNamesWithoutSetter Properties needing constructor initialization (modified by this method)
	 * @return Map of current property names to their original names in serialized data
	 */
	@Nonnull
	private static <X> Map<String, String> identifyPropertyReplacement(
		@Nonnull Class<X> containerClass,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Set<ArgumentKey> propertyNamesWithoutSetter
	) {
		// Create a map to hold the property renames (current name → original name)
		final Map<String, String> renames = createHashMap(propertyNamesWithoutSetter.size());

		// Get fields with @RenamedData annotations
		final Map<Field, List<RenamedData>> renamedFields = reflectionLookup.getFields(containerClass, RenamedData.class);

		// Process each field with rename annotations
		renamedFields
			.entrySet()
			.stream()
			// For each field, find first matching rename annotation
			.map(fieldEntry ->
				fieldEntry.getValue()  // Get all RenamedData annotations on this field
					.stream()
					.flatMap(renamedAnnotation -> Arrays.stream(renamedAnnotation.value()))  // Get all original names
					// Filter to only include original names that exist in our serialized data
					.filter(renamedAnnotationValue ->
						propertyNamesWithoutSetter.contains(new ArgumentKey(renamedAnnotationValue, Object.class)))
					// Create an ArgumentKeyWithRename to track the mapping
					.map(renamedAnnotationValue ->
						new ArgumentKeyWithRename(
							fieldEntry.getKey().getName(),      // Current property name (from field)
							fieldEntry.getKey().getType(),      // Property type
							renamedAnnotationValue              // Original property name
						)
					)
					.findFirst()  // We only need one match per field
					.orElse(null)
			)
			// Filter out fields that didn't match any properties
			.filter(Objects::nonNull)
			// Update the property set and rename mapping
			.forEach(it -> {
				// Remove the original property name from the set
				propertyNamesWithoutSetter.remove(new ArgumentKey(it.getOriginalProperty(), it.getType()));

				// Add the renamed property to the set
				propertyNamesWithoutSetter.add(it);

				// Record the mapping (current name → original name)
				renames.put(it.getName(), it.getOriginalProperty());
			});

		return renames;
	}

	/**
	 * Verifies that the specified class is either primitive or implements the {@link Serializable} interface.
	 *
	 * @param theClass the class to check for serializability; must not be null
	 * @throws IllegalArgumentException if the specified class does not meet the requirements
	 */
	private static void assertSerializable(@Nonnull Class<?> theClass) {
		Assert.isTrue(
			theClass.isPrimitive() || Serializable.class.isAssignableFrom(theClass),
			() -> new SerializableClassRequiredException(theClass)
		);
	}

	/**
	 * Creates a converter instance for serialization of the passed object.
	 * This constructor is used when converting from a Java object to Evita's serialized form.
	 *
	 * @param container The object to be serialized
	 */
	public ComplexDataObjectConverter(@Nonnull T container) {
		this.reflectionLookup = new ReflectionLookup(ReflectionCachingBehaviour.NO_CACHE);
		this.container = container;
		//noinspection unchecked
		this.containerClass = (Class<T>) container.getClass();
	}

	/**
	 * Creates a converter instance for deserialization to the specified class.
	 * This constructor is used when converting from Evita's serialized form back to a Java object.
	 *
	 * @param containerClass   The target class for deserialization
	 * @param reflectionLookup The reflection utility to use for deserialization
	 * @throws InvalidDataObjectException when containerClass doesn't have an appropriate constructor
	 */
	public ComplexDataObjectConverter(@Nonnull Class<T> containerClass, @Nonnull ReflectionLookup reflectionLookup) throws InvalidDataObjectException {
		this.reflectionLookup = reflectionLookup;
		this.container = null;
		this.containerClass = containerClass;
	}

	/**
	 * Converts the contained object to a serializable form that Evita can store.
	 *
	 * This instance method performs the actual conversion:
	 * - If the container is already a {@link ComplexDataObject} or its array, it is returned as is
	 * - If the container is a supported Evita data type, it is returned as is
	 * - Otherwise, it is converted to a generic {@link ComplexDataObject} structure
	 *
	 * @return A serialized representation of the container
	 * @throws SerializationFailedException If the conversion process fails
	 */
	public Serializable getSerializableForm() {
		if (this.container instanceof ComplexDataObject || this.container instanceof ComplexDataObject[]) {
			return this.container;
		} else {
			final Class<?> type = this.container.getClass().isArray() ?
				this.container.getClass().getComponentType() : this.container.getClass();
			return EvitaDataTypes.isSupportedType(type) ? this.container : convertToGenericType(this.container);
		}
	}

	/**
	 * Deserializes the provided serialized form back to the original object type.
	 *
	 * This method handles the complex deserialization process:
	 * - If the serialized form is already compatible with the target type, it is returned directly
	 * - For arrays, it reconstructs the array with properly deserialized elements
	 * - For complex objects, it navigates the structure and builds the target object
	 * - Tracks property extraction to ensure no data is lost during deserialization
	 *
	 * @param serializedForm The Evita serialized form to convert
	 * @return The deserialized object of type T
	 * @throws IncompleteDeserializationException When some properties cannot be deserialized
	 * @throws InconvertibleDataTypeException     When data types are incompatible
	 * @throws SerializationFailedException       When deserialization fails for other reasons
	 */
	@Nonnull
	public T getOriginalForm(@Nonnull Serializable serializedForm) {
		Assert.notNull(this.reflectionLookup, "Reflection lookup required!");
		if (serializedForm instanceof Object[] && EvitaDataTypes.isSupportedType(serializedForm.getClass().getComponentType())) {
			//noinspection unchecked
			return (T) serializedForm;
		} else if (ComplexDataObject.class.isAssignableFrom(this.containerClass) && serializedForm instanceof ComplexDataObject) {
			//noinspection unchecked
			return (T) serializedForm;
		} else if (EvitaDataTypes.isSupportedType(serializedForm.getClass())) {
			//noinspection unchecked
			return (T) serializedForm;
		} else {
			//noinspection ConstantConditions
			final ComplexDataObject complexDataObject = (ComplexDataObject) serializedForm;
			final ExtractionContext extractionCtx = new ExtractionContext();
			final T result;
			// we need to take care only of array - other collection types, such as List, Set, Map cannot be used as
			// top level containers because generics cannot be propagated to the methods due to JVM limitations
			final DataItem root = complexDataObject.root();
			if (this.containerClass.isArray()) {
				try {
					//noinspection unchecked
					result = (T) deserializeArray(this.reflectionLookup, this.containerClass, root, extractionCtx);
				} catch (IllegalAccessException | InvocationTargetException e) {
					throw new SerializationFailedException(
						"Failed to deserialize root array.", e
					);
				}
			} else if (root instanceof DataItemMap dataItemMap) {
				// usually there will be single top level POJO object
				result = extractData(this.containerClass, dataItemMap, this.reflectionLookup, extractionCtx);
			} else {
				throw new InconvertibleDataTypeException(this.containerClass, root.getClass());
			}
			// check whether all data has been deserialized or marked as discarded
			if (!extractionCtx.getNotExtractedProperties().isEmpty()) {
				// we need to take discarded properties into an account - so error might not occur
				throw new IncompleteDeserializationException(extractionCtx.getNotExtractedProperties());
			}
			return result;
		}
	}

	/**
	 * Converts the provided container object to a generic type representation in the form of a ComplexDataObject.
	 * Handles both array-based and non-array-based container objects.
	 *
	 * @param container the container object to be converted, must not be null
	 * @return a ComplexDataObject representation of the provided container, never null
	 * @throws IllegalArgumentException if no usable properties can be found in the container
	 */
	@Nonnull
	private ComplexDataObject convertToGenericType(@Nonnull T container) {
		final DataItem rootNode;
		if (container instanceof final Object[] containerArray) {
			final DataItem[] dataItems = new DataItem[containerArray.length];
			for (int i = 0; i < containerArray.length; i++) {
				final Object containerItem = containerArray[i];
				dataItems[i] = collectData((Serializable) containerItem, this.reflectionLookup, "[" + i + "].");
			}
			final Class<?> arrayType = container.getClass().getComponentType();
			assertSerializable(arrayType);
			rootNode = new DataItemArray(dataItems);
		} else {
			rootNode = collectData(container, this.reflectionLookup, "");
		}

		final ComplexDataObject result = new ComplexDataObject(rootNode);
		Assert.isTrue(
			!result.isEmpty(),
			"No usable properties found on " + container.getClass() + ". This is probably a problem."
		);
		return result;
	}

	/**
	 * Collects property data from an object using reflection.
	 * This method handles both Java Records and regular classes with getters.
	 *
	 * @param container        The object to extract properties from
	 * @param reflectionLookup Reflection utility to find methods and properties
	 * @param propertyPrefix   Prefix for property path (used for error messages and nested property path construction)
	 * @return A DataItemMap containing all serialized properties
	 * @throws SerializationFailedException If any property access fails
	 */
	private DataItem collectData(
		@Nonnull Serializable container,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull String propertyPrefix
	) {
		final DataItemMap result;
		final Class<? extends Serializable> containerClass = container.getClass();

		if (containerClass.isRecord()) {
			// Special handling for Java Records - use direct component accessors
			final Map<String, DataItem> dataItems = createHashMap(containerClass.getRecordComponents().length);
			for (RecordComponent recordComponent : containerClass.getRecordComponents()) {
				// Skip properties marked with NonSerializedData annotation
				if (recordComponent.getAnnotation(NonSerializedData.class) == null) {
					try {
						// Access the record component value via its accessor method
						final Object propertyValue = recordComponent.getAccessor().invoke(container);
						final Class<?> propertyClass = recordComponent.getType();
						final Type propertyType = recordComponent.getGenericType();
						final String propertyName = recordComponent.getName();

						// Collect the property and add it to the result map
						dataItems.put(
							propertyName,
							collectDataForProperty(
								propertyPrefix + propertyName, propertyValue,
								propertyClass, propertyType, reflectionLookup
							)
						);
					} catch (IllegalAccessException | InvocationTargetException e) {
						throw new SerializationFailedException(
							"Failed to retrieve value from getter: " + recordComponent.getAccessor().toGenericString(), e
						);
					}
				}
			}
			result = new DataItemMap(dataItems);
		} else {
			// Standard Java class - use getters with matching setters or constructor args
			final Collection<Method> getters = reflectionLookup.findAllGettersHavingCorrespondingSetterOrConstructorArgument(containerClass);
			final Map<String, DataItem> dataItems = createHashMap(getters.size());

			for (Method getter : getters) {
				// Skip properties marked with NonSerializedData annotation
				if (reflectionLookup.getAnnotationInstanceForProperty(getter, NonSerializedData.class) == null) {
					try {
						// Extract property value and metadata using reflection
						final Object propertyValue = getter.invoke(container);
						final Class<?> propertyClass = getter.getReturnType();
						final Type propertyType = getter.getGenericReturnType();
						final String propertyName = ReflectionLookup.getPropertyNameFromMethodName(getter.getName());

						// Collect the property and add it to the result map
						dataItems.put(
							propertyName,
							collectDataForProperty(
								propertyPrefix + propertyName, propertyValue,
								propertyClass, propertyType, reflectionLookup
							)
						);
					} catch (IllegalAccessException | InvocationTargetException e) {
						throw new SerializationFailedException(
							"Failed to retrieve value from getter: " + getter.toGenericString(), e
						);
					}
				}
			}
			result = new DataItemMap(dataItems);
		}
		return result;
	}

	/**
	 * Converts a property value to the appropriate DataItem representation based on its type.
	 * This method handles the type-specific serialization logic for different Java types.
	 *
	 * @param propertyName     The name of the property being processed
	 * @param propertyValue    The value of the property (could be null)
	 * @param propertyClass    The declared class of the property
	 * @param propertyType     The generic type of the property (for collections)
	 * @param reflectionLookup Reflection utility for accessing type information
	 * @return A DataItem representation of the property, or null if the property value is null
	 * @throws SerializationFailedException If the property cannot be serialized
	 */
	@Nullable
	private DataItem collectDataForProperty(
		@Nonnull String propertyName,
		@Nullable Object propertyValue,
		@Nonnull Class<?> propertyClass,
		@Nonnull Type propertyType,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		// Handle null values consistently across all types
		if (propertyValue == null) {
			// For collection types, we still need to check component type serialization
			if (Set.class.isAssignableFrom(propertyClass) ||
				List.class.isAssignableFrom(propertyClass)) {
				final Class<? extends Serializable> valueType = reflectionLookup.extractGenericType(propertyType, 0);
				assertSerializable(valueType);
			} else if (propertyClass.isArray()) {
				final Class<?> arrayType = propertyClass.getComponentType();
				assertSerializable(arrayType);
			} else if (!propertyClass.isPrimitive() && !propertyClass.isEnum() &&
				!EvitaDataTypes.isSupportedType(propertyClass) &&
				!propertyClass.getPackageName().startsWith("java.")) {
				// For complex objects, ensure they're serializable even if null
				assertSerializable(propertyClass);
			}
			return null;
		}

		// Handle supported Evita types directly
		if (EvitaDataTypes.isSupportedType(propertyClass)) {
			return new DataItemValue((Serializable) propertyValue);
		}
		// Handle enums by converting to string
		else if (propertyClass.isEnum()) {
			return new DataItemValue(propertyValue.toString());
		}
		// Handle Sets - checking if empty or need to serialize elements
		else if (Set.class.isAssignableFrom(propertyClass)) {
			final Class<? extends Serializable> valueType = reflectionLookup.extractGenericType(propertyType, 0);
			assertSerializable(valueType);

			if (((Set<?>) propertyValue).isEmpty()) {
				return new DataItemArray(EMPTY_DATA_ITEMS);
			} else {
				return serializeSet(propertyName, propertyType, reflectionLookup, (Set<?>) propertyValue);
			}
		}
		// Handle Lists - checking if empty or need to serialize elements
		else if (List.class.isAssignableFrom(propertyClass)) {
			final Class<? extends Serializable> valueType = reflectionLookup.extractGenericType(propertyType, 0);
			assertSerializable(valueType);

			if (((List<?>) propertyValue).isEmpty()) {
				return new DataItemArray(EMPTY_DATA_ITEMS);
			} else {
				return serializeList(propertyName, propertyType, reflectionLookup, (List<?>) propertyValue);
			}
		}
		// Handle Arrays - checking if empty or need to serialize elements
		else if (propertyClass.isArray()) {
			final Class<?> arrayType = propertyClass.getComponentType();
			assertSerializable(arrayType);

			if (Array.getLength(propertyValue) == 0) {
				return new DataItemArray(EMPTY_DATA_ITEMS);
			} else {
				//noinspection unchecked
				return serializePrimitiveArray(propertyName, (Class<? extends Serializable>) arrayType, reflectionLookup, propertyValue);
			}
		}
		// Handle Maps - checking if empty or need to serialize entries
		else if (Map.class.isAssignableFrom(propertyClass)) {
			if (((Map<?, ?>) propertyValue).isEmpty()) {
				return DataItemMap.EMPTY;
			} else {
				return serializeMap(
					propertyName, propertyType, reflectionLookup, (Map<?, ?>) propertyValue
				);
			}
		}
		// Special handling for floating point types to ensure precision
		// by converting to BigDecimal for storage
		else if (float.class.equals(propertyClass)) {
			return new DataItemValue(new BigDecimal(Float.toString((float) propertyValue)));
		} else if (Float.class.equals(propertyClass)) {
			return new DataItemValue(new BigDecimal(propertyValue.toString()));
		} else if (double.class.equals(propertyClass)) {
			return new DataItemValue(new BigDecimal(Double.toString((double) propertyValue)));
		} else if (Double.class.equals(propertyClass)) {
			return new DataItemValue(new BigDecimal(propertyValue.toString()));
		}
		// Reject unsupported Java standard library types
		else if (propertyClass.getPackageName().startsWith("java.")) {
			throw new SerializationFailedException("Unsupported data type " + propertyClass + ": " + propertyValue);
		}
		// Handle nested custom objects recursively
		else {
			return collectData((Serializable) propertyValue, reflectionLookup, propertyName);
		}
	}

	/**
	 * Serializes a given map into a {@link DataItemMap} structure. The method ensures that all keys
	 * in the map are serializable and converts them to a supported type if necessary. It serializes both
	 * the map's keys and values, using the provided property name, type, and reflection lookup context.
	 *
	 * @param propertyName the name of the property being serialized
	 * @param propertyType the type of the property being serialized
	 * @param reflectionLookup helper object for extracting generic type information
	 * @param propertyValue the map to be serialized
	 * @return a {@link DataItemMap} containing serialized representations of the input map's keys and values
	 */
	@Nonnull
	private DataItemMap serializeMap(
		@Nonnull String propertyName,
		@Nonnull Type propertyType,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Map<?, ?> propertyValue
	) {
		final Map<String, DataItem> dataItems = createHashMap(propertyValue.size());
		for (Entry<?, ?> entry : propertyValue.entrySet()) {
			final Object itemKey = entry.getKey();
			Assert.isTrue(
				itemKey instanceof Serializable,
				() -> new SerializationFailedException("Map key " + itemKey + " in property " + propertyName + " is not serializable!")
			);
			final Serializable serializableItemKey = EvitaDataTypes.toSupportedType((Serializable) itemKey);
			final Object itemValue = entry.getValue();
			final Class<?> itemClass = itemValue == null ?
				reflectionLookup.extractGenericType(propertyType, 1) : itemValue.getClass();

			if (itemValue == null) {
				final Class<? extends Serializable> mapValueType = reflectionLookup.extractGenericType(propertyType, 1);
				dataItems.put(
					serializableItemKey instanceof String s ? s : EvitaDataTypes.formatValue(serializableItemKey),
					new DataItemValue(mapValueType)
				);
			} else if (EvitaDataTypes.isSupportedType(itemClass)) {
				dataItems.put(
					serializableItemKey instanceof String s ? s : EvitaDataTypes.formatValue(serializableItemKey),
					new DataItemValue((Serializable) itemValue)
				);
			} else {
				dataItems.put(
					serializableItemKey instanceof String s ? s : EvitaDataTypes.formatValue(serializableItemKey),
					collectData(
						(Serializable) itemValue, reflectionLookup,
						propertyName + "[" + EvitaDataTypes.formatValue(serializableItemKey) + "]" + "."
					)
				);
			}
		}
		return new DataItemMap(dataItems);
	}

	/**
	 * Serializes a primitive array into a {@link DataItemArray}. This method processes the elements of the array,
	 * converting each item into a {@link DataItem}. If the item is null, it will be represented as null in the resulting array.
	 * If the item's type is supported, it will be converted into a {@link DataItemValue}.
	 * Otherwise, the method recursively collects data for serialization.
	 *
	 * @param propertyName the name of the property being serialized, used to construct the hierarchical path
	 * @param propertyClass the class type of the property elements, used to determine serialization rules
	 * @param reflectionLookup helper instance for reflection tasks, facilitating serialization of complex objects
	 * @param propertyValue the primitive array to be serialized into a {@link DataItemArray}
	 * @return the {@link DataItemArray} representation of the serialized primitive array
	 */
	@Nonnull
	private DataItemArray serializePrimitiveArray(
		@Nonnull String propertyName,
		@Nonnull Class<? extends Serializable> propertyClass,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Object propertyValue
	) {
		final DataItem[] dataItems = new DataItem[Array.getLength(propertyValue)];
		for (int i = 0; i < Array.getLength(propertyValue); i++) {
			final Object itemValue = Array.get(propertyValue, i);
			if (itemValue == null) {
				dataItems[i] = null;
			} else if (EvitaDataTypes.isSupportedType(propertyClass)) {
				dataItems[i] = new DataItemValue((Serializable) itemValue);
			} else {
				dataItems[i] = collectData((Serializable) itemValue, reflectionLookup, propertyName + "[" + i + "]" + ".");
			}
		}
		return new DataItemArray(dataItems);
	}

	/**
	 * Serializes a list of property values into a {@link DataItemArray}.
	 *
	 * @param propertyName the name of the property to be serialized
	 * @param propertyType the type of the property being serialized
	 * @param reflectionLookup utility to introspect and extract type information
	 * @param propertyValue the list of property values to be serialized
	 * @return a {@link DataItemArray} containing the serialized representation of the property values
	 */
	@Nonnull
	private DataItemArray serializeList(
		@Nonnull String propertyName,
		@Nonnull Type propertyType,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull List<?> propertyValue
	) {
		final DataItem[] dataItems = new DataItem[propertyValue.size()];
		for (int i = 0; i < propertyValue.size(); i++) {
			final Object itemValue = propertyValue.get(i);
			final Class<?> itemClass = itemValue == null ?
				reflectionLookup.extractGenericType(propertyType, 1) : itemValue.getClass();

			if (itemValue == null) {
				dataItems[i] = null;
			} else if (EvitaDataTypes.isSupportedType(itemClass)) {
				dataItems[i] = new DataItemValue((Serializable) itemValue);
			} else {
				dataItems[i] = collectData((Serializable) itemValue, reflectionLookup, propertyName + "[" + i + "]" + ".");
			}
		}
		return new DataItemArray(dataItems);
	}

	/**
	 * Serializes a set of property values into a {@link DataItemArray}.
	 *
	 * @param propertyName the name of the property being serialized
	 * @param propertyType the type of the property being serialized
	 * @param reflectionLookup the reflection utility used to extract type metadata
	 * @param propertyValue the set of property values to be serialized
	 * @return a {@link DataItemArray} containing the serialized representation of the property values
	 * @throws NullPointerException if any of the parameters are null
	 */
	@Nonnull
	private DataItemArray serializeSet(
		@Nonnull String propertyName,
		@Nonnull Type propertyType,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Set<?> propertyValue
	) {
		final DataItem[] dataItems = new DataItem[propertyValue.size()];
		final Iterator<?> it = propertyValue.iterator();
		int i = 0;
		while (it.hasNext()) {
			final Object itemValue = it.next();
			final Class<?> itemClass = itemValue == null ?
				reflectionLookup.extractGenericType(propertyType, 1) : itemValue.getClass();
			if (itemValue == null) {
				dataItems[i++] = null;
			} else if (EvitaDataTypes.isSupportedType(itemClass)) {
				dataItems[i++] = new DataItemValue((Serializable) itemValue);
			} else {
				dataItems[i++] = collectData((Serializable) itemValue, reflectionLookup, propertyName + "[" + i + "]" + ".");
			}
		}
		return new DataItemArray(dataItems);
	}

	/**
	 * Extracts data from a serialized form into a new instance of the container class.
	 * This is a key method in the deserialization process that handles:
	 * 1. Identifying available properties in the target class
	 * 2. Finding properties in the serialized data that don't have matching fields in the target class
	 * 3. Instantiating the target object with appropriate constructor arguments
	 * 4. Populating remaining properties via setters
	 * 5. Tracking property extraction to detect data loss
	 *
	 * @param <X>              The type of container to create and populate
	 * @param containerClass   The class of the container to instantiate
	 * @param serializedForm   The serialized data to extract properties from
	 * @param reflectionLookup Reflection utility for accessing type information
	 * @param extractionCtx    Context tracking the extraction process
	 * @return A new instance of the container with properties populated from serializedForm
	 * @throws InvalidDataObjectException   If the container cannot be instantiated
	 * @throws SerializationFailedException If properties cannot be accessed or set
	 */
	private <X> X extractData(
		@Nonnull Class<X> containerClass,
		@Nonnull DataItemMap serializedForm,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull ExtractionContext extractionCtx
	) {
		final Set<String> propertyNames;
		final Collection<Method> writers;

		// Handle Java Records differently from regular classes
		if (containerClass.isRecord()) {
			// Records have no setters, we'll use the constructor only
			writers = Collections.emptyList();
			// Extract property names from record components
			propertyNames = Arrays.stream(containerClass.getRecordComponents())
				.map(RecordComponent::getName)
				.collect(Collectors.toSet());
		} else {
			// Regular classes use setters for property population
			writers = reflectionLookup.findAllSetters(containerClass);
			// Extract property names from setter methods
			propertyNames = writers
				.stream()
				.map(it -> ReflectionLookup.getPropertyNameFromMethodName(it.getName()))
				.collect(Collectors.toSet());
		}

		// Convert serialized property names to ArgumentKey objects for matching with constructors
		final Set<ArgumentKey> allPropertyNames = serializedForm.getPropertyNames()
			.stream()
			.map(it -> new ArgumentKey(it, Object.class))
			.collect(Collectors.toSet());

		// Identify properties in the serialized form that don't have corresponding properties in the target class
		final Set<ArgumentKey> unknownProperties = allPropertyNames
			.stream()
			.filter(it -> !propertyNames.contains(it.getName()))
			.collect(Collectors.toSet());

		// Start tracking property extraction to detect and handle any data loss
		extractionCtx.pushPropertyNames(serializedForm.getPropertyNames());

		// Instantiate the target object using constructor arguments from the serialized form
		final X resultContainer = instantiateContainerByMatchingConstructor(
			containerClass, reflectionLookup, extractionCtx, unknownProperties, allPropertyNames,
			// Function to extract and register values from the serialized form for constructor arguments
			(propertyName, requiredType, requiredGenericType) -> extractValueAndRegisterIt(
				serializedForm, reflectionLookup, extractionCtx, propertyName, requiredType, requiredGenericType
			)
		);

		// For each setter in a regular class (not a record), populate properties not handled by constructor
		for (Method setter : writers) {
			// Skip properties marked with @NonSerializedData
			if (reflectionLookup.getAnnotationInstanceForProperty(setter, NonSerializedData.class) != null) {
				continue;
			}
			// Extract data and populate property via setter
			extractDataToSetter((Serializable) resultContainer, serializedForm, reflectionLookup, extractionCtx, setter);
		}

		// Finish tracking property extraction and check for any unextracted properties
		extractionCtx.popPropertyNames();

		return resultContainer;
	}

	/**
	 * Extracts a value from the serialized form and registers it as extracted in the context.
	 * This method is used when populating constructor arguments during object instantiation.
	 *
	 * @param serializedForm      The serialized data to extract the value from
	 * @param reflectionLookup    Reflection utility for type information
	 * @param extractionCtx       Context tracking the extraction process
	 * @param propertyName        The name of the property to extract
	 * @param requiredType        The target type for the extracted value
	 * @param requiredGenericType The generic type information (for collections)
	 * @return The extracted value, converted to the required type, or null if not found
	 * @throws SerializationFailedException If extraction or conversion fails
	 */
	@Nullable
	private Object extractValueAndRegisterIt(
		@Nonnull DataItemMap serializedForm,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull ExtractionContext extractionCtx,
		@Nonnull String propertyName,
		@Nonnull Class<?> requiredType,
		@Nonnull Type requiredGenericType
	) {
		try {
			// Register that this property is being extracted to avoid data loss detection
			extractionCtx.addExtractedProperty(propertyName);

			// Extract the actual value from the map and convert it to the required type
			return extractMapItem(
				serializedForm, reflectionLookup, propertyName, requiredType, requiredGenericType, extractionCtx
			);
		} catch (IllegalAccessException | InvocationTargetException e) {
			// Provide a detailed error message with property type and name information
			throw new SerializationFailedException(
				"Failed to deserialize value for constructor parameter: " + requiredGenericType + " " + propertyName, e
			);
		}
	}

	/**
	 * Extracts data from the provided serialized form using metadata and reflection, and sets the value
	 * to the provided setter method on the specified container object.
	 *
	 * @param container The object to which the data will be set using the setter method. Must not be null.
	 * @param serializedForm The map containing serialized data from which the property value will be extracted. Must not be null.
	 * @param reflectionLookup A utility for retrieving reflection-based metadata about the setter method and annotations. Must not be null.
	 * @param extractionCtx The context holding extraction-specific data and state, including tracking of properties processed. Must not be null.
	 * @param setter The setter method used to set the extracted data on the container object. Must not be null.
	 */
	private void extractDataToSetter(
		@Nonnull Serializable container,
		@Nonnull DataItemMap serializedForm,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull ExtractionContext extractionCtx,
		@Nonnull Method setter
	) {
		try {
			final String propertyName = ReflectionLookup.getPropertyNameFromMethodName(setter.getName());
			//noinspection unchecked
			final Class<Serializable> propertyType = (Class<Serializable>) setter.getParameterTypes()[0];
			final Object propertyValue = extractMapItem(
				serializedForm,
				reflectionLookup, propertyName, propertyType,
				setter.getGenericParameterTypes()[0],
				extractionCtx
			);
			final RenamedData renamed = reflectionLookup.getAnnotationInstanceForProperty(setter, RenamedData.class);
			if (propertyValue == null && renamed != null) {
				final Object fallbackPropertyValue = extractFallbackData(
					serializedForm, reflectionLookup, setter, propertyType, renamed, extractionCtx
				);
				setter.invoke(container, fallbackPropertyValue);
			} else {
				if (propertyType.isInstance(propertyValue)) {
					setter.invoke(container, propertyValue);
				} else if (propertyType.isPrimitive() && EvitaDataTypes.getWrappingPrimitiveClass(propertyType).isInstance(propertyValue)) {
					setter.invoke(container, propertyValue);
				} else if (EvitaDataTypes.isSupportedType(propertyType)) {
					setter.invoke(container, EvitaDataTypes.toTargetType((Serializable) propertyValue, propertyType));
				}
				if (propertyValue == null || EvitaDataTypes.isSupportedType(propertyType)) {
					extractionCtx.addExtractedProperty(propertyName);
				}
			}
		} catch (IllegalAccessException | InvocationTargetException e) {
			throw new SerializationFailedException(
				"Failed to set value via setter: " + setter.toGenericString(), e
			);
		}
	}

	/**
	 * Extracts fallback data from a serialized data map using specified property aliases.
	 *
	 * @param serializedForm the serialized data map from which the fallback data is extracted
	 * @param reflectionLookup a utility for reflection-based operations
	 * @param setter the setter method for the property
	 * @param propertyType the type of the property being processed
	 * @param renamed metadata containing potential alias property names
	 * @param extractionCtx the context of extraction operation
	 * @return the extracted fallback data if available and compatible, or null if no matching data is found
	 * @throws IllegalAccessException if access to the setter method is not allowed
	 * @throws InvocationTargetException if the invocation of the setter method throws an exception
	 */
	@Nullable
	private Object extractFallbackData(
		@Nonnull DataItemMap serializedForm,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Method setter,
		@Nonnull Class<?> propertyType,
		@Nonnull RenamedData renamed,
		@Nonnull ExtractionContext extractionCtx
	) throws IllegalAccessException, InvocationTargetException {
		for (String aliasPropertyName : renamed.value()) {
			final Object fallbackPropertyValue = extractMapItem(
				serializedForm, reflectionLookup, aliasPropertyName, propertyType, setter.getGenericParameterTypes()[0], extractionCtx
			);
			if (fallbackPropertyValue != null && EvitaDataTypes.isSupportedType(propertyType)) {
				return fallbackPropertyValue;
			}
		}
		return null;
	}

	/**
	 * Extracts a property value from a DataItemMap based on the specified property name and type.
	 * If the property exists within the DataItemMap, it is processed and returned; otherwise, null is returned.
	 *
	 * @param serializedForm the DataItemMap containing the serialized properties
	 * @param reflectionLookup a utility for reflection-based type and property information
	 * @param propertyName the name of the property to extract
	 * @param propertyType the expected class type of the property
	 * @param genericReturnType the generic type information of the property
	 * @param extractionCtx the context used to track extraction operations
	 * @return the extracted property value, or null if the property is not present in the map
	 * @throws IllegalAccessException if an attempt to invoke a method or access a field is not permitted
	 * @throws InvocationTargetException if the underlying method throws an exception
	 */
	@Nullable
	private Object extractMapItem(
		@Nonnull DataItemMap serializedForm,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull String propertyName,
		@Nonnull Class<?> propertyType,
		@Nonnull Type genericReturnType,
		@Nonnull ExtractionContext extractionCtx
	) throws IllegalAccessException, InvocationTargetException {
		final DataItem property = serializedForm.getProperty(propertyName);
		if (property != null) {
			extractionCtx.addExtractedProperty(propertyName);
			return extractItem(property, reflectionLookup, propertyType, genericReturnType, extractionCtx);
		} else {
			return null;
		}
	}

	/**
	 * Central method for extracting values from serialized data items.
	 * Dispatches to appropriate type-specific deserialization methods based on dataItem type and target property type.
	 *
	 * @param <E>               Type parameter for enum handling
	 * @param dataItem          The serialized data item to extract
	 * @param reflectionLookup  Reflection utility for type information
	 * @param propertyType      The target type to extract to
	 * @param genericReturnType The generic type information (important for collections)
	 * @param extractionCtx     Context tracking extraction process
	 * @return The extracted and converted value
	 * @throws InvocationTargetException      If instantiation fails
	 * @throws IllegalAccessException         If property access fails
	 * @throws InconvertibleDataTypeException If the dataItem cannot be converted to the target type
	 * @throws UnsupportedDataTypeException   If the type combination is not supported
	 */
	@Nullable
	private <E extends Enum<E>> Object extractItem(
		@Nullable DataItem dataItem,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Class<?> propertyType,
		@Nonnull Type genericReturnType,
		@Nonnull ExtractionContext extractionCtx
	) throws InvocationTargetException, IllegalAccessException {
		// Handle null items
		if (dataItem == null) {
			return null;
		}

		// Handle value items (containing primitive/simple values)
		if (dataItem instanceof DataItemValue dataItemValue) {
			// Direct conversion for supported Evita types
			if (EvitaDataTypes.isSupportedType(propertyType)) {
				//noinspection unchecked
				return EvitaDataTypes.toTargetType(dataItemValue.value(), (Class<? extends Serializable>) propertyType);
			}
			// Special handling for float and double types to preserve precision
			else if (float.class.equals(propertyType) || Float.class.equals(propertyType)) {
				return ofNullable(dataItemValue.value()).map(it -> Float.parseFloat(it.toString())).orElse(null);
			} else if (double.class.equals(propertyType) || Double.class.equals(propertyType)) {
				return ofNullable(dataItemValue.value()).map(it -> Double.parseDouble(it.toString())).orElse(null);
			}
			// Handle enum conversion from string
			else if (propertyType.isEnum()) {
				String enumValue = Objects.requireNonNull(dataItemValue.value()).toString();
				if (enumValue.charAt(0) == '\'' && enumValue.charAt(enumValue.length() - 1) == '\'') {
					enumValue = enumValue.substring(1, enumValue.length() - 1);
				}
				//noinspection unchecked
				return Enum.valueOf((Class<E>) propertyType, enumValue);
			}
			// Handle single value that needs to be converted to collection types
			else if (Set.class.isAssignableFrom(propertyType)) {
				return deserializeSet(reflectionLookup, genericReturnType, dataItemValue, extractionCtx);
			} else if (List.class.isAssignableFrom(propertyType)) {
				return deserializeList(reflectionLookup, genericReturnType, dataItemValue, extractionCtx);
			} else if (propertyType.isArray()) {
				return deserializeArray(reflectionLookup, genericReturnType, dataItemValue, extractionCtx);
			}
		}
		// Handle array items (containing multiple values)
		else if (dataItem instanceof DataItemArray dataItemArray) {
			// When expecting a primitive type but got an array, use first element
			if (EvitaDataTypes.isSupportedType(propertyType)) {
				return extractItem(dataItemArray.children()[0], reflectionLookup, propertyType, genericReturnType, extractionCtx);
			}
			// Handle collection type deserialization
			else if (Set.class.isAssignableFrom(propertyType)) {
				return deserializeSet(reflectionLookup, genericReturnType, dataItemArray, extractionCtx);
			} else if (List.class.isAssignableFrom(propertyType)) {
				return deserializeList(reflectionLookup, genericReturnType, dataItemArray, extractionCtx);
			} else if (propertyType.isArray()) {
				return deserializeArray(reflectionLookup, genericReturnType, dataItemArray, extractionCtx);
			} else {
				// If we get here, we have an array but the target type can't handle arrays
				throw new UnsupportedDataTypeException(propertyType, EvitaDataTypes.getSupportedDataTypes());
			}
		}
		// Handle map items (containing key-value pairs)
		else if (dataItem instanceof DataItemMap dataItemMap) {
			if (Map.class.isAssignableFrom(propertyType)) {
				return deserializeMap(reflectionLookup, genericReturnType, dataItemMap, extractionCtx);
			} else {
				// When not expecting a map, treat as a complex object
				return extractData(propertyType, dataItemMap, reflectionLookup, extractionCtx);
			}
		}

		// If we get here, we couldn't convert the dataItem to the target type
		throw new InconvertibleDataTypeException(propertyType, dataItem);
	}

	/**
	 * Deserializes a serialized form into a Set of objects.
	 * Handles both array-based and single-value serialized forms.
	 *
	 * @param reflectionLookup  Reflection utility for type information
	 * @param genericReturnType The generic type of the set to create (for element type)
	 * @param serializedForm    The serialized data to convert to a Set
	 * @param extractionCtx     Context tracking extraction process
	 * @return A Set containing the deserialized objects
	 * @throws InvocationTargetException  If element instantiation fails
	 * @throws IllegalAccessException     If element property access fails
	 * @throws InvalidDataObjectException If the serialized form cannot be converted to a Set
	 */
	private Set<Object> deserializeSet(
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Type genericReturnType,
		@Nonnull DataItem serializedForm,
		@Nonnull ExtractionContext extractionCtx
	) throws InvocationTargetException, IllegalAccessException {
		// Extract the element type from the generic return type
		final Class<? extends Serializable> innerClass = reflectionLookup.extractGenericType(genericReturnType, 0);
		assertSerializable(innerClass);

		// Handle array-based serialized form (normal case)
		if (serializedForm instanceof DataItemArray dataItemArray) {
			final DataItem[] children = dataItemArray.children();

			// Return empty set for empty arrays
			if (dataItemArray.isEmpty()) {
				return new HashSet<>();
			} else {
				// Create a set with appropriate initial capacity
				final Set<Object> result = CollectionUtils.createHashSet(children.length);
				int index = 0;

				// Process each array element
				for (DataItem child : children) {
					// Track array index for error reporting
					extractionCtx.pushIndex(index++);

					// Extract and convert the individual element
					result.add(extractItem(child, reflectionLookup, innerClass, genericReturnType, extractionCtx));
					extractionCtx.popIndex();
				}
				return result;
			}
		}
		// Handle single value serialized form (edge case)
		else if (serializedForm instanceof DataItemValue dataItemValue) {
			final Set<Object> result = CollectionUtils.createHashSet(1);
			extractionCtx.pushIndex(0);
			result.add(extractItem(dataItemValue, reflectionLookup, innerClass, genericReturnType, extractionCtx));
			extractionCtx.popIndex();
			return result;
		}
		// Handle invalid serialized form
		else {
			throw new InvalidDataObjectException(
				"Stored data type " + serializedForm.getClass() +
					" cannot be deserialized to a Set of " + innerClass.getName() + "!"
			);
		}
	}

	/**
	 * Deserializes a serialized form into a List of objects.
	 * Handles both array-based and single-value serialized forms.
	 *
	 * @param reflectionLookup  Reflection utility for type information
	 * @param genericReturnType The generic type of the list to create (for element type)
	 * @param serializedForm    The serialized data to convert to a List
	 * @param extractionCtx     Context tracking extraction process
	 * @return A List containing the deserialized objects
	 * @throws InvocationTargetException  If element instantiation fails
	 * @throws IllegalAccessException     If element property access fails
	 * @throws InvalidDataObjectException If the serialized form cannot be converted to a List
	 */
	private List<Object> deserializeList(
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Type genericReturnType,
		@Nonnull DataItem serializedForm,
		@Nonnull ExtractionContext extractionCtx
	) throws InvocationTargetException, IllegalAccessException {
		// Extract the element type from the generic return type
		final Class<? extends Serializable> innerClass = reflectionLookup.extractGenericType(genericReturnType, 0);
		assertSerializable(innerClass);

		// Handle array-based serialized form (normal case)
		if (serializedForm instanceof DataItemArray dataItemArray) {
			final DataItem[] children = dataItemArray.children();

			// Return empty list for empty arrays
			if (dataItemArray.isEmpty()) {
				return new ArrayList<>();
			} else {
				// Create a list with appropriate initial capacity
				final List<Object> result = new ArrayList<>(children.length);
				int index = 0;

				// Process each array element, preserving order
				for (DataItem child : children) {
					// Track array index for error reporting
					extractionCtx.pushIndex(index++);

					// Extract and convert the individual element
					result.add(extractItem(child, reflectionLookup, innerClass, genericReturnType, extractionCtx));
					extractionCtx.popIndex();
				}
				return result;
			}
		}
		// Handle single value serialized form (edge case)
		else if (serializedForm instanceof DataItemValue dataItemValue) {
			final List<Object> result = new ArrayList<>(1);
			extractionCtx.pushIndex(0);
			result.add(extractItem(dataItemValue, reflectionLookup, innerClass, genericReturnType, extractionCtx));
			extractionCtx.popIndex();
			return result;
		}
		// Handle invalid serialized form
		else {
			throw new InvalidDataObjectException(
				"Stored data type " + serializedForm.getClass() +
					" cannot be deserialized to a List of " + innerClass.getName() + "!"
			);
		}
	}

	/**
	 * Deserializes a serialized form into an array of objects.
	 * Handles both array-based and single-value serialized forms.
	 *
	 * @param reflectionLookup  Reflection utility for type information
	 * @param genericReturnType The type of the array to create (for component type)
	 * @param serializedForm    The serialized data to convert to an array
	 * @param extractionCtx     Context tracking extraction process
	 * @return An array containing the deserialized objects
	 * @throws InvocationTargetException  If element instantiation fails
	 * @throws IllegalAccessException     If element property access fails
	 * @throws InvalidDataObjectException If the serialized form cannot be converted to an array
	 */
	private Object[] deserializeArray(
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Type genericReturnType,
		@Nonnull DataItem serializedForm,
		@Nonnull ExtractionContext extractionCtx
	) throws InvocationTargetException, IllegalAccessException {
		// Extract the component type from the generic return type
		//noinspection unchecked
		final Class<? extends Serializable> innerClass = (Class<? extends Serializable>) ((Class<?>) genericReturnType).getComponentType();
		assertSerializable(innerClass);

		// Handle array-based serialized form (normal case)
		if (serializedForm instanceof DataItemArray dataItemArray) {
			final DataItem[] children = dataItemArray.children();

			// Return empty array for empty serialized arrays
			if (dataItemArray.isEmpty()) {
				return (Object[]) Array.newInstance(innerClass, 0);
			} else {
				// Create an array with the same length as the serialized data
				final Object[] result = (Object[]) Array.newInstance(innerClass, children.length);

				// Process each array element, preserving order
				for (int i = 0; i < children.length; i++) {
					// Track array index for error reporting
					extractionCtx.pushIndex(i);

					// Extract and convert the individual element
					result[i] = extractItem(children[i], reflectionLookup, innerClass, genericReturnType, extractionCtx);
					extractionCtx.popIndex();
				}
				return result;
			}
		}
		// Handle single value serialized form (edge case)
		else if (serializedForm instanceof DataItemValue dataItemValue) {
			// Create a single-element array
			final Object[] result = (Object[]) Array.newInstance(innerClass, 1);
			extractionCtx.pushIndex(0);
			result[0] = extractItem(dataItemValue, reflectionLookup, innerClass, genericReturnType, extractionCtx);
			extractionCtx.popIndex();
			return result;
		}
		// Handle invalid serialized form
		else {
			throw new InvalidDataObjectException(
				"Stored data type " + serializedForm.getClass() +
					" cannot be deserialized to an array of " + innerClass.getName() + "!"
			);
		}
	}

	/**
	 * Deserializes a DataItemMap into a Map with the correct key and value types.
	 * Converts string keys back to their original types.
	 *
	 * @param reflectionLookup  Reflection utility for type information
	 * @param genericReturnType The generic type of the map to create (for key/value types)
	 * @param serializedForm    The serialized map data
	 * @param extractionCtx     Context tracking extraction process
	 * @return A Map containing the deserialized key-value pairs
	 * @throws InvocationTargetException If value instantiation fails
	 * @throws IllegalAccessException    If value property access fails
	 */
	private Map<Serializable, Object> deserializeMap(
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Type genericReturnType,
		@Nonnull DataItemMap serializedForm,
		@Nonnull ExtractionContext extractionCtx
	) throws InvocationTargetException, IllegalAccessException {
		// Return empty map for empty serialized maps
		if (serializedForm.isEmpty()) {
			return new HashMap<>();
		} else {
			// Extract key and value types from the generic return type
			final Class<? extends Serializable> keyClass = reflectionLookup.extractGenericType(genericReturnType, 0);
			assertSerializable(keyClass);
			final Class<? extends Serializable> valueClass = reflectionLookup.extractGenericType(genericReturnType, 1);
			assertSerializable(valueClass);

			final Set<String> propertyNames = serializedForm.getPropertyNames();

			// Push property names to extraction context to track extraction
			extractionCtx.pushPropertyNames(propertyNames);

			// Create a map with appropriate initial capacity
			final Map<Serializable, Object> result = CollectionUtils.createLinkedHashMap(propertyNames.size());

			// Process each map entry
			for (String propertyName : propertyNames) {
				// Mark this property as extracted
				extractionCtx.addExtractedProperty(propertyName);

				// Get and convert the value
				final DataItem valueItem = Objects.requireNonNull(serializedForm.getProperty(propertyName));

				// Convert the string key back to its original type and store with extracted value
				result.put(
					EvitaDataTypes.toTargetType(propertyName, keyClass),
					extractItem(valueItem, reflectionLookup, valueClass, genericReturnType, extractionCtx)
				);
			}

			// Pop property names from extraction context
			extractionCtx.popPropertyNames();

			return result;
		}
	}

	/**
	 * This class is used to propagate renamed property name along with argument key.
	 */
	public static class ArgumentKeyWithRename extends ArgumentKey {
		@Getter private final String originalProperty;

		public ArgumentKeyWithRename(@Nonnull String name, @Nonnull Class<?> type, @Nonnull String originalProperty) {
			super(name, type);
			this.originalProperty = originalProperty;
		}

	}

	/**
	 * This internal extraction context allows us to detect a data that were part of {@link ComplexDataObject} but
	 * were not extracted to the target "user class". We need to monitor this, because there is high risk of losing data.
	 * Context also allows us to process {@link @DiscardedData} annotations and allow continuous model evolution.
	 */
	private static class ExtractionContext {
		private static final String NULL_STRING = "";
		/**
		 * Stack containing name of the lastExtractedProperty allows to reconstruct "property path" for signalling
		 * complete (deep-wise) name of the not extracted property.
		 */
		private final Deque<String> lastExtractedProperty = new ArrayDeque<>(16);
		/**
		 * Stack containing "property path" for signalling complete (deep-wise) name of the not extracted property.
		 */
		private final Deque<String> propertyPath = new ArrayDeque<>(16);
		/**
		 * Stack of properties that are expected to be extracted into the result Java object. The stack / set is used
		 * to check that every data item gets converted into some Java property.
		 */
		private final Deque<Set<String>> propertySets = new ArrayDeque<>(16);
		/**
		 * Contains information about all properties found in the {@link ComplexDataObject} that were not deserialized
		 * and thus lead to information loss.
		 */
		private Set<String> notExtractedProperties;

		/**
		 * Adds property that which value was migrated to user class instance.
		 */
		public void addNotExtractedProperty(@Nonnull String propertyName) {
			if (this.notExtractedProperties == null) {
				this.notExtractedProperties = new HashSet<>();
			}

			final StringBuilder sb = new StringBuilder(128);
			final Iterator<String> it = this.propertyPath.descendingIterator();
			while (it.hasNext()) {
				sb.append(it.next());
			}
			final String composedPropertyName = sb + (this.propertyPath.isEmpty() ? "" : ".") + propertyName;
			this.notExtractedProperties.add(composedPropertyName);
		}

		/**
		 * Returns set of all properties that has some data attached but were not mapped to the user class and were
		 * not marked as discarded as well.
		 */
		public Set<String> getNotExtractedProperties() {
			return this.notExtractedProperties == null ? Collections.emptySet() : this.notExtractedProperties;
		}

		/**
		 * Adds property that has been found on user class as "discarded".
		 */
		public void addDiscardedProperty(@Nonnull String propertyName) {
			final Set<String> currentProperties = Objects.requireNonNull(this.propertySets.peek());
			currentProperties.remove(propertyName);
		}

		/**
		 * Initializes set of properties that should be monitored for extraction.
		 */
		public void pushPropertyNames(@Nonnull Set<String> propertyNames) {
			if (!this.propertySets.isEmpty()) {
				this.propertyPath.push((this.propertyPath.isEmpty() ? "" : ".") + this.lastExtractedProperty.peek());
			}
			this.propertySets.push(new HashSet<>(propertyNames));
			this.lastExtractedProperty.push(NULL_STRING);
		}

		/**
		 * Examines the property name set - if there is any property left it means that some data were not extracted
		 * to the Java object and this would lead to data loss that needs to be signalled by an exception.
		 */
		public void popPropertyNames() {
			final Set<String> notExtractedProperties = this.propertySets.pop();
			notExtractedProperties.forEach(this::addNotExtractedProperty);
			if (!this.propertySets.isEmpty()) {
				this.propertyPath.pop();
			}
			this.lastExtractedProperty.pop();
		}

		/**
		 * Records information about property extraction - i.e. successful propagation to result Java object.
		 */
		public void addExtractedProperty(@Nonnull String propertyName) {
			final Set<String> currentProperties = Objects.requireNonNull(this.propertySets.peek());
			currentProperties.remove(propertyName);
			this.lastExtractedProperty.pop();
			this.lastExtractedProperty.push(propertyName);
		}

		/**
		 * Allows to track position in the extracted arrays.
		 */
		public void pushIndex(int index) {
			this.propertyPath.push("[" + index + "]");
		}

		/**
		 * Clears information about position in the array.
		 */
		public void popIndex() {
			if (!this.propertySets.isEmpty()) {
				final String popped = this.propertyPath.pop();
				Assert.isPremiseValid(popped.charAt(0) == '[', "Sanity check!");
			}
		}
	}

}
