/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.externalApi.certificate;

import com.linecorp.armeria.common.TlsKeyPair;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.CertificateOptions;
import io.evitadb.externalApi.configuration.CertificatePath;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Service responsible for loading and reloading certificates and providing a reference to a wrapper that holds currently loaded certificates.
 *
 * @author Tomáš Pozler, FG Forrest a.s. (c) 2024
 */
@Slf4j
public class CertificateService {
	private final AtomicReference<LoadedCertificates> loadedCertificates = new AtomicReference<>();
	private final CertificatesLoader certificatesLoader;
	private final CertificateOptions certificateSettings;

	private long lastModifiedTime = System.currentTimeMillis();

	/**
	 * Obtains an instance of a CertificateFactory for generating certificates of the X.509 type.
	 * If the factory instance cannot be obtained due to a CertificateException, a GenericEvitaInternalError
	 * is thrown encapsulating the original exception.
	 *
	 * @return a CertificateFactory instance configured for X.509 certificates
	 * @throws GenericEvitaInternalError if the CertificateFactory instance cannot be created
	 */
	@Nonnull
	private static CertificateFactory getCertificateFactory() {
		try {
			return CertificateFactory.getInstance("X.509");
		} catch (CertificateException e) {
			throw new GenericEvitaInternalError("Failed to get certificate factory instance.", e);
		}
	}

	/**
	 * Initializes the certificate loader with the provided options, path, and scheduler.
	 * Optionally starts a background task to watch for certificate changes.
	 *
	 * @param apiOptions                           The API options containing settings required for certificate loading.
	 * @param shouldStartCertificateChangedWatcher A boolean flag indicating if a watcher should be started to observe certificate changes.
	 * @param certificatePath                      The path to the certificate files needed for secure communications.
	 * @param serviceExecutor                      The scheduler service to use for executing periodic tasks, such as checking for modified certificates.
	 */
	public CertificateService(
		@Nonnull ApiOptions apiOptions,
		boolean shouldStartCertificateChangedWatcher,
		@Nonnull CertificatePath certificatePath,
		@Nonnull Scheduler serviceExecutor
	) {
		this.certificateSettings = apiOptions.certificate();
		final List<String> allowedClientCertificatePaths = apiOptions.endpoints()
			.values()
			.stream()
			.filter(endpoint -> endpoint.isEnabled() && endpoint.getMtlsConfiguration() != null && endpoint.isMtlsEnabled())
			.flatMap(mtlsEndpoint -> mtlsEndpoint.getMtlsConfiguration().allowedClientCertificatePaths().stream())
			.distinct()
			.collect(Collectors.toList());
		this.certificatesLoader = new CertificatesLoader(apiOptions, allowedClientCertificatePaths, getCertificateFactory(), certificatePath);
		this.loadedCertificates.set(this.certificatesLoader.reinitialize());
		if (shouldStartCertificateChangedWatcher) {
			serviceExecutor.scheduleAtFixedRate(this::reloadCertificatesIfAnyModified, 0, 1, TimeUnit.MINUTES);
		}
	}

	/**
	 * Reloads certificates if any of them was modified.
	 *
	 * @return true if any certificate was modified, false otherwise
	 */
	public boolean reloadCertificatesIfAnyModified() {
		final Path pathToWatch = getCertificateFolderPathPath();
		final boolean wasModified;
		try {
			long millisOfCertDirectoryLatestUpdate = getNewestModifiedTimeMillis(pathToWatch.toFile());
			if (isAnyCertificateFileChanged(pathToWatch)) {
				log.info("Reinitializing modified certificates.");
				this.loadedCertificates.set(this.certificatesLoader.reinitialize());
				this.lastModifiedTime = millisOfCertDirectoryLatestUpdate;
				wasModified = true;
			} else {
				wasModified = false;
			}
		} catch (IOException e) {
			throw new GenericEvitaInternalError("Failed to get last modified time of the certificate folder.", e);
		}
		return wasModified;
	}

	public static long getNewestModifiedTimeMillis(@Nullable File directory) {
		if (directory == null || !directory.isDirectory()) {
			throw new IllegalArgumentException("Invalid directory: " + directory);
		}

		long newestModified = directory.lastModified();

		final File[] files = directory.listFiles();
		if (files == null) {
			// If we cannot list the files, just return the directory's own lastModified time
			return newestModified;
		}

		for (File file : files) {
			if (file.isDirectory()) {
				newestModified = Math.max(newestModified, getNewestModifiedTimeMillis(file));
			} else {
				newestModified = Math.max(newestModified, file.lastModified());
			}
		}

		return newestModified;
	}

	/**
	 * Retrieves the currently loaded certificates.
	 *
	 * @return the current set of loaded certificates
	 * @throws IllegalArgumentException if the certificates have not been loaded
	 */
	@Nonnull
	public LoadedCertificates getLoadedCertificates() {
		final LoadedCertificates theCertificatesBundle = this.loadedCertificates.get();
		Assert.notNull(
			theCertificatesBundle,
			"Certificates have not been loaded yet. Please call initCertificateLoader() first."
		);
		return theCertificatesBundle;
	}

	/**
	 * Retrieves the TLS key pair from the currently loaded certificates.
	 *
	 * @return the TLS key pair if certificates are loaded; otherwise, null
	 * @throws IllegalArgumentException if the certificates have not been loaded
	 */
	@Nullable
	public TlsKeyPair getTlsKeyPair() {
		return getLoadedCertificates().tlsKeyPair();
	}

	/**
	 * Checks whether any of the certificate files within the specified directory have been modified since the last known modification time.
	 *
	 * @param pathToWatch the path to the directory containing the certificate files to monitor
	 * @return true if any certificate file has been changed since the last known modification time, false otherwise
	 * @throws IOException if an I/O error occurs accessing the file system
	 * @throws EvitaInvalidUsageException if the specified path is not a directory
	 */
	private boolean isAnyCertificateFileChanged(@Nonnull Path pathToWatch) throws IOException {
		final File directory = pathToWatch.toFile();
		if (pathToWatch.toFile().isDirectory()) {
			final File[] files = directory.listFiles();
			if (files == null) {
				throw new EvitaInvalidUsageException("The configured certificate path does not point to a directory.");
			} else {
				return Arrays.stream(files)
					.anyMatch(file -> file.lastModified() > this.lastModifiedTime);
			}
		} else {
			throw new EvitaInvalidUsageException("The configured certificate path does not point to a directory.");
		}
	}

	/**
	 * Retrieves the path to the directory where certificates are stored.
	 *
	 * @return the Path object representing the directory containing certificates
	 */
	@Nonnull
	private Path getCertificateFolderPathPath() {
		return Paths.get(this.certificateSettings.getFolderPath().toString());
	}
}
