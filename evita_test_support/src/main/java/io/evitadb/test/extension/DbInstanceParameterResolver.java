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
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.AbstractApiConfiguration;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.ApiOptions.Builder;
import io.evitadb.externalApi.configuration.CertificateSettings;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.PortManager;
import io.evitadb.test.TestConstants;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.OnDataSetTearDown;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;
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
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;
import static io.evitadb.utils.CollectionUtils.property;
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
@Slf4j
public class DbInstanceParameterResolver implements ParameterResolver, BeforeAllCallback, AfterAllCallback, AfterEachCallback, EvitaTestSupport {
	protected static final Path STORAGE_PATH = Path.of(System.getProperty("java.io.tmpdir") + File.separator + "evita");
	protected final static AtomicInteger CREATED_EVITA_ENTITIES = new AtomicInteger();
	protected final static AtomicInteger CREATED_EVITA_INSTANCES = new AtomicInteger();
	protected final static AtomicInteger PEAK_EVITA_INSTANCES = new AtomicInteger();
	private static final Map<String, ExternalApiProviderRegistrar> AVAILABLE_PROVIDERS = ExternalApiServer.gatherExternalApiProviders()
		.stream()
		.collect(Collectors.toMap(
			ExternalApiProviderRegistrar::getExternalApiCode,
			Function.identity()
		));
	private static final String EVITA_DATA_SET_INDEX = "__dataSetIndex";
	private static final String EVITA_ANONYMOUS_EVITA = "__anonymousEvita";
	private static final Random RANDOM = new Random();

	/**
	 * Indexes all methods annotated with {@link DataSet} annotation into the `dataSets` index.
	 */
	private static void indexTestClass(@Nonnull Map<String, DataSetInfo> dataSets, @Nonnull Class<?> testClass) {
		final Map<String, DataSetInfo> dataSetsInThisClass = new HashMap<>(8);
		for (Method declaredMethod : testClass.getDeclaredMethods()) {
			ofNullable(declaredMethod.getAnnotation(DataSet.class))
				.ifPresent(it -> {
					declaredMethod.setAccessible(true);
					dataSetsInThisClass.computeIfAbsent(
						it.value(),
						dsName -> new DataSetInfo(
							it.catalogName(),
							new CatalogInitMethod(declaredMethod, it.expectedCatalogState()),
							new LinkedList<>(),
							it.openWebApi(),
							it.readOnly(),
							it.destroyAfterClass()
						)
					);
				});
			ofNullable(declaredMethod.getAnnotation(OnDataSetTearDown.class))
				.ifPresent(it -> {
					declaredMethod.setAccessible(true);
					final DataSetInfo dataSetInfo = dataSetsInThisClass.get(it.value());
					Assert.notNull(dataSetInfo, "There is no set up method for dataset `" + it.value() + "` in this class!");
					dataSetInfo.destroyMethods().add(declaredMethod);
				});
		}

		if (!Object.class.equals(testClass.getSuperclass())) {
			indexTestClass(dataSetsInThisClass, testClass.getSuperclass());
		}

		// propagate only those datasets from this class that were not already defined in other classes
		for (Entry<String, DataSetInfo> dataSetInfo : dataSetsInThisClass.entrySet()) {
			dataSets.putIfAbsent(dataSetInfo.getKey(), dataSetInfo.getValue());
		}
	}

