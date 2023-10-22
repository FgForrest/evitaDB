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

package io.evitadb.api.proxy.impl.entityBuilder;

import io.evitadb.api.proxy.SealedEntityProxy;
import io.evitadb.api.requestResponse.data.InstanceEditor;
import one.edee.oss.proxycian.MethodClassification;
import one.edee.oss.proxycian.recipe.Advice;

import java.io.Serial;
import java.util.Arrays;
import java.util.List;

/**
 * Advice allowing to implement all supported abstract methods on proxy wrapping {@link InstanceEditor}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class EntityBuilderAdvice implements Advice<SealedEntityProxy> {
	@Serial private static final long serialVersionUID = -4131476723105030817L;
	/**
	 * We may reuse singleton instance since advice is stateless.
	 */
	public static final EntityBuilderAdvice INSTANCE = new EntityBuilderAdvice();
	/**
	 * List of all method classifications supported by this advice.
	 */
	@SuppressWarnings("unchecked")
	private static final List<MethodClassification<?, SealedEntityProxy>> METHOD_CLASSIFICATION = Arrays.asList(
		new MethodClassification[]{
			SetAttributeMethodClassifier.INSTANCE,
			SetAssociatedDataMethodClassifier.INSTANCE/*,
			SetReferenceMethodClassifier.INSTANCE,
			SetParentEntityMethodClassifier.INSTANCE,
			SetPriceMethodClassifier.INSTANCE*/
		}
	);

	@Override
	public Class<SealedEntityProxy> getRequestedStateContract() {
		return SealedEntityProxy.class;
	}

	@Override
	public List<MethodClassification<?, SealedEntityProxy>> getMethodClassification() {
		return METHOD_CLASSIFICATION;
	}

}
