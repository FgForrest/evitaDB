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

package io.evitadb.api.proxy.impl.entity;

import io.evitadb.api.proxy.SealedEntityProxy;
import one.edee.oss.proxycian.MethodClassification;
import one.edee.oss.proxycian.recipe.Advice;

import java.io.Serial;
import java.util.Arrays;
import java.util.List;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class EntityContractAdvice implements Advice<SealedEntityProxy> {
	public static final EntityContractAdvice INSTANCE = new EntityContractAdvice();
	@Serial private static final long serialVersionUID = -5338309229187809879L;
	@SuppressWarnings("unchecked")
	private static final List<MethodClassification<?, SealedEntityProxy>> METHOD_CLASSIFICATION = Arrays.asList(
		new MethodClassification[]{
			GetPrimaryKeyMethodClassifier.INSTANCE,
			GetEntityTypeMethodClassifier.INSTANCE,
			GetAttributeMethodClassifier.INSTANCE,
			GetAssociatedDataMethodClassifier.INSTANCE,
			GetReferenceMethodClassifier.INSTANCE
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
