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

package io.evitadb.utils;

import io.evitadb.dataType.data.ReflectionCachingBehaviour;
import io.evitadb.dataType.map.WeakConcurrentMap;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * This class provides access to the reflection information of the classes (i.e. annotated fields and methods).
 * Information is cached in production instance to provide optimal performance lookups.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
@RequiredArgsConstructor
public class ReflectionLookup {
	public final static ReflectionLookup NO_CACHE_INSTANCE = new ReflectionLookup(ReflectionCachingBehaviour.NO_CACHE);
	private final WeakConcurrentMap<Class<?>, Map<ConstructorKey, Constructor<?>>> constructorCache = new WeakConcurrentMap<>();
	private final WeakConcurrentMap<Class<?>, List<Annotation>> classCache = new WeakConcurrentMap<>();
	private final WeakConcurrentMap<Class<?>, Map<Field, List<Annotation>>> fieldCache = new WeakConcurrentMap<>();
	private final WeakConcurrentMap<Class<?>, Map<Method, List<Annotation>>> methodCache = new WeakConcurrentMap<>();
	private final WeakConcurrentMap<Class<?>, Map<String, PropertyDescriptor>> propertiesCache = new WeakConcurrentMap<>();
	private final WeakConcurrentMap<Class<?>, List<Method>> gettersWithCorrespondingSetterOrConstructor = new WeakConcurrentMap<>();
	private final WeakConcurrentMap<Class<?>, Set<Class<?>>> interfacesCache = new WeakConcurrentMap<>();
	private final WeakConcurrentMap<MethodAndPackage, Boolean> samePackageAnnotation = new WeakConcurrentMap<>();
	private final ReflectionCachingBehaviour cachingBehaviour;

	/**
	 * Returns property name from getter method (i.e. method starting with get/is).
	 */
	@Nonnull
	public static String getPropertyNameFromMethodName(@Nonnull String methodName) {
		return getPropertyNameFromMethodNameIfPossible(methodName)
			.orElseThrow(() -> new IllegalArgumentException("Method " + methodName + " must start with get or is in order to store localized label value!"));
	}

	/**
	 * Returns property name from getter method (i.e. method starting with get/is).
	 */
	@Nonnull
	public static Optional<String> getPropertyNameFromMethodNameIfPossible(@Nonnull String methodName) {
		if (methodName.startsWith("get") && methodName.length() > 3 && Character.isUpperCase(methodName.charAt(3))) {
			return of(StringUtils.uncapitalize(methodName.substring(3)));
		} else if (methodName.startsWith("is") && methodName.length() > 2 && Character.isUpperCase(methodName.charAt(2))) {
			return of(StringUtils.uncapitalize(methodName.substring(2)));
		} else if (methodName.startsWith("set") && methodName.length() > 3 && Character.isUpperCase(methodName.charAt(3))) {
			return of(StringUtils.uncapitalize(methodName.substring(3)));
		} else {
			return empty();
		}
	}

	/**
	 * When passed type is an array, its component type is returned, otherwise the type is returned back without any
	 * additional logic attached.
	 */
	@Nonnull
	public static Class<?> getSimpleType(@Nonnull Class<?> ofType) {
		return ofType.isArray() ? ofType.getComponentType() : ofType;
	}

	/**
	 * Returns all interfaces declared by passed `aClass` or transitively by those interfaces.
	 */
	private static Collection<Class<?>> getAllDeclaredInterfaces(@Nonnull Class<?> aClass) {
		return Stream.of(
				Arrays.stream(aClass.getInterfaces()),
				Arrays.stream(aClass.getInterfaces()).flatMap(it -> getAllDeclaredInterfaces(it).stream())
			)
			.flatMap(it -> it)
			.collect(Collectors.toList());
	}

	/**
	 * Return all interfaces that the given class implements as a Set,
	 * including ones implemented by superclasses.
	 * If the class itself is an interface, it gets returned as single interface.
	 *
	 * @param clazz the class to analyze for interfaces
	 * @return all interfaces that the given object implements
	 */
	@Nonnull
	private static Set<Class<?>> getAllInterfacesForClassAsSet(@Nonnull Class<?> clazz) {
		Assert.notNull(clazz, "Class must not be null");
		if (clazz.isInterface()) {
			return singleton(clazz);
		}
		final Set<Class<?>> interfaces = new LinkedHashSet<>();
		Class<?> current = clazz;
		while (current != null) {
			final Class<?>[] ifcs = current.getInterfaces();
			for (Class<?> ifc : ifcs) {
				interfaces.addAll(getAllInterfacesForClassAsSet(ifc));
			}
			current = current.getSuperclass();
		}
		return interfaces;
	}

	/**
	 * Method finds default (non-arg) constructor of the target class.
	 */
	@Nonnull
	public <T> Constructor<T> findDefaultConstructor(@Nonnull Class<T> onClass) {
		final Map<ConstructorKey, Constructor<?>> cachedConstructors = mapAndCacheConstructors(onClass);
		final Constructor<?> defaultConstructor = cachedConstructors.get(new ConstructorKey(onClass, emptySet()));
		Assert.notNull(defaultConstructor, "No non-arg constructor found on class: " + onClass);
		//noinspection unchecked
		return (Constructor<T>) defaultConstructor;
	}

