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

package io.evitadb.server;

import ch.qos.logback.core.Context;
import ch.qos.logback.core.ContextBase;
import ch.qos.logback.core.spi.ContextAwareBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.AbstractApiConfiguration;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.http.ExternalApiServer;
import io.evitadb.server.configuration.EvitaServerConfiguration;
import io.evitadb.server.exception.ConfigurationParseException;
import io.evitadb.server.yaml.AbstractClassDeserializer;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ConsoleWriter;
import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import io.evitadb.utils.ConsoleWriter.ConsoleDecoration;
import lombok.Getter;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.io.StringSubstitutorReader;
import org.apache.commons.text.lookup.StringLookupFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Map;
import java.util.Map.Entry;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * We use two different web servers - Netty for gRPC and Undertow for graphQL and REST APIs. We plan to unify all APIs
 * under Undertow 3.x version that's going to be based on Netty instead of XNIO. Currently, we at least to try reusing
 * single executor service so that there are not so many threads competing for the limited amount of CPUs.
 *
 * @author Jan Novotn?? (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EvitaServer {
	/**
	 * Logger.
	 */
	private static Logger log;
	/**
	 * Name of the argument for specification of evita configuration file path.
	 */
	public static final String OPTION_EVITA_CONFIGURATION_FILE = "configFile";
	/**
	 * Default name of the evita configuration file relative to the "working directory".
	 */
	public static final String DEFAULT_EVITA_CONFIGURATION_FILE = "evita-configuration.yaml";
	/**
	 * Pattern for matching Java arguments `-Dname=value`
	 */
	private static final Pattern OPTION_JAVA_ARGUMENT = Pattern.compile("-D(\\S+)=(\\S+)");
	/**
	 * Pattern for matching Unix like arguments `--name=value`
	 */
	private static final Pattern OPTION_GNU_ARGUMENT = Pattern.compile("--(\\S+)=(\\S+)");
	private static final String DEFAULT_MANIFEST_LOCATION = "META-INF/MANIFEST.MF";
	private static final String IMPLEMENTATION_VENDOR_TITLE = "Implementation-Title";
	private static final String IO_EVITADB_TITLE = "evitaDB - Standalone server";
	private static final String IMPLEMENTATION_VERSION = "Implementation-Version";
	/**
	 * Instance of the {@link EvitaConfiguration} initialized from the evita configuration file.
	 */
	private final EvitaConfiguration evitaConfiguration;
	/**
	 * Instance of the {@link ApiOptions} initialized from the evita configuration file.
	 */
	private final ApiOptions apiOptions;
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
	 * Rock'n'Roll.
	 */
	public static void main(String[] args) {
		final Map<String, String> options = parseOptions(args);
		// somehow the -D arguments are not propagated to system properties, so we need to do it manually
		for (Entry<String, String> argEntry : options.entrySet()) {
			System.setProperty(argEntry.getKey(), argEntry.getValue());
		}
		// and then initialize logger so that `logback.configurationFile` argument might apply
		log = LoggerFactory.getLogger(EvitaServer.class);

		ConsoleWriter.write(
			"""
				\n\n            _ _        ____  ____ \s
				  _____   _(_) |_ __ _|  _ \\| __ )\s
				 / _ \\ \\ / / | __/ _` | | | |  _ \\\s
				|  __/\\ V /| | || (_| | |_| | |_) |
				 \\___| \\_/ |_|\\__\\__,_|____/|____/\s\n\n""",
			ConsoleColor.DARK_GREEN
		);
		ConsoleWriter.write("alpha build %s (keep calm and report bugs ????)\n", new Object[] {readVersion()}, ConsoleColor.LIGHT_GRAY);
		ConsoleWriter.write("Visit us at: ");
		ConsoleWriter.write("https://evitadb.io\n\n", ConsoleColor.DARK_BLUE, ConsoleDecoration.UNDERLINE);

		final Path configFilePath = ofNullable(options.get(OPTION_EVITA_CONFIGURATION_FILE))
			.map(it -> Paths.get("").resolve(it))
			.orElseGet(() -> Paths.get("").resolve(DEFAULT_EVITA_CONFIGURATION_FILE));

		final EvitaServer evitaServer = new EvitaServer(configFilePath, options);
		final ShutdownSequence shutdownSequence = new ShutdownSequence(evitaServer);
		Runtime.getRuntime().addShutdownHook(new Thread(shutdownSequence));
		evitaServer.run();
	}

	/**
	 * Method parses the options from the command line and creates a key-value map from them.
	 */
	private static Map<String, String> parseOptions(String[] args) {
		final Map<String, String> map = CollectionUtils.createHashMap(args.length);
		for (String arg : args) {
			final Matcher javaArgMatcher = OPTION_JAVA_ARGUMENT.matcher(arg);
			final Matcher gnuArgMatcher = OPTION_GNU_ARGUMENT.matcher(arg);
			if (javaArgMatcher.matches()) {
				map.put(javaArgMatcher.group(1), javaArgMatcher.group(2));
			} else if (gnuArgMatcher.matches()) {
				map.put(gnuArgMatcher.group(1), gnuArgMatcher.group(2));
			}
		}
		return map;
	}

	/**
	 * Method reads the current evitaDB version from the Manifest file where the version is injected during Maven build.
	 */
	private static String readVersion() {
		try {
			final Enumeration<URL> resources = EvitaServer.class.getClassLoader().getResources(DEFAULT_MANIFEST_LOCATION);
			while (resources.hasMoreElements()) {
				try (final InputStream manifestStream = resources.nextElement().openStream()) {
					final Manifest manifest = new Manifest(manifestStream);
					final Attributes mainAttributes = manifest.getMainAttributes();
					if (IO_EVITADB_TITLE.equals(mainAttributes.getValue(IMPLEMENTATION_VENDOR_TITLE))) {
						return ofNullable(mainAttributes.getValue(IMPLEMENTATION_VERSION)).orElse("?");
					}
				}
			}
		} catch (Exception ignored) {
			// just return unknown value
		}
		return "?";
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
							.orElseGet(() -> System.getenv(variable))
					)
			)
		);
		stringSubstitutor.setEnableSubstitutionInVariables(false);
		stringSubstitutor.setEnableUndefinedVariableException(false);
		stringSubstitutor.setValueDelimiter(':');
		return stringSubstitutor;
	}

	/**
	 * Constructor that initializes the EvitaServer.
	 */
	public EvitaServer(@Nonnull Path configFileLocation, @Nonnull Map<String, String> arguments) {
		this.externalApiProviders = ExternalApiServer.gatherExternalApiProviders();
		final EvitaServerConfiguration evitaServerConfig = parseConfiguration(configFileLocation, arguments);
		this.evitaConfiguration = new EvitaConfiguration(
			evitaServerConfig.server(),
			evitaServerConfig.storage(),
			evitaServerConfig.cache()
		);
		this.apiOptions = evitaServerConfig.api();
	}

	public void run() {
		this.evita = new Evita(evitaConfiguration);
		this.externalApiServer = new ExternalApiServer(
			this.evita, this.apiOptions, this.externalApiProviders
		);
		externalApiServer.start();
	}

	public void stop() {
		if (externalApiServer != null) {
			externalApiServer.close();
		}
		ConsoleWriter.write("Server stopped, bye.");
	}

	/**
	 * Method parses contents of `configFileLocation` YAML file using `arguments` for variable replacement and returns
	 * the loaded configuration as a result.
	 */
	@Nonnull
	private EvitaServerConfiguration parseConfiguration(@Nonnull Path configFileLocation, @Nonnull Map<String, String> arguments) throws ConfigurationParseException {
		final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
		yaml.registerModule(createAbstractApiConfigModule());
		yaml.registerModule(new ParameterNamesModule());
		yaml.addHandler(new SpecialConfigInputFormatsHandler());
		final StringSubstitutor stringSubstitutor = createStringSubstitutor(arguments);
		final EvitaServerConfiguration evitaServerConfig;
		try (
			final Reader reader = new StringSubstitutorReader(
				new InputStreamReader(
					new BufferedInputStream(
						new FileInputStream(
							configFileLocation.toFile()
						)
					), StandardCharsets.UTF_8
				),
				stringSubstitutor
			)
		) {
			evitaServerConfig = yaml.readValue(reader, EvitaServerConfiguration.class);
		} catch (IOException e) {
			throw new ConfigurationParseException(
				"Failed to parse configuration file `" + configFileLocation + "` due to: " + e.getMessage() + ".",
				"Failed to parse configuration file.", e
			);
		}
		return evitaServerConfig;
	}

	/**
	 * Method creates instance of {@link AbstractClassDeserializer} that allows deserialization of upfront unknownd
	 * configuration files based on the presence of {@link ExternalApiProviderRegistrar} on the classpath.
	 */
	@Nonnull
	private SimpleModule createAbstractApiConfigModule() {
		final SimpleModule module = new SimpleModule();
		final AbstractClassDeserializer<AbstractApiConfiguration> deserializer = new AbstractClassDeserializer<>(AbstractApiConfiguration.class);
		for (ExternalApiProviderRegistrar<?> apiProviderRegistrar : this.externalApiProviders) {
			deserializer.registerConcreteClass(
				apiProviderRegistrar.getExternalApiCode(),
				apiProviderRegistrar.getConfigurationClass()
			);
		}
		module.addDeserializer(AbstractApiConfiguration.class, deserializer);
		return module;
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
			evitaServer.stop();
			stop();
		}

		protected void stop() {
			this.addInfo("Logback context being closed via shutdown hook");
			Context hookContext = this.getContext();
			if (hookContext instanceof ContextBase) {
				ContextBase context = (ContextBase)hookContext;
				context.stop();
			}

		}

	}

}
