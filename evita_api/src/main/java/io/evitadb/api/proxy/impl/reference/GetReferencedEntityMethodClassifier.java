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

import io.evitadb.api.exception.EntityClassInvalidException;
import io.evitadb.api.proxy.impl.SealedEntityReferenceProxyState;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntityGroup;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassUtils;
import io.evitadb.utils.ReflectionLookup;
import one.edee.oss.proxycian.CurriedMethodContextInvocationHandler;
import one.edee.oss.proxycian.DirectMethodClassification;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.function.Function;

/**
 * TODO JNO - document me
 * TODO JNO - podporovat optional, možná i obecně pro atributy a tak
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class GetReferencedEntityMethodClassifier extends DirectMethodClassification<Object, SealedEntityReferenceProxyState> {
	public static final GetReferencedEntityMethodClassifier INSTANCE = new GetReferencedEntityMethodClassifier();

	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> singleEntityReferenceResult() {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final ReferenceContract theReference = theState.getReference();
			return new EntityReference(theReference.getReferencedEntityType(), theReference.getReferencedPrimaryKey());
		};
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> singleEntityGroupReferenceResult() {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final ReferenceContract theReference = theState.getReference();
			return theReference.getGroup().orElse(null);
		};
	}

	@Nonnull
	private static CurriedMethodContextInvocationHandler<Object, SealedEntityReferenceProxyState> singleEntityResult(
		@Nonnull String cleanReferenceName,
		@Nonnull Class<? extends EntityClassifier> itemType,
		@Nonnull Function<ReferenceDecorator, Optional<SealedEntity>> entityExtractor
	) {
		return (entityClassifier, theMethod, args, theState, invokeSuper) -> {
			final ReferenceContract reference = theState.getReference();
			Assert.isTrue(
				reference instanceof ReferenceDecorator,
				() -> "Entity `" + theState.getSealedEntity().getType() + "` references of type `" +
					cleanReferenceName + "` were not fetched with `entityFetch` requirement. " +
					"Related entity body is not available."
			);
			final ReferenceDecorator referenceDecorator = (ReferenceDecorator) reference;
			return entityExtractor.apply(referenceDecorator)
				.map(it -> theState.wrapTo(itemType, it))
				.orElse(null);
		};
	}

	private static boolean isReferencedEntityType(
		@Nonnull Class<?> proxyClass,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String entityType
	) {
		final String referencedEntityType = referenceSchema.getReferencedEntityType();
		if (entityType.equals(referencedEntityType)) {
			Assert.isTrue(
				referenceSchema.isReferencedEntityTypeManaged(),
				() -> new EntityClassInvalidException(
					proxyClass,
					"Entity class type `" + proxyClass + "` reference `" +
						referenceSchema.getName() + "` relates to the entity type `" + referencedEntityType +
						"` which is not managed by evitaDB and cannot be wrapped into a proxy class!"
				)
			);
			return true;
		} else {
			return false;
		}
	}

	private static boolean isReferencedEntityGroupType(
		@Nonnull Class<?> proxyClass,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String entityGroupType
	) {
		final String referencedGroupType = referenceSchema.getReferencedGroupType();
		if (entityGroupType.equals(referencedGroupType)) {
			Assert.isTrue(
				referenceSchema.isReferencedEntityTypeManaged(),
				() -> new EntityClassInvalidException(
					proxyClass,
					"Entity class type `" + proxyClass + "` reference `" +
						referenceSchema.getName() + "` relates to the entity group type `" + referencedGroupType +
						"` which is not managed by evitaDB and cannot be wrapped into a proxy class!"
				)
			);
			return true;
		} else {
			return false;
		}
	}

	public GetReferencedEntityMethodClassifier() {
		super(
			"getReferencedEntity",
			(method, proxyState) -> {
				if (!ClassUtils.isAbstractOrDefault(method) || method.getParameterCount() > 0) {
					return null;
				}
				final ReflectionLookup reflectionLookup = proxyState.getReflectionLookup();
				final ReferenceSchemaContract referenceSchema = proxyState.getReferenceSchema();
				final String cleanReferenceName = referenceSchema.getName();
				@SuppressWarnings("rawtypes") final Class returnType = method.getReturnType();

				if (!EntityClassifier.class.isAssignableFrom(returnType)) {
					return null;
				}

				final ReferencedEntity referencedEntity = reflectionLookup.getAnnotationInstance(method, ReferencedEntity.class);
				final ReferencedEntityGroup referencedEntityGroup = reflectionLookup.getAnnotationInstance(method, ReferencedEntityGroup.class);
				final Entity entityInstance = reflectionLookup.getClassAnnotation(returnType, Entity.class);
				final EntityRef entityRefInstance = reflectionLookup.getClassAnnotation(returnType, EntityRef.class);
				final Optional<String> entityType = Optional.ofNullable(entityInstance)
					.map(Entity::name)
					.or(() -> Optional.ofNullable(entityRefInstance).map(EntityRef::value));
				final boolean entityIsReferencedEntity = entityType
					.map(it -> isReferencedEntityType(proxyState.getProxyClass(), referenceSchema, it))
					.orElse(false);
				final boolean entityIsReferencedGroup = entityType
						.map(it -> isReferencedEntityGroupType(proxyState.getProxyClass(), referenceSchema, it))
						.orElse(false);

				if (returnType.equals(EntityReference.class)) {
					if (referencedEntity != null || entityIsReferencedEntity) {
						return singleEntityReferenceResult();
					} else {
						if (referencedEntityGroup != null || entityIsReferencedGroup) {
							return singleEntityGroupReferenceResult();
						} else {
							return null;
						}
					}
				} else {
					if (referencedEntity != null) {
						Assert.isTrue(
							entityType.isEmpty() || entityIsReferencedEntity,
							() -> new EntityClassInvalidException(
								returnType,
								"Entity class type `" + proxyState.getProxyClass() + "` reference `" +
									referenceSchema.getName() + "` relates to the referenced entity type `" +
									referenceSchema.getReferencedEntityType() + "`, but the return " +
									"class `" + returnType + "` is annotated with @Entity referencing `" +
									entityType.orElse("N/A") + "` entity type!"
							)
						);
						//noinspection unchecked
						return singleEntityResult(cleanReferenceName, returnType, ReferenceDecorator::getReferencedEntity);
					} else if (referencedEntityGroup != null) {
						Assert.isTrue(
							entityType.isEmpty() || entityIsReferencedGroup,
							() -> new EntityClassInvalidException(
								returnType,
								"Entity class type `" + proxyState.getProxyClass() + "` reference `" +
									referenceSchema.getName() + "` relates to the referenced entity group type `" +
									referenceSchema.getReferencedGroupType() + "`, but the return " +
									"class `" + returnType + "` is annotated with @Entity referencing `" +
									entityType.orElse("N/A") + "` entity type!"
							)
						);
						//noinspection unchecked
						return singleEntityResult(cleanReferenceName, returnType, ReferenceDecorator::getGroupEntity);
					} else if (entityType.isPresent()) {
						if (entityIsReferencedEntity) {
							//noinspection unchecked
							return singleEntityResult(cleanReferenceName, returnType, ReferenceDecorator::getReferencedEntity);
						} else if (entityIsReferencedGroup) {
							//noinspection unchecked
							return singleEntityResult(cleanReferenceName, returnType, ReferenceDecorator::getGroupEntity);
						} else {
							return null;
						}
					} else {
						return null;
					}
				}
			}
		);
	}

}