	/**
	 * Method finds default (non-arg) constructor of the target class.
	 */
	@Nullable
	public <T> Constructor<T> findAnyMatchingConstructor(@Nonnull Class<T> onClass, Set<ArgumentKey> propertyNames) {
		final Map<ConstructorKey, Constructor<?>> cachedConstructors = mapAndCacheConstructors(onClass);
		for (Entry<ConstructorKey, Constructor<?>> entry : cachedConstructors.entrySet()) {
			final ConstructorKey constructorKey = entry.getKey();
			if (propertyNames.containsAll(constructorKey.arguments())) {
				//noinspection unchecked
				return (Constructor<T>) entry.getValue();
			}
		}
		return null;
	}

	/**
	 * Method finds any constructor that match any combination of passed property names of the target class.
	 */
	@Nonnull
	public <T> Constructor<T> findConstructor(@Nonnull Class<T> onClass, Set<ArgumentKey> propertyNames) {
		final Map<ConstructorKey, Constructor<?>> cachedConstructors = mapAndCacheConstructors(onClass);
		final Constructor<?> argConstructor = cachedConstructors.get(new ConstructorKey(onClass, propertyNames));
		Assert.isTrue(
			argConstructor != null,
			"Constructor that would inject properties " +
				propertyNames
					.stream()
					.map(ArgumentKey::getName)
					.collect(Collectors.joining(", ")) +
				" not found on class: " + onClass
		);
		//noinspection unchecked
		return (Constructor<T>) argConstructor;
	}

	/**
	 * Returns appropriate field for passed propertyName.
	 */
	@Nullable
	public Field findPropertyField(@Nonnull Class<?> onClass, @Nonnull String propertyName) {
		Map<String, PropertyDescriptor> index = propertiesCache.get(onClass);
		if (index == null) {
			index = mapAndCacheGettersAndSetters(onClass);
		}
		final PropertyDescriptor getterSetterTuple = index.get(propertyName);
		return getterSetterTuple == null ? null : getterSetterTuple.field();
	}

	/**
	 * Returns appropriate setter method for passed getter method.
	 */
	@Nullable
	public Method findSetter(@Nonnull Class<?> onClass, @Nonnull Method method) {
		final String propertyName = getPropertyNameFromMethodName(method.getName());
		return findSetter(onClass, propertyName);
	}

	/**
	 * Returns appropriate setter method for passed getter method.
	 */
	@Nullable
	public Method findSetter(@Nonnull Class<?> onClass, @Nonnull String propertyName) {
		Map<String, PropertyDescriptor> index = propertiesCache.get(onClass);
		if (index == null) {
			index = mapAndCacheGettersAndSetters(onClass);
		}
		final PropertyDescriptor getterSetterTuple = index.get(propertyName);
		return getterSetterTuple == null ? null : getterSetterTuple.setter();
	}

	/**
	 * Returns all getters found on particular class or its superclasses. Ie. all getters that can be successfully called
	 * on class instance.
	 */
	@Nonnull
	public Collection<Method> findAllGetters(@Nonnull Class<?> onClass) {
		Map<String, PropertyDescriptor> index = propertiesCache.get(onClass);
		if (index == null) {
			index = mapAndCacheGettersAndSetters(onClass);
		}
		return index.values()
			.stream()
			.map(PropertyDescriptor::getter)
			.filter(Objects::nonNull)
			.collect(Collectors.toList());
	}

	/**
	 * Returns all getters that have corresponding setter for them (i.e. property is read/write) found on particular
	 * class or its superclasses. Ie. returns all getters that can be successfully called on class instance and which
	 * value can be written back.
	 */
	@Nonnull
	public Collection<Method> findAllGettersHavingCorrespondingSetter(@Nonnull Class<?> onClass) {
		Map<String, PropertyDescriptor> index = propertiesCache.get(onClass);
		if (index == null) {
			index = mapAndCacheGettersAndSetters(onClass);
		}
		return index.values()
			.stream()
			.filter(it -> it.getter() != null && it.setter() != null)
			.map(PropertyDescriptor::getter)
			.collect(Collectors.toList());
	}

	/**
	 * Returns all getters that have corresponding setter for them (i.e. property is read/write) found on particular
	 * class or its superclasses. Ie. returns all getters that can be successfully called on class instance and which
	 * value can be written back.
	 *
	 * It also returns getters that do have not appropriate setter, but target property that is possible to pass as
	 * constructor argument of the class.
	 */
	@Nonnull
	public Collection<Method> findAllGettersHavingCorrespondingSetterOrConstructorArgument(@Nonnull Class<?> onClass) {
		final List<Method> cachedGetters = gettersWithCorrespondingSetterOrConstructor.get(onClass);
		if (cachedGetters == null) {
			final List<Method> resolvedGetters = resolveGettersCorrespondingToSettersOrConstructorArgument(onClass);
			if (cachingBehaviour == ReflectionCachingBehaviour.CACHE) {
				gettersWithCorrespondingSetterOrConstructor.put(onClass, resolvedGetters);
			}
			return resolvedGetters;
		} else {
			return cachedGetters;
		}
	}

