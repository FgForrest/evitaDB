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

package io.evitadb.externalApi.system;

import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.EvitaSystemDataProvider;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.CertificatePath;
import io.evitadb.externalApi.configuration.CertificateSettings;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.system.configuration.SystemConfig;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.server.handlers.resource.FileResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.server.handlers.resource.ResourceManager;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;

/**
 * Registers System API provider to provide system API to clients.
 *
 * @author Tomáš Pozler, 2023
 */
public class SystemProviderRegistrar implements ExternalApiProviderRegistrar<SystemConfig> {
	@Nonnull
	@Override
	public String getExternalApiCode() {
		return SystemProvider.CODE;
	}

	@Nonnull
	@Override
	public Class<SystemConfig> getConfigurationClass() {
		return SystemConfig.class;
	}

	@Nonnull
	@Override
	public ExternalApiProvider register(@Nonnull EvitaSystemDataProvider evitaSystemDataProvider, @Nonnull ApiOptions apiOptions, @Nonnull SystemConfig externalApiConfiguration) {
		final File file;
		final CertificateSettings certificateSettings = apiOptions.certificate();
		if (certificateSettings.generateAndUseSelfSigned()) {
			file = new File(apiOptions.certificate().folderPath());
		} else {
			final CertificatePath certificatePath = certificateSettings.custom();
			if (certificatePath == null || certificatePath.certificate() == null || certificatePath.privateKey() == null) {
				throw new EvitaInternalError("Certificate path is not properly set in the configuration file.");
			}
			file = new File(certificatePath.certificate().substring(0, certificatePath.certificate().lastIndexOf(File.separator)));
		}
		final ResourceHandler fileSystemHandler;
		try (ResourceManager resourceManager = new FileResourceManager(file, 100)) {
			fileSystemHandler = new ResourceHandler(resourceManager, new BlockingHandler(exchange -> {
				exchange.setStatusCode(404);
				exchange.endExchange();
			}));
			return new SystemProvider(fileSystemHandler);
		} catch (IOException e) {
			throw new EvitaInternalError(e.getMessage(), e);
		}
	}
}
