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
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.core.Evita;
import io.evitadb.driver.EvitaClient;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.externalApi.configuration.AbstractApiOptions;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.ApiOptions.Builder;
import io.evitadb.externalApi.configuration.CertificateOptions;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.externalApi.lab.LabProvider;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.PortManager;
import io.evitadb.test.TestConstants;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.IsolateDataSetBySuffix;
import io.evitadb.test.annotation.OnDataSetTearDown;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.tester.GraphQLSchemaTester;
import io.evitadb.test.tester.GraphQLTester;
import io.evitadb.test.tester.LabApiTester;
import io.evitadb.test.tester.RestTester;
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
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
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
public class EvitaParameterResolver implements ParameterResolver, BeforeAllCallback, AfterAllCallback, AfterEachCallback, EvitaTestSupport {
	protected static final Path STORAGE_PATH = Path.of(System.getProperty("java.io.tmpdir") + File.separator + "evita");
	protected final static AtomicInteger CREATED_EVITA_ENTITIES = new AtomicInteger();
	protected final static AtomicInteger CREATED_EVITA_INSTANCES = new AtomicInteger();
	protected final static AtomicInteger PEAK_EVITA_INSTANCES = new AtomicInteger();
	protected static final AtomicReference<Map<String, DataSetInfo>> DATA_SET_INFO = new AtomicReference<>();
	private static final Map<String, ExternalApiProviderRegistrar> AVAILABLE_PROVIDERS = ExternalApiServer.gatherExternalApiProviders()
		.stream()
		.collect(Collectors.toMap(
			ExternalApiProviderRegistrar::getExternalApiCode,
			Function.identity()
		));
	private static final String PARAMETER_CATALOG_NAME = "catalogName";
	private static final String DATA_NAME_EVITA = "evita";
	private static final String DATA_NAME_CATALOG_NAME = "catalogName";
	private static final String DATA_NAME_EVITA_SESSION = "evitaSession";
	private static final String DATA_NAME_EVITA_SERVER = "evitaServer";
	private static final String EVITA_DATA_SET_INDEX = "__dataSetIndex";
	private static final String EVITA_ANONYMOUS_EVITA = "__anonymousEvita";
	private static final Random RANDOM = new Random();

