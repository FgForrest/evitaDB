/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package tool;


import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * This utility class provides methods for working with reflection, particularly for setting field values
 * on objects regardless of field accessibility modifiers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ReflectionUtils {

	/**
	 * Sets the value of a field on an object instance, regardless of the field's accessibility modifiers.
	 * This method will search for the field in the class hierarchy (including superclasses) and set the value
	 * even if the field is private or final.
	 *
	 * @param instance  the object instance on which to set the field value, must not be null
	 * @param fieldName the name of the field to set, must not be null
	 * @param value     the value to set on the field, must not be null
	 * @throws IllegalArgumentException if the field cannot be found in the class hierarchy
	 * @throws IllegalStateException    if there is an error setting the field value
	 */
	public static void setFieldValue(
		@Nonnull Object instance,
		@Nonnull String fieldName,
		@Nonnull Object value
	) {
		Class<?> currentClass = instance.getClass();

		// Search for the field in the class hierarchy
		while (currentClass != null && !Object.class.equals(currentClass)) {
			try {
				// Try to get the field from the current class
				java.lang.reflect.Field field = currentClass.getDeclaredField(fieldName);

				// Make the field accessible regardless of its modifiers
				field.setAccessible(true);

				// If the field is final, we need to modify the modifiers
				if (java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
					try {
						// Get the modifiers field from the Field class
						java.lang.reflect.Field modifiersField = java.lang.reflect.Field.class.getDeclaredField("modifiers");
						modifiersField.setAccessible(true);

						// Remove the final modifier
						modifiersField.setInt(field, field.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
					} catch (NoSuchFieldException | IllegalAccessException e) {
						// In Java 9+ the above approach doesn't work, so we'll use an alternative approach
						// This is a fallback - we'll try to set the field anyway
					}
				}

				// Set the field value
				field.set(instance, value);
				return;
			} catch (NoSuchFieldException e) {
				// Field not found in current class, try the superclass
				currentClass = currentClass.getSuperclass();
			} catch (IllegalAccessException e) {
				throw new IllegalStateException("Failed to set field '" + fieldName + "' on instance of " +
					instance.getClass().getName() + ": " + e.getMessage(), e);
			}
		}

		throw new IllegalArgumentException("Field '" + fieldName + "' not found in class hierarchy of " +
			instance.getClass().getName());
	}

	/**
	 * Gets the value of a field from an object instance, regardless of the field's accessibility modifiers.
	 * This method will search for the field in the class hierarchy (including superclasses) and retrieve the value
	 * even if the field is private.
	 *
	 * @param <T>       the expected type of the field value
	 * @param instance  the object instance from which to get the field value, must not be null
	 * @param fieldName the name of the field to get, must not be null
	 * @return the value of the field, may be null
	 * @throws IllegalArgumentException if the field cannot be found in the class hierarchy
	 * @throws IllegalStateException    if there is an error getting the field value
	 */
	@Nonnull
	public static <T> T getNonnullFieldValue(@Nonnull Object instance, @Nonnull String fieldName) {
		return Objects.requireNonNull(getFieldValue(instance, fieldName), "Field '" + fieldName + "' cannot be null");
	}

	/**
	 * Gets the value of a field from an object instance, regardless of the field's accessibility modifiers.
	 * This method will search for the field in the class hierarchy (including superclasses) and retrieve the value
	 * even if the field is private.
	 *
	 * @param <T>       the expected type of the field value
	 * @param instance  the object instance from which to get the field value, must not be null
	 * @param fieldName the name of the field to get, must not be null
	 * @return the value of the field, may be null
	 * @throws IllegalArgumentException if the field cannot be found in the class hierarchy
	 * @throws IllegalStateException    if there is an error getting the field value
	 */
	@Nullable
	public static <T> T getFieldValue(@Nonnull Object instance, @Nonnull String fieldName) {
		Class<?> currentClass = instance.getClass();

		// Search for the field in the class hierarchy
		while (currentClass != null && !Object.class.equals(currentClass)) {
			try {
				// Try to get the field from the current class
				java.lang.reflect.Field field = currentClass.getDeclaredField(fieldName);

				// Make the field accessible regardless of its modifiers
				field.setAccessible(true);

				// Get and return the field value
				@SuppressWarnings("unchecked")
				T value = (T) field.get(instance);
				return value;
			} catch (NoSuchFieldException e) {
				// Field not found in current class, try the superclass
				currentClass = currentClass.getSuperclass();
			} catch (IllegalAccessException e) {
				throw new IllegalStateException("Failed to get field '" + fieldName + "' from instance of " +
					instance.getClass().getName() + ": " + e.getMessage(), e);
			}
		}

		throw new IllegalArgumentException("Field '" + fieldName + "' not found in class hierarchy of " +
			instance.getClass().getName());
	}
}
