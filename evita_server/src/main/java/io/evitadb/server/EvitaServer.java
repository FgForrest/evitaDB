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

package io.evitadb.server;

import ch.qos.logback.classic.ClassicConstants;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.ContextBase;
import ch.qos.logback.core.spi.ContextAwareBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.AbstractApiOptions;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.server.configuration.EvitaServerConfiguration;
import io.evitadb.server.exception.ConfigurationParseException;
import io.evitadb.server.yaml.AbstractClassDeserializer;
import io.evitadb.server.yaml.EvitaConstructor;
import io.evitadb.server.yaml.SpecialConfigInputFormatsHandler;
import io.evitadb.server.yaml.UnknownPropertyProblemHandler;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ConsoleWriter;
import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import io.evitadb.utils.ConsoleWriter.ConsoleDecoration;
import io.evitadb.utils.ExceptionUtils;
import io.evitadb.utils.VersionUtils;
import lombok.Getter;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.io.StringSubstitutorReader;
import org.apache.commons.text.lookup.StringLookupFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.externalApi.configuration.TlsMode.FORCE_NO_TLS;
import static io.evitadb.externalApi.configuration.TlsMode.FORCE_TLS;
import static java.util.Optional.ofNullable;

/**
 * EvitaServer implementation allows to run evitaDB in standalone Java application (process) that opens HTTP endpoints
 * that can be used by clients to communicate with the evitaDB instance.
 *
 * As of time being we support three API formats:
 *
 * - gRPC: fastest, not so user-friendly - recommended for driver implementation and microservices architecture
 * - graphQL: web friendly JSON API optimized for direct consumption from browsers or JavaScript based server implementations
 * - REST: web friendly JSON API
 *
 * We use Armeria web servers for all APIs.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EvitaServer {
	/**
	 * Name of the argument for specification of evita configuration directory.
	 */
	public static final String OPTION_EVITA_CONFIGURATION_DIR = "configDir";
	/**
	 * Name of the argument for enabling/disabling strict configuration file check.
	 */
	public static final String OPTION_STRICT_CONFIG_FILE_CHECK = "strictConfigFileCheck";
	/**
	 * Pattern for matching Java arguments `-Dname=value`
	 */
	private static final Pattern OPTION_JAVA_ARGUMENT = Pattern.compile("-D(\\S+)=(.+)");
	/**
	 * Pattern for matching Unix like arguments `--name=value`
	 */
	private static final Pattern OPTION_GNU_ARGUMENT = Pattern.compile("--(\\S+)=(.+)");
	/**
	 * Pattern for matching simple arguments `name=value`
	 */
	private static final Pattern OPTION_SIMPLE_ARGUMENT = Pattern.compile("(\\S+)=(.+)");
	/**
	 * Classpath location of the default evita configuration file.
	 */
	private static final String DEFAULT_EVITA_CONFIGURATION = "/evita-configuration.yaml";
	/**
	 * Logger.
	 */
	private static Logger log;
	/**
	 * Instance of the {@link EvitaConfiguration} initialized from the evita configuration file.
	 */
	private final EvitaConfiguration evitaConfiguration;
	/**
	 * Instance of the {@link EvitaServerConfiguration} initialized from the evita configuration file.
	 */
	private final EvitaServerConfiguration evitaServerConfiguration;
	/**
	 * List of external API providers indexed by their {@link ExternalApiProviderRegistrar#getExternalApiCode()}.
	 */
	@SuppressWarnings("rawtypes")
	private final Collection<ExternalApiProviderRegistrar> externalApiProviders;
	/**
	 * Instance of the evitaDB.
	 */
	@Getter private Evita evita;
	/**
	 * Instance of the web server providing the HTTP endpoints.
	 */
	@Getter private ExternalApiServer externalApiServer;
	/**
	 * Reference to a future that is initialized when server is stopped.
	 */
	private CompletableFuture<Void> stopFuture;

	/**
	 * Rock'n'Roll.
	 */
	public static void main(String[] args) {
		final Map<String, String> options = parseOptions(args);
		// somehow the -D arguments are not propagated to system properties, so we need to do it manually
		for (Entry<String, String> argEntry : options.entrySet()) {
			System.setProperty(argEntry.getKey(), argEntry.getValue());
		}

		final String logMsg = initLog();

		final Path configDirPath = ofNullable(options.get(OPTION_EVITA_CONFIGURATION_DIR))
			.map(it -> Paths.get("").resolve(it))
			.orElseGet(() -> Paths.get(""));

		final boolean strictConfigFileCheck = ofNullable(options.get(OPTION_STRICT_CONFIG_FILE_CHECK))
			.map(Boolean::parseBoolean)
			.orElse(false);

		final EvitaServer evitaServer = new EvitaServer(configDirPath, strictConfigFileCheck, logMsg, options);
		final ShutdownSequence shutdownSequence = new ShutdownSequence(evitaServer);
		Runtime.getRuntime().addShutdownHook(new Thread(shutdownSequence));
		evitaServer.run();
	}

	/**
	 * Applies default values to endpoints in the configuration map.
	 * The method checks if the configuration map contains an "api" key
	 * with nested "endpoints" maps, and then it adds default values
	 * to each endpoint from the provided endpointDefaults map if any
	 * default values are missing.
	 *
	 * @param configuration    the main configuration map which includes endpoint configurations, must not be null
	 * @param endpointDefaults a map containing default values for the endpoints, must not be null
	 */
	@SuppressWarnings("unchecked")
	static void applyEndpointDefaults(
		@Nonnull Map<String, Object> configuration,
		@Nonnull Map<String, Object> endpointDefaults
	) {
		ofNullable(configuration.get("api"))
			.map(it -> (Map<String, Object>) it)
			.map(it -> it.get("endpoints"))
			.map(it -> (Map<String, Object>) it)
			.ifPresent(
				endpoints -> endpoints.values().forEach(
					endpoint -> endpointDefaults.forEach(
						(key, value) -> ((Map<String, Object>) endpoint).putIfAbsent(key, value)
					))
			);

	}

	/**
	 * Initializes the file from the specified location.
	 */
	@Nullable
	private static String initLog() {
		String logMsg;
		if (System.getProperty(ClassicConstants.CONFIG_FILE_PROPERTY) == null) {
			System.setProperty(ClassicConstants.CONFIG_FILE_PROPERTY, "META-INF/logback.xml");
			logMsg = null;
		} else {
			final String originalFilePath = System.getProperty(ClassicConstants.CONFIG_FILE_PROPERTY);
			final File logFile = new File(originalFilePath);
			if (!logFile.exists() || !logFile.isFile()) {
				logMsg = "original file `" + originalFilePath + "` doesn't exist";
				System.setProperty(ClassicConstants.CONFIG_FILE_PROPERTY, "META-INF/logback.xml");
			} else {
				logMsg = null;
			}
		}
		// and then initialize logger so that `logback.configurationFile` argument might apply
		Assert.isPremiseValid(getLog() != null, () -> "Logger should be initialized here.");
		return logMsg;
	}

	/**
	 * Method parses the options from the command line and creates a key-value map from them.
	 */
	private static Map<String, String> parseOptions(String[] args) {
		final Map<String, String> map = CollectionUtils.createHashMap(args.length);
		for (String arg : args) {
			final Matcher javaArgMatcher = OPTION_JAVA_ARGUMENT.matcher(arg.trim());
			final Matcher gnuArgMatcher = OPTION_GNU_ARGUMENT.matcher(arg.trim());
			final Matcher simpleArgMatcher = OPTION_SIMPLE_ARGUMENT.matcher(arg.trim());
			if (javaArgMatcher.matches()) {
				map.put(javaArgMatcher.group(1), javaArgMatcher.group(2));
			} else if (gnuArgMatcher.matches()) {
				map.put(gnuArgMatcher.group(1), gnuArgMatcher.group(2));
			} else if (simpleArgMatcher.matches()) {
				map.put(simpleArgMatcher.group(1), simpleArgMatcher.group(2));
			}
		}
		return map;
	}

	/**
	 * Creates Apache Commons {@link StringSubstitutor} that allows substitution of the variables from the system
	 * environment variables and the program invocation arguments.
	 */
	@Nonnull
	private static StringSubstitutor createStringSubstitutor(@Nonnull Map<String, String> arguments) {
		final StringSubstitutor stringSubstitutor = new StringSubstitutor(
			StringLookupFactory.INSTANCE.functionStringLookup(
				variable -> ofNullable(arguments.get(variable))
					.orElseGet(
						() -> ofNullable(System.getProperty(variable))
							.orElseGet(() -> System.getenv(transformEnvironmentVariable(variable)))
					)
			)
		);
		stringSubstitutor.setEnableSubstitutionInVariables(false);
		stringSubstitutor.setEnableUndefinedVariableException(false);
		stringSubstitutor.setValueDelimiter(':');
		return stringSubstitutor;
	}

	/**
	 * Linux operating systems don't allow dots in the environment variable names. Other systems in the industry
	 * follow mechanism that transform such variables to upper-case, replace dots with underscores and prepend the
	 * variable with the name of the application in upper-case. This method tries to mimic this behavior.
	 *
	 * @param variableName original variable name with dots
	 * @return transformed variable name
	 */
	@Nonnull
	private static String transformEnvironmentVariable(@Nonnull String variableName) {
		return "EVITADB_" + variableName.toUpperCase().replace('.', '_');
	}

	/**
	 * Returns and lazily initializes logger.
	 */
	@Nonnull
	private static Logger getLog() {
		if (log == null) {
			log = LoggerFactory.getLogger(EvitaServer.class);
		}
		return log;
	}

	/**
	 * Method merges multiple YAML files into a single configuration. Values from the last file override the previous
	 * ones.
	 *
	 * @param yamlMapper        YAML mapper to use
	 * @param stringSubstitutor variable substitutor to use
	 * @param readerFactory     lambda function that creates a reader from an input stream
	 * @param configDirLocation location of the configuration directory
	 * @param files             list of files to merge in the correct order
	 * @return merged configuration
	 * @throws IOException if an I/O error occurs
	 */
	@Nonnull
	private static EvitaServerConfiguration mergeYamlFiles(
		@Nonnull ObjectMapper yamlMapper,
		@Nonnull StringSubstitutor stringSubstitutor,
		@Nonnull Function<InputStream, Reader> readerFactory,
		@Nonnull Path configDirLocation,
		@Nonnull Path... files
	) throws IOException {
		final AtomicReference<Yaml> yamlParser = new AtomicReference<>();
		yamlParser.set(
			new Yaml(
				new EvitaConstructor(
					yamlParser,
					stringSubstitutor,
					configDirLocation
				)
			)
		);

		try (
			final InputStream resourceAsStream = EvitaServer.class.getResourceAsStream(DEFAULT_EVITA_CONFIGURATION)
		) {
			// iterate over all files in the directory and merge them into a single configuration
			Map<String, Object> finalYaml = loadYamlContents(readerFactory.apply(resourceAsStream), yamlParser.get());
			Map<String, Object> endpointDefaults = updateEndpointDefaults(Map.of(), finalYaml);
			for (Path file : files) {
				try (final InputStream fileInputStream = new BufferedInputStream(new FileInputStream(file.toFile()))) {
					final Map<String, Object> loadedYaml = loadYamlContents(readerFactory.apply(fileInputStream), yamlParser.get());
					endpointDefaults = updateEndpointDefaults(endpointDefaults, loadedYaml);
					finalYaml = combine(finalYaml, loadedYaml);
				}
			}
			// apply the api.endpointDefaults
			applyEndpointDefaults(finalYaml, endpointDefaults);
			return yamlMapper.convertValue(finalYaml, EvitaServerConfiguration.class);
		} catch (IOException e) {
			throw new ConfigurationParseException(
				"Failed to parse configuration files in folder `" + configDirLocation + "` due to: " + e.getMessage() + ".",
				"Failed to parse configuration files.", e
			);
		}
	}

	/**
	 * Method loads the contents of the YAML file into a map.
	 *
	 * @param reader reader to read the contents of the file
	 * @param yaml   YAML parser to use
	 * @return map with the contents of the YAML file
	 */
	@Nonnull
	private static Map<String, Object> loadYamlContents(@Nonnull Reader reader, @Nonnull Yaml yaml) {
		final Map<String, Object> values = yaml.load(reader);
		// backward compatibility with the old configuration format
		replaceDeprecatedSettings("", values);
		return values;
	}

	/**
	 * Method replaces deprecated settings in the configuration.
	 * TOBEDONE #538 - remove in the future
	 */
	private static void replaceDeprecatedSettings(@Nonnull String prefix, @Nonnull Map<String, Object> values) {
		final List<Object[]> itemsToAdd = new LinkedList<>();
		final Iterator<Entry<String, Object>> entryIterator = values.entrySet().iterator();
		while (entryIterator.hasNext()) {
			final Entry<String, Object> entry = entryIterator.next();
			//noinspection rawtypes
			if (entry.getValue() instanceof Map map) {
				//noinspection unchecked
				replaceDeprecatedSettings((prefix.isBlank() ? "" : prefix + ".") + entry.getKey(), map);
			} else if (entry.getKey().equals("keepAlive") && prefix.equals("api")) {
				itemsToAdd.add(
					new Object[]{
						"endpointDefaults",
						Map.of("keepAlive", entry.getValue())
					}
				);
				entryIterator.remove();
			} else if (entry.getKey().equals("exposeOn") && prefix.equals("api")) {
				itemsToAdd.add(
					new Object[]{
						"endpointDefaults",
						Map.of("exposeOn", entry.getValue())
					}
				);
				entryIterator.remove();
			} else if (entry.getKey().equals("tlsEnabled")) {
				entryIterator.remove();
				itemsToAdd.add(
					new Object[]{
						"tlsMode",
						entry.getValue().equals(Boolean.TRUE) ? FORCE_TLS : FORCE_NO_TLS
					}
				);
			}
		}
		for (Object[] replacedValues : itemsToAdd) {
			final String replacedKey = (String) replacedValues[0];
			final Object replacedValue = replacedValues[1];
			replaceValue(values, replacedKey, replacedValue);
		}
	}

	/**
	 * Updates the endpoint defaults by combining the provided defaults with those loaded from a YAML configuration.
	 *
	 * @param endpointDefaults the base map of endpoint defaults, must not be null
	 * @param loadedYaml       the loaded YAML map, must not be null
	 * @return the updated map of endpoint defaults
	 */
	@SuppressWarnings("unchecked")
	@Nonnull
	private static Map<String, Object> updateEndpointDefaults(
		@Nonnull Map<String, Object> endpointDefaults,
		@Nonnull Map<String, Object> loadedYaml
	) {
		return ofNullable(loadedYaml.get("api"))
			.map(it -> (Map<String, Object>) it)
			.map(it -> it.remove("endpointDefaults"))
			.map(it -> (Map<String, Object>) it)
			.map(it -> combine(endpointDefaults, it))
			.orElse(endpointDefaults);
	}

	/**
	 * Replaces a value in a map. If the replaced value is a map, it applies the replacement recursively.
	 *
	 * @param values        the map in which the value should be replaced
	 * @param replacedKey   the key of the value to be replaced
	 * @param replacedValue the new value that will replace the old one
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void replaceValue(@Nonnull Map values, String replacedKey, Object replacedValue) {
		if (replacedValue instanceof Map replacedMap) {
			final Object existingValues = values.get(replacedKey);
			if (existingValues == null) {
				values.put(replacedKey, replacedMap);
			} else {
				Assert.isPremiseValid(existingValues instanceof Map, () -> "Expected map, got: " + existingValues);
				replacedMap.forEach((key, value) -> replaceValue((Map) existingValues, (String) key, value));
			}
		} else {
			values.put(replacedKey, replacedValue);
		}
	}

	/**
	 * Method combines two maps into a single map. If the same key is present in both maps, the value from the second map
	 * overrides the value from the first map. If the value is a map, the method is called recursively.
	 *
	 * @param base  base map
	 * @param delta map with keys and values that should override the base map
	 * @return combined map
	 */
	@Nonnull
	private static Map<String, Object> combine(
		@Nonnull Map<String, Object> base,
		@Nonnull Map<String, Object> delta
	) {
		final Map<String, Object> combined = CollectionUtils.createHashMap(base.size() + delta.size());
		combined.putAll(base);
		for (Entry<String, Object> entry : delta.entrySet()) {
			if (combined.containsKey(entry.getKey())) {
				final Object existingValue = combined.get(entry.getKey());
				final Object newValue = entry.getValue();
				if (existingValue instanceof Map && newValue instanceof Map) {
					//noinspection unchecked
					combined.put(
						entry.getKey(),
						combine(
							(Map<String, Object>) combined.get(entry.getKey()),
							(Map<String, Object>) entry.getValue()
						)
					);
				} else {
					combined.put(entry.getKey(), entry.getValue());
				}
			} else {
				combined.put(entry.getKey(), entry.getValue());
			}
		}
		return combined;
	}

	/**
	 * Constructor that initializes the EvitaServer.
	 */
	public EvitaServer(@Nonnull Path configDirLocation, @Nonnull Map<String, String> arguments) {
		this(configDirLocation, false, null, arguments);
	}

	/**
	 * Constructor that initializes the EvitaServer.
	 */
	@SuppressWarnings({"UnnecessaryStringEscape", "EscapedSpace"})
	public EvitaServer(
		@Nonnull Path configDirLocation,
		boolean strictConfigFileCheck,
		@Nullable String logInitializationStatus,
		@Nonnull Map<String, String> arguments
	) {
		this.externalApiProviders = ExternalApiServer.gatherExternalApiProviders();
		final EvitaServerConfigurationWithLogFilesListing evitaServerConfigurationWithLogFilesListing = parseConfiguration(configDirLocation, strictConfigFileCheck, arguments);
		final EvitaServerConfiguration evitaServerConfig = evitaServerConfigurationWithLogFilesListing.configuration();
		this.evitaServerConfiguration = evitaServerConfig;
		this.evitaConfiguration = new EvitaConfiguration(
			evitaServerConfig.name(),
			evitaServerConfig.server(),
			evitaServerConfig.storage(),
			evitaServerConfig.transaction(),
			evitaServerConfig.cache()
		);

		if (this.evitaConfiguration.server().quiet()) {
			ConsoleWriter.setQuiet(true);
		}

		ConsoleWriter.write(
			"""
				\n\n            _ _        ____  ____ \s
				  _____   _(_) |_ __ _|  _ \\| __ )\s
				 / _ \\ \\ / / | __/ _` | | | |  _ \\\s
				|  __/\\ V /| | || (_| | |_| | |_) |
				 \\___| \\_/ |_|\\__\\__,_|____/|____/\s\n\n""",
			ConsoleColor.DARK_GREEN
		);
		ConsoleWriter.write("beta build %s (keep calm and report bugs 😉)", new Object[]{VersionUtils.readVersion()}, ConsoleColor.LIGHT_GRAY);
		ConsoleWriter.write("\n", ConsoleColor.WHITE);
		ConsoleWriter.write("Visit us at: ");
		ConsoleWriter.write("https://evitadb.io", ConsoleColor.DARK_BLUE, ConsoleDecoration.UNDERLINE);
		ConsoleWriter.write("\n\n", ConsoleColor.WHITE);
		ConsoleWriter.write("Log config used: " + System.getProperty(ClassicConstants.CONFIG_FILE_PROPERTY) + ofNullable(logInitializationStatus).map(it -> " (" + it + ")").orElse("") + "\n", ConsoleColor.DARK_GRAY);
		ConsoleWriter.write("Config files used:\n   - DEFAULT (on classpath)\n" + Arrays.stream(evitaServerConfigurationWithLogFilesListing.configFilesApplied()).map(it -> "   - " + it.toAbsolutePath()).collect(Collectors.joining("\n")), ConsoleColor.DARK_GRAY);
		ConsoleWriter.write("\n", ConsoleColor.WHITE);

		ConsoleWriter.write("Server name: ", ConsoleColor.WHITE);
		ConsoleWriter.write(this.evitaConfiguration.name(), ConsoleColor.BRIGHT_YELLOW);
		ConsoleWriter.write("\n", ConsoleColor.WHITE);
	}

	/**
	 * Constructor that initializes EvitaServer on top of existing and running Evita server.
	 */
	public EvitaServer(@Nonnull Evita evita, @Nonnull ApiOptions apiOptions) {
		this.externalApiProviders = ExternalApiServer.gatherExternalApiProviders();
		this.evita = evita;
		this.evitaConfiguration = evita.getConfiguration();
		this.evitaServerConfiguration = new EvitaServerConfiguration(
			this.evitaConfiguration.name(),
			this.evitaConfiguration.server(),
			this.evitaConfiguration.storage(),
			this.evitaConfiguration.transaction(),
			this.evitaConfiguration.cache(),
			apiOptions
		);
	}

	/**
	 * Method starts the {@link ExternalApiServer} and opens ports for all web APIs.
	 */
	public void run() {
		if (this.evita == null) {
			this.evita = new Evita(this.evitaConfiguration, false);
		}
		this.externalApiServer = new ExternalApiServer(
			this.evita, this.evitaServerConfiguration.api(), this.externalApiProviders
		);
		this.evita.management().setConfigurationSupplier(this::serializeConfiguration);

		try {
			this.externalApiServer.start();
		} catch (RuntimeException e) {
			ConsoleWriter.write("*".repeat(100) + "\n", ConsoleColor.BRIGHT_RED, ConsoleDecoration.BOLD);
			ConsoleWriter.write("!!! Failed to start external APIs due to: " + ExceptionUtils.getRootCause(e).getMessage() + "\n", ConsoleColor.BRIGHT_RED, ConsoleDecoration.BOLD);
			ConsoleWriter.write("*".repeat(100) + "\n", ConsoleColor.BRIGHT_RED, ConsoleDecoration.BOLD);
			log.error("Failed to start external APIs.", e);
		}

		// now schedule catalog loading
		this.evita.scheduleInitialCatalogLoading();
	}

	/**
	 * Method stops {@link ExternalApiServer} and closes all opened ports.
	 */
	@Nonnull
	public CompletableFuture<Void> stop() {
		if (this.stopFuture == null && this.externalApiServer != null) {
			this.stopFuture = this.externalApiServer.closeAsynchronously()
				.thenAccept(unused -> ConsoleWriter.write("Server stopped, bye.\n"));
		}
		return this.stopFuture == null ?
			CompletableFuture.completedFuture(null) : this.stopFuture;
	}

	/**
	 * Method serializes the configuration to a YAML string.
	 *
	 * @return serialized configuration
	 */
	@Nonnull
	private String serializeConfiguration() {
		try {
			final ObjectMapper yamlMapper = new ObjectMapper(
				YAMLFactory.builder()
					.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
					.enable(Feature.INDENT_ARRAYS)
					.enable(Feature.INDENT_ARRAYS_WITH_INDICATOR)
					.build()
			);
			return yamlMapper.writeValueAsString(this.evitaServerConfiguration);
		} catch (JsonProcessingException e) {
			log.error("Failed to serialize configuration.", e);
			return "Failed to serialize configuration.";
		}
	}

	/**
	 * Method parses contents of `configDirLocation` YAML files in alphabetical order and applies contents of the latter
	 * files to the former ones. The method allows using `arguments` for variable replacement and returns the loaded
	 * configuration as a result.
	 */
	@Nonnull
	private EvitaServerConfigurationWithLogFilesListing parseConfiguration(
		@Nonnull Path configDirLocation,
		boolean strictConfigFileCheck,
		@Nonnull Map<String, String> arguments
	) throws ConfigurationParseException {
		final StringSubstitutor stringSubstitutor = createStringSubstitutor(arguments);

		final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
		// if strictConfigFileCheck is enabled, we should fail on unknown properties
		final UnknownPropertyProblemHandler unknownPropertyProblemHandler;
		if (!strictConfigFileCheck) {
			unknownPropertyProblemHandler = new UnknownPropertyProblemHandler();
			yamlMapper.addHandler(unknownPropertyProblemHandler);
		} else {
			unknownPropertyProblemHandler = null;
		}

		yamlMapper.registerModule(createAbstractApiConfigModule(unknownPropertyProblemHandler));
		yamlMapper.registerModule(new ParameterNamesModule());
		yamlMapper.addHandler(new SpecialConfigInputFormatsHandler());

		final Path[] configFiles;
		final EvitaServerConfiguration evitaServerConfig;
		try (
			final Stream<Path> filesInDirectory = configDirLocation.toFile().exists() ?
				Files.list(configDirLocation) : Stream.empty()
		) {
			configFiles = filesInDirectory
				.filter(Files::isRegularFile)
				.filter(it -> {
					final String fileName = it.getFileName().toString().toLowerCase();
					return fileName.endsWith(".yaml") || fileName.endsWith(".yml");
				})
				.map(Path::toAbsolutePath)
				.map(Path::normalize)
				.sorted()
				.toArray(Path[]::new);
			evitaServerConfig = mergeYamlFiles(
				yamlMapper,
				stringSubstitutor,
				stream -> new StringSubstitutorReader(
					new InputStreamReader(
						new BufferedInputStream(stream), StandardCharsets.UTF_8
					),
					stringSubstitutor
				),
				configDirLocation,
				// list all files in the directory and filter only the YAML files
				// then sort them alphabetically and convert them to array
				// finally pass the array to the `mergeYamlFiles` method
				// to merge them into a single configuration
				// (the last file has the highest priority and overrides the previous ones)
				configFiles
			);
		} catch (IOException e) {
			throw new ConfigurationParseException(
				"Failed to parse configuration files from directory `" + configDirLocation + "` due to: " + e.getMessage() + ".",
				"Failed to parse configuration files.", e
			);
		}
		return new EvitaServerConfigurationWithLogFilesListing(
			evitaServerConfig,
			configFiles
		);
	}

	/**
	 * Method creates instance of {@link AbstractClassDeserializer} that allows deserialization of upfront unknownd
	 * configuration files based on the presence of {@link ExternalApiProviderRegistrar} on the classpath.
	 */
	@Nonnull
	private SimpleModule createAbstractApiConfigModule(@Nullable UnknownPropertyProblemHandler unknownPropertyProblemHandler) {
		final SimpleModule module = new SimpleModule();
		final AbstractClassDeserializer<AbstractApiOptions> deserializer = new AbstractClassDeserializer<>(
			AbstractApiOptions.class, unknownPropertyProblemHandler
		);
		for (ExternalApiProviderRegistrar<?> apiProviderRegistrar : this.externalApiProviders) {
			deserializer.registerConcreteClass(
				apiProviderRegistrar.getExternalApiCode(),
				apiProviderRegistrar.getConfigurationClass()
			);
		}
		module.addDeserializer(AbstractApiOptions.class, deserializer);
		return module;
	}

	/**
	 * Record contains final configuration and list of configuration files that were applied.
	 *
	 * @param configuration      final configuration
	 * @param configFilesApplied list of configuration files that were applied
	 */
	private record EvitaServerConfigurationWithLogFilesListing(
		@Nonnull EvitaServerConfiguration configuration,
		@Nonnull Path[] configFilesApplied
	) {
	}

	/**
	 * The shutdown sequence first stops the {@link EvitaServer} and then it shuts down logger instance correctly.
	 */
	static class ShutdownSequence extends ContextAwareBase implements Runnable {
		private final EvitaServer evitaServer;

		public ShutdownSequence(@Nonnull EvitaServer evitaServer) {
			this.evitaServer = evitaServer;
			this.setContext((Context) LoggerFactory.getILoggerFactory());
		}

		@Override
		public void run() {
			final Evita evita = this.evitaServer.getEvita();
			if (evita == null) {
				ConsoleWriter.write("evitaDB instance is still being initialized (aborting initialization).\n\n");
			} else {
				try (evita) {
					evita.getServiceExecutor().prepareForBeingShutdown();
					this.evitaServer.stop()
						.thenAccept(unused -> stop())
						.get(30, TimeUnit.SECONDS);
				} catch (ExecutionException | InterruptedException | TimeoutException e) {
					ConsoleWriter.write("Failed to stop evita server in dedicated time (30 secs.).\n");
				} finally {
					ConsoleWriter.write("evitaDB instance closed, all files synced on disk.\n\n");
				}
			}
		}

		protected void stop() {
			this.addInfo("Logback context being closed via shutdown hook");
			final Context hookContext = this.getContext();
			if (hookContext instanceof ContextBase theContext) {
				theContext.stop();
			}

		}

	}

}
