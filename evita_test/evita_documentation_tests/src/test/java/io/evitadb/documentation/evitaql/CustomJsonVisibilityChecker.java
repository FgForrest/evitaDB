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

package io.evitadb.documentation.evitaql;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * This implementation allows to hide certain fields from JSON serialization in the documentation process.
 * It accepts a list of {@link AllowedVisibility} objects that capture allowed contract interfaces that will only
 * produce the JSON fields.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2016
 */
public class CustomJsonVisibilityChecker implements VisibilityChecker<CustomJsonVisibilityChecker> {
	/**
	 * Configuration of the checker.
	 */
	private final List<AllowedVisibility> allowedVisibilities;

	/**
	 * Helper method for easy creation of {@link AllowedVisibility} configuration objects.
	 */
	@Nonnull
	public static AllowedVisibility allow(@Nonnull Class<?> contractClass, @Nonnull String... exceptMethods) {
		return new AllowedVisibility(contractClass, exceptMethods);
	}

	/**
	 * Returns true if the method is defined on `contractClass`.
	 */
	private static boolean isDeclaredOn(@Nonnull Class<?> contractClass, @Nonnull Method method) {
		try {
			final Method aClassMethod = contractClass.getDeclaredMethod(method.getName(), method.getParameterTypes());
			return aClassMethod.getReturnType().isAssignableFrom(method.getReturnType());
		} catch (NoSuchMethodException ignored) {
			return false;
		}
	}

	/**
	 * Constructor.
	 */
	public CustomJsonVisibilityChecker(@Nonnull AllowedVisibility... allowedVisibilities) {
		this.allowedVisibilities = Arrays.asList(allowedVisibilities);
	}

	@Override
	public CustomJsonVisibilityChecker with(JsonAutoDetect ann) {
		return this;
	}

	@Override
	public CustomJsonVisibilityChecker withOverrides(JsonAutoDetect.Value value) {
		return this;
	}

	@Override
	public CustomJsonVisibilityChecker with(Visibility v) {
		return this;
	}

	@Override
	public CustomJsonVisibilityChecker withVisibility(PropertyAccessor method, Visibility v) {
		return this;
	}

	@Override
	public CustomJsonVisibilityChecker withGetterVisibility(Visibility v) {
		return this;
	}

	@Override
	public CustomJsonVisibilityChecker withIsGetterVisibility(Visibility v) {
		return this;
	}

	@Override
	public CustomJsonVisibilityChecker withSetterVisibility(Visibility v) {
		return this;
	}

	@Override
	public CustomJsonVisibilityChecker withCreatorVisibility(Visibility v) {
		return this;
	}

	@Override
	public CustomJsonVisibilityChecker withFieldVisibility(Visibility v) {
		return this;
	}

	@Override
	public boolean isGetterVisible(Method m) {
		return isIsGetterVisible(m);
	}

	@Override
	public boolean isGetterVisible(AnnotatedMethod m) {
		return isIsGetterVisible(m.getAnnotated());
	}

	@Override
	public boolean isIsGetterVisible(Method m) {
		if (Visibility.PUBLIC_ONLY.isVisible(m)) {
			for (AllowedVisibility allowedVisibility : this.allowedVisibilities) {
				if (isDeclaredOn(allowedVisibility.contractClass(), m) &&
					Arrays.stream(allowedVisibility.exceptMethods()).noneMatch(it -> m.getName().equals(it))) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public boolean isIsGetterVisible(AnnotatedMethod m) {
		return isIsGetterVisible(m.getAnnotated());
	}

	@Override
	public boolean isSetterVisible(Method m) {
		return false;
	}

	@Override
	public boolean isSetterVisible(AnnotatedMethod m) {
		return false;
	}

	@Override
	public boolean isCreatorVisible(Member m) {
		return false;
	}

	@Override
	public boolean isCreatorVisible(AnnotatedMember m) {
		return false;
	}

	@Override
	public boolean isFieldVisible(Field f) {
		return false;
	}

	@Override
	public boolean isFieldVisible(AnnotatedField f) {
		return false;
	}

	/**
	 * DTO that captures the visibility allowance.
	 *
	 * @param contractClass makes all getters in the class visible as fields
	 * @param exceptMethods allows to exclude certain getter methods
	 */
	public record AllowedVisibility(
		@Nonnull Class<?> contractClass,
		@Nonnull String[] exceptMethods
	) {
	}

}
