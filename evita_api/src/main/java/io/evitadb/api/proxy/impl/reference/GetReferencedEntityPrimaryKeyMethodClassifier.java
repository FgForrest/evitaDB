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

package io.evitadb.api.proxy.impl.reference;

import io.evitadb.api.proxy.impl.SealedEntityReferenceProxyState;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.ClassUtils;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.DirectMethodClassification;

import java.util.Optional;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetReferencedEntityPrimaryKeyMethodClassifier extends DirectMethodClassification<Object, SealedEntityReferenceProxyState> {
	public static final GetReferencedEntityPrimaryKeyMethodClassifier INSTANCE = new GetReferencedEntityPrimaryKeyMethodClassifier();

	public GetReferencedEntityPrimaryKeyMethodClassifier() {
		super(
			"getReferencedEntityPrimaryKey",
			(method, proxyState) -> {
				if (!ClassUtils.isAbstractOrDefault(method) || method.getParameterCount() > 0) {
					return null;
				}
				final ReflectionLookup reflectionLookup = proxyState.getReflectionLookup();
				final ReferencedEntity referencedEntity = reflectionLookup.getAnnotationInstance(method, ReferencedEntity.class);
				@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();
				final Optional<String> propertyName = ReflectionLookup.getPropertyNameFromMethodNameIfPossible(method.getName());
				if (Number.class.isAssignableFrom(EvitaDataTypes.toWrappedForm(returnType)) && referencedEntity != null || (
					!reflectionLookup.hasAnnotationInSamePackage(method, ReferencedEntity.class) &&
						propertyName
								.map(pName -> "primaryKey".equals(pName) ||
									"entityPrimaryKey".equals(pName) ||
									"pk".equals(pName) ||
									"id".equals(pName))
								.orElse(false)
					)
				) {
					//noinspection unchecked
					return (entityClassifier, theMethod, args, theState, invokeSuper) -> EvitaDataTypes.toTargetType(
						theState.getReference().getReferencedPrimaryKey(), returnType
					);
				} else {
					return null;
				}
			}
		);
	}

}