	/**
	 * Returns all getters that have passed `annotationType` defined on them.
	 */
	public <T extends Annotation> List<Method> findAllGettersHavingAnnotation(@Nonnull Class<?> onClass, @Nonnull Class<T> annotationType) {
		return findAllGetters(onClass)
			.stream()
			.filter(it -> getAnnotationInstance(it, annotationType) != null)
			.toList();
	}

	/**
	 * Returns all setters found on particular class or its superclasses. Ie. all setters that can be successfully called
	 * on class instance.
	 */
	@Nonnull
	public Collection<Method> findAllSetters(@Nonnull Class<?> onClass) {
		Map<String, PropertyDescriptor> index = propertiesCache.get(onClass);
		if (index == null) {
			index = mapAndCacheGettersAndSetters(onClass);
		}
		return index.values()
			.stream()
			.map(PropertyDescriptor::setter)
			.filter(Objects::nonNull)
			.collect(Collectors.toList());
	}

	/**
	 * Returns all setters that have corresponding getter for them (i.e. property is read/write) found on particular
	 * class or its superclasses. Ie. returns all setters that can be successfully called on class instance and which
	 * value can be read back.
	 */
	@Nonnull
	public Collection<Method> findAllSettersHavingCorrespondingSetter(@Nonnull Class<?> onClass) {
		Map<String, PropertyDescriptor> index = propertiesCache.get(onClass);
		if (index == null) {
			index = mapAndCacheGettersAndSetters(onClass);
		}
		return index.values()
			.stream()
			.filter(it -> it.getter() != null && it.setter() != null)
			.map(PropertyDescriptor::setter)
			.collect(Collectors.toList());
	}

	/**
	 * Returns appropriate getter method for passed getter method.
	 */
	@Nullable
	public Method findGetter(@Nonnull Class<?> onClass, @Nonnull Method method) {
		final String propertyName = getPropertyNameFromMethodName(method.getName());
		return findGetter(onClass, propertyName);
	}

	/**
	 * Returns appropriate getter method for passed getter method.
	 */
	@Nullable
	public Method findGetter(@Nonnull Class<?> onClass, @Nonnull String propertyName) {
		Map<String, PropertyDescriptor> index = propertiesCache.get(onClass);
		if (index == null) {
			index = mapAndCacheGettersAndSetters(onClass);
		}
		final PropertyDescriptor getterSetterTuple = index.get(propertyName);
		return getterSetterTuple == null ? null : getterSetterTuple.getter();
	}

	/**
	 * Returns all annotations from the class. Annotations are also looked up in superclass/implements hierarchy.
	 * Annotations on annotation are also taken into account.
	 */
	@Nonnull
	public List<Annotation> getClassAnnotations(@Nonnull Class<?> type) {
		List<Annotation> cachedInformations = classCache.get(type);
		if (cachedInformations == null) {
			final Set<Annotation> informations = new LinkedHashSet<>(16);
			final Set<Class<?>> alreadyDetectedAnnotations = new HashSet<>();
			if (!Objects.equals(Object.class, type)) {
				getClassAnnotationsThroughSuperClasses(informations, type, alreadyDetectedAnnotations);
			}
			cachedInformations = new ArrayList<>(informations);
			if (cachingBehaviour == ReflectionCachingBehaviour.CACHE) {
				classCache.put(type, cachedInformations);
			}
		}

		return cachedInformations;
	}

	/**
	 * Returns closest annotation of certain type from the class. Annotations are also looked up in superclass/implements hierarchy.
	 * Annotations on annotation are also taken into account.
	 */
	@Nullable
	public <T extends Annotation> T getClassAnnotation(@Nonnull Class<?> type, @Nonnull Class<T> annotationType) {
		final List<T> result = getClassAnnotations(type, annotationType);
		return result.isEmpty() ? null : result.get(0);
	}

	/**
	 * Returns all annotations of certain type from the class. Annotations are also looked up in superclass/implements hierarchy.
	 * Annotations on annotation are also taken into account.
	 */
	@Nonnull
	public <T extends Annotation> List<T> getClassAnnotations(@Nonnull Class<?> type, @Nonnull Class<T> annotationType) {
		final List<Annotation> cachedInformation = getClassAnnotations(type);
		final List<T> result = new ArrayList<>(cachedInformation.size());
		final Class<?> containerAnnotation = getRepeatableContainerAnnotation(annotationType);
		for (Annotation annotation : cachedInformation) {
			addAnnotationIfMatches(annotationType, containerAnnotation, result, annotation);
		}

		return result;
	}

	/**
	 * Returns true if method has at least one annotation in the same package as the passed annotation.
	 */
	public boolean hasAnnotationInSamePackage(@Nonnull Method method, @Nonnull Class<? extends Annotation> annotation) {
		return samePackageAnnotation.computeIfAbsent(
			new MethodAndPackage(method, annotation.getPackage()),
			tuple -> Arrays.stream(tuple.method().getAnnotations())
				.anyMatch(it -> Objects.equals(it.getClass().getPackage(), tuple.annotationPackage()))
		);
	}

