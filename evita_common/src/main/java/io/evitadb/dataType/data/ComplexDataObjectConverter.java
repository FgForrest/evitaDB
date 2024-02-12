/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ComplexDataObjectConverter<T extends Serializable> {
	private static final DataItem[] EMPTY_DATA_ITEMS = new DataItem[0];
	private final ReflectionLookup reflectionLookup;
	private final T container;
	private final Class<T> containerClass;

	/**
	 * Method guarantees returning one supported {@link EvitaDataTypes}. If {@link #container}
	 * doesn't represent one, it is converted to {@link ComplexDataObject} automatically.
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
	 * Method deserializes internal object to original one. If inner object is one of the {@link EvitaDataTypes} it is
	 * returned immediately. If not deserialization from {@link ComplexDataObject} occurs.
	 */
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
	 * Creates converter instance for (de)serialization of the passed object.
	 */
	public ComplexDataObjectConverter(@Nonnull T container) {
		this.reflectionLookup = new ReflectionLookup(ReflectionCachingBehaviour.NO_CACHE);
		this.container = container;
		//noinspection unchecked
		this.containerClass = (Class<T>) container.getClass();
	}

	/**
	 * Creates converter instance and prepares empty container for the sake of deserialization.
	 *
	 * @throws InvalidDataObjectException when containerClass doesn't have default constructor
	 */
	public ComplexDataObjectConverter(@Nonnull Class<T> containerClass, @Nonnull ReflectionLookup reflectionLookup) throws InvalidDataObjectException {
		this.reflectionLookup = reflectionLookup;
		this.container = null;
		this.containerClass = containerClass;
	}

	/**
	 * Method guarantees returning one supported {@link EvitaDataTypes}. If {@link #container}
	 * doesn't represent one, it is converted to {@link ComplexDataObject} automatically.
	 */
	public Serializable getSerializableForm() {
		if (container instanceof ComplexDataObject || container instanceof ComplexDataObject[]) {
			return container;
		} else {
			final Class<?> type = container.getClass().isArray() ?
				container.getClass().getComponentType() : container.getClass();
			return EvitaDataTypes.isSupportedType(type) ? container : convertToGenericType(container);
		}
	}

	/**
	 * Method deserializes internal object to original one. If inner object is one of the {@link EvitaDataTypes} it is
	 * returned immediately. If not deserialization from {@link ComplexDataObject} occurs.
	 */
	@Nullable
	public T getOriginalForm(@Nonnull Serializable serializedForm) {
		Assert.notNull(reflectionLookup, "Reflection lookup required!");
		if (serializedForm instanceof Object[] && EvitaDataTypes.isSupportedType(serializedForm.getClass().getComponentType())) {
			//noinspection unchecked
			return (T) serializedForm;
		} else if (ComplexDataObject.class.isAssignableFrom(containerClass) && serializedForm instanceof ComplexDataObject) {
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
			if (containerClass.isArray()) {
				try {
					//noinspection unchecked
					result = (T) deserializeArray(reflectionLookup, containerClass, root, extractionCtx);
				} catch (IllegalAccessException | InvocationTargetException e) {
					throw new SerializationFailedException(
						"Failed to deserialize root array.", e
					);
				}
			} else if (root instanceof DataItemMap dataItemMap) {
				// usually there will be single top level POJO object
				result = extractData(containerClass, dataItemMap, reflectionLookup, extractionCtx);
			} else {
				throw new InconvertibleDataTypeException(containerClass, root.getClass());
			}
			// check whether all data has been deserialized or marked as discarded
			if (!extractionCtx.getNotExtractedProperties().isEmpty()) {
				// we need to take discarded properties into an account - so error might not occur
				throw new IncompleteDeserializationException(extractionCtx.getNotExtractedProperties());
			}
			return result;
		}
	}

	/*
		PRIVATE METHODS
	 */

	@Nonnull
	private ComplexDataObject convertToGenericType(@Nonnull T container) {
		final DataItem rootNode;
		if (container instanceof final Object[] containerArray) {
			final DataItem[] dataItems = new DataItem[containerArray.length];
			for (int i = 0; i < containerArray.length; i++) {
				final Object containerItem = containerArray[i];
				dataItems[i] = collectData((Serializable) containerItem, reflectionLookup, "[" + i + "].");
			}
			final Class<?> arrayType = container.getClass().getComponentType();
			assertSerializable(arrayType);
			rootNode = new DataItemArray(dataItems);
		} else {
			rootNode = collectData(container, reflectionLookup, "");
		}

		final ComplexDataObject result = new ComplexDataObject(rootNode);
		Assert.isTrue(
			!result.isEmpty(),
			"No usable properties found on " + container.getClass() + ". This is probably a problem."
		);
		return result;
	}

	private DataItem collectData(
		@Nonnull Serializable container,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull String propertyPrefix
	) {
		final DataItemMap result;
		final Class<? extends Serializable> containerClass = container.getClass();
		if (containerClass.isRecord()) {
			final Map<String, DataItem> dataItems = createHashMap(containerClass.getRecordComponents().length);
			for (RecordComponent recordComponent : containerClass.getRecordComponents()) {
				if (recordComponent.getAnnotation(NonSerializedData.class) == null) {
					try {
						final Object propertyValue = recordComponent.getAccessor().invoke(container);
						final Class<?> propertyClass = recordComponent.getType();
						final Type propertyType = recordComponent.getGenericType();
						final String propertyName = recordComponent.getName();
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
			final Collection<Method> getters = reflectionLookup.findAllGettersHavingCorrespondingSetterOrConstructorArgument(containerClass);
			final Map<String, DataItem> dataItems = createHashMap(getters.size());
			for (Method getter : getters) {
				if (reflectionLookup.getAnnotationInstanceForProperty(getter, NonSerializedData.class) == null) {
					try {
						final Object propertyValue = getter.invoke(container);
						final Class<?> propertyClass = getter.getReturnType();
						final Type propertyType = getter.getGenericReturnType();
						final String propertyName = ReflectionLookup.getPropertyNameFromMethodName(getter.getName());
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

	@Nullable
	private DataItem collectDataForProperty(
		@Nonnull String propertyName,
		@Nullable Object propertyValue,
		@Nonnull Class<?> propertyClass,
		@Nonnull Type propertyType,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		if (EvitaDataTypes.isSupportedType(propertyClass)) {
			if (propertyValue == null) {
				return null;
			} else {
				return new DataItemValue((Serializable) propertyValue);
			}
		} else if (propertyClass.isEnum()) {
			if (propertyValue == null) {
				return null;
			} else {
				return new DataItemValue(propertyValue.toString());
			}
		} else if (Set.class.isAssignableFrom(propertyClass)) {
			if (propertyValue == null) {
				final Class<? extends Serializable> valueType = reflectionLookup.extractGenericType(propertyType, 0);
				assertSerializable(valueType);
				return null;
			} else if (((Set<?>) propertyValue).isEmpty()) {
				final Class<? extends Serializable> valueType = reflectionLookup.extractGenericType(propertyType, 0);
				assertSerializable(valueType);
				return new DataItemArray(EMPTY_DATA_ITEMS);
			} else {
				return serializeSet(propertyName, propertyType, reflectionLookup, (Set<?>) propertyValue);
			}
		} else if (List.class.isAssignableFrom(propertyClass)) {
			if (propertyValue == null) {
				final Class<? extends Serializable> valueType = reflectionLookup.extractGenericType(propertyType, 0);
				assertSerializable(valueType);
				return null;
			} else if (((List<?>) propertyValue).isEmpty()) {
				final Class<? extends Serializable> valueType = reflectionLookup.extractGenericType(propertyType, 0);
				assertSerializable(valueType);
				return new DataItemArray(EMPTY_DATA_ITEMS);
			} else {
				return serializeList(propertyName, propertyType, reflectionLookup, (List<?>) propertyValue);
			}
		} else if (propertyClass.isArray()) {
			final Class<?> arrayType = propertyClass.getComponentType();
			assertSerializable(arrayType);
			if (propertyValue == null) {
				return null;
			} else if (Array.getLength(propertyValue) == 0) {
				return new DataItemArray(EMPTY_DATA_ITEMS);
			} else {
				//noinspection unchecked
				return serializePrimitiveArray(propertyName, (Class<? extends Serializable>) arrayType, reflectionLookup, propertyValue);
			}
		} else if (Map.class.isAssignableFrom(propertyClass)) {
			if (propertyValue == null) {
				return null;
			} else if (((Map<?, ?>) propertyValue).isEmpty()) {
				return DataItemMap.EMPTY;
			} else {
				return serializeMap(
					propertyName, propertyType, reflectionLookup, (Map<?, ?>) propertyValue
				);
			}
		} else if (float.class.equals(propertyClass)) {
			if (propertyValue == null) {
				return null;
			} else {
				return new DataItemValue(new BigDecimal(Float.toString((float)propertyValue)));
			}
		} else if (Float.class.equals(propertyClass)) {
			if (propertyValue == null) {
				return null;
			} else {
				return new DataItemValue(new BigDecimal(propertyValue.toString()));
			}
		} else if (double.class.equals(propertyClass)) {
			if (propertyValue == null) {
				return null;
			} else {
				return new DataItemValue(new BigDecimal(Double.toString((double)propertyValue)));
			}
		} else if (Double.class.equals(propertyClass)) {
			if (propertyValue == null) {
				return null;
			} else {
				return new DataItemValue(new BigDecimal(propertyValue.toString()));
			}
		} else if (propertyClass.getPackageName().startsWith("java.")) {
			throw new SerializationFailedException("Unsupported data type " + propertyClass + ": " + propertyValue);
		} else if (propertyValue == null) {
			assertSerializable(propertyClass);
			return null;
		} else {
			return collectData((Serializable) propertyValue, reflectionLookup, propertyName);
		}
	}

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

	private DataItemArray serializeArray(
		@Nonnull String propertyName,
		@Nonnull Class<? extends Serializable> propertyClass,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Object[] propertyValue
	) {
		final DataItem[] dataItems = new DataItem[propertyValue.length];
		for (int i = 0; i < propertyValue.length; i++) {
			final Object itemValue = propertyValue[i];
			if (itemValue == null) {
				dataItems[i] = new DataItemValue(propertyClass);
			} else if (EvitaDataTypes.isSupportedType(propertyClass)) {
				dataItems[i] = new DataItemValue((Serializable) itemValue);
			} else {
				dataItems[i] = collectData((Serializable) itemValue, reflectionLookup, propertyName + "[" + i + "]" + ".");
			}
		}
		return new DataItemArray(dataItems);
	}

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

	private <X> X extractData(
		@Nonnull Class<X> containerClass,
		@Nonnull DataItemMap serializedForm,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull ExtractionContext extractionCtx
	) {
		final Set<String> propertyNames;
		final Collection<Method> writers;
		if (containerClass.isRecord()) {
			writers = Collections.emptyList();
			propertyNames = Arrays.stream(containerClass.getRecordComponents())
				.map(RecordComponent::getName)
				.collect(Collectors.toSet());
		} else {
			writers = reflectionLookup.findAllSetters(containerClass);
			propertyNames = writers
				.stream()
				.map(it -> ReflectionLookup.getPropertyNameFromMethodName(it.getName()))
				.collect(Collectors.toSet());
		}
		final Set<ArgumentKey> allPropertyNames = serializedForm.getPropertyNames()
			.stream()
			.map(it -> new ArgumentKey(it, Object.class))
			.collect(Collectors.toSet());
		final Set<ArgumentKey> unknownProperties = allPropertyNames
			.stream()
			.filter(it -> !propertyNames.contains(it.getName()))
			.collect(Collectors.toSet());

		extractionCtx.pushPropertyNames(serializedForm.getPropertyNames());

		final X resultContainer = instantiateContainerByMatchingConstructor(
			containerClass, reflectionLookup, extractionCtx, unknownProperties, allPropertyNames,
			(propertyName, requiredType, requiredGenericType) -> extractValueAndRegisterIt(
				serializedForm, reflectionLookup, extractionCtx, propertyName, requiredType, requiredGenericType
			)
		);

		for (Method setter : writers) {
			if (reflectionLookup.getAnnotationInstanceForProperty(setter, NonSerializedData.class) != null) {
				// skip property
				continue;
			}
			extractDataToSetter((Serializable) resultContainer, serializedForm, reflectionLookup, extractionCtx, setter);
		}

		extractionCtx.popPropertyNames();

		return resultContainer;
	}

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
			extractionCtx.addExtractedProperty(propertyName);
			return extractMapItem(
				serializedForm, reflectionLookup, propertyName, requiredType, requiredGenericType, extractionCtx
			);
		} catch (IllegalAccessException | InvocationTargetException e) {
			throw new SerializationFailedException(
				"Failed to deserialize value for constructor parameter: " + requiredGenericType + " " + propertyName, e
			);
		}
	}

	@Nonnull
	private <X> X instantiateContainerByMatchingConstructor(
		@Nonnull Class<X> containerClass,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull ExtractionContext extractionCtx,
		@Nonnull Set<ArgumentKey> propertyNamesWithoutSetter,
		@Nonnull Set<ArgumentKey> allPropertyNames,
		@Nonnull TriFunction<String, Class<?>, Type, Object> argumentFetcher
	) {
		final Set<String> discards = identifyPropertyDiscard(containerClass, reflectionLookup, extractionCtx, propertyNamesWithoutSetter);
		Map<String, String> renames = null;
		Constructor<X> appropriateConstructor = null;
		try {
			appropriateConstructor = reflectionLookup.findConstructor(containerClass, propertyNamesWithoutSetter);
		} catch (IllegalArgumentException ex) {
			// try to find renamed and discarded fields to find alternative constructor
			// we do this in catch block to avoid performance penalty - most of the DTOs will not have any rename / discard annotations
			renames = identifyPropertyReplacement(containerClass, reflectionLookup, propertyNamesWithoutSetter);
			if (!(renames.isEmpty() && discards.isEmpty())) {
				appropriateConstructor = instantiateContainerByFallbackConstructorWithRenamedAndDiscardedArguments(
					containerClass, reflectionLookup, propertyNamesWithoutSetter
				);
			}
			if (appropriateConstructor == null) {
				appropriateConstructor = reflectionLookup.findAnyMatchingConstructor(containerClass, allPropertyNames);
				if (appropriateConstructor == null) {
					// fallback to default constructor
					appropriateConstructor = reflectionLookup.findConstructor(containerClass, Collections.emptySet());
				}
			}
		}

		final X resultContainer;
		try {
			final Object[] initArgs = getInitArgs(argumentFetcher, renames, appropriateConstructor);
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

	private <X> Set<String> identifyPropertyDiscard(
		@Nonnull Class<X> containerClass,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull ExtractionContext extractionCtx,
		@Nonnull Set<ArgumentKey> propertyNamesWithoutSetter
	) {
		final DiscardedData discardedData = reflectionLookup.getClassAnnotation(containerClass, DiscardedData.class);
		if (discardedData == null) {
			return Collections.emptySet();
		} else {
			final Set<String> discardedProperties = new HashSet<>(discardedData.value().length);
			for (String discardedProperty : discardedData.value()) {
				discardedProperties.add(discardedProperty);
				extractionCtx.addDiscardedProperty(discardedProperty);
				propertyNamesWithoutSetter.remove(new ArgumentKey(discardedProperty, Object.class));
			}
			return discardedProperties;
		}
	}

	@Nullable
	private <X> Constructor<X> instantiateContainerByFallbackConstructorWithRenamedAndDiscardedArguments(
		@Nonnull Class<X> containerClass,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Set<ArgumentKey> propertyNamesWithoutSetter
	) {
		try {
			// try to find constructor with renamed properties instead
			return reflectionLookup.findConstructor(
				containerClass,
				propertyNamesWithoutSetter
			);
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	@Nonnull
	private <X> Object[] getInitArgs(
		@Nonnull TriFunction<String, Class<?>, Type, Object> argumentFetcher,
		@Nullable Map<String, String> renames,
		@Nonnull Constructor<X> appropriateConstructor
	) {
		final Map<String, String> finalRenames = renames;
		return Arrays.stream(appropriateConstructor.getParameters())
			.map(it ->
				argumentFetcher.apply(
					finalRenames == null ? it.getName() : finalRenames.getOrDefault(it.getName(), it.getName()),
					it.getType(), it.getParameterizedType()
				)
			)
			.toArray();
	}

	private <X> Map<String, String> identifyPropertyReplacement(
		@Nonnull Class<X> containerClass,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Set<ArgumentKey> propertyNamesWithoutSetter
	) {
		final Map<String, String> renames = createHashMap(propertyNamesWithoutSetter.size());
		final Map<Field, List<RenamedData>> renamedFields = reflectionLookup.getFields(containerClass, RenamedData.class);
		renamedFields
			.entrySet()
			.stream()
			.map(fieldEntry ->
				fieldEntry.getValue()
					.stream()
					.flatMap(renamedAnnotation -> Arrays.stream(renamedAnnotation.value()))
					.filter(renamedAnnotationValue -> propertyNamesWithoutSetter.contains(new ArgumentKey(renamedAnnotationValue, Object.class)))
					.map(renamedAnnotationValue ->
						new ArgumentKeyWithRename(
							fieldEntry.getKey().getName(), fieldEntry.getKey().getType(), renamedAnnotationValue
						)
					)
					.findFirst()
					.orElse(null)
			)
			.filter(Objects::nonNull)
			.forEach(it -> {
				propertyNamesWithoutSetter.remove(new ArgumentKey(it.getOriginalProperty(), it.getType()));
				propertyNamesWithoutSetter.add(it);
				renames.put(it.getName(), it.getOriginalProperty());
			});
		return renames;
	}

	private void extractDataToSetter(
		@Nonnull Serializable container,
		@Nonnull DataItemMap serializedForm,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull ExtractionContext extractionCtx,
		@Nonnull Method setter
	) {
		try {
			final String propertyName = ReflectionLookup.getPropertyNameFromMethodName(setter.getName());
			final Class<?> propertyType = setter.getParameterTypes()[0];
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
				setter.invoke(container, propertyValue);
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

	@Nullable
	private <E extends Enum<E>> Object extractItem(
		@Nullable DataItem dataItem,
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Class<?> propertyType,
		@Nonnull Type genericReturnType,
		@Nonnull ExtractionContext extractionCtx
	) throws InvocationTargetException, IllegalAccessException {
		if (dataItem instanceof DataItemValue dataItemValue) {
			if (EvitaDataTypes.isSupportedType(propertyType)) {
				return dataItemValue.value();
			} else if (float.class.equals(propertyType) || Float.class.equals(propertyType)) {
				return ofNullable(dataItemValue.value()).map(it -> Float.parseFloat(it.toString())).orElse(null);
			} else if (double.class.equals(propertyType) || Double.class.equals(propertyType)) {
				return ofNullable(dataItemValue.value()).map(it -> Double.parseDouble(it.toString())).orElse(null);
			} else if (propertyType.isEnum()) {
				//noinspection unchecked
				return Enum.valueOf((Class<E>)propertyType, dataItemValue.value().toString());
			} else if (Set.class.isAssignableFrom(propertyType)) {
				return deserializeSet(reflectionLookup, genericReturnType, dataItemValue, extractionCtx);
			} else if (List.class.isAssignableFrom(propertyType)) {
				return deserializeList(reflectionLookup, genericReturnType, dataItemValue, extractionCtx);
			} else if (propertyType.isArray()) {
				return deserializeArray(reflectionLookup, genericReturnType, dataItemValue, extractionCtx);
			}
		} else if (dataItem instanceof DataItemArray dataItemArray) {
			if (EvitaDataTypes.isSupportedType(propertyType)) {
				return extractItem(dataItemArray.children()[0], reflectionLookup, propertyType, genericReturnType, extractionCtx);
			} else if (Set.class.isAssignableFrom(propertyType)) {
				return deserializeSet(reflectionLookup, genericReturnType, dataItemArray, extractionCtx);
			} else if (List.class.isAssignableFrom(propertyType)) {
				return deserializeList(reflectionLookup, genericReturnType, dataItemArray, extractionCtx);
			} else if (propertyType.isArray()) {
				return deserializeArray(reflectionLookup, genericReturnType, dataItemArray, extractionCtx);
			} else {
				throw new UnsupportedDataTypeException(propertyType, EvitaDataTypes.getSupportedDataTypes());
			}
		} else if (dataItem instanceof DataItemMap dataItemMap) {
			if (Map.class.isAssignableFrom(propertyType)) {
				return deserializeMap(reflectionLookup, genericReturnType, dataItemMap, extractionCtx);
			} else {
				return extractData(propertyType, dataItemMap, reflectionLookup, extractionCtx);
			}
		} else if (dataItem == null) {
			return null;
		}

		throw new InconvertibleDataTypeException(propertyType, dataItem);
	}

	private Set<Object> deserializeSet(
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Type genericReturnType,
		@Nonnull DataItem serializedForm,
		@Nonnull ExtractionContext extractionCtx
	) throws InvocationTargetException, IllegalAccessException {
		final Class<? extends Serializable> innerClass = reflectionLookup.extractGenericType(genericReturnType, 0);
		assertSerializable(innerClass);
		if (serializedForm instanceof DataItemArray dataItemArray) {
			final DataItem[] children = dataItemArray.children();
			if (dataItemArray.isEmpty()) {
				return new HashSet<>();
			} else {
				final Set<Object> result = CollectionUtils.createHashSet(children.length);
				int index = 0;
				for (DataItem child : children) {
					extractionCtx.pushIndex(index++);
					result.add(extractItem(child, reflectionLookup, innerClass, genericReturnType, extractionCtx));
					extractionCtx.popIndex();
				}
				return result;
			}
		} else if (serializedForm instanceof DataItemValue dataItemValue) {
			final Set<Object> result = CollectionUtils.createHashSet(1);
			extractionCtx.pushIndex(0);
			result.add(extractItem(dataItemValue, reflectionLookup, innerClass, genericReturnType, extractionCtx));
			extractionCtx.popIndex();
			return result;
		} else {
			throw new InvalidDataObjectException("Stored data type " + serializedForm.getClass() + " cannot be deserialized to array of " + innerClass.getName() + "!");
		}
	}

	private List<Object> deserializeList(
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Type genericReturnType,
		@Nonnull DataItem serializedForm,
		@Nonnull ExtractionContext extractionCtx
	) throws InvocationTargetException, IllegalAccessException {
		final Class<? extends Serializable> innerClass = reflectionLookup.extractGenericType(genericReturnType, 0);
		assertSerializable(innerClass);
		if (serializedForm instanceof DataItemArray dataItemArray) {
			final DataItem[] children = dataItemArray.children();
			if (dataItemArray.isEmpty()) {
				return new ArrayList<>();
			} else {
				final List<Object> result = new ArrayList<>(children.length);
				int index = 0;
				for (DataItem child : children) {
					extractionCtx.pushIndex(index++);
					result.add(extractItem(child, reflectionLookup, innerClass, genericReturnType, extractionCtx));
					extractionCtx.popIndex();
				}
				return result;
			}
		} else if (serializedForm instanceof DataItemValue dataItemValue) {
			final List<Object> result = new ArrayList<>(1);
			extractionCtx.pushIndex(0);
			result.add(extractItem(dataItemValue, reflectionLookup, innerClass, genericReturnType, extractionCtx));
			extractionCtx.popIndex();
			return result;
		} else {
			throw new InvalidDataObjectException("Stored data type " + serializedForm.getClass() + " cannot be deserialized to array of " + innerClass.getName() + "!");
		}
	}

	private Object[] deserializeArray(
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Type genericReturnType,
		@Nonnull DataItem serializedForm,
		@Nonnull ExtractionContext extractionCtx
	) throws InvocationTargetException, IllegalAccessException {
		//noinspection unchecked
		final Class<? extends Serializable> innerClass = (Class<? extends Serializable>) ((Class<?>) genericReturnType).getComponentType();
		assertSerializable(innerClass);
		if (serializedForm instanceof DataItemArray dataItemArray) {
			final DataItem[] children = dataItemArray.children();
			if (dataItemArray.isEmpty()) {
				return (Object[]) Array.newInstance(innerClass, 0);
			} else {
				final Object[] result = (Object[]) Array.newInstance(innerClass, children.length);
				for (int i = 0; i < children.length; i++) {
					extractionCtx.pushIndex(i);
					result[i] = extractItem(children[i], reflectionLookup, innerClass, genericReturnType, extractionCtx);
					extractionCtx.popIndex();
				}
				return result;
			}
		} else if (serializedForm instanceof DataItemValue dataItemValue) {
			final Object[] result = (Object[]) Array.newInstance(innerClass, 1);
			extractionCtx.pushIndex(0);
			result[0] = extractItem(dataItemValue, reflectionLookup, innerClass, genericReturnType, extractionCtx);
			extractionCtx.popIndex();
			return result;
		} else {
			throw new InvalidDataObjectException("Stored data type " + serializedForm.getClass() + " cannot be deserialized to array of " + innerClass.getName() + "!");
		}
	}

	private Map<Serializable, Object> deserializeMap(
		@Nonnull ReflectionLookup reflectionLookup,
		@Nonnull Type genericReturnType,
		@Nonnull DataItemMap serializedForm,
		@Nonnull ExtractionContext extractionCtx
	) throws InvocationTargetException, IllegalAccessException {
		if (serializedForm.isEmpty()) {
			return new HashMap<>();
		} else {
			final Class<? extends Serializable> keyClass = reflectionLookup.extractGenericType(genericReturnType, 0);
			assertSerializable(keyClass);
			final Class<? extends Serializable> valueClass = reflectionLookup.extractGenericType(genericReturnType, 1);
			assertSerializable(valueClass);

			final Set<String> propertyNames = serializedForm.getPropertyNames();

			extractionCtx.pushPropertyNames(propertyNames);
			final Map<Serializable, Object> result = CollectionUtils.createLinkedHashMap(propertyNames.size());
			for (String propertyName : propertyNames) {
				extractionCtx.addExtractedProperty(propertyName);
				final DataItem valueItem = Objects.requireNonNull(serializedForm.getProperty(propertyName));
				result.put(
					EvitaDataTypes.toTargetType(propertyName, keyClass),
					extractItem(valueItem, reflectionLookup, valueClass, genericReturnType, extractionCtx)
				);
			}

			extractionCtx.popPropertyNames();

			return result;
		}
	}

	private void assertSerializable(@Nonnull Class<?> theClass) {
		Assert.isTrue(
			theClass.isPrimitive() || Serializable.class.isAssignableFrom(theClass),
			() -> new SerializableClassRequiredException(theClass)
		);
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
			if (notExtractedProperties == null) {
				notExtractedProperties = new HashSet<>();
			}

			final StringBuilder sb = new StringBuilder();
			final Iterator<String> it = propertyPath.descendingIterator();
			while (it.hasNext()) {
				sb.append(it.next());
			}
			final String composedPropertyName = sb + (propertyPath.isEmpty() ? "" : ".") + propertyName;
			notExtractedProperties.add(composedPropertyName);
		}

		/**
		 * Returns set of all properties that has some data attached but were not mapped to the user class and were
		 * not marked as discarded as well.
		 */
		public Set<String> getNotExtractedProperties() {
			return notExtractedProperties == null ? Collections.emptySet() : notExtractedProperties;
		}

		/**
		 * Adds property that has been found on user class as "discarded".
		 */
		public void addDiscardedProperty(@Nonnull String propertyName) {
			final Set<String> currentProperties = Objects.requireNonNull(propertySets.peek());
			currentProperties.remove(propertyName);
		}

		/**
		 * Initializes set of properties that should be monitored for extraction.
		 */
		public void pushPropertyNames(@Nonnull Set<String> propertyNames) {
			if (!propertySets.isEmpty()) {
				propertyPath.push((propertyPath.isEmpty() ? "" : ".") + lastExtractedProperty.peek());
			}
			propertySets.push(new HashSet<>(propertyNames));
			lastExtractedProperty.push(NULL_STRING);
		}

		/**
		 * Examines the property name set - if there is any property left it means that some data were not extracted
		 * to the Java object and this would lead to data loss that needs to be signalled by an exception.
		 */
		public void popPropertyNames() {
			final Set<String> notExtractedProperties = propertySets.pop();
			notExtractedProperties.forEach(this::addNotExtractedProperty);
			if (!propertySets.isEmpty()) {
				propertyPath.pop();
			}
			lastExtractedProperty.pop();
		}

		/**
		 * Records information about property extraction - i.e. successful propagation to result Java object.
		 */
		public void addExtractedProperty(@Nonnull String propertyName) {
			final Set<String> currentProperties = Objects.requireNonNull(propertySets.peek());
			currentProperties.remove(propertyName);
			lastExtractedProperty.pop();
			lastExtractedProperty.push(propertyName);
		}

		/**
		 * Allows to track position in the extracted arrays.
		 */
		public void pushIndex(int index) {
			propertyPath.push("[" + index + "]");
		}

		/**
		 * Clears information about position in the array.
		 */
		public void popIndex() {
			if (!propertySets.isEmpty()) {
				final String popped = propertyPath.pop();
				Assert.isPremiseValid(popped.charAt(0) == '[', "Sanity check!");
			}
		}
	}

}