	/**
	 * Indexes all methods annotated with {@link DataSet} annotation into the `dataSets` index.
	 */
	private static void indexTestClass(@Nonnull Map<String, DataSetInfo> dataSets, @Nonnull Class<?> testClass, @Nullable IsolateDataSetBySuffix isolateDataSetBySuffix) {
		final IsolateDataSetBySuffix isolateDataSetBySuffixToUse = isolateDataSetBySuffix == null ?
			testClass.getAnnotation(IsolateDataSetBySuffix.class) :
			isolateDataSetBySuffix;
		final Map<String, DataSetInfo> dataSetsInThisClass = new HashMap<>(8);
		for (Method declaredMethod : testClass.getDeclaredMethods()) {
			ofNullable(declaredMethod.getAnnotation(DataSet.class))
				.ifPresent(it -> {
					declaredMethod.setAccessible(true);
					final String dataSetName = it.value() + (isolateDataSetBySuffixToUse != null ? "_" + isolateDataSetBySuffixToUse.value() : "");
					dataSetsInThisClass.computeIfAbsent(
						dataSetName,
						dsName -> new DataSetInfo(
							dataSetName,
							it.catalogName(),
							new CatalogInitMethod(declaredMethod, it.expectedCatalogState()),
							new LinkedList<>(),
							it.openWebApi(),
							it.readOnly(),
							it.destroyAfterClass()
						)
					);
				});
		}
		for (Method declaredMethod : testClass.getDeclaredMethods()) {
			ofNullable(declaredMethod.getAnnotation(OnDataSetTearDown.class))
				.ifPresent(it -> {
					declaredMethod.setAccessible(true);
					final DataSetInfo dataSetInfo = dataSetsInThisClass.get(it.value());
					Assert.notNull(dataSetInfo, "There is no set up method for dataset `" + it.value() + "` in this class!");
					dataSetInfo.destroyMethods().add(declaredMethod);
				});
		}

		if (!Object.class.equals(testClass.getSuperclass())) {
			indexTestClass(dataSetsInThisClass, testClass.getSuperclass(), isolateDataSetBySuffixToUse);
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
		final Path evitaExportPath = STORAGE_PATH.resolve(randomFolderName + "_export");
		if (evitaDataPath.toFile().exists()) {
			io.evitadb.utils.FileUtils.deleteDirectory(evitaDataPath);
			io.evitadb.utils.FileUtils.deleteDirectory(evitaExportPath);
		}
		Assert.isTrue(evitaDataPath.toFile().mkdirs(), "Fail to create directory: " + evitaDataPath);
		Assert.isTrue(evitaExportPath.toFile().mkdirs(), "Fail to create directory: " + evitaDataPath);
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.server(
					// disable automatic session termination
					// to avoid closing sessions when you stop at breakpoint
					ServerOptions.builder()
						.closeSessionsAfterSecondsOfInactivity(-1)
						.serviceThreadPool(
							ThreadPoolOptions.serviceThreadPoolBuilder()
								.minThreadCount(1)
								.maxThreadCount(1)
								.queueSize(10_000)
								.build()
						)
						.requestThreadPool(
							ThreadPoolOptions.requestThreadPoolBuilder()
								.queueSize(10_000)
								.build()
						)
						.transactionThreadPool(
							ThreadPoolOptions.transactionThreadPoolBuilder()
								.queueSize(10_000)
								.build()
						)
						.build()
				)
				.storage(
					// point evitaDB to a test directory (temp directory)
					StorageOptions.builder()
						.storageDirectory(evitaDataPath)
						.exportDirectory(evitaExportPath)
						.maxOpenedReadHandles(1000)
						.syncWrites(false)
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
					DATA_SET_INFO.set(newDataSets);
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
			final Class<?> possibleValueClass;
			if (possibleValue instanceof LazyParameter<?> lazyParameter) {
				possibleValueClass = lazyParameter.type();
			} else if (possibleValue != null) {
				possibleValueClass = possibleValue.getClass();
			} else {
				possibleValueClass = null;
			}
			final Object matchingValue;
			if (possibleValueClass != null && parameter.getType().isAssignableFrom(possibleValueClass)) {
				// find by name
				matchingValue = possibleValue;
			} else {
				// find by type
				matchingValue = availableArguments.values()
					.stream()
					.filter(it -> {
						if (it instanceof LazyParameter<?> lazyParameter) {
							return parameter.getType().isAssignableFrom(lazyParameter.type());
						} else {
							return parameter.getType().isInstance(it);
						}
					})
					.findFirst()
					.orElse(null);
			}

			result[i] = matchingValue instanceof LazyParameter<?> lazyParameter ?
				lazyParameter.factory().get() : matchingValue;
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
				// 10 s request timeout - tests are highly parallel, squeezing our CI infrastructure
				.requestTimeoutInMillis(10_000)
				.certificate(
					CertificateOptions.builder()
						.folderPath(evita.getConfiguration().storage().storageDirectory().toString() + "-certificates")
						.build()
				);
			final int[] ports = portManager.allocatePorts(datasetName, dataSetInfo.webApi().length);
			int portIndex = 0;
			for (String webApiCode : dataSetInfo.webApi()) {
				final AbstractApiOptions webApiConfig;
				final Class<?> configurationClass = AVAILABLE_PROVIDERS.get(webApiCode).getConfigurationClass();
				try {
					final Constructor<?> hostConstructor = configurationClass.getConstructor(String.class);
					webApiConfig = (AbstractApiOptions) hostConstructor.newInstance(
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

	@Nonnull
	private static Namespace createTestMethodLocalNamespace(ExtensionContext context) {
		return Namespace.create(
			EvitaParameterResolver.class,
			context.getRequiredTestInstance(),
			context.getRequiredTestMethod()
		);
	}

	@Nonnull
	private static EvitaServer openWebApi(
		@Nonnull Evita evita,
		@Nonnull ApiOptions apiOptions
	) {
		final EvitaServer evitaServer = new EvitaServer(evita, apiOptions);
		evitaServer.run();

		final AbstractApiOptions cfg = apiOptions.getEndpointConfiguration(SystemProvider.CODE);
		if (cfg == null) {
			// system provider was not initialized
			return evitaServer;
		}

		// verify the server is running
		Exception lastException = null;
		int initAttempt = 0;
		do {
			for (String baseUrl : cfg.getBaseUrls()) {
				final String testUrl = baseUrl + "server-name";
				try {
					final URL website = new URL(testUrl);
					try (
						final Reader reader = Channels.newReader(Channels.newChannel(website.openStream()), StandardCharsets.UTF_8);
					) {
						// try to read server name from the system endpoint
						final char[] buffer = new char[50];
						final int read = reader.read(buffer);
						log.info("Server name available on url `{}`: {}", cfg.getBaseUrls()[0], new String(buffer, 0, read));
						return evitaServer;
					}
				} catch (Exception ex) {
					lastException = ex;
				}
			}
			initAttempt++;
			try {
				// if not ready wait for a while
				Thread.sleep(200);
			} catch (InterruptedException e) {
				return evitaServer;
			}
			// try at most 5 times
		} while (initAttempt < 3000);

		throw new IllegalStateException(
			"Evita server hasn't started on url " + Arrays.stream(cfg.getBaseUrls()).map(it -> "`" + it + "server-name`").collect(Collectors.joining(", ")) + " within 10 minutes!",
			lastException
		);
	}

	@Override
	public void beforeAll(ExtensionContext context) {
		// index data set bootstrap methods
		final Map<String, DataSetInfo> dataSets = getDataSetIndex(context);
		final Class<?> testClass = context.getRequiredTestClass();
		indexTestClass(dataSets, testClass, null);
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
		// if session has been opened, close it and liquidate
		final EvitaSessionContract sessionContract = (EvitaSessionContract) context
			.getStore(createTestMethodLocalNamespace(context))
			.get(EvitaSessionContract.class);
		ofNullable(sessionContract).ifPresent(session -> {
			try {
				log.info("Closing session {} after test {}...", session, context.getRequiredTestMethod().getName());
				session.close();
			} catch (Exception ex) {
				log.warn("The session was rolled back due to exception: " + ex.getMessage());
			}
		});

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
		final IsolateDataSetBySuffix isolateDataSetBySuffix = extensionContext.getTestClass()
			.map(it -> it.getAnnotation(IsolateDataSetBySuffix.class))
			.orElse(null);
		final BiFunction<ParameterContext, ExtensionContext, Optional<DataSetInfo>> dataSetInfoProvider = (pc, ec) -> {
			final UseDataSet methodUseDataSet = ofNullable(extensionContext.getRequiredTestMethod().getAnnotation(UseDataSet.class))
				.orElseGet(() -> getAnnotationOnSuperMethod(extensionContext, UseDataSet.class));
			final UseDataSet parameterUseDataSet = ofNullable(parameterContext.getParameter().getAnnotation(UseDataSet.class))
				.orElseGet(() -> getParameterAnnotationOnSuperMethod(parameterContext, extensionContext, UseDataSet.class));
			Assert.isTrue(
				parameterUseDataSet == null || methodUseDataSet == null,
				"UseDataSet annotation can be specified on parameter OR method level, but not both!"
			);
			final UseDataSet useDataSet = ofNullable(methodUseDataSet).orElse(parameterUseDataSet);
			if (useDataSet == null) {
				return Optional.empty();
			} else {
				final Map<String, DataSetInfo> dataSetIndex = getDataSetIndex(extensionContext);
				return Optional.of(
					getInitializedDataSetInfo(
						useDataSet, isolateDataSetBySuffix, dataSetIndex, parameterContext, extensionContext
					)
				);
			}
		};

		return EvitaContract.class.isAssignableFrom(parameterContext.getParameter().getType()) ||
			EvitaSessionContract.class.isAssignableFrom(parameterContext.getParameter().getType()) ||
			EvitaServer.class.isAssignableFrom(parameterContext.getParameter().getType()) ||
			GraphQLTester.class.isAssignableFrom(parameterContext.getParameter().getType()) ||
			GraphQLSchemaTester.class.isAssignableFrom(parameterContext.getParameter().getType()) ||
			RestTester.class.isAssignableFrom(parameterContext.getParameter().getType()) ||
			LabApiTester.class.isAssignableFrom(parameterContext.getParameter().getType()) ||
			EvitaClient.class.isAssignableFrom(parameterContext.getParameter().getType()) ||
			(String.class.isAssignableFrom(parameterContext.getParameter().getType()) && PARAMETER_CATALOG_NAME.equals(parameterContext.getParameter().getName())) ||
			dataSetInfoProvider.apply(parameterContext, extensionContext)
				.map(DataSetInfo::dataCarrier)
				.map(
					it -> ofNullable(it.getValueByName(parameterContext.getParameter().getName()))
						.orElseGet(() -> it.getValueByType(parameterContext.getParameter().getType()))
				)
				.orElse(null) != null;
	}

	@Nullable
	@Override
	public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
		final IsolateDataSetBySuffix isolateDataSetBySuffix = extensionContext.getTestClass()
			.map(it -> it.getAnnotation(IsolateDataSetBySuffix.class))
			.orElse(null);
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
		final DataSetInfo dataSetInfo = getInitializedDataSetInfo(useDataSet, isolateDataSetBySuffix, dataSetIndex, parameterContext, extensionContext);

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
		if (EvitaServer.class.isAssignableFrom(requestedParam.getType())) {
			// return initialized Evita server instance
			return dataSetInfo.evitaServerInstance();
		} else if (EvitaClient.class.isAssignableFrom(requestedParam.getType())) {
			// return new evita client
			return dataSetInfo.evitaClient(
				evitaServer -> {
					final AbstractApiOptions grpcConfig = evitaServer.getExternalApiServer()
						.getApiOptions()
						.getEndpointConfiguration(GrpcProvider.CODE);
					if (grpcConfig == null) {
						throw new ParameterResolutionException("gRPC web API was not opened for the dataset `" + useDataSet.value() + "`!");
					}
					final AbstractApiOptions systemConfig = evitaServer.getExternalApiServer()
						.getApiOptions()
						.getEndpointConfiguration(SystemProvider.CODE);
					if (systemConfig == null) {
						throw new ParameterResolutionException("System web API was not opened for the dataset `" + useDataSet.value() + "`!");
					}
					return new EvitaClient(
						EvitaClientConfiguration.builder()
							.certificateFolderPath(Path.of(evitaServer.getEvita().getConfiguration().storage().storageDirectory().toString() + "-client"))
							.host(grpcConfig.getHost()[0].hostAddress())
							.port(grpcConfig.getHost()[0].port())
							.systemApiPort(systemConfig.getHost()[0].port())
							.timeoutUnit(10, TimeUnit.MINUTES)
							.build()
					);
				}
			);
		} else if (EvitaContract.class.isAssignableFrom(requestedParam.getType())) {
			// return initialized Evita instance
			return dataSetInfo.evitaInstance();
		} else if (EvitaSessionContract.class.isAssignableFrom(requestedParam.getType())) {
			// return new read-write session in dry run mode
			final EvitaSessionContract session = dataSetInfo.evitaInstance().createSession(
				new SessionTraits(
					dataSetInfo.catalogName(),
					SessionFlags.READ_WRITE, SessionFlags.DRY_RUN
				)
			);
			extensionContext
				.getStore(createTestMethodLocalNamespace(extensionContext))
				.put(EvitaSessionContract.class, session);
			return session;
		} else if (GraphQLTester.class.isAssignableFrom(requestedParam.getType())) {
			// return new GraphQL tester
			return dataSetInfo.graphQLTester(
				evitaServer -> {
					final AbstractApiOptions gqlConfig = evitaServer.getExternalApiServer()
						.getApiOptions()
						.getEndpointConfiguration(GraphQLProvider.CODE);
					if (gqlConfig == null) {
						throw new ParameterResolutionException("GraphQL web API was not opened for the dataset `" + useDataSet.value() + "`!");
					}
					return new GraphQLTester(
						"https://" + gqlConfig.getHost()[0].hostAddressWithPort() + "/gql"
					);
				}
			);
		} else if (GraphQLSchemaTester.class.isAssignableFrom(requestedParam.getType())) {
			// return new GraphQL schema tester
			return dataSetInfo.graphQLSchemaTester(
				evitaServer -> {
					final AbstractApiOptions gqlConfig = evitaServer.getExternalApiServer()
						.getApiOptions()
						.getEndpointConfiguration(GraphQLProvider.CODE);
					if (gqlConfig == null) {
						throw new ParameterResolutionException("GraphQL web API was not opened for the dataset `" + useDataSet.value() + "`!");
					}
					return new GraphQLSchemaTester(
						"https://" + gqlConfig.getHost()[0].hostAddressWithPort() + "/gql"
					);
				}
			);
		} else if (LabApiTester.class.isAssignableFrom(requestedParam.getType())) {
			// return new Lab API tester
			return dataSetInfo.labApiTester(
				evitaServer -> {
					final AbstractApiOptions labApiConfig = evitaServer.getExternalApiServer()
						.getApiOptions()
						.getEndpointConfiguration(LabProvider.CODE);
					if (labApiConfig == null) {
						throw new ParameterResolutionException("Lab API was not opened for the dataset `" + useDataSet.value() + "`!");
					}
					return new LabApiTester(
						"https://" + labApiConfig.getHost()[0].hostAddressWithPort() + "/lab"
					);
				}
			);
		} else if (RestTester.class.isAssignableFrom(requestedParam.getType())) {
			// return new Rest tester
			return dataSetInfo.restTester(
				evitaServer -> {
					final AbstractApiOptions restConfig = evitaServer.getExternalApiServer()
						.getApiOptions()
						.getEndpointConfiguration(RestProvider.CODE);
					if (restConfig == null) {
						throw new ParameterResolutionException("REST web API was not opened for the dataset `" + useDataSet.value() + "`!");
					}
					return new RestTester(
						"https://" + restConfig.getHost()[0].hostAddressWithPort() + "/rest"
					);
				}
			);
		} else if (PARAMETER_CATALOG_NAME.equals(requestedParam.getName())) {
			// return catalog name
			return dataSetInfo.catalogName();
		} else {
			throw new ParameterResolutionException("Unrecognized parameter " + parameterContext + "!");
		}
	}

	@Nonnull
	public DataSetInfo getInitializedDataSetInfo(
		@Nullable UseDataSet useDataSet,
		@Nullable IsolateDataSetBySuffix isolateDataSetBySuffix,
		@Nonnull Map<String, DataSetInfo> dataSetIndex,
		@Nonnull ParameterContext parameterContext,
		@Nonnull ExtensionContext extensionContext
	) {
		if (useDataSet == null) {
			final Executable declaringExecutable = parameterContext.getDeclaringExecutable();
			final String evitaInstanceId = getUniqueHash(
				declaringExecutable.getDeclaringClass().getName(),
				declaringExecutable.toGenericString()
			);
			final String anonymousEvita = EVITA_ANONYMOUS_EVITA + "_" + evitaInstanceId;

			final DataSetInfo alreadyExistingAnonymousInstance = dataSetIndex.get(anonymousEvita);
			if (alreadyExistingAnonymousInstance == null) {

				// method doesn't use data set - so it needs to start with empty db
				final Evita evita = createEvita(TestConstants.TEST_CATALOG, evitaInstanceId);
				evita.updateCatalog(TestConstants.TEST_CATALOG, session -> {
					session.goLiveAndClose();
				});
				final DataSetInfo dataSetInfo = new DataSetInfo(
					anonymousEvita,
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
					anonymousEvita,
					dataSetInfo
				);
				CREATED_EVITA_INSTANCES.incrementAndGet();
				PEAK_EVITA_INSTANCES.set(
					(int) Math.max(PEAK_EVITA_INSTANCES.get(),
						dataSetIndex.values().stream().filter(it -> it.evitaInstance() != null).count())
				);
				return dataSetInfo;
			} else {
				return alreadyExistingAnonymousInstance;
			}
		} else {
			final String dataSetToUse = isolateDataSetBySuffix == null ? useDataSet.value() : useDataSet.value() + "_" + isolateDataSetBySuffix.value();
			final DataSetInfo dataSetInfo = dataSetIndex.get(dataSetToUse);
			if (dataSetInfo == null) {
				throw new ParameterResolutionException("Requested data set " + dataSetToUse + " has no initialization method within the class (Method with @DataSet annotation)!");
			}
			synchronized (dataSetInfo) {
				final BiPredicate<ExtensionContext, DataSetState> dataStateTearDownFct = (terminationContext, dataSetState) -> {
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
				};
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
									property(DATA_NAME_EVITA, evita),
									property(DATA_NAME_CATALOG_NAME, dataSetInfo.catalogName()),
									property(
										DATA_NAME_EVITA_SESSION,
										new LazyParameter<>(
											EvitaSessionContract.class,
											() -> evita.createReadWriteSession(dataSetInfo.catalogName())
										)
									)
								);
								if (evitaServer != null) {
									argumentDictionary.put(DATA_NAME_EVITA_SERVER, evitaServer);
								}
								final Object[] arguments = placeArguments(initMethod, argumentDictionary);
								if (arguments == null) {
									throw new ParameterResolutionException("Data set init method may have only these arguments: evita instance, catalog name, evita server instance. Failed to init " + dataSetToUse + ".");
								} else {
									methodResult = initMethod.invoke(testClassInstance, arguments);
								}
								for (Object argument : arguments) {
									if (argument instanceof EvitaSessionContract session) {
										log.info("Closing session {} data set initialization {}...", session, extensionContext.getRequiredTestMethod().getName());
										session.close();
									}
								}
							} catch (Exception e) {
								// close the server instance and free ports
								ofNullable(evitaServer)
									.ifPresent(it -> getPortManager().releasePortsOnCompletion(dataSetToUse, it.stop()));

								// close evita and clear data
								evita.close();

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
								dataStateTearDownFct
							);
						}
					);

					CREATED_EVITA_INSTANCES.incrementAndGet();
					PEAK_EVITA_INSTANCES.set((int) Math.max(PEAK_EVITA_INSTANCES.get(), dataSetIndex.values().stream().filter(it -> it.evitaInstance() != null).count()));
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
					return dataSetInfo.updateState(
						extensionContext.getRequiredTestInstance(),
						extensionContext.getRequiredTestMethod(),
						dataStateTearDownFct
					);
				}
			}
		}
	}