	/**
	 * Returns index of all fields with list of annotations used on them. Fields are also looked up in superclass
	 * hierarchy. Annotations on annotation are also taken into account.
	 */
	@Nonnull
	public Map<Field, List<Annotation>> getFields(@Nonnull Class<?> type) {
		Map<Field, List<Annotation>> cachedInformations = fieldCache.get(type);

		if (cachedInformations == null) {
			cachedInformations = new LinkedHashMap<>(16);
			final Set<String> foundFields = new HashSet<>();
			if (!Objects.equals(Object.class, type)) {
				getFieldAnnotationsThroughSuperClasses(cachedInformations, foundFields, type);
			}
			if (cachingBehaviour == ReflectionCachingBehaviour.CACHE) {
				fieldCache.put(type, cachedInformations);
			}
		}

		return cachedInformations;
	}

	/**
	 * Returns index of all fields with certain type of annotation. Fields are also looked up in superclass hierarchy.
	 * Annotations on annotation are also taken into account.
	 */
	@Nonnull
	public <T extends Annotation> Map<Field, List<T>> getFields(@Nonnull Class<?> type, @Nonnull Class<T> annotationType) {
		final Map<Field, List<Annotation>> cachedInformations = getFields(type);
		final Map<Field, List<T>> result = createLinkedHashMap(cachedInformations.size());
		final Class<?> containerAnnotation = getRepeatableContainerAnnotation(annotationType);
		for (Entry<Field, List<Annotation>> entry : cachedInformations.entrySet()) {
			final List<T> fieldResult = new LinkedList<>();
			for (Annotation annotation : entry.getValue()) {
				addAnnotationIfMatches(annotationType, containerAnnotation, fieldResult, annotation);
			}
			if (!fieldResult.isEmpty()) {
				result.put(entry.getKey(), fieldResult);
			}
		}

		return result;
	}

	/**
	 * Returns index of all methods with list of annotations used on them. Methods are also looked up in superclass
	 * and implements hierarchy. Annotations on annotation are also taken into account.
	 */
	@Nonnull
	public Map<Method, List<Annotation>> getMethods(@Nonnull Class<?> type) {
		Map<Method, List<Annotation>> cachedInformations = methodCache.get(type);

		if (cachedInformations == null) {
			cachedInformations = new LinkedHashMap<>(16);
			final Set<MethodAnnotationKey> foundMethodAnnotations = new HashSet<>();
			Class<?> tmpClass = type;
			if (!Objects.equals(Object.class, tmpClass)) {
				do {
					registerMethods(cachedInformations, foundMethodAnnotations, tmpClass);
					tmpClass = tmpClass.getSuperclass();
				} while (tmpClass != null && !Objects.equals(Object.class, tmpClass));
			}

			final Set<Class<?>> interfaces = getAllImplementedInterfaces(type);
			for (Class<?> anInterface : interfaces) {
				registerMethods(cachedInformations, foundMethodAnnotations, anInterface);
			}

			if (cachingBehaviour == ReflectionCachingBehaviour.CACHE) {
				methodCache.put(type, cachedInformations);
			}
		}

		return cachedInformations;
	}

	/**
	 * Returns index of all methods with certain type of annotation. Methods are also looked up in superclass / implements
	 * hierarchy. Annotations on annotation are also taken into account.
	 */
	@Nonnull
	public <T extends Annotation> Map<Method, List<T>> getMethods(@Nonnull Class<?> type, @Nonnull Class<T> annotationType) {
		final Map<Method, List<Annotation>> cachedInformation = getMethods(type);
		final Map<Method, List<T>> result = createLinkedHashMap(cachedInformation.size());
		final Class<?> containerAnnotation = getRepeatableContainerAnnotation(annotationType);
		for (Entry<Method, List<Annotation>> entry : cachedInformation.entrySet()) {
			final List<T> methodResult = new LinkedList<>();
			for (Annotation annotation : entry.getValue()) {
				addAnnotationIfMatches(annotationType, containerAnnotation, methodResult, annotation);
			}
			if (!methodResult.isEmpty()) {
				result.put(entry.getKey(), methodResult);
			}
		}

		return result;
	}

	/**
	 * Returns annotation of certain type on certain field.
	 * Annotations on annotation are also taken into account.
	 * First matching annotation is returned.
	 */
	@Nullable
	public <T extends Annotation> T getAnnotationInstance(@Nonnull Field field, @Nonnull Class<T> annotationType) {
		final T annotation = field.getAnnotation(annotationType);
		if (annotation == null) {
			for (Annotation fieldAnnotation : field.getAnnotations()) {
				final T result = getAnnotation(annotationType, fieldAnnotation.annotationType(), new LinkedList<>());
				if (result != null) {
					return result;
				}
			}
			return null;
		} else {
			return annotation;
		}
	}

	/**
	 * Returns annotation of certain type on certain method.
	 * Annotations on annotation are also taken into account.
	 * First matching annotation is returned.
	 */
	@Nullable
	public <T extends Annotation> T getAnnotationInstance(@Nonnull Method method, @Nonnull Class<T> annotationType) {
		final T annotation = method.getAnnotation(annotationType);
		if (annotation == null) {
			for (Annotation fieldAnnotation : method.getAnnotations()) {
				final T result = getAnnotation(annotationType, fieldAnnotation.annotationType(), new LinkedList<>());
				if (result != null) {
					return result;
				}
			}
			return null;
		} else {
			return annotation;
		}
	}

