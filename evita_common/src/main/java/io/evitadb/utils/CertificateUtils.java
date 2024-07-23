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

package io.evitadb.utils;

import javax.annotation.Nonnull;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.Locale;

/**
 * This class contains utility constants and methods used by both the client and the server.
 */
public class CertificateUtils {
	/**
	 * Default name of the root certificate authority's certificate file.
	 */
	private static final String CA_CERT_NAME = "evitaDB-CA-selfSigned";
	/**
	 * Default name of the server certificate file.
	 */
	private static final String SERVER_CERT_NAME = "server";
	/**
	 * Default dame of the client certificate.
	 */
	private static final String CLIENT_CERT_NAME = "client";
	/**
	 * Default extension of the certificate file.
	 */
	private static final String CERTIFICATE_EXTENSION = ".crt";
	/**
	 * Default extension of the private key file.
	 */
	private static final String CERTIFICATE_KEY_EXTENSION = ".key";

	/**
	 * Returns the name of the root CA certificate file.
	 */
	public static String getGeneratedRootCaCertificateName() {
		return CA_CERT_NAME;
	}

	/**
	 * Returns the name and the extension of the root CA certificate file.
	 */
	public static String getGeneratedRootCaCertificateFileName() {
		return CA_CERT_NAME + CERTIFICATE_EXTENSION;
	}

	/**
	 * Returns the name and the extension of the root CA certificate private key file.
	 */
	public static String getGeneratedRootCaCertificateKeyFileName() {
		return CA_CERT_NAME + CERTIFICATE_KEY_EXTENSION;
	}

	/**
	 * Returns the name and the extension of the server certificate file.
	 */
	public static String getGeneratedServerCertificateFileName() {
		return SERVER_CERT_NAME + CERTIFICATE_EXTENSION;
	}

	/**
	 * Returns the name and the extension of the client certificate file.
	 */
	public static String getGeneratedClientCertificateFileName() {
		return CLIENT_CERT_NAME + CERTIFICATE_EXTENSION;
	}

	/**
	 * Returns the name and the extension of the server certificate private key file.
	 */
	public static String getGeneratedServerCertificatePrivateKeyFileName() {
		return SERVER_CERT_NAME + CERTIFICATE_KEY_EXTENSION;
	}

	/**
	 * Returns the name and the extension of the client certificate private key file.
	 */
	public static String getGeneratedClientCertificatePrivateKeyFileName() {
		return CLIENT_CERT_NAME + CERTIFICATE_KEY_EXTENSION;
	}

	public static String getServerCertName() {
		return SERVER_CERT_NAME;
	}

	public static String getClientCertName() {
		return CLIENT_CERT_NAME;
	}

	public static String getCertificateExtension() {
		return CERTIFICATE_EXTENSION;
	}

	public static String getCertificateKeyExtension() {
		return CERTIFICATE_KEY_EXTENSION;
	}

	/**
	 * This method accepts an object that represents a certificate and returns its fingerprint.
	 *
	 * @param certificate the certificate of which fingerprint should be calculated
	 * @return a fingerprint of the provided certificate
	 */
	@Nonnull
	public static String getCertificateFingerprint(@Nonnull Certificate certificate) throws NoSuchAlgorithmException, CertificateEncodingException {
		final StringBuilder sb = new StringBuilder(128);
		final MessageDigest md = MessageDigest.getInstance("SHA-256");
		final byte[] messageDigest = md.digest(certificate.getEncoded());
		for (byte b : messageDigest) {
			sb.append(String.format("%02x", b & 0xff)).append(':');
		}
		return sb.toString().toUpperCase(Locale.getDefault()).substring(0, sb.length() - 1);
	}

}
