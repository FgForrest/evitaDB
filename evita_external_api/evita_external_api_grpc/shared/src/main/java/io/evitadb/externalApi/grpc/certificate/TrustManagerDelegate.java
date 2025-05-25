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

package io.evitadb.externalApi.grpc.certificate;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * This class is used to delegate the trust manager to the main trust manager and the fallback trust manager. Its goal
 * is to bypass the certificate validation if the main trust manager fails. It should be used to add non-trusted root
 * CA certificates to the trust manager.
 */
public class TrustManagerDelegate implements X509TrustManager {
	private final X509TrustManager mainTrustManager;
	private final X509TrustManager fallbackTrustManager;
	public TrustManagerDelegate(X509TrustManager mainTrustManager, X509TrustManager fallbackTrustManager) {
		this.mainTrustManager = mainTrustManager;
		this.fallbackTrustManager = fallbackTrustManager;
	}
	@Override
	public void checkClientTrusted(final X509Certificate[] x509Certificates, final String authType) throws CertificateException {
		try {
			this.mainTrustManager.checkClientTrusted(x509Certificates, authType);
		} catch(CertificateException ignored) {
			this.fallbackTrustManager.checkClientTrusted(x509Certificates, authType);
		}
	}
	@Override
	public void checkServerTrusted(final X509Certificate[] x509Certificates, final String authType) throws CertificateException {
		try {
			this.mainTrustManager.checkServerTrusted(x509Certificates, authType);
		} catch(CertificateException ignored) {
			this.fallbackTrustManager.checkServerTrusted(x509Certificates, authType);
		}
	}
	@Override
	public X509Certificate[] getAcceptedIssuers() {
		return this.fallbackTrustManager.getAcceptedIssuers();
	}
}