	/**
	 * Returns annotation of certain type on certain method. If no annotation is found on the method, annotation is
	 * looked up on field of appropriate property name.
	 * Annotations on annotation are also taken into account.
	 * First matching annotation is returned.
	 */
	@Nullable
	public <T extends Annotation> T getAnnotationInstanceForProperty(@Nonnull Method method, @Nonnull Class<T> annotationType) {
		return ofNullable(getAnnotationInstance(method, annotationType))
			.orElseGet(() -> {
				final String propertyName = getPropertyNameFromMethodName(method.getName());
				final Field propertyField = findPropertyField(method.getDeclaringClass(), propertyName);
				if (propertyField == null) {
					return null;
				} else {
					final Map<Field, List<T>> annotatedFields = getFields(method.getDeclaringClass(), annotationType);
					final List<T> annotations = annotatedFields.get(propertyField);
					if (annotations == null || annotations.isEmpty()) {
						return null;
					} else {
						return annotations.get(0);
					}
				}
			});
	}

	/**
	 * Extracts specific class for generic one.
	 */
	@Nonnull
	public Class<? extends Serializable> extractGenericType(@Nonnull Type genericReturnType, int position) {
		Assert.isTrue(genericReturnType instanceof ParameterizedType, "Cannot infer generic class from: " + genericReturnType);
		final ParameterizedType parameterizedType = (ParameterizedType) genericReturnType;
		final Type actualType = parameterizedType.getActualTypeArguments()[position];
		Assert.isTrue(actualType instanceof Class, "Cannot infer generic class from: " + genericReturnType);
		//noinspection unchecked
		return (Class<? extends Serializable>) actualType;
	}

	/**
	 * Returns set of all interfaces that are implemented by passed class.
	 */
	@Nonnull
	public Set<Class<?>> getAllImplementedInterfaces(@Nonnull Class<?> aClass) {
		final Set<Class<?>> ifaces = interfacesCache.get(aClass);
		if (ifaces == null) {
			final Set<Class<?>> mainInterfaces = getAllInterfacesForClassAsSet(aClass);
			final Set<Class<?>> resolvedIfaces = new HashSet<>(mainInterfaces);
			mainInterfaces.forEach(it -> resolvedIfaces.addAll(getAllDeclaredInterfaces(it)));
			if (cachingBehaviour == ReflectionCachingBehaviour.CACHE) {
				interfacesCache.put(aClass, resolvedIfaces);
			}
			return resolvedIfaces;
		}
		return ifaces;
	}

	private static void registerMethods(Map<Method, List<Annotation>> cachedInformations, Set<MethodAnnotationKey> foundMethodAnnotations, Class<?> tmpClass) {
		for (Method method : tmpClass.getDeclaredMethods()) {
			final Annotation[] someAnnotation = method.getAnnotations();
			if (someAnnotation != null && someAnnotation.length > 0) {
				method.setAccessible(true);
				for (Annotation annotation : someAnnotation) {
					final MethodAnnotationKey methodAnnotationKey = new MethodAnnotationKey(method.getName(), annotation);
					if (!foundMethodAnnotations.contains(methodAnnotationKey)) {
						List<Annotation> cachedAnnotations = cachedInformations.computeIfAbsent(method, k -> new LinkedList<>());
						cachedAnnotations.add(annotation);
						foundMethodAnnotations.add(methodAnnotationKey);
					}
				}
			}
		}
	}

	@Nullable
	private static <T extends Annotation> Class<?> getRepeatableContainerAnnotation(Class<T> annotationType) {
		final Class<?> containerAnnotation;
		final Repeatable repeatable = annotationType.getAnnotation(Repeatable.class);
		if (repeatable != null) {
			containerAnnotation = repeatable.value();
		} else {
			containerAnnotation = null;
		}
		return containerAnnotation;
	}

