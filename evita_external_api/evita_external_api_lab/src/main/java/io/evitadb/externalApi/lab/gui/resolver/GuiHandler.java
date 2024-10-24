/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.file.HttpFile;
import io.evitadb.externalApi.lab.configuration.GuiConfig;
import io.evitadb.externalApi.lab.configuration.LabConfig;
import io.evitadb.externalApi.lab.gui.dto.EvitaDBConnection;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Serves static files of lab GUI from fs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class GuiHandler implements HttpService {

	private static final Encoder BASE_64_ENCODER = Base64.getEncoder();

	private static final String EVITALAB_SERVER_NAME_COOKIE = "evitalab_servername";
	private static final String EVITALAB_READONLY_COOKIE = "evitalab_readonly";
	private static final String EVITALAB_PRECONFIGURED_CONNECTIONS_COOKIE = "evitalab_pconnections";

	private static final Pattern ASSETS_PATTERN = Pattern.compile("/assets/([a-zA-Z0-9\\-]+/)*[a-zA-Z0-9\\-]+\\.[a-z0-9]+");
	private static final Pattern ROOT_ASSETS_PATTERN = Pattern.compile("(/logo)?/[a-zA-Z0-9\\-]+\\.[a-z0-9]+");

	@Nonnull private final LabConfig labConfig;
	@Nonnull private final String serverName;
	@Nonnull private final ObjectMapper objectMapper;

	private List<EvitaDBConnection> preconfiguredConnections;

	@Nonnull
	public static GuiHandler create(
		@Nonnull LabConfig labConfig,
		@Nonnull String serverName,
		@Nonnull ObjectMapper objectMapper
	) {
		return new GuiHandler(labConfig, serverName, objectMapper);
	}

	private GuiHandler(@Nonnull LabConfig labConfig,
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
	private HttpResponse serveRoot(@Nonnull ServiceRequestContext ctx,
	                               @Nonnull HttpRequest req,
	                               @Nonnull ClassLoader classLoader,
	                               @Nonnull Path fsPath) throws Exception {
		passServerName(ctx);
		passReadOnlyFlag(ctx);
		passPreconfiguredEvitaDBConnections(ctx);

		return HttpFile.of(
			classLoader,
			createJarResourceLocationWithForwardSlashes(fsPath.resolve("index.html"))
		)
			.asService()
			.serve(ctx, req);
	}

	@Nonnull
	private HttpResponse serveAssets(@Nonnull ServiceRequestContext ctx,
	                                 @Nonnull HttpRequest req,
	                                 @Nonnull ClassLoader classLoader,
									 @Nonnull String path,
	                                 @Nonnull Path fsPath) throws Exception {
		return HttpFile.of(
			classLoader,
			createJarResourceLocationWithForwardSlashes(
				fsPath.resolve(Paths.get(path.substring(1)))
			)
		)
			.asService()
			.serve(ctx, req);
	}


	/**
	 * Creates a resource location with forward slashes. It is necessary to handle it this way for the sake of Windows
	 * compatibility - it needs forwards slashes within jar file resources.
	 * @param path path of a jar file resource to be converted
	 * @return path of a jar file resource with forward slashes
	 */
	@Nonnull
	private static String createJarResourceLocationWithForwardSlashes(@Nonnull Path path) {
		return path.toString().replace("\\", "/");
	}

	private void passServerName(@Nonnull ServiceRequestContext ctx) {
		ctx.addAdditionalResponseHeader(
			HttpHeaderNames.SET_COOKIE,
			createCookie(EVITALAB_SERVER_NAME_COOKIE, serverName)
		);
	}

	/**
	 * Sends a {@link #EVITALAB_READONLY_COOKIE} cookie to the evitaLab with {@link GuiConfig#isReadOnly()} flag.
	 * If true, the evitaLab GUI will be in read-only mode.
	 */
	private void passReadOnlyFlag(@Nonnull ServiceRequestContext ctx) {
		ctx.addAdditionalResponseHeader(
			HttpHeaderNames.SET_COOKIE,
			createCookie(EVITALAB_READONLY_COOKIE, String.valueOf(labConfig.getGui().isReadOnly()))
		);
	}

	/**
	 * Sends a {@link #EVITALAB_PRECONFIGURED_CONNECTIONS_COOKIE} cookie to the evitaLab with preconfigured
	 * evitaDB connections.
	 */
	private void passPreconfiguredEvitaDBConnections(@Nonnull ServiceRequestContext ctx) throws IOException {
		final List<EvitaDBConnection> preconfiguredConnections = resolvePreconfiguredEvitaDBConnections();
		final String serializedSelfConnection = objectMapper.writeValueAsString(preconfiguredConnections);

		ctx.addAdditionalResponseHeader(
			HttpHeaderNames.SET_COOKIE,
			createCookie(EVITALAB_PRECONFIGURED_CONNECTIONS_COOKIE, serializedSelfConnection)
		);
	}

	@Nonnull
	private List<EvitaDBConnection> resolvePreconfiguredEvitaDBConnections() {
		if (preconfiguredConnections == null) {
			final List<EvitaDBConnection> customPreconfiguredConnections = labConfig.getGui().getPreconfiguredConnections();
			if (customPreconfiguredConnections != null) {
				preconfiguredConnections = customPreconfiguredConnections;
			} else {
				final EvitaDBConnection selfConnection = new EvitaDBConnection(
					null,
					serverName,
					labConfig.getResolvedExposeOnUrl()
				);
				preconfiguredConnections = List.of(selfConnection);
			}
		}
		return preconfiguredConnections;
	}

	@Nonnull
	private static String createCookie(@Nonnull String name, @Nonnull String value) {
		return name + "=" + BASE_64_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8)) + ";Max-Age=30;SameSite=Strict";
	}
}
