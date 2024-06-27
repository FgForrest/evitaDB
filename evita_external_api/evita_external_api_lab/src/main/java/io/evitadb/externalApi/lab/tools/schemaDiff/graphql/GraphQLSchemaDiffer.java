/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.externalApi.lab.tools.schemaDiff.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.GraphQLException;
import graphql.schema.GraphQLSchema;
import graphql.schema.diffing.SchemaDiffing;
import graphql.schema.diffing.ana.EditOperationAnalysisResult;
import graphql.schema.diffing.ana.SchemaDifference.*;
import graphql.schema.diffing.ana.SchemaDifference.ObjectDeletion;
import graphql.schema.diffing.ana.SchemaDifference.ObjectDifference;
import graphql.schema.diffing.ana.SchemaDifference.ObjectFieldAddition;
import graphql.schema.diffing.ana.SchemaDifference.ObjectInterfaceImplementationAddition;
import graphql.schema.diffing.ana.SchemaDifference.ObjectInterfaceImplementationDeletion;
import graphql.schema.diffing.ana.SchemaDifference.ObjectModification;
import graphql.schema.diffing.ana.SchemaDifference.ObjectModificationDetail;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import io.evitadb.externalApi.lab.tools.schemaDiff.graphql.SchemaDiff.Change;
import io.evitadb.externalApi.lab.tools.schemaDiff.graphql.SchemaDiff.ChangeType;
import io.evitadb.externalApi.lab.tools.schemaDiff.graphql.SchemaDiff.Severity;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.evitadb.externalApi.rest.exception.RestInvalidArgumentException;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Set;

import static graphql.schema.idl.RuntimeWiring.newRuntimeWiring;
import static io.evitadb.utils.CollectionUtils.createLinkedHashSet;

