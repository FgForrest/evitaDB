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

package io.evitadb.test.extension;

import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.Evita;
import io.evitadb.core.sequence.SequenceService;
import io.evitadb.test.TestConstants;
import io.evitadb.test.annotation.CatalogName;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.OnDataSetTearDown;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.utils.Assert;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This is special extension to JUnit platform that:
 *
 * a) spins up new evitaDB instance implementation (i.e. creates reference of {@link Evita} implementation that
 * can be used in tests)
 * b) tears down evitaDB instance after test is finished
 * c) provides dependency injection support for two parameters:
 * - `{@link Evita} evita` parameter - injecting current evitaDB instance to it
 * - `{@link String} catalogName` parameter - injecting current evitaDB catalog name that is expected to be used in test
 *
 * In order this extension works this must be fulfilled:
 *
 * - this class name is referenced in file `/META-INF/services/org.junit.jupiter.api.extension.Extension`
 * - test needs to be run with JVM argument `-Djunit.jupiter.extensions.autodetection.enabled=true`
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class DbInstanceParameterResolver implements ParameterResolver, BeforeAllCallback, AfterAllCallback, AfterEachCallback {
	private static final String EVITA_INSTANCE = "__evitaInstance";
	private static final String EVITA_DATA_SET_INDEX = "__dataSetIndex";
	private static final String EVITA_CURRENT_DATA_SET = "__currentDataSet";
	private static final String EVITA_CURRENT_SET_RETURN_OBJECT = "__currentDataSetReturnObject";
	protected static final Path STORAGE_PATH = Path.of(System.getProperty("java.io.tmpdir") + File.separator + "evita");

	@Override
	public void beforeAll(ExtensionContext context) {
		// when test is marked with functional test or integration test tag
		if (context.getTags().contains(TestConstants.FUNCTIONAL_TEST) || context.getTags().contains(TestConstants.INTEGRATION_TEST)) {
			// index data set bootstrap methods
			final Map<String, DataSetInfo> dataSets = new HashMap<>();
			final Class<?> testClass = context.getRequiredTestClass();
			indexTestClass(dataSets, testClass);
			final Store store = getStore(context);
			store.put(EVITA_DATA_SET_INDEX, dataSets);
		}
	}

	@Override
	public void afterAll(ExtensionContext context) throws Exception {
		// when test is marked with functional test or integration test tag
		if (context.getTags().contains(TestConstants.FUNCTIONAL_TEST) || context.getTags().contains(TestConstants.INTEGRATION_TEST)) {
			// always clear evita at the end of the test class
			destroyEvitaInstanceIfPresent(context);
		}
	}

	@Override
	public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
		// this implementation can inject evitaDB instance and String catalogName to the test
		return Evita.class.isAssignableFrom(parameterContext.getParameter().getType()) ||
			"catalogName".equals(parameterContext.getParameter().getName()) ||
			ofNullable(extensionContext.getRequiredTestMethod().getAnnotation(UseDataSet.class))
				.orElseGet(() -> getAnnotationOnSuperMethod(extensionContext, UseDataSet.class)) != null;
	}

	@Override
	public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
		final Store store = getStore(extensionContext);

		// get catalog name from class annotation or use default
		final String catalogName = ofNullable(extensionContext.getRequiredTestClass().getAnnotation(CatalogName.class))
			.map(CatalogName::value)
			.orElse(TestConstants.TEST_CATALOG);

		// when Evita implementation is required
		final UseDataSet methodUseDataSet = ofNullable(extensionContext.getRequiredTestMethod().getAnnotation(UseDataSet.class))
			.orElseGet(() -> getAnnotationOnSuperMethod(extensionContext, UseDataSet.class));

		final Parameter requestedParam = parameterContext.getParameter();
		if (Evita.class.isAssignableFrom(requestedParam.getType())) {
			final UseDataSet parameterUseDataSet = ofNullable(requestedParam.getAnnotation(UseDataSet.class))
					.orElseGet(() -> getParameterAnnotationOnSuperMethod(parameterContext, extensionContext, UseDataSet.class));
			Assert.isTrue(
				parameterUseDataSet == null || methodUseDataSet == null,
				"UseDataSet annotation can be specified on parameter OR method level, but not both!"
			);
			final UseDataSet useDataSet = ofNullable(methodUseDataSet).orElse(parameterUseDataSet);

			try {
				final Evita evita;
				// return initialized Evita instance
				if (useDataSet != null) {
					final String currentDataSet = getCurrentDataSet(store);
					final String dataSetToUse = useDataSet.value();
					if (Objects.equals(currentDataSet, dataSetToUse)) {
						// do nothing - reuse current dataset
						evita = getEvitaInstance(store);
					} else {
						// reinitialize evitaDB from scratch
						destroyEvitaInstanceIfPresent(extensionContext);
						evita = createNewEvitaInstance(store, catalogName);
						// call method that initializes the dataset
						final Map<String, DataSetInfo> dataSetIndex = getDataSetIndex(store);
						final DataSetInfo dataSetInfo = dataSetIndex.get(dataSetToUse);
						final Object testClassInstance = extensionContext.getRequiredTestInstance();
						if (dataSetInfo == null) {
							throw new ParameterResolutionException("Requested data set " + dataSetToUse + " has no initialization method within the class (Method with @DataSet annotation)!");
						} else {
							final Object methodResult;
							try {
								final Method initMethod = dataSetInfo.initMethod().method();
								if (initMethod.getParameterCount() == 1) {
									methodResult = initMethod.invoke(testClassInstance, evita);
								} else if (initMethod.getParameterCount() == 2) {
									methodResult = initMethod.invoke(testClassInstance, evita, catalogName);
								} else {
									throw new ParameterResolutionException("Data set init method may have one or two arguments (evita instance / catalog name). Failed to init " + dataSetToUse + ".");
								}
							} catch (InvocationTargetException | IllegalAccessException e) {
								throw new ParameterResolutionException("Failed to set up data set " + dataSetToUse, e);
							}
							if (methodResult != null) {
								store.put(EVITA_CURRENT_SET_RETURN_OBJECT, methodResult instanceof DataCarrier ? methodResult : new DataCarrier(methodResult));
							}
						}
						// set current dataset to context
						store.put(EVITA_CURRENT_DATA_SET, dataSetToUse);
						// fill in the reference to the test instance, that is known only now
						dataSetIndex.put(
							dataSetToUse,
							new DataSetInfo(
								dataSetToUse,
								dataSetInfo.initMethod(),
								dataSetInfo.destroyMethods()
									.stream()
									.map(it -> new CatalogDestroyMethod(it.method(), testClassInstance))
									.toList()
							)
						);
						// switch to alive state if required
						if (dataSetInfo.initMethod().expectedState() == CatalogState.ALIVE) {
							evita.updateCatalog(catalogName, evitaSessionBase -> {
								evitaSessionBase.goLiveAndClose();
							});
						}
					}
				} else {
					// reinitialize evitaDB from scratch (method doesn't use data set - so it needs to start with empty db)
					destroyEvitaInstanceIfPresent(extensionContext);
					evita = createNewEvitaInstance(store, catalogName);
					evita.updateCatalog(catalogName, evitaSessionBase -> { evitaSessionBase.goLiveAndClose(); });
				}
				if (evita == null) {
					throw new ParameterResolutionException("Evita instance was not initialized yet or current test class is neither functional nor integration test (check tags)!");
				} else {
					return evita;
				}
			} catch (IOException ex) {
				throw new ParameterResolutionException("Failed to initialize Evita instance due to an exception!", ex);
			}
			// when catalog name is required
		} else if ("catalogName".equals(requestedParam.getName())) {
			// return resolved test catalog name
			return catalogName;
		} else if (methodUseDataSet != null && getCurrentDataSetReturnObject(store) != null) {
			final DataCarrier currentDataSetReturnObject = getCurrentDataSetReturnObject(store);
			final Object valueByName = currentDataSetReturnObject.getValueByName(requestedParam.getName());
			if (valueByName != null && requestedParam.getType().isInstance(valueByName)) {
				return valueByName;
			} else {
				final Object valueByType = currentDataSetReturnObject.getValueByType(requestedParam.getType());
				if (valueByType != null) {
					return valueByType;
				}
			}
			throw new ParameterResolutionException("Unrecognized parameter " + parameterContext + "!");
		} else {
			throw new ParameterResolutionException("Unrecognized parameter " + parameterContext + "!");
		}
	}

	@Override
	public void afterEach(ExtensionContext extensionContext) throws Exception {
		// when Evita implementation is required
		final UseDataSet methodUseDataSet = ofNullable(extensionContext.getRequiredTestMethod().getAnnotation(UseDataSet.class))
			.orElseGet(() -> getAnnotationOnSuperMethod(extensionContext, UseDataSet.class));

		if (methodUseDataSet != null && methodUseDataSet.destroyAfterTest()) {
			// destroy Evita instance
			destroyEvitaInstanceIfPresent(extensionContext);
		}
	}

	@Nullable
	private <T extends Annotation> T getParameterAnnotationOnSuperMethod(ParameterContext parameterContext, ExtensionContext extensionContext, Class<T> annotationClass) {
		final Class<?> testSuperClass = extensionContext.getRequiredTestInstance().getClass().getSuperclass();
		if (Object.class.equals(testSuperClass)) {
			return null;
		}

		final Method testMethod = extensionContext.getRequiredTestMethod();
		Method testMethodOnSuperClass = null;
		for (Method superClassMethod : testSuperClass.getDeclaredMethods()) {
			if (superClassMethod.getName().equals(testMethod.getName())
				&& superClassMethod.getParameterCount() == testMethod.getParameterCount()
				&& allParametersAreCompliant(superClassMethod, testMethod)) {
				testMethodOnSuperClass = superClassMethod;
				break;
			}
		}
		if (testMethodOnSuperClass == null) {
			return null;
		}
		int index = -1;
		for (int i = 0; i < testMethod.getParameters().length; i++) {
			final Parameter parameter = testMethod.getParameters()[i];
			if (parameter.equals(parameterContext.getParameter())) {
				index = i;
				break;
			}
		}
		final Parameter superClassParameter = testMethodOnSuperClass.getParameters()[index];
		return superClassParameter.getAnnotation(annotationClass);
	}

	@Nullable
	private <T extends Annotation> T getAnnotationOnSuperMethod(ExtensionContext extensionContext, Class<T> annotationClass) {
		final Class<?> testSuperClass = extensionContext.getRequiredTestInstance().getClass().getSuperclass();
		if (Object.class.equals(testSuperClass)) {
			return null;
		}

		final Method testMethod = extensionContext.getRequiredTestMethod();
		Method testMethodOnSuperClass = null;
		for (Method superClassMethod : testSuperClass.getDeclaredMethods()) {
			if (superClassMethod.getName().equals(testMethod.getName())
				&& superClassMethod.getParameterCount() == testMethod.getParameterCount()
				&& allParametersAreCompliant(superClassMethod, testMethod)) {
				testMethodOnSuperClass = superClassMethod;
				break;
			}
		}
		if (testMethodOnSuperClass == null) {
			return null;
		} else {
			return testMethodOnSuperClass.getAnnotation(annotationClass);
		}
	}

	private boolean allParametersAreCompliant(Method superClassMethod, Method testMethod) {
		for (int i = 0; i < superClassMethod.getParameters().length; i++) {
			final Parameter superParameter = superClassMethod.getParameters()[i];
			final Parameter thisParameter = testMethod.getParameters()[i];
			if (!superParameter.getType().isAssignableFrom(thisParameter.getType())) {
				return false;
			}
		}
		return true;
	}

	protected Evita getEvitaInstance(Store store) {
		return (Evita) store.get(EVITA_INSTANCE);
	}

	protected String getCurrentDataSet(Store store) {
		return (String) store.get(EVITA_CURRENT_DATA_SET);
	}

	protected DataCarrier getCurrentDataSetReturnObject(Store store) {
		return (DataCarrier) store.get(EVITA_CURRENT_SET_RETURN_OBJECT);
	}

	protected Map<String, DataSetInfo> getDataSetIndex(Store store) {
		//noinspection unchecked
		return (Map<String, DataSetInfo>) store.get(EVITA_DATA_SET_INDEX);
	}

	protected Evita createEvita(@Nonnull String catalogName) {
		if (STORAGE_PATH.toFile().exists()) {
			try {
				FileUtils.deleteDirectory(STORAGE_PATH.toFile());
			} catch (IOException e) {
				fail("Failed to empty directory: " + STORAGE_PATH, e);
			}
		}
		Assert.isTrue(STORAGE_PATH.toFile().mkdirs(), "Fail to create directory: " + STORAGE_PATH);
		SequenceService.reset();
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.storage(
					StorageOptions.builder()
						.storageDirectory(STORAGE_PATH)
						.maxOpenedReadHandles(1000)
						.build()
				)
				.build()
		);
		evita.defineCatalog(catalogName);
		return evita;
	}

	protected void destroyEvitaData() throws IOException {
		FileUtils.deleteDirectory(STORAGE_PATH.toFile());
	}

	private void indexTestClass(Map<String, DataSetInfo> dataSets, Class<?> testClass) {
		for (Method declaredMethod : testClass.getDeclaredMethods()) {
			ofNullable(declaredMethod.getAnnotation(DataSet.class))
				.ifPresent(it -> {
					declaredMethod.setAccessible(true);
					dataSets.computeIfAbsent(
						it.value(),
						dsName -> new DataSetInfo(
							dsName,
							new CatalogInitMethod(declaredMethod, it.expectedCatalogState()),
							new LinkedList<>()
						)
					);
				});
			ofNullable(declaredMethod.getAnnotation(OnDataSetTearDown.class))
				.ifPresent(it -> {
					declaredMethod.setAccessible(true);
					final DataSetInfo dataSetInfo = dataSets.get(it.value());
					Assert.notNull(dataSetInfo, "There is no set up method for datast `" + it.value() + "` in this class!");
					dataSetInfo.destroyMethods().add(new CatalogDestroyMethod(declaredMethod, null));
				});
		}
		if (!Object.class.equals(testClass.getSuperclass())) {
			indexTestClass(dataSets, testClass.getSuperclass());
		}
	}

	private Evita createNewEvitaInstance(Store store, String catalogName) throws IOException {
		// clear evitaDB directory
		destroyEvitaData();
		// create evita instance and configure test catalog
		final Evita evita = createEvita(catalogName);
		// store references to thread local variables for use in test
		store.put(EVITA_INSTANCE, evita);
		return evita;
	}

	private void destroyEvitaInstanceIfPresent(ExtensionContext context) throws IOException {
		final Store store = getStore(context);

		// clear references in thread locals
		final String dataSetName = (String) store.remove(EVITA_CURRENT_DATA_SET);
		final Evita evitaInstance = (Evita) store.remove(EVITA_INSTANCE);

		if (dataSetName != null) {
			// call destroy methods
			final Map<String, DataSetInfo> dataSets = (Map<String, DataSetInfo>) store.get(EVITA_DATA_SET_INDEX);
			final DataSetInfo dataSetInfo = dataSets.get(dataSetName);
			for (CatalogDestroyMethod destroyMethod : dataSetInfo.destroyMethods()) {
				try {
					Assert.notNull(destroyMethod.testInstance(), "Test instance was not initialized!");
					destroyMethod.method().invoke(destroyMethod.testInstance());
				} catch (InvocationTargetException | IllegalAccessException e) {
					throw new ParameterResolutionException("Failed to tear down data set " + dataSetName, e);
				}
			}

			// close evita and clear data
			evitaInstance.close();
			destroyEvitaData();
		}
	}

	private Store getStore(ExtensionContext context) {
		return context.getRoot().getStore(Namespace.GLOBAL);
	}

	private record DataSetInfo(
		@Nonnull String catalogName,
		@Nonnull CatalogInitMethod initMethod,
		@Nonnull List<CatalogDestroyMethod> destroyMethods
	) {}

	private record CatalogInitMethod(@Nonnull Method method, @Nonnull CatalogState expectedState) {}
	private record CatalogDestroyMethod(@Nonnull Method method, @Nullable Object testInstance) {}

}