	/**
	 * Creates new evitaDB instance with one catalog of `catalogName`.
	 */
	@Nonnull
	private static Evita createEvita(@Nonnull String catalogName, @Nonnull String randomFolderName) {
		final Path evitaDataPath = STORAGE_PATH.resolve(randomFolderName);
		if (evitaDataPath.toFile().exists()) {
			try {
				FileUtils.deleteDirectory(evitaDataPath.toFile());
			} catch (IOException e) {
				fail("Failed to empty directory: " + evitaDataPath, e);
			}
		}
		Assert.isTrue(evitaDataPath.toFile().mkdirs(), "Fail to create directory: " + evitaDataPath);
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.server(
					// disable automatic session termination
					// to avoid closing sessions when you stop at breakpoint
					ServerOptions.builder()
						.closeSessionsAfterSecondsOfInactivity(-1)
						.build()
				)
				.storage(
					// point evitaDB to a test directory (temp directory)
					StorageOptions.builder()
						.storageDirectory(evitaDataPath)
						.maxOpenedReadHandles(1000)
						.build()
				)
				.cache(
					// disable cache for tests
					CacheOptions.builder()
						.enabled(false)
						.build()
				)
				.build()
		);
		evita.defineCatalog(catalogName);
		return evita;
	}

	/**
	 * Returns a single {@link UseDataSet} defined on a method parameter or returns the annotation found on the method.
	 */
	@Nullable
	private static UseDataSet resolveUseDataSetAnnotation(@Nonnull ExtensionContext extensionContext, UseDataSet methodUseDataSet) {
		UseDataSet useDataSet = methodUseDataSet;
		if (useDataSet == null) {
			for (Annotation[] parameterAnnotation : extensionContext.getRequiredTestMethod().getParameterAnnotations()) {
				for (Annotation annotation : parameterAnnotation) {
					if (annotation instanceof UseDataSet parameterUseDataSet) {
						if (useDataSet == null) {
							useDataSet = parameterUseDataSet;
						} else {
							throw new ParameterResolutionException("Test method may have maximum of one parameter annotated with @UseDataSet annotation!");
						}
					}
				}
			}
		}
		return useDataSet;
	}

	/**
	 * Tries to find `annotationClass` annotation on an similar method on superclass.
	 */
	@Nullable
	private static <T extends Annotation> T getParameterAnnotationOnSuperMethod(
		@Nonnull ParameterContext parameterContext,
		@Nonnull ExtensionContext extensionContext,
		@Nonnull Class<T> annotationClass
	) {
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

	/**
	 * Returns true if all method parameters are compliant (have corresponding Java types).
	 */
	private static boolean allParametersAreCompliant(@Nonnull Method superClassMethod, @Nonnull Method testMethod) {
		for (int i = 0; i < superClassMethod.getParameters().length; i++) {
			final Parameter superParameter = superClassMethod.getParameters()[i];
			final Parameter thisParameter = testMethod.getParameters()[i];
			if (!superParameter.getType().isAssignableFrom(thisParameter.getType())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Method finds an annotation of `annotationClass` on super class.
	 */
	@Nullable
	private static <T extends Annotation> T getAnnotationOnSuperMethod(
		@Nonnull ExtensionContext extensionContext,
		@Nonnull Class<T> annotationClass
	) {
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

	/**
	 * Retrieves dataset index from store.
	 */
	@Nonnull
	private static Map<String, DataSetInfo> getDataSetIndex(@Nonnull ExtensionContext context) {
		final Store store = context.getRoot().getStore(Namespace.GLOBAL);
		synchronized (RANDOM) {
			//noinspection unchecked
			return ofNullable((Map<String, DataSetInfo>) store.get(EVITA_DATA_SET_INDEX))
				.orElseGet(() -> {
					final Map<String, DataSetInfo> newDataSets = new ConcurrentHashMap<>(32);
					store.put(EVITA_DATA_SET_INDEX, newDataSets);
					return newDataSets;
				});
		}
	}

	/**
	 * Collects method input arguments in correct order trying to match them primarily by name, secondarily by type
	 * against `availableArguments` offering.
	 */
	@Nullable
	private static Object[] placeArguments(@Nonnull Method method, @Nonnull Map<String, Object> availableArguments) {
		final Parameter[] parameters = method.getParameters();
		final Object[] result = new Object[parameters.length];
		for (int i = 0; i < parameters.length; i++) {
			final Parameter parameter = parameters[i];
			final Object possibleValue = availableArguments.get(parameter.getName());
			if (parameter.getType().isInstance(possibleValue)) {
				// find by name
				result[i] = possibleValue;
			} else {
				// find by type
				result[i] = availableArguments.values()
					.stream()
					.filter(it -> parameter.getType().isInstance(it))
					.findFirst()
					.orElse(null);
			}
		}
		return Arrays.stream(result).allMatch(Objects::nonNull) ?
			result : null;
	}

	/**
	 * Creates {@link ApiOptions} for {@link EvitaServer}.
	 */
	@Nonnull
	private static ApiOptions createApiOptions(
		@Nonnull String datasetName,
		@Nonnull DataSetInfo dataSetInfo,
		@Nonnull Evita evita,
		@Nonnull PortManager portManager
	) {
		final String[] unknownApis = Arrays.stream(dataSetInfo.webApi())
			.filter(it -> !AVAILABLE_PROVIDERS.containsKey(it))
			.toArray(String[]::new);
		if (ArrayUtils.isEmpty(unknownApis)) {
			final Builder apiOptionsBuilder = ApiOptions.builder()
				.certificate(
					CertificateSettings.builder()
						.folderPath(evita.getConfiguration().storage().storageDirectory().toString() + "-certificates")
						.build()
				);
			final int[] ports = portManager.allocatePorts(datasetName, dataSetInfo.webApi().length);
			int portIndex = 0;
			for (String webApiCode : dataSetInfo.webApi()) {
				final AbstractApiConfiguration webApiConfig;
				final Class<?> configurationClass = AVAILABLE_PROVIDERS.get(webApiCode).getConfigurationClass();
				try {
					final Constructor<?> hostConstructor = configurationClass.getConstructor(String.class);
					webApiConfig = (AbstractApiConfiguration) hostConstructor.newInstance(
						"localhost:" + ports[portIndex++]
					);
				} catch (InvocationTargetException | NoSuchMethodException | InstantiationException |
				         IllegalAccessException e) {
					throw new IllegalStateException(
						"Cannot initialize web api config `" + webApiCode + "` with host name. " +
							"Each config class (`" + configurationClass + "`) needs to have a constructor with " +
							"a single String argument accepting web api host configuration!", e);
				}
				apiOptionsBuilder.enable(webApiCode, webApiConfig);
			}
			return apiOptionsBuilder.build();
		} else {
			throw new ParameterResolutionException(
				"Unknown web API identification: " + String.join(", ", unknownApis)
			);
		}
	}

	@Override
	public void beforeAll(ExtensionContext context) {
		// index data set bootstrap methods
		final Map<String, DataSetInfo> dataSets = getDataSetIndex(context);
		final Class<?> testClass = context.getRequiredTestClass();
		indexTestClass(dataSets, testClass);
	}

	@Override
	public void afterAll(ExtensionContext context) {
		final Map<String, DataSetInfo> dataSetIndex = getDataSetIndex(context);
		for (Entry<String, DataSetInfo> entry : dataSetIndex.entrySet()) {
			final DataSetInfo dataSetInfo = entry.getValue();
			dataSetInfo.destroyIfPredicateMatches(
				entry.getKey(), dataSetInfo, getPortManager(), context
			);
		}
	}

	@Override
	public void afterEach(ExtensionContext context) {
		final Map<String, DataSetInfo> dataSetIndex = getDataSetIndex(context);
		final Iterator<Entry<String, DataSetInfo>> it = dataSetIndex.entrySet().iterator();
		while (it.hasNext()) {
			final Entry<String, DataSetInfo> entry = it.next();
			final DataSetInfo dataSetInfo = entry.getValue();
			if (dataSetInfo.destroyIfPredicateMatches(entry.getKey(), dataSetInfo, getPortManager(), context)) {
				if (entry.getKey().startsWith(EVITA_ANONYMOUS_EVITA)) {
					it.remove();
				}
			}
		}
	}

	@Override
	public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
		final UseDataSet methodUseDataSet = ofNullable(extensionContext.getRequiredTestMethod().getAnnotation(UseDataSet.class))
			.orElseGet(() -> getAnnotationOnSuperMethod(extensionContext, UseDataSet.class));
		final UseDataSet useDataSet = resolveUseDataSetAnnotation(extensionContext, methodUseDataSet);
		final Optional<DataSetInfo> dataSetInfo = ofNullable(useDataSet)
			.map(it -> getDataSetIndex(extensionContext).get(it.value()));

		return EvitaContract.class.isAssignableFrom(parameterContext.getParameter().getType()) ||
			EvitaSessionContract.class.isAssignableFrom(parameterContext.getParameter().getType()) ||
			EvitaServer.class.isAssignableFrom(parameterContext.getParameter().getType()) ||
			dataSetInfo.isPresent();
	}

	@Nullable
	@Override
	public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
		// when Evita implementation is required
		final UseDataSet methodUseDataSet = ofNullable(extensionContext.getRequiredTestMethod().getAnnotation(UseDataSet.class))
			.orElseGet(() -> getAnnotationOnSuperMethod(extensionContext, UseDataSet.class));

		final Map<String, DataSetInfo> dataSetIndex = getDataSetIndex(extensionContext);
		final Parameter requestedParam = parameterContext.getParameter();
		final UseDataSet parameterUseDataSet = ofNullable(requestedParam.getAnnotation(UseDataSet.class))
			.orElseGet(() -> getParameterAnnotationOnSuperMethod(parameterContext, extensionContext, UseDataSet.class));
		Assert.isTrue(
			parameterUseDataSet == null || methodUseDataSet == null,
			"UseDataSet annotation can be specified on parameter OR method level, but not both!"
		);
		final UseDataSet useDataSet = ofNullable(methodUseDataSet).orElse(parameterUseDataSet);
		final DataSetInfo dataSetInfo = getInitializedDataSetInfo(useDataSet, dataSetIndex, extensionContext);

		// prefer the data created by the test itself
		final DataCarrier dataCarrier = dataSetInfo.dataCarrier();
		if (dataCarrier != null) {
			final Object valueByName = dataCarrier.getValueByName(requestedParam.getName());
			if (valueByName != null && requestedParam.getType().isInstance(valueByName)) {
				return valueByName;
			} else {
				final Object valueByType = dataCarrier.getValueByType(requestedParam.getType());
				if (valueByType != null) {
					return valueByType;
				}
			}
		}

		// now resolve the default types
		if (EvitaContract.class.isAssignableFrom(requestedParam.getType())) {
			// return initialized Evita instance
			return dataSetInfo.evitaInstance();
		} else if (EvitaServer.class.isAssignableFrom(requestedParam.getType())) {
			// return initialized Evita server instance
			return dataSetInfo.evitaServerInstance();
		} else if (EvitaSessionContract.class.isAssignableFrom(requestedParam.getType())) {
			return dataSetInfo.evitaSession(
				evita -> evita.createSession(
					new SessionTraits(
						dataSetInfo.catalogName(),
						SessionFlags.READ_WRITE, SessionFlags.DRY_RUN
					)
				)
			);
		} else if ("catalogName".equals(requestedParam.getName())) {
			// return catalog name
			return dataSetInfo.catalogName();
		} else {
			throw new ParameterResolutionException("Unrecognized parameter " + parameterContext + "!");
		}
	}

	@Nonnull
	public DataSetInfo getInitializedDataSetInfo(@Nullable UseDataSet useDataSet, @Nonnull Map<String, DataSetInfo> dataSetIndex, @Nonnull ExtensionContext extensionContext) {
		if (useDataSet == null) {
			final DataSetInfo alreadyExistingAnonymousInstance = dataSetIndex.get(EVITA_ANONYMOUS_EVITA);
			if (alreadyExistingAnonymousInstance == null) {
				// method doesn't use data set - so it needs to start with empty db
				final String randomFolderName = Long.toHexString(RANDOM.nextLong());
				final Evita evita = createEvita(TestConstants.TEST_CATALOG, randomFolderName);
				evita.updateCatalog(TestConstants.TEST_CATALOG, session -> {
					session.goLiveAndClose();
				});
				final DataSetInfo dataSetInfo = new DataSetInfo(
					TestConstants.TEST_CATALOG,
					null,
					Collections.emptyList(),
					new String[0],
					false,
					false,
					new AtomicReference<>(
						new DataSetState(
							extensionContext.getRequiredTestInstance(),
							extensionContext.getRequiredTestMethod(),
							evita,
							null,
							null,
							(terminationContext, dataSetState) -> terminationContext.getTestMethod()
								.map(m -> m.equals(dataSetState.testMethod()))
								.orElse(false)
						)
					)
				);
				dataSetIndex.put(
					EVITA_ANONYMOUS_EVITA + "_" + randomFolderName,
					dataSetInfo
				);
				CREATED_EVITA_INSTANCES.incrementAndGet();
				PEAK_EVITA_INSTANCES.set((int) Math.max(PEAK_EVITA_INSTANCES.get(), dataSetIndex.values().stream().filter(it -> it.evitaInstance() != null).count()));
				return dataSetInfo;
			} else {
				return alreadyExistingAnonymousInstance;
			}
		} else {
			final String dataSetToUse = useDataSet.value();
			final DataSetInfo dataSetInfo = dataSetIndex.get(dataSetToUse);
			if (dataSetInfo == null) {
				throw new ParameterResolutionException("Requested data set " + dataSetToUse + " has no initialization method within the class (Method with @DataSet annotation)!");
			}
			synchronized (dataSetInfo) {
				//noinspection resource
				if (dataSetInfo.evitaInstance() == null) {
					// fill in the reference to the test instance, that is known only now
					dataSetInfo.init(
						() -> {
							final String randomFolderName = Long.toHexString(RANDOM.nextLong());
							final Evita evita = createEvita(dataSetInfo.catalogName(), randomFolderName);
							final EvitaServer evitaServer;
							if (ArrayUtils.isEmpty(dataSetInfo.webApi())) {
								evitaServer = null;
							} else {
								final ApiOptions apiOptions = createApiOptions(dataSetToUse, dataSetInfo, evita, getPortManager());
								evitaServer = openWebApi(evita, apiOptions);
							}
							// call method that initializes the dataset
							final Object testClassInstance = extensionContext.getRequiredTestInstance();
							final Object methodResult;
							try {
								final Method initMethod = dataSetInfo.initMethod().method();
								final LinkedHashMap<String, Object> argumentDictionary = createLinkedHashMap(
									property("evita", evita),
									property("catalogName", dataSetInfo.catalogName())
								);
								if (evitaServer != null) {
									argumentDictionary.put("evitaServer", evitaServer);
								}
								final Object[] arguments = placeArguments(initMethod, argumentDictionary);
								if (arguments == null) {
									throw new ParameterResolutionException("Data set init method may have only these arguments: evita instance, catalog name, evita server instance. Failed to init " + dataSetToUse + ".");
								} else {
									methodResult = initMethod.invoke(testClassInstance, arguments);
								}
							} catch (InvocationTargetException | IllegalAccessException e) {
								throw new ParameterResolutionException("Failed to set up data set " + dataSetToUse, e);
							}

							final DataCarrier dataCarrier;
							if (methodResult != null) {
								dataCarrier = methodResult instanceof DataCarrier dc ? dc : new DataCarrier(methodResult);
							} else {
								dataCarrier = null;
							}

							// switch to alive state if required
							if (dataSetInfo.initMethod().expectedState() == CatalogState.ALIVE) {
								evita.updateCatalog(dataSetInfo.catalogName(), evitaSessionBase -> {
									evitaSessionBase.goLiveAndClose();
								});
							}

							if (dataSetInfo.readOnly()) {
								evita.setReadOnly();
							}

							return new DataSetState(
								extensionContext.getRequiredTestInstance(),
								extensionContext.getRequiredTestMethod(),
								evita, evitaServer, dataCarrier,
								(terminationContext, dataSetState) -> {
									if (useDataSet.destroyAfterTest()) {
										return terminationContext.getTestMethod()
											.map(m -> m.equals(dataSetState.testMethod()))
											.orElse(false);
									} else if (dataSetInfo.destroyAfterClass() && terminationContext.getTestMethod().isEmpty()) {
										return terminationContext.getRequiredTestClass()
											.equals(dataSetState.testInstance().getClass());
									} else {
										return false;
									}
								}
							);
						}
					);

					CREATED_EVITA_INSTANCES.incrementAndGet();
					PEAK_EVITA_INSTANCES.set((int) Math.max(PEAK_EVITA_INSTANCES.get(), dataSetIndex.values().stream().filter(it -> it.evitaInstance() != null).count() + 1));
					CREATED_EVITA_ENTITIES.addAndGet(
						dataSetInfo.evitaInstance()
							.getCatalogs()
							.stream()
							.flatMapToInt(
								it -> it.getEntityTypes()
									.stream()
									.map(it::getCollectionForEntityOrThrowException)
									.mapToInt(EntityCollectionContract::size)
							)
							.sum()
					);

					return dataSetInfo;
				} else {
					return dataSetInfo;
				}
			}
		}
	}

	@Nonnull
	private EvitaServer openWebApi(
		@Nonnull Evita evita,
		@Nonnull ApiOptions apiOptions
	) {
		final EvitaServer evitaServer = new EvitaServer(evita, apiOptions);
		evitaServer.run();
		return evitaServer;
	}

	private record DataSetInfo(
		@Nonnull String catalogName,
		@Nullable CatalogInitMethod initMethod,
		@Nonnull List<Method> destroyMethods,
		@Nonnull String[] webApi,
		boolean readOnly,
		boolean destroyAfterClass,
		@Nonnull AtomicReference<DataSetState> dataSetInfoAtomicReference
	) {

		private DataSetInfo(
			@Nonnull String catalogName, @Nullable CatalogInitMethod initMethod,
			@Nonnull List<Method> destroyMethods, @Nonnull String[] webApi,
			boolean readOnly, boolean destroyAfterClass,
			@Nonnull AtomicReference<DataSetState> dataSetInfoAtomicReference
		) {
			this.catalogName = catalogName;
			this.initMethod = initMethod;
			this.destroyMethods = destroyMethods;
			this.webApi = webApi;
			this.readOnly = readOnly;
			this.destroyAfterClass = destroyAfterClass;
			this.dataSetInfoAtomicReference = dataSetInfoAtomicReference;
		}

		public DataSetInfo(
			@Nonnull String catalogName, @Nullable CatalogInitMethod initMethod,
			@Nonnull List<Method> destroyMethods, @Nonnull String[] webApi,
			boolean readOnly, boolean destroyAfterClass
		) {
			this(catalogName, initMethod, destroyMethods, webApi, readOnly, destroyAfterClass, new AtomicReference<>());
		}

		@Nullable
		public Evita evitaInstance() {
			return ofNullable(this.dataSetInfoAtomicReference.get())
				.map(DataSetState::evitaInstance)
				.orElse(null);
		}

		@Nullable
		public EvitaServer evitaServerInstance() {
			return ofNullable(this.dataSetInfoAtomicReference.get())
				.map(DataSetState::evitaServerInstance)
				.orElse(null);
		}

		@Nullable
		public DataCarrier dataCarrier() {
			return ofNullable(this.dataSetInfoAtomicReference.get())
				.map(DataSetState::dataCarrier)
				.orElse(null);
		}

		@Nullable
		public EvitaSessionContract evitaSession(@Nonnull Function<Evita, EvitaSessionContract> factory) {
			return ofNullable(this.dataSetInfoAtomicReference.get())
				.map(DataSetState::session)
				.map(it -> ofNullable(it.get())
					.orElseGet(
						() -> ofNullable(evitaInstance())
							.map(evita -> {
								final EvitaSessionContract newSession = factory.apply(evita);
								it.set(newSession);
								return newSession;
							})
							.orElse(null)
					)
				)
				.orElse(null);
		}

		public void init(@Nonnull Supplier<DataSetState> stateCreator) {
			final DataSetState previousState = this.dataSetInfoAtomicReference.compareAndExchange(
				null,
				stateCreator.get()
			);
			if (previousState != null) {
				throw new IllegalStateException("Previous state should be null!");
			}
		}

		public boolean destroyIfPredicateMatches(
			@Nonnull String dataSetName,
			@Nonnull DataSetInfo dataSetInfo,
			@Nonnull PortManager portManager,
			@Nonnull ExtensionContext extensionContext
		) {
			final DataSetState state = this.dataSetInfoAtomicReference.get();
			return ofNullable(state)
				.filter(it -> {
					// if session has been opened, close it and liquidate
					ofNullable(it.session().getAndSet(null))
						.ifPresent(EvitaSessionContract::close);
					return it.destroyPredicate().test(extensionContext, it);
				})
				.map(it -> {
					this.dataSetInfoAtomicReference.set(null);
					it.destroy(dataSetName, dataSetInfo, portManager);
					return true;
				})
				.orElse(false);
		}
	}

	private record CatalogInitMethod(@Nonnull Method method, @Nonnull CatalogState expectedState) {
	}

	private record DataSetState(
		@Nonnull Object testInstance,
		@Nonnull Method testMethod,
		@Nullable Evita evitaInstance,
		@Nullable EvitaServer evitaServerInstance,
		@Nullable DataCarrier dataCarrier,
		@Nonnull BiPredicate<ExtensionContext, DataSetState> destroyPredicate,
		@Nonnull AtomicReference<EvitaSessionContract> session
	) {

		private DataSetState(@Nonnull Object testInstance, @Nonnull Method testMethod, @Nullable Evita evitaInstance, @Nullable EvitaServer evitaServerInstance, @Nullable DataCarrier dataCarrier, @Nonnull BiPredicate<ExtensionContext, DataSetState> destroyPredicate) {
			this(testInstance, testMethod, evitaInstance, evitaServerInstance, dataCarrier, destroyPredicate, new AtomicReference<>());
		}

		/**
		 * Destroys the data set and closes the evitaDB server.
		 */
		public void destroy(
			@Nonnull String dataSetName,
			@Nonnull DataSetInfo dataSetInfo,
			@Nonnull PortManager portManager
		) {
			// call destroy methods
			for (Method destroyMethod : dataSetInfo.destroyMethods()) {
				try {
					final HashMap<String, Object> availableParameters = createLinkedHashMap(
						property("evita", evitaInstance),
						property("evitaServer", evitaServerInstance),
						property("catalogName", dataSetInfo.catalogName())
					);
					for (Entry<String, Object> entry : dataCarrier.entrySet()) {
						availableParameters.put(entry.getKey(), entry.getValue());
					}
					int counter = 0;
					for (Object anonymousValue : dataCarrier.anonymousValues()) {
						availableParameters.put("__anonymousValue_" + counter++, anonymousValue);
					}
					final Object[] arguments = placeArguments(
						destroyMethod,
						availableParameters
					);
					destroyMethod.invoke(testInstance, arguments);
				} catch (InvocationTargetException | IllegalAccessException e) {
					throw new ParameterResolutionException("Failed to tear down data set " + dataSetName, e);
				}
			}

			// get the storage directory from evita configuration
			final Path storageDirectory = evitaInstance.getConfiguration().storage().storageDirectory();

			// close evita and clear data
			evitaInstance.close();

			// close the server instance and free ports
			ofNullable(evitaServerInstance)
				.ifPresent(it -> {
					it.stop();
					portManager.releasePorts(dataSetName);
				});

			// close all closeable elements in data carrier
			if (dataCarrier != null) {
				Stream.concat(
					dataCarrier.entrySet().stream().filter(Objects::nonNull).map(Entry::getValue),
					dataCarrier.anonymousValues().stream()
				)
					.filter(it -> it instanceof Closeable)
					.forEach(it -> {
						try {
							((Closeable)it).close();
						} catch (IOException e) {
							log.error("Failed to close `" + it.getClass() + "` at the data set finalization!", e);
						}
					});
			}

			// delete the directory
			final Path evitaDataPath = STORAGE_PATH.resolve(storageDirectory);
			try {
				FileUtils.deleteDirectory(evitaDataPath.toFile());
			} catch (IOException e) {
				fail("Failed to empty directory: " + evitaDataPath, e);
			}
		}

	}

}