	/**
	 * Generates a unique hash by concatenating and hashing the provided strings using the SHA-256 algorithm.
	 *
	 * @param strings The strings to be concatenated and hashed.
	 * @return The generated unique hash as a hexadecimal string.
	 * @throws RuntimeException if the SHA-256 algorithm is not found.
	 */
	@Nonnull
	public String getUniqueHash(@Nonnull String... strings) {
		try {
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String string : strings) {
				digest.update(string.getBytes(StandardCharsets.UTF_8));
			}
			final byte[] encodedhash = digest.digest();
			final StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
			for (byte b : encodedhash) {
				final String hex = Integer.toHexString(0xff & b);
				if (hex.length() == 1) {
					hexString.append('0');
				}
				hexString.append(hex);
			}
			return hexString.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-256 algorithm not found.", e);
		}
	}

	protected record DataSetInfo(
		@Nonnull String name,
		@Nonnull String catalogName,
		@Nullable CatalogInitMethod initMethod,
		@Nonnull List<Method> destroyMethods,
		@Nonnull String[] webApi,
		boolean readOnly,
		boolean destroyAfterClass,
		@Nonnull AtomicReference<DataSetState> dataSetInfoAtomicReference
	) {

		public DataSetInfo(
			@Nonnull String name, @Nonnull String catalogName, @Nullable CatalogInitMethod initMethod,
			@Nonnull List<Method> destroyMethods, @Nonnull String[] webApi,
			boolean readOnly, boolean destroyAfterClass
		) {
			this(name, catalogName, initMethod, destroyMethods, webApi, readOnly, destroyAfterClass, new AtomicReference<>());
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
		public EvitaClient evitaClient(@Nonnull Function<EvitaServer, EvitaClient> factory) {
			return ofNullable(this.dataSetInfoAtomicReference.get())
				.map(DataSetState::client)
				.map(it -> ofNullable(it.get())
					.orElseGet(
						() -> ofNullable(evitaServerInstance())
							.map(evitaServer -> {
								final EvitaClient newSession = factory.apply(evitaServer);
								it.set(newSession);
								return newSession;
							})
							.orElseThrow(() -> new ParameterResolutionException("gRPC web API was not opened for the dataset `" + name + "`!"))
					)
				)
				.orElse(null);
		}

		@Nullable
		public GraphQLTester graphQLTester(@Nonnull Function<EvitaServer, GraphQLTester> factory) {
			return ofNullable(this.dataSetInfoAtomicReference.get())
				.map(DataSetState::graphQLTester)
				.map(it -> ofNullable(it.get())
					.orElseGet(
						() -> ofNullable(evitaServerInstance())
							.map(evitaServer -> {
								final GraphQLTester newTester = factory.apply(evitaServer);
								it.set(newTester);
								return newTester;
							})
							.orElseThrow(() -> new ParameterResolutionException("GraphQL web API was not opened for the dataset `" + name + "`!"))
					)
				)
				.orElse(null);
		}

		@Nullable
		public GraphQLSchemaTester graphQLSchemaTester(@Nonnull Function<EvitaServer, GraphQLSchemaTester> factory) {
			return ofNullable(this.dataSetInfoAtomicReference.get())
				.map(DataSetState::graphQLSchemaTester)
				.map(it -> ofNullable(it.get())
					.orElseGet(
						() -> ofNullable(evitaServerInstance())
							.map(evitaServer -> {
								final GraphQLSchemaTester newTester = factory.apply(evitaServer);
								it.set(newTester);
								return newTester;
							})
							.orElseThrow(() -> new ParameterResolutionException("GraphQL web API was not opened for the dataset `" + name + "`!"))
					)
				)
				.orElse(null);
		}

		@Nullable
		public RestTester restTester(@Nonnull Function<EvitaServer, RestTester> factory) {
			return ofNullable(this.dataSetInfoAtomicReference.get())
				.map(DataSetState::restTester)
				.map(it -> ofNullable(it.get())
					.orElseGet(
						() -> ofNullable(evitaServerInstance())
							.map(evitaServer -> {
								final RestTester newTester = factory.apply(evitaServer);
								it.set(newTester);
								return newTester;
							})
							.orElseThrow(() -> new ParameterResolutionException("REST web API was not opened for the dataset `" + name + "`!"))
					)
				)
				.orElse(null);
		}

		@Nullable
		public LabApiTester labApiTester(@Nonnull Function<EvitaServer, LabApiTester> factory) {
			return ofNullable(this.dataSetInfoAtomicReference.get())
				.map(DataSetState::labApiTester)
				.map(it -> ofNullable(it.get())
					.orElseGet(
						() -> ofNullable(evitaServerInstance())
							.map(evitaServer -> {
								final LabApiTester newTester = factory.apply(evitaServer);
								it.set(newTester);
								return newTester;
							})
							.orElseThrow(() -> new ParameterResolutionException("Lab API was not opened for the dataset `" + name + "`!"))
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

		public DataSetInfo updateState(@Nonnull Object testInstance, @Nonnull Method testMethod, @Nonnull BiPredicate<ExtensionContext, DataSetState> destroyPredicate) {
			final DataSetState theState = this.dataSetInfoAtomicReference.updateAndGet(
				existingState -> existingState.update(testInstance, testMethod, destroyPredicate)
			);
			if (theState == null) {
				throw new IllegalStateException("Previous state should not be null!");
			}
			return this;
		}

		public boolean destroyIfPredicateMatches(
			@Nonnull String dataSetName,
			@Nonnull DataSetInfo dataSetInfo,
			@Nonnull PortManager portManager,
			@Nonnull ExtensionContext extensionContext
		) {
			final DataSetState state = this.dataSetInfoAtomicReference.get();
			return ofNullable(state)
				.filter(it -> it.destroyPredicate().test(extensionContext, it))
				.map(it -> {
					this.dataSetInfoAtomicReference.set(null);
					it.destroy(dataSetName, dataSetInfo, portManager);
					return true;
				})
				.orElse(false);
		}

		public boolean destroy(
			@Nonnull String dataSetName,
			@Nonnull DataSetInfo dataSetInfo,
			@Nonnull PortManager portManager
		) {
			final DataSetState state = this.dataSetInfoAtomicReference.get();
			return ofNullable(state)
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

	private record LazyParameter<T>(
		@Nonnull Class<T> type,
		@Nonnull Supplier<T> factory
	) {
	}

	private record DataSetState(
		@Nonnull Object testInstance,
		@Nonnull Method testMethod,
		@Nullable Evita evitaInstance,
		@Nullable EvitaServer evitaServerInstance,
		@Nullable DataCarrier dataCarrier,
		@Nonnull BiPredicate<ExtensionContext, DataSetState> destroyPredicate,
		@Nonnull AtomicReference<EvitaClient> client,
		@Nonnull AtomicReference<GraphQLTester> graphQLTester,
		@Nonnull AtomicReference<GraphQLSchemaTester> graphQLSchemaTester,
		@Nonnull AtomicReference<RestTester> restTester,
		@Nonnull AtomicReference<LabApiTester> labApiTester
	) {

		private DataSetState(@Nonnull Object testInstance, @Nonnull Method testMethod, @Nullable Evita evitaInstance, @Nullable EvitaServer evitaServerInstance, @Nullable DataCarrier dataCarrier, @Nonnull BiPredicate<ExtensionContext, DataSetState> destroyPredicate) {
			this(testInstance, testMethod, evitaInstance, evitaServerInstance, dataCarrier, destroyPredicate, new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
		}

		@Nonnull
		public DataSetState update(@Nonnull Object testInstance, @Nonnull Method testMethod, @Nonnull BiPredicate<ExtensionContext, DataSetState> destroyPredicate) {
			return new DataSetState(
				testInstance, testMethod,
				evitaInstance, evitaServerInstance, dataCarrier,
				destroyPredicate,
				client, graphQLTester, graphQLSchemaTester, restTester, labApiTester
			);
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
						property(DATA_NAME_EVITA, evitaInstance),
						property(DATA_NAME_EVITA_SERVER, evitaServerInstance),
						property(DATA_NAME_CATALOG_NAME, dataSetInfo.catalogName())
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
			log.info("Closing Evita instance for data set `{}`", dataSetName);
			evitaInstance.close();

			// close the client
			ofNullable(client.get())
				.ifPresent(EvitaClient::close);

			// close the server instance and free ports
			ofNullable(evitaServerInstance)
				.ifPresent(it -> portManager.releasePortsOnCompletion(dataSetName, it.stop()));

			// close all closeable elements in data carrier
			if (dataCarrier != null) {
				Stream.concat(
						dataCarrier.entrySet().stream().filter(Objects::nonNull).map(Entry::getValue),
						dataCarrier.anonymousValues().stream()
					)
					.filter(it -> it instanceof Closeable)
					.forEach(it -> {
						try {
							log.info("Closing resource `{}`", it);
							((Closeable) it).close();
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
