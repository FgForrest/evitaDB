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

package io.evitadb.externalApi.lab.gui.resolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.ServerCacheControl;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.file.HttpFile;
import io.evitadb.externalApi.lab.configuration.LabOptions;
import io.evitadb.externalApi.lab.gui.dto.EvitaDBConnection;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Serves static files of lab GUI from fs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class GuiHandler implements HttpService {

	private static final Encoder BASE_64_ENCODER = Base64.getEncoder();

	private static final String EVITALAB_DATA_SET_PARAM_NAME = "evitalab";
	private static final String EVITALAB_SERVER_NAME_PARAM_NAME = "evitalab-server-name";
	private static final String EVITALAB_READONLY_PARAM_NAME = "evitalab-readonly";
	private static final String EVITALAB_PRECONFIGURED_CONNECTIONS_PARAM_NAME = "evitalab-pconnections";

	private static final Pattern ASSETS_PATTERN = Pattern.compile("/assets/([a-zA-Z0-9\\-_]+/)*[a-zA-Z0-9\\-_]+\\.[a-z0-9]+");
	private static final Pattern ROOT_ASSETS_PATTERN = Pattern.compile("(/logo)?/[a-zA-Z0-9\\-]+\\.[a-z0-9]+");

	@Nonnull private final LabOptions labConfig;
	@Nonnull private final String serverName;
	@Nonnull private final ObjectMapper objectMapper;

	private List<EvitaDBConnection> defaultConnections;

	@Nonnull
	public static GuiHandler create(
		@Nonnull LabOptions labConfig,
		@Nonnull String serverName,
		@Nonnull ObjectMapper objectMapper
	) {
		return new GuiHandler(labConfig, serverName, objectMapper);
	}

	/**
	 * Creates a resource location with forward slashes. It is necessary to handle it this way for the sake of Windows
	 * compatibility - it needs forwards slashes within jar file resources.
	 *
	 * @param path path of a jar file resource to be converted
	 * @return path of a jar file resource with forward slashes
	 */
	@Nonnull
	private static String createJarResourceLocationWithForwardSlashes(@Nonnull Path path) {
		return path.toString().replace("\\", "/");
	}

	/**
	 * Determines whether the lab data set parameter is present and its value in the query parameters of the current
	 * service request context.
	 *
	 * @param ctx the service request context containing query parameters
	 * @return true if the lab data set parameter is present and evaluates to true, otherwise false
	 */
	@Nonnull
	private static Boolean isLabDataSet(@Nonnull ServiceRequestContext ctx) {
		return Boolean.parseBoolean(ctx.queryParam(EVITALAB_DATA_SET_PARAM_NAME));
	}

	/**
	 * Passes a param to the evitaLab as system property. Its value is encoded with Base64
	 */
	private static void passEncodedParam(@Nonnull QueryParamsBuilder params, @Nonnull String name, @Nonnull String value) {
		params.add(name, BASE_64_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8)));
	}

	/**
	 * Serves static assets for the GUI from the specified path or JAR resources.
	 * The method locates the requested resource using the provided class loader and path,
	 * ensuring compatibility with forward-slash resource referencing. It also applies caching
	 * for immutable assets.
	 *
	 * @param ctx         the current service request context
	 * @param req         the HTTP request to handle
	 * @param classLoader the class loader used to locate the asset
	 * @param path        the relative path to the requested asset
	 * @param fsPath      the base file system path of the assets
	 * @return an {@link HttpResponse} containing the served asset or an error response
	 * @throws Exception if an error occurs while serving the asset
	 */
	@Nonnull
	private static HttpResponse serveAssets(
		@Nonnull ServiceRequestContext ctx,
		@Nonnull HttpRequest req,
		@Nonnull ClassLoader classLoader,
		@Nonnull String path,
		@Nonnull Path fsPath
	) throws Exception {
		return HttpFile.builder(
				classLoader,
				createJarResourceLocationWithForwardSlashes(
					fsPath.resolve(Paths.get(path.substring(1)))
				)
			)
			// all assets are versioned so we can cache them for long and never change
			.cacheControl(ServerCacheControl.IMMUTABLE)
			.build()
			.asService()
			.serve(ctx, req);
	}

	/**
	 * Determines if the original client request was using HTTPS.
	 *
	 * @param ctx ServiceRequestContext of the current request
	 * @return true if the original request was HTTPS, based on the actual connection or X-Forwarded-Proto
	 */
	private static boolean isClientHttps(@Nonnull ServiceRequestContext ctx) {
		// Check if the connection itself is over TLS
		if (ctx.sessionProtocol().isTls()) {
			return true; // Direct connection is secure
		}

		// Check the "X-Forwarded-Proto" header (set by NGINX or proxies) for client protocol
		final String forwardedProto = ctx.request().headers().get(HttpHeaderNames.X_FORWARDED_PROTO);
		return forwardedProto != null && forwardedProto.equalsIgnoreCase("https");
	}

	private GuiHandler(@Nonnull LabOptions labConfig,
	                   @Nonnull String serverName,
	                   @Nonnull ObjectMapper objectMapper) {
		this.labConfig = labConfig;
		this.serverName = serverName;
		this.objectMapper = objectMapper;
	}

	@Nonnull
	@Override
	public HttpResponse serve(@Nonnull ServiceRequestContext ctx, @Nonnull HttpRequest req) throws Exception {
		final String path;
		if (ctx.query() == null) {
			path = req.path();
		} else {
			path = req.path().replace("?" + ctx.query(), "");
		}
		final ClassLoader classLoader = getClass().getClassLoader();
		final Path fsPath = Paths.get("META-INF/lab/gui/dist");
		if (path.isEmpty() || path.equals("/") || path.equals("/index.html")) {
			return serveRoot(ctx, req, classLoader, fsPath);
		} else if (ROOT_ASSETS_PATTERN.matcher(path).matches() || ASSETS_PATTERN.matcher(path).matches()) {
			return serveAssets(ctx, req, classLoader, path, fsPath);
		}
		return HttpResponse.of(404);
	}

	@Nonnull
	private HttpResponse serveRoot(
		@Nonnull ServiceRequestContext ctx,
		@Nonnull HttpRequest req,
		@Nonnull ClassLoader classLoader,
		@Nonnull Path fsPath
	) throws Exception {
		final boolean labDataSetParamValue = isLabDataSet(ctx);
		if (!labDataSetParamValue) {
			final QueryParamsBuilder paramsWithLabData = ctx.queryParams().toBuilder();
			passLabDataSet(paramsWithLabData);
			passServerName(paramsWithLabData);
			passReadOnlyFlag(paramsWithLabData);

			final String authority = req.headers().authority();
			passPreconfiguredEvitaDBConnections(
				paramsWithLabData,
				authority == null ? null : (isClientHttps(ctx) ? "https://" : "http://") + authority
			);

			final String newQueryString = paramsWithLabData.toQueryString();

			// pass data to lab by redirecting the browser to new URL with the data
			return HttpResponse.builder()
				.status(HttpStatus.SEE_OTHER)
				.header(HttpHeaderNames.LOCATION, ctx.path() + "?" + newQueryString)
				// we don't want to cache evitaLab index file because in development environments, developers often
				// switch evitaDB server under same hostname and each evitaDB server may have different version or config
				// therefore we need to make sure that the initialization of evitaLab is always fresh
				.header(HttpHeaderNames.CACHE_CONTROL, ServerCacheControl.DISABLED.asHeaderValue())
				.build();
		}

		return HttpFile.builder(
				classLoader,
				createJarResourceLocationWithForwardSlashes(fsPath.resolve("index.html"))
			)
			// we don't want to cache evitaLab index file because in development environments, developers often
			// switch evitaDB server under same hostname and each evitaDB server may have different version or config
			// therefore we need to make sure that the initialization of evitaLab is always fresh
			.lastModified(false)
			.cacheControl(ServerCacheControl.DISABLED)
			.build()
			.asService()
			.serve(ctx, req);
	}

	/**
	 * Creates a {@link #EVITALAB_DATA_SET_PARAM_NAME} param to the evitaLab as system property to indicate that lab data
	 * were set and should not be set again.
	 */
	private void passLabDataSet(@Nonnull QueryParamsBuilder params) {
		params.add(EVITALAB_DATA_SET_PARAM_NAME, Boolean.TRUE.toString());
	}

	/**
	 * Passes a {@link #EVITALAB_SERVER_NAME_PARAM_NAME} param to the evitaLab as system property to specify source server.
	 */
	private void passServerName(@Nonnull QueryParamsBuilder params) {
		passEncodedParam(params, EVITALAB_SERVER_NAME_PARAM_NAME, this.serverName);
	}

	/**
	 * Passes a {@link #EVITALAB_READONLY_PARAM_NAME} param to the evitaLab as system property to specify
	 * in which mode should evitaLab run.
	 * If true, the evitaLab GUI will be in read-only mode.
	 */
	private void passReadOnlyFlag(@Nonnull QueryParamsBuilder params) {
		passEncodedParam(params, EVITALAB_READONLY_PARAM_NAME, String.valueOf(this.labConfig.getGui().isReadOnly()));
	}

	/**
	 * Passes a {@link #EVITALAB_PRECONFIGURED_CONNECTIONS_PARAM_NAME} param to the evitaLab as system property with preconfigured
	 * evitaDB connections.
	 */
	private void passPreconfiguredEvitaDBConnections(@Nonnull QueryParamsBuilder params, @Nullable String incomingRequestHostAndPort) throws IOException {
		final List<EvitaDBConnection> connections = resolvePreconfiguredEvitaDBConnections(incomingRequestHostAndPort);
		final String serializedSelfConnection = this.objectMapper.writeValueAsString(connections);

		passEncodedParam(params, EVITALAB_PRECONFIGURED_CONNECTIONS_PARAM_NAME, serializedSelfConnection);
	}

	@Nonnull
	private List<EvitaDBConnection> resolvePreconfiguredEvitaDBConnections(@Nullable String incomingRequestHostAndPort) {
		if (
			// connections not cached
			this.defaultConnections == null ||
			// or cached with different URL than incoming
			(incomingRequestHostAndPort != null && !Objects.equals(incomingRequestHostAndPort, this.defaultConnections.get(0).serverUrl()))
		) {
			// generate self-connection
			final String serverUrl;
			if (this.labConfig.getGui().isPreferIncomingHostAndPort() && incomingRequestHostAndPort != null) {
				serverUrl = incomingRequestHostAndPort;
			} else {
				serverUrl = this.labConfig.getResolvedExposeOnUrl();
			}
			// and cache
			this.defaultConnections = List.of(
				new EvitaDBConnection(
					null,
					this.serverName,
					serverUrl
				)
			);
		}
		return this.defaultConnections;
	}
}
