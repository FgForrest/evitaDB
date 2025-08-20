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

package io.evitadb.api.proxy.impl.entity;

import io.evitadb.api.proxy.WithVersion;
import io.evitadb.api.proxy.impl.SealedEntityProxyState;
import one.edee.oss.proxycian.DirectMethodClassification;
import one.edee.oss.proxycian.util.ReflectionUtils;

/**
 * Identifies methods that are used to get entity version from an entity and provides their implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetVersionMethodClassifier extends DirectMethodClassification<Object, SealedEntityProxyState> {
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final GetVersionMethodClassifier INSTANCE = new GetVersionMethodClassifier();

	public GetVersionMethodClassifier() {
		super(
			"getVersion",
			(method, proxyState) -> {
				// We are interested only in abstract methods without arguments
				if (method.getParameterCount() > 0) {
					return null;
				}

				if (ReflectionUtils.isMatchingMethodPresentOn(method, WithVersion.class)) {
					return (entityClassifier, theMethod, args, theState, invokeSuper) -> theState.entity().version();
				}

				// this method is not classified by this implementation
				return null;
			}
		);
	}

}