	private static <T extends Annotation> void addAnnotationIfMatches(Class<T> annotationType, Class<?> containerAnnotation, List<T> fieldResult, Annotation annotation) {
		if (annotationType.isInstance(annotation)) {
			//noinspection unchecked
			fieldResult.add((T) annotation);
		}
		if (ofNullable(containerAnnotation).map(it -> containerAnnotation.isInstance(annotation)).orElse(false)) {
			final Annotation[] wrappedAnnotations;
			try {
				wrappedAnnotations = (Annotation[]) annotation.getClass().getMethod("value").invoke(annotation);
			} catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
				throw new IllegalArgumentException("Repeatable annotation unwind error: " + e.getMessage(), e);
			}
			for (Annotation wrappedAnnotation : wrappedAnnotations) {
				//noinspection unchecked
				fieldResult.add((T) wrappedAnnotation);
			}
		}
	}

	private static <T extends Annotation> T getAnnotation(Class<T> searchedAnnotationType, Class<?> type, Deque<Class<? extends Annotation>> processedAnnotations) {
		final Annotation[] annotations = type.getAnnotations();
		for (Annotation annItem : annotations) {
			if (searchedAnnotationType.isInstance(annItem)) {
				//noinspection unchecked
				return (T) annItem;
			}
			final Class<? extends Annotation> annType = annItem.annotationType();
			try {
				processedAnnotations.push(annType);
				final T innerAnnotation = annType.getAnnotation(searchedAnnotationType);
				if (innerAnnotation == null) {
					if (!processedAnnotations.contains(annType)) {
						return getAnnotation(searchedAnnotationType, annType, processedAnnotations);
					}
				} else {
					return innerAnnotation;
				}
			} finally {
				processedAnnotations.pop();
			}
		}
		return null;
	}

	private static List<Annotation> expand(Annotation[] annotation) {
		if (ArrayUtils.isEmpty(annotation)) {
			return emptyList();
		} else {
			final List<Annotation> allAnnotations = new LinkedList<>();
			for (Annotation annItem : annotation) {
				if (!annItem.annotationType().getName().startsWith("java.lang.annotation")) {
					allAnnotations.add(annItem);
					allAnnotations.addAll(expand(annItem.annotationType().getAnnotations()));
				}
			}
			return allAnnotations;
		}
	}

	private Map<String, PropertyDescriptor> mapAndCacheGettersAndSetters(@Nonnull Class<?> onClass) {
		final Map<String, PropertyDescriptor> index = mapGettersAndSetters(onClass);
		if (cachingBehaviour == ReflectionCachingBehaviour.CACHE) {
			propertiesCache.put(onClass, index);
		}
		return index;
	}

	private static Map<String, PropertyDescriptor> mapGettersAndSetters(Class<?> onClass) {
		final Map<String, PropertyDescriptor> result = new LinkedHashMap<>();
		final Method[] methods = onClass.getMethods();
		for (Method method : methods) {
			final String methodName = method.getName();
			final String propertyName;
			final boolean isGetter;
			if (methodName.startsWith("get") && methodName.length() > 3 && Character.isUpperCase(methodName.charAt(3)) && !void.class.equals(method.getReturnType()) && method.getParameterCount() == 0) {
				propertyName = StringUtils.uncapitalize(methodName.substring(3));
				isGetter = true;
			} else if (methodName.startsWith("is") && methodName.length() > 2 && Character.isUpperCase(methodName.charAt(2)) && !void.class.equals(method.getReturnType()) && method.getParameterCount() == 0) {
				propertyName = StringUtils.uncapitalize(methodName.substring(2));
				isGetter = true;
			} else if (methodName.startsWith("set") && methodName.length() > 3 && Character.isUpperCase(methodName.charAt(3)) && void.class.equals(method.getReturnType()) && method.getParameterCount() == 1) {
				propertyName = StringUtils.uncapitalize(methodName.substring(3));
				isGetter = false;
			} else {
				continue;
			}

			final Field propertyField = mapField(onClass, propertyName);
			registerPropertyDescriptor(result, method, propertyName, isGetter, propertyField);
		}
		return result;
	}

	private Map<ConstructorKey, Constructor<?>> mapAndCacheConstructors(@Nonnull Class<?> onClass) {
		final Map<ConstructorKey, Constructor<?>> cachedResult = constructorCache.get(onClass);
		if (cachedResult == null) {
			final Map<ConstructorKey, Constructor<?>> index = mapConstructors(onClass);
			if (cachingBehaviour == ReflectionCachingBehaviour.CACHE) {
				constructorCache.put(onClass, index);
			}
			return index;
		} else {
			return cachedResult;
		}
	}

	private static Map<ConstructorKey, Constructor<?>> mapConstructors(Class<?> onClass) {
		final HashMap<ConstructorKey, Constructor<?>> mappedConstructors = new HashMap<>();
		for (Constructor<?> constructor : onClass.getConstructors()) {
			try {
				final Parameter[] parameters = constructor.getParameters();
				Assert.isTrue(
					parameters.length == constructor.getParameterTypes().length,
					"Source file was not compiled with -parameters option. There are no names for constructor arguments!"
				);
				final LinkedHashSet<ArgumentKey> arguments = new LinkedHashSet<>(parameters.length);
				for (final Parameter parameter : parameters) {
					arguments.add(
						new ArgumentKey(
							parameter.getName(),
							parameter.getType()
						)
					);
				}
				mappedConstructors.put(
					new ConstructorKey(onClass, arguments),
					constructor
				);
			} catch (Exception ex) {
				log.error(
					"Constructor " + constructor.toGenericString() + " on class " + onClass +
						" is unusable for reflection access due to: " + ex.getMessage()
				);
			}
		}
		return mappedConstructors;
	}

	/**
	 * Computes list of all getter methods that match properties injected by constructor arguments along with all getter
	 * methods that have corresponding setter method.
	 */
	@Nonnull
	private List<Method> resolveGettersCorrespondingToSettersOrConstructorArgument(@Nonnull Class<?> onClass) {
		final Map<String, PropertyDescriptor> index = ofNullable(propertiesCache.get(onClass))
			.orElseGet(() -> mapAndCacheGettersAndSetters(onClass));

		final Stream<Method> simplePropertiesWithSetter = index.values()
			.stream()
			.filter(it -> it.getter() != null && it.setter() != null)
			.map(PropertyDescriptor::getter);

		final Map<String, Method> propertiesWithoutSetter = index.values()
			.stream()
			.filter(it -> it.getter() != null && it.setter() == null)
			.collect(
				Collectors.toMap(
					it -> getPropertyNameFromMethodName(it.getter().getName()),
					PropertyDescriptor::getter
				)
			);

		final List<WeightedConstructorKey> constructorsByBestFit = weightAndSortAllConstructors(onClass, propertiesWithoutSetter);

		if (constructorsByBestFit.isEmpty()) {
			return simplePropertiesWithSetter.collect(Collectors.toList());
		} else {
			final WeightedConstructorKey bestConstructor = constructorsByBestFit.get(0);
			final long bestFitWeight = bestConstructor.weight();
			if (bestFitWeight > 0) {
				final List<Method> gettersCombined = getGettersForSettersAndBestConstructor(
					simplePropertiesWithSetter, propertiesWithoutSetter, constructorsByBestFit,
					bestConstructor, bestFitWeight
				);
				if (gettersCombined != null) {
					return gettersCombined;
				}
			}
			return simplePropertiesWithSetter.collect(Collectors.toList());
		}
	}

	/**
	 * Returns getter method for all setters and also getters that return value injected by constructor. If there are
	 * multiple constructors only "best" one is returned - i.e. the one that allows to inject biggest count of properties
	 * without setters. If there are multiple such constructors - none is used, because it represents ambiguous situation.
	 */
	@Nullable
	private static List<Method> getGettersForSettersAndBestConstructor(Stream<Method> simplePropertiesWithSetter, Map<String, Method> propertiesWithoutSetter, List<WeightedConstructorKey> constructorsByBestFit, WeightedConstructorKey bestConstructor, long bestFitWeight) {
		if (constructorsByBestFit.size() == 1) {
			return returnMethodsForBestConstructor(
				simplePropertiesWithSetter, propertiesWithoutSetter, bestConstructor
			);
		} else {
			final Set<WeightedConstructorKey> collidingConstructors = new HashSet<>(constructorsByBestFit.size());
			collidingConstructors.add(bestConstructor);
			for (int i = 1; i < constructorsByBestFit.size(); i++) {
				final WeightedConstructorKey weightedConstructorKey = constructorsByBestFit.get(i);
				if (weightedConstructorKey.weight() == bestFitWeight) {
					collidingConstructors.add(weightedConstructorKey);
				} else {
					return returnMethodsForBestConstructor(
						simplePropertiesWithSetter, propertiesWithoutSetter, bestConstructor
					);
				}
			}

			log.error(
				"Multiple constructors fit to read-only getters: " +
					collidingConstructors
						.stream()
						.map(WeightedConstructorKey::toString)
						.collect(Collectors.joining(", "))
			);
		}
		return null;
	}

	/**
	 * Method will assign weight for each constructor. Weight is simple count of all arguments of the constructor argument
	 * that match properties without setter method. All types of the arguments must match and there must not be any other
	 * non-paired argument.
	 */
	@Nonnull
	private List<WeightedConstructorKey> weightAndSortAllConstructors(@Nonnull Class<?> onClass, Map<String, Method> propertiesInjectedByConstructor) {
		return mapAndCacheConstructors(onClass)
			.keySet()
			.stream()
			.map(it -> {
				final WeightedConstructorKey weightedConstructorKey = new WeightedConstructorKey(
					it,
					it.arguments()
						.stream()
						.filter(x ->
							ofNullable(propertiesInjectedByConstructor.get(x.getName()))
								.filter(y -> y.getReturnType().isAssignableFrom(x.getType()))
								.isPresent()
						)
						.count()
				);
				if (weightedConstructorKey.weight() == weightedConstructorKey.constructorKey().arguments().size()) {
					return weightedConstructorKey;
				} else {
					return new WeightedConstructorKey(it, -1);
				}
			})
			.sorted(Comparator.comparingLong(WeightedConstructorKey::weight).reversed())
			.collect(Collectors.toList());
	}

	/**
	 * Method returns all getter methods that match properties injected by constructor arguments along with all getter
	 * methods that have corresponding setter method.
	 */
	@Nonnull
	private static List<Method> returnMethodsForBestConstructor(Stream<Method> simplePropertiesWithSetter, Map<String, Method> propertiesInjectedByConstructor, WeightedConstructorKey bestConstructor) {
		final Set<String> constructorArgs = bestConstructor.constructorKey().arguments()
			.stream()
			.map(ArgumentKey::getName)
			.collect(Collectors.toSet());

		final Stream<Method> propertiesWithConstructor = propertiesInjectedByConstructor
			.entrySet()
			.stream()
			.filter(it -> constructorArgs.contains(it.getKey()))
			.map(Entry::getValue);

		return Stream.concat(
			simplePropertiesWithSetter,
			propertiesWithConstructor
		).collect(Collectors.toList());
	}

	/**
	 * Finds field on passed class. If field is not found, it traverses through super classes to find it.
	 */
	private static Field mapField(@Nonnull Class<?> onClass, @Nonnull String propertyName) {
		try {
			return onClass.getDeclaredField(propertyName);
		} catch (NoSuchFieldException e) {
			if (Object.class.equals(onClass) || onClass.getSuperclass() == null) {
				return null;
			} else {
				return mapField(onClass.getSuperclass(), propertyName);
			}
		}
	}

	/**
	 * Goes through inheritance chain and looks up for annotations.
	 */
	private static void getFieldAnnotationsThroughSuperClasses(Map<Field, List<Annotation>> annotations, Set<String> foundFields, Class<?> examinedClass) {
		do {
			for (Field field : examinedClass.getDeclaredFields()) {
				final Annotation[] someAnnotation = field.getAnnotations();
				if (someAnnotation != null && someAnnotation.length > 0 && !foundFields.contains(field.getName())) {
					field.setAccessible(true);
					annotations.put(field, expand(someAnnotation));
					foundFields.add(field.getName());
				}
			}
			examinedClass = examinedClass.getSuperclass();
		} while (examinedClass != null && !Objects.equals(Object.class, examinedClass));
	}

	/**
	 * Goes through inheritance chain and looks up for annotations.
	 */
	private void getClassAnnotationsThroughSuperClasses(Set<Annotation> annotations, Class<?> examinedClass, Set<Class<?>> alreadyDetectedAnnotations) {
		do {
			final Annotation[] someAnnotation = examinedClass.getAnnotations();
			final Set<Class<?>> addedAnnotationsInThisRound = new HashSet<>();
			if (someAnnotation.length > 0) {
				for (Annotation annotation : expand(someAnnotation)) {
					processRepeatableAnnotations(annotations, alreadyDetectedAnnotations, addedAnnotationsInThisRound, annotation);
				}
			}
			for (Class<?> implementedInterface : examinedClass.getInterfaces()) {
				final List<Annotation> interfaceAnnotation = getClassAnnotations(implementedInterface);
				if (!interfaceAnnotation.isEmpty()) {
					annotations.addAll(expand(interfaceAnnotation.toArray(new Annotation[0])));
				}
			}

			alreadyDetectedAnnotations.addAll(addedAnnotationsInThisRound);
			examinedClass = examinedClass.getSuperclass();
		} while (examinedClass != null && !Objects.equals(Object.class, examinedClass));
	}

	private static void processRepeatableAnnotations(Set<Annotation> annotations, Set<Class<?>> alreadyDetectedAnnotations, Set<Class<?>> addedAnnotationsInThisRound, Annotation annotation) {
		final Class<?> containerAnnotation = getRepeatableContainerAnnotation(annotation.annotationType());
		if (!alreadyDetectedAnnotations.contains(annotation.annotationType()) && (containerAnnotation == null || !alreadyDetectedAnnotations.contains(containerAnnotation))) {
			annotations.add(annotation);
			addedAnnotationsInThisRound.add(annotation.annotationType());
			ofNullable(containerAnnotation).ifPresent(addedAnnotationsInThisRound::add);
		}
	}

	/**
	 * Registers property descriptor.
	 */
	private static void registerPropertyDescriptor(Map<String, PropertyDescriptor> result, Method method, String propertyName, boolean isGetter, Field propertyField) {
		final PropertyDescriptor existingTuple = result.get(propertyName);
		if (existingTuple == null) {
			result.put(
				propertyName,
				new PropertyDescriptor(
					propertyField,
					isGetter ? method : null,
					isGetter ? null : method
				)
			);
		} else {
			result.put(
				propertyName,
				new PropertyDescriptor(
					propertyField,
					isGetter ? method : existingTuple.getter(),
					isGetter ? existingTuple.setter() : method
				)
			);
		}
	}

	private record MethodAnnotationKey(String methodName, Annotation annotation) {
	}

	private record PropertyDescriptor(Field field, Method getter, Method setter) {
	}

	/**
	 * Constructor key identifies certain arg specific constructor.
	 *
	 * @param arguments this SHOULD BE LinkedHashSet implementation or emptySet
	 */

	private record ConstructorKey(Class<?> type, Set<ArgumentKey> arguments) {
		@Override
		public String toString() {
			return type.getSimpleName() + "(" +
				arguments.stream()
					.map(it -> it.getType().getSimpleName() + " " + it.getName())
					.collect(Collectors.joining(", "))
				+ ")";
		}
	}

	/**
	 * Contains constructor key and its weight (i.e. match with non-assigned properties).
	 */

	private record WeightedConstructorKey(ConstructorKey constructorKey, long weight) {
		@Override
		public String toString() {
			return constructorKey.toString();
		}
	}

	/**
	 * Argument key is used for looking up for constructors. Type is not used in equals and hash code because we would
	 * like to search by property name and type may not exactly match (i.e. it could be super type)
	 */
	@Data
	@EqualsAndHashCode(of = "name")
	public static class ArgumentKey {
		private final String name;
		private final Class<?> type;
	}

	/**
	 * Cache key for {@link #hasAnnotationInSamePackage(Method, Class)}.
	 */
	private record MethodAndPackage(
		@Nonnull Method method,
		@Nonnull Package annotationPackage) {

	}

}
