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

package io.evitadb.api.proxy.impl;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.utils.ArrayUtils;
import one.edee.oss.proxycian.utils.GenericsUtils.GenericBundle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Supplier;

import static one.edee.oss.proxycian.utils.GenericsUtils.getGenericType;
import static one.edee.oss.proxycian.utils.GenericsUtils.getMethodReturnType;
import static one.edee.oss.proxycian.utils.GenericsUtils.getNestedMethodReturnTypes;

/**
 * Class contains utility methods for working with generics.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class ProxyUtils {

	/**
	 * Returns the wrapped generic type of the method if it is an Optional or OptionalInt or OptionalLong.
	 */
	@Nullable
	public static Class<?> getWrappedGenericType(
		@Nonnull Method method,
		@Nonnull Class<?> ownerClass
	) {
		final Class<?> returnType = method.getReturnType();
		final Class<?> wrappedType;
		if (Optional.class.isAssignableFrom(returnType)) {
			wrappedType = getMethodReturnType(ownerClass, method);
		} else if (OptionalInt.class.isAssignableFrom(returnType)) {
			wrappedType = int.class;
		} else if (OptionalLong.class.isAssignableFrom(returnType)) {
			wrappedType = long.class;
		} else {
			wrappedType = null;
		}
		return wrappedType;
	}

	/**
	 * Returns multiple wrapped generic type of the method.
	 */
	public static Class<?>[] getResolvedTypes(
		@Nonnull Method method,
		@Nonnull Class<?> ownerClass
	) {
		final List<Class<?>> collectedTypes = new LinkedList<>();
		collectedTypes.add(method.getReturnType());

		List<GenericBundle> nestedMethodReturnTypes = getNestedMethodReturnTypes(ownerClass, method);
		while (!nestedMethodReturnTypes.isEmpty()) {
			collectedTypes.add(nestedMethodReturnTypes.get(0).getResolvedType());
			final GenericBundle[] genericTypes = nestedMethodReturnTypes.get(0).getGenericTypes();
			nestedMethodReturnTypes = ArrayUtils.isEmpty(genericTypes) ? Collections.emptyList() : Arrays.asList(genericTypes);
		}

		return collectedTypes.toArray(Class[]::new);
	}

	/**
	 * Returns multiple wrapped generic type of the parameter.
	 */
	public static Class<?>[] getResolvedTypes(
		@Nonnull Parameter parameter,
		@Nonnull Class<?> ownerClass
	) {
		final List<Class<?>> collectedTypes = new LinkedList<>();
		collectedTypes.add(parameter.getType());

		List<GenericBundle> nestedMethodReturnTypes = getNestedParameterTypes(ownerClass, parameter);
		while (!nestedMethodReturnTypes.isEmpty()) {
			collectedTypes.add(nestedMethodReturnTypes.get(0).getResolvedType());
			final GenericBundle[] genericTypes = nestedMethodReturnTypes.get(0).getGenericTypes();
			nestedMethodReturnTypes = ArrayUtils.isEmpty(genericTypes) ? Collections.emptyList() : Arrays.asList(genericTypes);
		}

		return collectedTypes.toArray(Class[]::new);
	}

	/**
	 * Creates a function that wraps the result into an Optional or primitive optional.
	 */
	@Nonnull
	public static ResultWrapper createOptionalWrapper(@Nonnull Method method, @Nullable Class<?> optionalType) {
		if (optionalType == null) {
			final Class<?>[] exceptionTypes = method.getExceptionTypes();
			return ArrayUtils.isEmpty(exceptionTypes) ?
				UnaryResultWrapperSwallowing.INSTANCE :
				new UnaryResultWrapperRethrowing(exceptionTypes);
		} else if (int.class == optionalType) {
			final Class<?>[] exceptionTypes = method.getExceptionTypes();
			return ArrayUtils.isEmpty(exceptionTypes) ?
				OptionalIntUnaryResultWrapperSwallowing.INSTANCE :
				new OptionalIntUnaryResultWrapperRethrowing(exceptionTypes);
		} else if (long.class == optionalType) {
			final Class<?>[] exceptionTypes = method.getExceptionTypes();
			return ArrayUtils.isEmpty(exceptionTypes) ?
				OptionalLongUnaryResultWrapperSwallowing.INSTANCE :
				new OptionalLongUnaryResultWrapperRethrowing(exceptionTypes);
		} else {
			final Class<?>[] exceptionTypes = method.getExceptionTypes();
			return ArrayUtils.isEmpty(exceptionTypes) ?
				OptionalUnaryResultWrapperSwallowing.INSTANCE :
				new OptionalUnaryResultWrapperRethrowing(exceptionTypes);
		}
	}

	/**
	 * Returns generic types used in the parameter of the method or constructor.
	 *
	 * @param mainClass class that contains the method or constructor
	 * @param parameter parameter of the method or constructor
	 * @return list of generic types
	 */
	@Nonnull
	public static List<GenericBundle> getNestedParameterTypes(@Nonnull Class<?> mainClass, @Nonnull Parameter parameter) {
		Type genericReturnType = parameter.getParameterizedType();
		Class<?> returnType = parameter.getType();
		if (genericReturnType == returnType) {
			return Collections.emptyList();
		} else {
			if (!(genericReturnType instanceof Class)) {
				List<GenericBundle> resolvedTypes = getGenericType(mainClass, genericReturnType);
				if (!resolvedTypes.isEmpty()) {
					return resolvedTypes;
				}
			}

			return Collections.emptyList();
		}
	}

	/**
	 * Marks {@link ResultWrapper} operators that wrap the value to an optional wrapper.
	 */
	public interface OptionalProducingOperator {

	}

	/**
	 * The interface is used to wrap the result of the method call into an optional wrapper. Implementation might also
	 * decide to swallow or rethrow possible exception that might occur during the {@link Supplier#get()} method call.
	 */
	public interface ResultWrapper {

		/**
		 * Implementation might (or may not) wrap the result of the method call into an optional wrapper.
		 * @param resultProducer supplier that produces the result of the method call
		 * @return wrapped result
		 */
		@Nullable
		Object wrap(@Nonnull Supplier<Object> resultProducer);

	}

	/**
	 * The class is returns directly the result produced by {@link Supplier} and swallows all
	 * possible exceptions that might occur during the {@link Supplier#get()} method call.
	 */
	private record UnaryResultWrapperSwallowing() implements ResultWrapper {
		private final static UnaryResultWrapperSwallowing INSTANCE = new UnaryResultWrapperSwallowing();

		@Nullable
		@Override
		public Object wrap(@Nonnull Supplier<Object> resultProducer) {
			try {
				return resultProducer.get();
			} catch (ContextMissingException ex) {
				return null;
			}
		}
	}

	/**
	 * The class is returns directly the result produced by {@link Supplier} and rethrows all exceptions declared as
	 * thrown by the originally called method. The undeclared exceptions that might occur in {@link Supplier#get()}
	 * method call are swallowed.
	 *
	 * @param declaredExceptions exceptions declared as thrown by the originally called method
	 */
	private record UnaryResultWrapperRethrowing(
		@Nonnull Class<?>[] declaredExceptions
	) implements ResultWrapper {

		@Nullable
		@Override
		public Object wrap(@Nonnull Supplier<Object> resultProducer) {
			try {
				return resultProducer.get();
			} catch (Exception ex) {
				for (Class<?> declaredException : this.declaredExceptions) {
					if (declaredException.isInstance(ex)) {
						throw ex;
					}
				}
				return null;
			}
		}
	}

	/**
	 * The class is used to wrap the result of the method call into an {@link OptionalInt} wrapper and swallows all
	 * possible exceptions that might occur during the {@link Supplier#get()} method call.
	 */
	private record OptionalIntUnaryResultWrapperSwallowing() implements ResultWrapper, OptionalProducingOperator {
		private final static OptionalIntUnaryResultWrapperSwallowing INSTANCE = new OptionalIntUnaryResultWrapperSwallowing();

		@Nonnull
		@Override
		public Object wrap(@Nonnull Supplier<Object> resultProducer) {
			try {
				final Object value = resultProducer.get();
				return value == null ? OptionalInt.empty() : OptionalInt.of((Integer) value);
			} catch (ContextMissingException ex) {
				return OptionalInt.empty();
			}
		}
	}

	/**
	 * The class is used to wrap the result of the method call into an {@link OptionalInt} wrapper and rethrows all
	 * exceptions declared as thrown by the originally called method. The undeclared exceptions that might occur
	 * in {@link Supplier#get()} method call are swallowed.
	 *
	 * @param declaredExceptions exceptions declared as thrown by the originally called method
	 */
	private record OptionalIntUnaryResultWrapperRethrowing(
		@Nonnull Class<?>[] declaredExceptions
	) implements ResultWrapper, OptionalProducingOperator {

		@Nonnull
		@Override
		public Object wrap(@Nonnull Supplier<Object> resultProducer) {
			try {
				final Object value = resultProducer.get();
				return value == null ? OptionalInt.empty() : OptionalInt.of((Integer) value);
			} catch (Exception ex) {
				for (Class<?> declaredException : this.declaredExceptions) {
					if (declaredException.isInstance(ex)) {
						throw ex;
					}
				}
				return OptionalInt.empty();
			}
		}
	}

	/**
	 * The class is used to wrap the result of the method call into an {@link OptionalLong} wrapper and swallows all
	 * possible exceptions that might occur during the {@link Supplier#get()} method call.
	 */
	private record OptionalLongUnaryResultWrapperSwallowing() implements ResultWrapper, OptionalProducingOperator {
		private final static OptionalLongUnaryResultWrapperSwallowing INSTANCE = new OptionalLongUnaryResultWrapperSwallowing();

		@Nonnull
		@Override
		public Object wrap(@Nonnull Supplier<Object> resultProducer) {
			try {
				final Object value = resultProducer.get();
				return value == null ? OptionalLong.empty() : OptionalLong.of((Long) value);
			} catch (ContextMissingException ex) {
				return OptionalLong.empty();
			}
		}
	}

	/**
	 * The class is used to wrap the result of the method call into an {@link OptionalLong} wrapper and rethrows all
	 * exceptions declared as thrown by the originally called method. The undeclared exceptions that might occur
	 * in {@link Supplier#get()} method call are swallowed.
	 *
	 * @param declaredExceptions exceptions declared as thrown by the originally called method
	 */
	private record OptionalLongUnaryResultWrapperRethrowing(
		@Nonnull Class<?>[] declaredExceptions
	) implements ResultWrapper, OptionalProducingOperator {

		@Nonnull
		@Override
		public Object wrap(@Nonnull Supplier<Object> resultProducer) {
			try {
				final Object value = resultProducer.get();
				return value == null ? OptionalLong.empty() : OptionalLong.of((Long) value);
			} catch (Exception ex) {
				for (Class<?> declaredException : this.declaredExceptions) {
					if (declaredException.isInstance(ex)) {
						throw ex;
					}
				}
				return OptionalLong.empty();
			}
		}
	}

	/**
	 * The class is used to wrap the result of the method call into an {@link Optional} wrapper and swallows all
	 * possible exceptions that might occur during the {@link Supplier#get()} method call.
	 */
	private record OptionalUnaryResultWrapperSwallowing() implements ResultWrapper, OptionalProducingOperator {
		private final static OptionalUnaryResultWrapperSwallowing INSTANCE = new OptionalUnaryResultWrapperSwallowing();

		@Nonnull
		@Override
		public Object wrap(@Nonnull Supplier<Object> resultProducer) {
			try {
				final Object value = resultProducer.get();
				// collections are not allowed in the evitaDB values, but may be present on proxied interfaces
				// when empty collection is returned, the value is null and we need to return Optional.empty()
				if (value instanceof Collection<?> collection) {
					return collection.isEmpty() ? Optional.empty() : Optional.of(collection);
				} else {
					return Optional.ofNullable(value);
				}
			} catch (ContextMissingException ex) {
				return Optional.empty();
			}
		}
	}

	/**
	 * The class is used to wrap the result of the method call into an {@link Optional} wrapper and rethrows all
	 * exceptions declared as thrown by the originally called method. The undeclared exceptions that might occur
	 * in {@link Supplier#get()} method call are swallowed.
	 *
	 * @param declaredExceptions exceptions declared as thrown by the originally called method
	 */
	private record OptionalUnaryResultWrapperRethrowing(
		@Nonnull Class<?>[] declaredExceptions
	) implements ResultWrapper, OptionalProducingOperator {

		@Nonnull
		@Override
		public Object wrap(@Nonnull Supplier<Object> resultProducer) {
			try {
				final Object value = resultProducer.get();
				// collections are not allowed in the evitaDB values, but may be present on proxied interfaces
				// when empty collection is returned, the value is null and we need to return Optional.empty()
				if (value instanceof Collection<?> collection) {
					return collection.isEmpty() ? Optional.empty() : Optional.of(collection);
				} else {
					return Optional.ofNullable(value);
				}
			} catch (Exception ex) {
				for (Class<?> declaredException : this.declaredExceptions) {
					if (declaredException.isInstance(ex)) {
						throw ex;
					}
				}
				return Optional.empty();
			}
		}
	}

}
