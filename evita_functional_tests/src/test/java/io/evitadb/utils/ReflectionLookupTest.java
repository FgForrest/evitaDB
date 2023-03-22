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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifies behaviour of {@link ReflectionLookup} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2020
 */
class ReflectionLookupTest {
	private final ReflectionLookup tested = new ReflectionLookup(ReflectionCachingBehaviour.NO_CACHE);

	void shouldReturnClassAnnotationsForSettingsClass() {
		final List<MarkedClass> annotations = tested.getClassAnnotations(SomeSettings.class, MarkedClass.class);
		assertEquals(2, annotations.size());
		assertEquals("SomeSettings", annotations.get(0).value());
		assertEquals("Transactional", annotations.get(1).value());
	}

	void shouldReturnClassAnnotationsForSettingsClassWithInterface() {
		final List<MarkedClass> annotations = tested.getClassAnnotations(ExtendedStandardSettings.class, MarkedClass.class);
		assertEquals(3, annotations.size());
		assertEquals("AnotherSettings", annotations.get(0).value());
		assertEquals("SomeSettings", annotations.get(1).value());
		assertEquals("Transactional", annotations.get(2).value());
	}

	void shouldReturnClassAnnotationsForSettingsClassWithOverriddenAnnotation() {
		final List<MarkedClass> annotations = tested.getClassAnnotations(ExtendedSettings.class, MarkedClass.class);
		assertEquals(2, annotations.size());
		assertEquals("Whatever", annotations.get(0).value());
		assertEquals("AnotherSettings", annotations.get(1).value());
	}

	void shouldReturnFieldAnnotationsForSettingsClass() {
		final Map<Field, List<ClearBeforeStore>> fields = tested.getFields(SomeSettings.class, ClearBeforeStore.class);
		assertEquals(1, fields.size());
		assertNotNull(getFieldByName(fields, "whatever"));
	}

	void shouldReturnFieldAnnotationsForClassWithAnotherAnnotations() {
		final Map<Field, List<TranslateToWidgetId>> fields = tested.getFields(SomeSettings.class, TranslateToWidgetId.class);
		assertEquals(1, fields.size());
		assertNotNull(getFieldByName(fields, "anotherField"));
	}

	void shouldReturnAllFieldsWithInheritedAnnotations() {
		final Map<Field, List<NodeReference>> fields = tested.getFields(SomeSettings.class, NodeReference.class);
		assertEquals(3, fields.size());
		assertNotNull(getFieldByName(fields, "someField"));
		assertNotNull(getFieldByName(fields, "anotherField"));
		assertNotNull(getFieldByName(fields, "expression"));
	}

	void shouldReturnProperFieldAnnotationInstance() {
		final Map<Field, List<NodeReference>> fields = tested.getFields(SomeSettings.class, NodeReference.class);
		assertTrue(tested.getAnnotationInstance(getFieldByName(fields, "someField"), NodeReference.class).exact());
		assertTrue(tested.getAnnotationInstance(getFieldByName(fields, "anotherField"), NodeReference.class).exact());
		assertFalse(tested.getAnnotationInstance(getFieldByName(fields, "expression"), NodeReference.class).exact());
	}

	void shouldReturnGettersAndSetters() throws Exception {
		final Class<PojoExample> examinedClass = PojoExample.class;
		assertNull(tested.findSetter(examinedClass, examinedClass.getMethod("getReadOnly")));
		assertNull(tested.findGetter(examinedClass, examinedClass.getMethod("setWriteOnly", String.class)));
		assertEquals(examinedClass.getMethod("isValid"), tested.findGetter(examinedClass, examinedClass.getMethod("setValid", boolean.class)));
		assertEquals(examinedClass.getMethod("getDate"), tested.findGetter(examinedClass, examinedClass.getMethod("setDate", LocalDateTime.class)));
		assertEquals(examinedClass.getMethod("getNumber"), tested.findGetter(examinedClass, examinedClass.getMethod("setNumber", int.class)));
		assertEquals(examinedClass.getMethod("getText"), tested.findGetter(examinedClass, examinedClass.getMethod("setText", String.class)));
		assertEquals(examinedClass.getMethod("setValid", boolean.class), tested.findSetter(examinedClass, examinedClass.getMethod("isValid")));
		assertEquals(examinedClass.getMethod("setDate", LocalDateTime.class), tested.findSetter(examinedClass, examinedClass.getMethod("getDate")));
		assertEquals(examinedClass.getMethod("setNumber", int.class), tested.findSetter(examinedClass, examinedClass.getMethod("getNumber")));
		assertEquals(examinedClass.getMethod("setText", String.class), tested.findSetter(examinedClass, examinedClass.getMethod("getText")));
	}