/**
 * Compares and analyzes differences between two GraphQL schemas.
 *
 * Note: This class is not thread-safe. New instance should be created for each comparison.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
@Slf4j
@NotThreadSafe
public class GraphQLSchemaDiffer {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final SchemaParser schemaParser = new SchemaParser();
	private final SchemaGenerator schemaGenerator = new SchemaGenerator();

	private final GraphQLSchema oldSchema;
	private final GraphQLSchema newSchema;

	private final Set<Change> breakingChanges = createLinkedHashSet(5);
	private final Set<Change> nonBreakingChanges = createLinkedHashSet(20);
	private final Set<Change> unclassifiedChanges = createLinkedHashSet(5);

	/**
	 * Compare and analyze differences between two GraphQL schema definitions
	 *
	 * @param oldSchemaDefinition string representation of the old schema
	 * @param newSchemaDefinition string representation of the new schema
	 * @return diff of changes in new schema compared to the old schema
	 */
	@Nonnull
	public static SchemaDiff analyze(@Nonnull String oldSchemaDefinition, @Nonnull String newSchemaDefinition) {
		final GraphQLSchemaDiffer differ = new GraphQLSchemaDiffer(oldSchemaDefinition, newSchemaDefinition);
		differ.analyze();
		return new SchemaDiff(differ.breakingChanges, differ.nonBreakingChanges, differ.unclassifiedChanges);
	}

	private GraphQLSchemaDiffer(@Nonnull String oldSchemaDefinition, @Nonnull String newSchemaDefinition) {
		this.oldSchema = parseGraphQLSchema(oldSchemaDefinition);
		this.newSchema = parseGraphQLSchema(newSchemaDefinition);
	}

	private void analyze() {
		final EditOperationAnalysisResult rawDiffResult;
		try {
			rawDiffResult = new SchemaDiffing().diffAndAnalyze(oldSchema, newSchema);
		} catch (Exception e) {
			throw new RestInvalidArgumentException(
				"Couldn't analyze schema differences: " + e.getMessage(),
				"Couldn't analyze schema differences.",
				e
			);
		}

		rawDiffResult.getObjectDifferences().forEach((__, difference) -> analyzeDifference(difference));
		rawDiffResult.getInterfaceDifferences().forEach((__, difference) -> analyzeDifference(difference));
		rawDiffResult.getUnionDifferences().forEach((__, difference) -> analyzeDifference(difference));
		rawDiffResult.getEnumDifferences().forEach((__, difference) -> analyzeDifference(difference));
		rawDiffResult.getInputObjectDifferences().forEach((__, difference) -> analyzeDifference(difference));
		rawDiffResult.getScalarDifferences().forEach((__, difference) -> analyzeDifference(difference));
		rawDiffResult.getDirectiveDifferences().forEach((__, difference) -> analyzeDifference(difference));
	}

	private void analyzeDifference(@Nonnull ObjectDifference difference) {
		if (difference instanceof ObjectAddition objectAddition) {
			addChange(ChangeType.TYPE_ADDED, objectAddition.getName());
		} else if (difference instanceof ObjectDeletion objectDeletion) {
			addChange(ChangeType.TYPE_REMOVED, objectDeletion.getName());
		} else if (difference instanceof ObjectModification objectModification) {
			if (objectModification.isNameChanged()) {
				addChange(ChangeType.TYPE_RENAMED, objectModification.getOldName(), objectModification.getNewName());
			}
			objectModification.getDetails().forEach(detail -> analyzeDifference(objectModification, detail));
		} else {
			addUnclassifiedChange(difference);
		}
	}

	private void analyzeDifference(@Nonnull ObjectModification parentType, @Nonnull ObjectModificationDetail difference) {
		if (difference instanceof ObjectInterfaceImplementationAddition objectInterfaceImplementationAddition) {
			addChange(ChangeType.TYPE_ADDED_TO_INTERFACE, parentType.getNewName(), objectInterfaceImplementationAddition.getName());
		} else if (difference instanceof ObjectInterfaceImplementationDeletion objectInterfaceImplementationDeletion) {
			addChange(ChangeType.TYPE_REMOVED_FROM_INTERFACE, parentType.getNewName(), objectInterfaceImplementationDeletion.getName());
		} else if (difference instanceof ObjectFieldAddition objectFieldAddition) {
			addChange(ChangeType.FIELD_ADDED, parentType.getNewName(), objectFieldAddition.getName());
		} else if (difference instanceof ObjectFieldDeletion objectFieldDeletion) {
			addChange(ChangeType.FIELD_REMOVED, parentType.getNewName(), objectFieldDeletion.getName());
		} else if (difference instanceof ObjectFieldRename objectFieldRename) {
			addChange(ChangeType.FIELD_RENAMED, parentType.getNewName(), objectFieldRename.getOldName(), objectFieldRename.getNewName());
		} else if (difference instanceof ObjectFieldArgumentRename objectFieldArgumentRename) {
			addChange(
				ChangeType.ARG_RENAMED,
				parentType.getNewName(),
				objectFieldArgumentRename.getFieldName(),
				objectFieldArgumentRename.getOldName(),
				objectFieldArgumentRename.getNewName()
			);
		} else if (difference instanceof ObjectFieldTypeModification objectFieldTypeModification) {
			addChange(
				ChangeType.FIELD_CHANGED_TYPE,
				parentType.getNewName(),
				objectFieldTypeModification.getFieldName(),
				objectFieldTypeModification.getOldType(),
				objectFieldTypeModification.getNewType()
			);
		} else if (difference instanceof ObjectFieldArgumentDeletion objectFieldArgumentDeletion) {
			addChange(ChangeType.ARG_REMOVED,  parentType.getNewName(), objectFieldArgumentDeletion.getFieldName(), objectFieldArgumentDeletion.getName());
		} else if (difference instanceof ObjectFieldArgumentAddition objectFieldArgumentAddition) {
			addChange(ChangeType.ARG_ADDED, parentType.getNewName(), objectFieldArgumentAddition.getFieldName(), objectFieldArgumentAddition.getName());
		} else if (difference instanceof ObjectFieldArgumentTypeModification objectFieldArgumentTypeModification) {
			addChange(
				ChangeType.ARG_CHANGED_TYPE,
				parentType.getNewName(),
				objectFieldArgumentTypeModification.getFieldName(),
				objectFieldArgumentTypeModification.getArgumentName(),
				objectFieldArgumentTypeModification.getOldType(),
				objectFieldArgumentTypeModification.getNewType()
			);
		} else if (difference instanceof ObjectFieldArgumentDefaultValueModification objectFieldArgumentDefaultValueModification) {
			addChange(
				ChangeType.ARG_DEFAULT_VALUE_CHANGE,
				parentType.getNewName(),
				objectFieldArgumentDefaultValueModification.getFieldName(),
				objectFieldArgumentDefaultValueModification.getArgumentName(),
				objectFieldArgumentDefaultValueModification.getOldValue(),
				objectFieldArgumentDefaultValueModification.getNewValue()
			);
		} else if (difference instanceof AppliedDirectiveAddition appliedDirectiveAddition) {
			addChange(ChangeType.APPLIED_DIRECTIVE_ADDED, parentType.getNewName(), appliedDirectiveAddition.getName());
		} else if (difference instanceof AppliedDirectiveDeletion appliedDirectiveDeletion) {
			addChange(ChangeType.APPLIED_DIRECTIVE_REMOVED, parentType.getNewName(), appliedDirectiveDeletion.getName());
		} else if (difference instanceof AppliedDirectiveArgumentDeletion appliedDirectiveArgumentDeletion) {
			// todo lho review name of directive
			addChange(ChangeType.APPLIED_DIRECTIVE_ARG_REMOVED, parentType.getNewName(), appliedDirectiveArgumentDeletion.getArgumentName());
		} else if (difference instanceof AppliedDirectiveArgumentValueModification appliedDirectiveArgumentValueModification) {
			// todo lho review name of directive
			addChange(
				ChangeType.APPLIED_DIRECTIVE_ARG_VALUE_CHANGED,
				parentType.getNewName(),
				appliedDirectiveArgumentValueModification.getArgumentName(),
				appliedDirectiveArgumentValueModification.getOldValue(),
				appliedDirectiveArgumentValueModification.getNewValue()
			);
		} else if (difference instanceof AppliedDirectiveArgumentRename appliedDirectiveArgumentRename) {
			// todo lho review name of directive
			addChange(
				ChangeType.APPLIED_DIRECTIVE_ARG_RENAMED,
				parentType.getNewName(),
				appliedDirectiveArgumentRename.getOldName(),
				appliedDirectiveArgumentRename.getNewName()
			);
		} else {
			addUnclassifiedChange(difference);
		}
	}

	private void analyzeDifference(@Nonnull InterfaceDifference difference) {
		if (difference instanceof InterfaceAddition interfaceAddition) {
			addChange(ChangeType.TYPE_ADDED, interfaceAddition.getName());
		} else if (difference instanceof InterfaceDeletion interfaceDeletion) {
			addChange(ChangeType.TYPE_REMOVED, interfaceDeletion.getName());
		} else if (difference instanceof InterfaceModification interfaceModification) {
			if (interfaceModification.isNameChanged()) {
				addChange(ChangeType.TYPE_RENAMED, interfaceModification.getOldName(), interfaceModification.getNewName());
			}

			interfaceModification.getDetails().forEach(detail -> analyzeDifference(interfaceModification, detail));
		} else {
			addUnclassifiedChange(difference);
		}
	}

	private void analyzeDifference(@Nonnull InterfaceModification parentType, @Nonnull InterfaceModificationDetail difference) {
		if (difference instanceof InterfaceInterfaceImplementationAddition interfaceInterfaceImplementationAddition) {
			// todo lho review
			addChange(ChangeType.TYPE_ADDED_TO_INTERFACE, parentType.getNewName(), interfaceInterfaceImplementationAddition.getName());
		} else if (difference instanceof InterfaceInterfaceImplementationDeletion interfaceInterfaceImplementationDeletion) {
			// todo lho review
			addChange(ChangeType.TYPE_REMOVED_FROM_INTERFACE, parentType.getNewName(), interfaceInterfaceImplementationDeletion.getName());
		} else if (difference instanceof InterfaceFieldAddition interfaceFieldAddition) {
			addChange(ChangeType.FIELD_ADDED, parentType.getNewName(), interfaceFieldAddition.getName());
		} else if (difference instanceof InterfaceFieldDeletion interfaceFieldDeletion) {
			addChange(ChangeType.FIELD_REMOVED, parentType.getNewName(), interfaceFieldDeletion.getName());
		} else if (difference instanceof InterfaceFieldRename interfaceFieldRename) {
			addChange(ChangeType.FIELD_RENAMED, parentType.getNewName(), interfaceFieldRename.getOldName(), interfaceFieldRename.getNewName());
		} else if (difference instanceof InterfaceFieldTypeModification interfaceFieldTypeModification) {
			addChange(
				ChangeType.FIELD_CHANGED_TYPE,
				parentType.getNewName(),
				interfaceFieldTypeModification.getFieldName(),
				interfaceFieldTypeModification.getOldType(),
				interfaceFieldTypeModification.getNewType()
			);
		} else if (difference instanceof InterfaceFieldArgumentDeletion interfaceFieldArgumentDeletion) {
			addChange(
				ChangeType.ARG_REMOVED,
				parentType.getNewName(),
				interfaceFieldArgumentDeletion.getFieldName(),
				interfaceFieldArgumentDeletion.getName()
			);
		} else if (difference instanceof InterfaceFieldArgumentAddition interfaceFieldArgumentAddition) {
			addChange(
				ChangeType.ARG_ADDED,
				parentType.getNewName(),
				interfaceFieldArgumentAddition.getFieldName(),
				interfaceFieldArgumentAddition.getName()
			);
		} else if (difference instanceof InterfaceFieldArgumentTypeModification interfaceFieldArgumentTypeModification) {
			addChange(
				ChangeType.ARG_CHANGED_TYPE,
				parentType.getNewName(),
				interfaceFieldArgumentTypeModification.getFieldName(),
				interfaceFieldArgumentTypeModification.getArgumentName(),
				interfaceFieldArgumentTypeModification.getOldType(),
				interfaceFieldArgumentTypeModification.getNewType()
			);
		} else if (difference instanceof InterfaceFieldArgumentDefaultValueModification interfaceFieldArgumentDefaultValueModification) {
			addChange(
				ChangeType.ARG_DEFAULT_VALUE_CHANGE,
				parentType.getNewName(),
				interfaceFieldArgumentDefaultValueModification.getFieldName(),
				interfaceFieldArgumentDefaultValueModification.getArgumentName(),
				interfaceFieldArgumentDefaultValueModification.getOldValue(),
				interfaceFieldArgumentDefaultValueModification.getNewValue()
			);
		} else if (difference instanceof InterfaceFieldArgumentRename interfaceFieldArgumentRename) {
			addChange(
				ChangeType.ARG_RENAMED,
				parentType.getNewName(),
				interfaceFieldArgumentRename.getFieldName(),
				interfaceFieldArgumentRename.getOldName(),
				interfaceFieldArgumentRename.getNewName()
			);
		} else if (difference instanceof AppliedDirectiveAddition appliedDirectiveAddition) {
			addChange(ChangeType.APPLIED_DIRECTIVE_ADDED, parentType.getNewName(), appliedDirectiveAddition.getName());
		} else if (difference instanceof AppliedDirectiveDeletion appliedDirectiveDeletion) {
			addChange(ChangeType.APPLIED_DIRECTIVE_REMOVED, parentType.getNewName(), appliedDirectiveDeletion.getName());
		} else if (difference instanceof AppliedDirectiveArgumentDeletion appliedDirectiveArgumentDeletion) {
			// todo lho review name of directive
			addChange(ChangeType.APPLIED_DIRECTIVE_ARG_REMOVED, parentType.getNewName(), appliedDirectiveArgumentDeletion.getArgumentName());
		} else if (difference instanceof AppliedDirectiveArgumentValueModification appliedDirectiveArgumentValueModification) {
			// todo lho review name of directive
			addChange(
				ChangeType.APPLIED_DIRECTIVE_ARG_VALUE_CHANGED,
				parentType.getNewName(),
				appliedDirectiveArgumentValueModification.getArgumentName(),
				appliedDirectiveArgumentValueModification.getOldValue(),
				appliedDirectiveArgumentValueModification.getNewValue()
			);
		} else if (difference instanceof AppliedDirectiveArgumentRename appliedDirectiveArgumentRename) {
			// todo lho review name of directive
			addChange(
				ChangeType.APPLIED_DIRECTIVE_ARG_RENAMED,
				parentType.getNewName(),
				appliedDirectiveArgumentRename.getOldName(),
				appliedDirectiveArgumentRename.getNewName()
			);
		} else {
			addUnclassifiedChange(difference);
		}
	}

	private void analyzeDifference(@Nonnull UnionDifference difference) {
		if (difference instanceof UnionAddition unionAddition) {
			addChange(ChangeType.TYPE_ADDED, unionAddition.getName());
		} else if (difference instanceof UnionDeletion unionDeletion) {
			addChange(ChangeType.TYPE_REMOVED, unionDeletion.getName());
		} else if (difference instanceof UnionModification unionModification) {
			if (unionModification.isNameChanged()) {
				addChange(ChangeType.TYPE_RENAMED, unionModification.getOldName(), unionModification.getNewName());
			}
			unionModification.getDetails().forEach(detail -> analyzeDifference(unionModification, detail));
		} else {
			addUnclassifiedChange(difference);
		}
	}

	private void analyzeDifference(@Nonnull UnionModification parentType, @Nonnull UnionModificationDetail difference) {
		if (difference instanceof UnionMemberAddition unionMemberAddition) {
			addChange(ChangeType.TYPE_ADDED_TO_UNION, parentType.getNewName(), unionMemberAddition.getName());
		} else if (difference instanceof UnionMemberDeletion unionMemberDeletion) {
			addChange(ChangeType.TYPE_REMOVED_FROM_UNION, parentType.getNewName(), unionMemberDeletion.getName());
		} else if (difference instanceof AppliedDirectiveAddition appliedDirectiveAddition) {
			addChange(ChangeType.APPLIED_DIRECTIVE_ADDED, parentType.getNewName(), appliedDirectiveAddition.getName());
		} else if (difference instanceof AppliedDirectiveDeletion appliedDirectiveDeletion) {
			addChange(ChangeType.APPLIED_DIRECTIVE_REMOVED, parentType.getNewName(), appliedDirectiveDeletion.getName());
		} else {
			addUnclassifiedChange(difference);
		}
	}

	private void analyzeDifference(@Nonnull EnumDifference difference) {
		if (difference instanceof EnumAddition enumAddition) {
			addChange(ChangeType.TYPE_ADDED, enumAddition.getName());
		} else if (difference instanceof EnumDeletion enumDeletion) {
			addChange(ChangeType.TYPE_REMOVED, enumDeletion.getName());
		} else if (difference instanceof EnumModification enumModification) {
			if (enumModification.isNameChanged()) {
				addChange(ChangeType.TYPE_RENAMED, enumModification.getOldName(), enumModification.getNewName());
			}
			enumModification.getDetails().forEach(detail -> analyzeDifference(enumModification, detail));
		} else {
			addUnclassifiedChange(difference);
		}
	}

	private void analyzeDifference(@Nonnull EnumModification parentType, @Nonnull EnumModificationDetail difference) {
		if (difference instanceof EnumValueAddition enumValueAddition) {
			addChange(ChangeType.VALUE_ADDED_TO_ENUM, parentType.getNewName(), enumValueAddition.getName());
		} else if (difference instanceof EnumValueRenamed enumValueRenamed) {
			addChange(ChangeType.VALUE_RENAMED, parentType.getNewName(), enumValueRenamed.getOldName(), enumValueRenamed.getNewName());
		} else if (difference instanceof EnumValueDeletion enumValueDeletion) {
			addChange(ChangeType.VALUE_REMOVED_FROM_ENUM, parentType.getNewName(), enumValueDeletion.getName());
		} else if (difference instanceof AppliedDirectiveAddition appliedDirectiveAddition) {
			addChange(ChangeType.APPLIED_DIRECTIVE_ADDED, parentType.getNewName(), appliedDirectiveAddition.getName());
		} else if (difference instanceof AppliedDirectiveDeletion appliedDirectiveDeletion) {
			addChange(ChangeType.APPLIED_DIRECTIVE_REMOVED, parentType.getNewName(), appliedDirectiveDeletion.getName());
		} else {
			addUnclassifiedChange(difference);
		}
	}

	private void analyzeDifference(@Nonnull InputObjectDifference difference) {
		if (difference instanceof InputObjectAddition inputObjectAddition) {
			addChange(ChangeType.TYPE_ADDED, inputObjectAddition.getName());
		} else if (difference instanceof InputObjectDeletion inputObjectDeletion) {
			addChange(ChangeType.TYPE_REMOVED, inputObjectDeletion.getName());
		} else if (difference instanceof InputObjectModification inputObjectModification) {
			if (inputObjectModification.isNameChanged()) {
				addChange(ChangeType.TYPE_RENAMED, inputObjectModification.getOldName(), inputObjectModification.getNewName());
			}
			inputObjectModification.getDetails().forEach(detail -> analyzeDifference(inputObjectModification, detail));
		} else {
			addUnclassifiedChange(difference);
		}
	}

	private void analyzeDifference(@Nonnull InputObjectModification parentType, @Nonnull InputObjectModificationDetail difference) {
		if (difference instanceof InputObjectFieldDeletion inputObjectFieldDeletion) {
			addChange(ChangeType.INPUT_FIELD_REMOVED, parentType.getNewName(), inputObjectFieldDeletion.getName());
		} else if (difference instanceof InputObjectFieldRename inputObjectFieldRename) {
			addChange(ChangeType.INPUT_FIELD_RENAMED, parentType.getNewName(), inputObjectFieldRename.getOldName(), inputObjectFieldRename.getNewName());
		} else if (difference instanceof InputObjectFieldDefaultValueModification inputObjectFieldDefaultValueModification) {
			addChange(
				ChangeType.INPUT_FIELD_DEFAULT_VALUE_CHANGE,
				parentType.getNewName(),
				inputObjectFieldDefaultValueModification.getFieldName(),
				inputObjectFieldDefaultValueModification.getOldDefaultValue(),
				inputObjectFieldDefaultValueModification.getNewDefaultValue()
			);
		} else if (difference instanceof InputObjectFieldTypeModification inputObjectFieldTypeModification) {
			addChange(
				ChangeType.INPUT_FIELD_CHANGED_TYPE,
				parentType.getNewName(),
				inputObjectFieldTypeModification.getFieldName(),
				inputObjectFieldTypeModification.getOldType(),
				inputObjectFieldTypeModification.getNewType()
			);
		} else if (difference instanceof InputObjectFieldAddition inputObjectFieldAddition) {
			addChange(ChangeType.FIELD_ADDED, parentType.getNewName(), inputObjectFieldAddition.getName());
		} else if (difference instanceof AppliedDirectiveAddition appliedDirectiveAddition) {
			addChange(ChangeType.APPLIED_DIRECTIVE_ADDED, parentType.getNewName(), appliedDirectiveAddition.getName());
		} else if (difference instanceof AppliedDirectiveDeletion appliedDirectiveDeletion) {
			addChange(ChangeType.APPLIED_DIRECTIVE_REMOVED, parentType.getNewName(), appliedDirectiveDeletion.getName());
		} else {
			addUnclassifiedChange(difference);
		}
	}

	private void analyzeDifference(@Nonnull ScalarDifference difference) {
		if (difference instanceof ScalarAddition scalarAddition) {
			addChange(ChangeType.TYPE_ADDED, scalarAddition.getName());
		} else if (difference instanceof ScalarDeletion scalarDeletion) {
			addChange(ChangeType.TYPE_REMOVED, scalarDeletion.getName());
		} else if (difference instanceof ScalarModification scalarModification) {
			if (scalarModification.isNameChanged()) {
				addChange(ChangeType.TYPE_RENAMED, scalarModification.getOldName(), scalarModification.getNewName());
			}
			scalarModification.getDetails().forEach(detail -> analyzeDifference(scalarModification, detail));
		} else {
			addUnclassifiedChange(difference);
		}
	}

	private void analyzeDifference(@Nonnull ScalarModification parentType, @Nonnull ScalarModificationDetail difference) {
		if (difference instanceof AppliedDirectiveAddition appliedDirectiveAddition) {
			addChange(ChangeType.APPLIED_DIRECTIVE_ADDED, parentType.getNewName(), appliedDirectiveAddition.getName());
		} else if (difference instanceof AppliedDirectiveDeletion appliedDirectiveDeletion) {
			addChange(ChangeType.APPLIED_DIRECTIVE_REMOVED, parentType.getNewName(), appliedDirectiveDeletion.getName());
		} else {
			addUnclassifiedChange(difference);
		}
	}

	private void analyzeDifference(@Nonnull DirectiveDifference difference) {
		if (difference instanceof DirectiveAddition directiveAddition) {
			addChange(ChangeType.DIRECTIVE_ADDED, directiveAddition.getName());
		} else if (difference instanceof DirectiveDeletion directiveDeletion) {
			addChange(ChangeType.DIRECTIVE_REMOVED, directiveDeletion.getName());
		} else if (difference instanceof DirectiveModification directiveModification) {
			if (directiveModification.isNameChanged()) {
				addChange(ChangeType.DIRECTIVE_RENAMED, directiveModification.getOldName(), directiveModification.getNewName());
			}
			directiveModification.getDetails().forEach(detail -> analyzeDifference(directiveModification, detail));
		} else {
			addUnclassifiedChange(difference);
		}
	}

	private void analyzeDifference(@Nonnull DirectiveModification parentDirective, @Nonnull DirectiveModificationDetail difference) {
		if (difference instanceof DirectiveArgumentDeletion directiveArgumentDeletion) {
			addChange(ChangeType.ARG_REMOVED, parentDirective.getNewName(), directiveArgumentDeletion.getName());
		} else if (difference instanceof DirectiveArgumentAddition directiveArgumentAddition) {
			addChange(ChangeType.ARG_ADDED, parentDirective.getNewName(), directiveArgumentAddition.getName());
		} else if (difference instanceof DirectiveArgumentTypeModification directiveArgumentTypeModification) {
			addChange(
				ChangeType.ARG_CHANGED_TYPE,
				parentDirective.getNewName(),
				directiveArgumentTypeModification.getArgumentName(),
				directiveArgumentTypeModification.getOldType(),
				directiveArgumentTypeModification.getNewType()
			);
		} else if (difference instanceof DirectiveArgumentDefaultValueModification directiveArgumentDefaultValueModification) {
			addChange(
				ChangeType.ARG_DEFAULT_VALUE_CHANGE,
				parentDirective.getNewName(),
				directiveArgumentDefaultValueModification.getArgumentName(),
				directiveArgumentDefaultValueModification.getOldValue(),
				directiveArgumentDefaultValueModification.getNewValue()
			);
		} else if (difference instanceof DirectiveArgumentRename directiveArgumentRename) {
			addChange(
				ChangeType.ARG_RENAMED,
				parentDirective.getNewName(),
				directiveArgumentRename.getOldName(),
				directiveArgumentRename.getNewName()
			);
		} else if (difference instanceof AppliedDirectiveAddition appliedDirectiveAddition) {
			addChange(ChangeType.APPLIED_DIRECTIVE_ADDED, parentDirective.getNewName(), appliedDirectiveAddition.getName());
		} else if (difference instanceof AppliedDirectiveDeletion appliedDirectiveDeletion) {
			addChange(ChangeType.APPLIED_DIRECTIVE_REMOVED, parentDirective.getNewName(), appliedDirectiveDeletion.getName());
		} else {
			addUnclassifiedChange(difference);
		}
	}

	private void addChange(@Nonnull ChangeType changeType, @Nonnull Object...args) {
		final Change change = new Change(changeType, args);
		if (changeType.getSeverity() == Severity.BREAKING) {
			breakingChanges.add(change);
		} else if (changeType.getSeverity() == Severity.NON_BREAKING) {
			nonBreakingChanges.add(change);
		} else {
			throw new RestInternalError("Unsupported change type `" + changeType + "`.");
		}
	}

	private void addUnclassifiedChange(@Nonnull Object difference) {
		String differenceDetail;
		try {
			differenceDetail = objectMapper.writeValueAsString(difference);
		} catch (JsonProcessingException e) {
			log.error("Couldn't serialize schema difference: ", e);
			differenceDetail = null;
		}
		unclassifiedChanges.add(new Change(
			ChangeType.UNCLASSIFIED,
			difference.getClass().getSimpleName(),
			differenceDetail
		));
	}

	@Nonnull
	private GraphQLSchema parseGraphQLSchema(@Nonnull String schemaDefinition) {
		try {
			return SchemaGenerator.createdMockedSchema(schemaDefinition);
		} catch (GraphQLException e) {
			throw new RestInvalidArgumentException("Couldn't parse GraphQL schema: " + e.getMessage(), e);
		}
	}
}
