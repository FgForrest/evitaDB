/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.query.expression.visitor;

import io.evitadb.api.query.expression.object.ElementAccessStep;
import io.evitadb.api.query.expression.object.NullSafeAccessStep;
import io.evitadb.api.query.expression.object.ObjectAccessOperator;
import io.evitadb.api.query.expression.object.ObjectAccessStep;
import io.evitadb.api.query.expression.object.PropertyAccessStep;
import io.evitadb.api.query.expression.object.SpreadAccessStep;
import io.evitadb.api.query.expression.operand.ConstantOperand;
import io.evitadb.api.query.expression.operand.VariableOperand;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.ExpressionNodeVisitor;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

/**
 * Traverses an expression tree and collects all accessed data paths. For example:
 *
 * <pre>
 *      $entity.references['brand'].attributes['order']
 *      -> result in the following accessed data path:
 *      ["$entity", "references", "brand", "attributes", "order"]
 * </pre>
 *
 * or a more complex example with multiple paths:
 *
 * <pre>
 *      $entity.references['brand'].*[$.attributes['tag'] ?? $.attributes['fallbackTag'] ?? $.referencedEntity]
 *      -> result in following accessed data paths:
 *      ["$entity", "references", "brand", "attributes", "tag"]
 * 	    ["$entity", "references", "brand", "attributes", "fallbackTag"]
 * 		["$entity", "references", "brand", "referencedEntity"]
 * </pre>
 *
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AccessedDataFinder implements ExpressionNodeVisitor {

	@Nonnull private final List<List<PathItem>> accessedPaths = new LinkedList<>();
	@Nullable private List<PathItem> currentPath = null;

	/**
	 * Analyzes the given expression node and determines all accessed paths within the expression tree.
	 * The method traverses the expression tree and collects paths that are accessed, compacting them
	 * to remove redundancy and overlap.
	 *
	 * @param expressionNode the root node of the expression tree to analyze. Must not be null.
	 * @return a list of typed path item lists, where each sub-list represents a unique accessed path
	 *         within the expression tree. The paths are compacted to eliminate redundant entries.
	 */
	@Nonnull
	public static List<List<PathItem>> findAccessedPaths(@Nonnull ExpressionNode expressionNode) {
		final AccessedDataFinder finder = new AccessedDataFinder();
		expressionNode.accept(finder);
		return compactPaths(finder.accessedPaths);
	}

	@Override
	public void visit(@Nonnull ExpressionNode node) {
		if (node instanceof ObjectAccessOperator objectAccessOperator) {
			visit(objectAccessOperator);
		} else if (node instanceof ConstantOperand constantOperand) {
			visit(constantOperand);
		} else if (node instanceof VariableOperand variableOperand) {
			visit(variableOperand);
		} else {
			// traverse children to find potential nested accessed data
			final ExpressionNode[] children = node.getChildren();
			if (children == null || children.length == 0) {
				return;
			}

			if (children.length > 1) {
				// save the current path to revert to after visiting children
				final List<PathItem> parentPath = this.currentPath;

				// traverse children and generate possible multiple paths
				for (ExpressionNode child : children) {
					if (parentPath == null) {
						// no parent path yet, let the child handle its own new path
						child.accept(this);
					} else {
						// link the child paths to the parent path
						final LinkedList<PathItem> childPath = new LinkedList<>(parentPath);
						this.currentPath = childPath;
						child.accept(this);
						this.accessedPaths.add(childPath);
					}
				}

				// revert to the parent path so that the parent can continue where they left off
				this.currentPath = parentPath;
			} else {
				children[0].accept(this);
			}
		}
	}

	private void visit(@Nonnull ObjectAccessOperator objectAccessOperator) {
		final boolean hasParentPath = this.currentPath != null;

		final List<PathItem> path;
		if (hasParentPath) {
			// continue the current path from the parent
			path = this.currentPath;
		} else {
			// no parent path yet, create a new root path
			path = new LinkedList<>();
			this.currentPath = path;
		}

		// add an operand path first
		final ExpressionNode[] children = objectAccessOperator.getChildren();
		Assert.isTrue(children != null && children.length == 1, "Object access operator must have exactly one child.");
		children[0].accept(this);

		// add steps path
		ObjectAccessStep step = objectAccessOperator.getAccessChain();
		do {
			if (step instanceof PropertyAccessStep propertyAccessStep) {
				path.add(new IdentifierPathItem(propertyAccessStep.getPropertyIdentifier()));
			} else if (step instanceof ElementAccessStep elementAccessStep) {
				elementAccessStep.getElementIdentifierOperand().accept(this);
			} else if (step instanceof SpreadAccessStep spreadAccessStep) {
				spreadAccessStep.getMappingExpression().accept(this);
			} else if (step instanceof NullSafeAccessStep) {
				continue;
			} else {
				throw new GenericEvitaInternalError("Unsupported step `" + step.getClass().getName() + "`.");
			}
		} while ((step = step.getNext()) != null);

		// store the current path if it is a root
		if (!hasParentPath) {
			this.currentPath = null;
			this.accessedPaths.add(path);
		}
	}

	private void visit(@Nonnull ConstantOperand constantOperand) {
		if (this.currentPath != null) {
			final Serializable value = constantOperand.getValue();
			// null literal used in element access (e.g., references[null]) — record as "null" string
			this.currentPath.add(new ElementPathItem(value != null ? value.toString() : "null"));
		}
	}

	private void visit(@Nonnull VariableOperand variableOperand) {
		if (this.currentPath != null && !variableOperand.isThis()) {
			// note: the variable name cannot be null if it doesn't reference `this`
			//noinspection DataFlowIssue
			this.currentPath.add(new VariablePathItem(variableOperand.getVariableName()));
		}
	}

	@Nonnull
	private static List<List<PathItem>> compactPaths(@Nonnull List<List<PathItem>> paths) {
		final List<List<PathItem>> sortedPaths = new LinkedList<>(paths)
			.stream()
			.sorted(Comparator.<List<PathItem>>comparingInt(List::size).reversed())
			.toList();

		final List<List<PathItem>> compactedPaths = new LinkedList<>();
		for (List<PathItem> path : sortedPaths) {
			boolean exists = false;
			path: for (final List<PathItem> existingCompactedPath : compactedPaths) {
				if (path.equals(existingCompactedPath)) {
					exists = true;
					break;
				}
				for (int i = existingCompactedPath.size() - 1; i >= 1; i--) {
					final List<PathItem> slicedExistingCompactedPath = existingCompactedPath.subList(0, i);
					if (path.equals(slicedExistingCompactedPath)) {
						exists = true;
						break path;
					}
				}
			}

			if (!exists) {
				compactedPaths.add(path);
			}
		}

		return List.copyOf(compactedPaths);
	}
}