	void shouldReturnAllGetters() throws Exception {
		final Class<PojoExample> examinedClass = PojoExample.class;
		final Collection<Method> allGetters = tested.findAllGetters(examinedClass);
		assertEquals(6, allGetters.size());
	}

	void shouldReturnAllSetters() {
		final Class<PojoExample> examinedClass = PojoExample.class;
		final Collection<Method> allGetters = tested.findAllSetters(examinedClass);
		assertEquals(5, allGetters.size());
	}

	void shouldReturnAllGettersAndSettersHavingCorrespondingSetter() {
		final Class<PojoExample> examinedClass = PojoExample.class;
		final Collection<Method> allGetters = tested.findAllGettersHavingCorrespondingSetter(examinedClass);
		final Collection<Method> allSetters = tested.findAllSettersHavingCorrespondingSetter(examinedClass);
		assertEquals(4, allGetters.size());
		assertEquals(4, allSetters.size());
	}

	@Test
	void shouldRecognizeGenericsFromBasicCollections() {
		assertEquals(String.class, tested.extractGenericType(tested.findGetter(GenericsExample.class, "set").getGenericReturnType(), 0));
		assertEquals(String.class, tested.extractGenericType(tested.findGetter(GenericsExample.class, "list").getGenericReturnType(), 0));
		assertEquals(String.class, tested.extractGenericType(tested.findGetter(GenericsExample.class, "map").getGenericReturnType(), 0));
		assertEquals(Integer.class, tested.extractGenericType(tested.findGetter(GenericsExample.class, "map").getGenericReturnType(), 1));
	}

	@Test
	void shouldFindPropertyField() {
		assertNotNull(tested.findPropertyField(PropertyClassExample.class, "propertyA"));
		assertNotNull(tested.findPropertyField(PropertyClassExample.class, "propertyB"));
		assertNotNull(tested.findPropertyField(PropertyClassExample.class, "propertyD"));
		assertNull(tested.findPropertyField(PropertyClassExample.class, "propertyC"));
	}

	@Test
	void shouldFindPropertyAnnotation() {
		assertNotNull(tested.getAnnotationInstanceForProperty(tested.findGetter(PropertyClassExample.class, "propertyA"), PropertyAnnotation.class));
		assertNotNull(tested.getAnnotationInstanceForProperty(tested.findGetter(PropertyClassExample.class, "propertyB"), PropertyAnnotation.class));
		assertNotNull(tested.getAnnotationInstanceForProperty(tested.findGetter(PropertyClassExample.class, "propertyC"), PropertyAnnotation.class));
		assertNull(tested.getAnnotationInstanceForProperty(tested.findGetter(PropertyClassExample.class, "propertyD"), PropertyAnnotation.class));
	}

	@Test
	void shouldFindPropertyGettersOnImmutableDto() {
		final Collection<Method> getters = tested.findAllGettersHavingCorrespondingSetterOrConstructorArgument(ImmutableDto.class);
		assertEquals(3, getters.size());
	}

	@Test
	void shouldFindPropertyGettersOnImmutableDtoWithUnusableConstructor() {
		final Collection<Method> getters = tested.findAllGettersHavingCorrespondingSetterOrConstructorArgument(ImmutableDtoWithUnusableConstructor.class);
		assertEquals(0, getters.size());
	}

	@Test
	void shouldFindPropertyGettersOnImmutableDtoWithMultipleConstructors() {
		final Collection<Method> getters = tested.findAllGettersHavingCorrespondingSetterOrConstructorArgument(ImmutableDtoWithMultipleConstructors.class);
		assertEquals(5, getters.size());
	}

	@Test
	void shouldNotFindPropertyGettersOnImmutableDtoWithAmbiguousConstructors() {
		final Collection<Method> getters = tested.findAllGettersHavingCorrespondingSetterOrConstructorArgument(ImmutableDtoWithAmbiguousConstructors.class);
		assertEquals(0, getters.size());
	}

	@Test
	void shouldFindAllGettersWithAnnotation() {
		final List<Method> gettersHavingAnnotation = tested.findAllGettersHavingAnnotation(ClassWithGetters.class, PropertyAnnotation.class);
		assertEquals(2, gettersHavingAnnotation.size());
		final Set<String> properties = gettersHavingAnnotation.stream()
			.map(it -> ReflectionLookup.getPropertyNameFromMethodName(it.getName()))
			.collect(Collectors.toSet());
		assertTrue(properties.contains("age"));
		assertTrue(properties.contains("sex"));
	}

