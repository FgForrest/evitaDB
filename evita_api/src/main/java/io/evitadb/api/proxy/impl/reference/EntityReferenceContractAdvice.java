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

package io.evitadb.api.proxy.impl.reference;

import io.evitadb.api.proxy.SealedEntityReferenceProxy;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import one.edee.oss.proxycian.MethodClassification;
import one.edee.oss.proxycian.recipe.Advice;

import java.io.Serial;
import java.util.Arrays;
import java.util.List;

/**
 * Advice allowing to implement all supported abstract methods on proxy wrapping {@link ReferenceContract}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class EntityReferenceContractAdvice implements Advice<SealedEntityReferenceProxy> {
	@Serial private static final long serialVersionUID = -5338309229187809879L;
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final EntityReferenceContractAdvice INSTANCE = new EntityReferenceContractAdvice();
	/**
	 * List of all method classifications supported by this advice.
	 */
	@SuppressWarnings("unchecked")
	private static final List<MethodClassification<?, SealedEntityReferenceProxy>> METHOD_CLASSIFICATION = Arrays.asList(
		new MethodClassification[]{
			GetReferencedEntityPrimaryKeyMethodClassifier.INSTANCE,
			GetReferencedGroupEntityPrimaryKeyMethodClassifier.INSTANCE,
			GetReferencedEntityMethodClassifier.INSTANCE,
			GetReferenceAttributeMethodClassifier.INSTANCE
		}
	);

	@Override
	public Class<SealedEntityReferenceProxy> getRequestedStateContract() {
		return SealedEntityReferenceProxy.class;
	}

	@Override
	public List<MethodClassification<?, SealedEntityReferenceProxy>> getMethodClassification() {
		return METHOD_CLASSIFICATION;
	}

}
