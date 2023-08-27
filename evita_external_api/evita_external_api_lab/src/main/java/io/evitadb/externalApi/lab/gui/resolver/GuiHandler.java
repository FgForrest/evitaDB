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

package io.evitadb.externalApi.lab.gui.resolver;

import io.evitadb.externalApi.exception.ExternalApiInternalError;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.server.handlers.resource.Resource;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.server.handlers.resource.ResourceManager;
import io.undertow.server.handlers.resource.ResourceSupplier;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;

/**
 * Serves static files of lab GUI from fs.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class GuiHandler extends ResourceHandler {

	private GuiHandler(ResourceSupplier resourceSupplier) {
		super(resourceSupplier);
	}

	@Nonnull
	public static GuiHandler create() {
		try (final ResourceManager rm = new ClassPathResourceManager(GuiHandler.class.getClassLoader(), "META-INF/lab/gui/dist")) {
			return new GuiHandler(new GuiResourceSupplier(rm));
		} catch (IOException e) {
			throw new ExternalApiInternalError("Failed to load GUI resources.", e);
		}
	}

	@RequiredArgsConstructor
	private static class GuiResourceSupplier implements ResourceSupplier {

		@Nonnull private final ResourceManager resourceManager;

		@Override
		public Resource getResource(HttpServerExchange exchange, String path) throws IOException {
			if (path == null) {
				return null;
			} else if (path.isEmpty() || path.equals("/index.html")) {
				return resourceManager.getResource("index.html");
			} else if (path.equals("/favicon.ico")) {
				return resourceManager.getResource("favicon.ico");
			} else if (path.startsWith("/assets")) {
				return resourceManager.getResource(path);
			} else {
				return null;
			}
		}
	}
}