	private Field getFieldByName(Map<Field, ?> fields, String fieldName) {
		for (Entry<Field, ?> entry : fields.entrySet()) {
			if (entry.getKey().getName().equals(fieldName)) {
				return entry.getKey();
			}
		}
		return null;
	}

	@MarkedClass("SomeSettings")
	@MarkedClass("Transactional")
	@Data
	private static class SomeSettings  {
		@NodeReference
		private String someField;
		@TranslateToWidgetId
		private String anotherField;
		@ClearBeforeStore
		private Boolean whatever;
		@ConstraintWithExpressions
		private String expression;
	}

	@MarkedClass("AnotherSettings")
	private interface AnotherSettings {

	}

	@MarkedClass("Whatever")
	private static class ExtendedSettings extends SomeSettings implements AnotherSettings {
	}

	private static class ExtendedStandardSettings extends SomeSettings implements AnotherSettings {
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface ClearBeforeStore {
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE})
	public @interface NodeReference {
		boolean exact() default true;
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	@NodeReference
	public @interface TranslateToWidgetId {

	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	@NodeReference(exact = false)
	public @interface ConstraintWithExpressions {
	}

	@Repeatable(MarkedClasses.class)
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	@NodeReference(exact = false)
	public @interface MarkedClass {
		String value();
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	@NodeReference(exact = false)
	public @interface MarkedClasses {
		MarkedClass[] value();
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
	@NodeReference
	public @interface PropertyAnnotation {

	}

	@Data
	@AllArgsConstructor
	private static class PojoExample {
		private final Boolean readOnly;
		private boolean valid;
		private String text;
		private LocalDateTime date;
		private int number;

		public void setWriteOnly(String whatever) {

		}

	}

	@Data
	private static class GenericsExample {
		private final Set<String> set;
		private final List<String> list;
		private final Map<String, Integer> map;

	}

	@Data
	private static class PropertyClassExampleParent {
		protected int propertyB;
		private URL propertyD;
	}

	@EqualsAndHashCode(callSuper = true)
	@Data
	private static class PropertyClassExample extends PropertyClassExampleParent {
		@PropertyAnnotation
		private final String propertyA;

		@PropertyAnnotation
		public int getPropertyB() {
			return propertyB;
		}

		@PropertyAnnotation
		public LocalDate getPropertyC() {
			return LocalDate.now();
		}

	}

	@Data
	public static class ImmutableDto {
		private final int id;
		private final String name;
		private final boolean dropped;
	}

	@Data
	public static class ImmutableDtoWithUnusableConstructor {
		private final int id;
		private final String name;
		private final boolean dropped;

		public ImmutableDtoWithUnusableConstructor(int id, String name, boolean dropped, boolean breakingArgument) {
			this.id = id;
			this.name = name;
			this.dropped = dropped;
		}
	}

	@Data
	@AllArgsConstructor
	public static class ImmutableDtoWithMultipleConstructors {
		private final int id;
		private final String name;
		private final boolean dropped;
		private final BigDecimal someNumber;
		private final OffsetDateTime someTime;

		public ImmutableDtoWithMultipleConstructors(int id, String name) {
			this.id = id;
			this.name = name;
			this.dropped = false;
			this.someNumber = null;
			this.someTime = null;
		}

		public ImmutableDtoWithMultipleConstructors(int id, String name, boolean dropped) {
			this.id = id;
			this.name = name;
			this.dropped = dropped;
			this.someNumber = null;
			this.someTime = null;
		}
	}

	@Data
	public static class ImmutableDtoWithAmbiguousConstructors {
		private final int id;
		private final String name;
		private final boolean dropped;
		private final BigDecimal someNumber;
		private final OffsetDateTime someTime;

		public ImmutableDtoWithAmbiguousConstructors(int id, String name, OffsetDateTime someTime) {
			this.id = id;
			this.name = name;
			this.dropped = false;
			this.someNumber = null;
			this.someTime = someTime;
		}

		public ImmutableDtoWithAmbiguousConstructors(int id, String name, boolean dropped) {
			this.id = id;
			this.name = name;
			this.dropped = dropped;
			this.someNumber = null;
			this.someTime = null;
		}
	}

	private interface ClassWithGetters {

		@PropertyAnnotation
		int getAge();

		@PropertyAnnotation
		String getSex();

		OffsetDateTime getChanged();

	}

}