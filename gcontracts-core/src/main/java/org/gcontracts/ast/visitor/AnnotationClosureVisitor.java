/**
 * Copyright (c) 2011, Andre Steingress
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 *
 * 1.) Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 * disclaimer.
 * 2.) Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * 3.) Neither the name of Andre Steingress nor the names of its contributors may be used to endorse or
 * promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.gcontracts.ast.visitor;

import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.EmptyStatement;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.io.ReaderSource;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;
import org.gcontracts.ClassInvariantViolation;
import org.gcontracts.PostconditionViolation;
import org.gcontracts.PreconditionViolation;
import org.gcontracts.annotations.Invariant;
import org.gcontracts.annotations.meta.AnnotationContract;
import org.gcontracts.annotations.meta.ContractElement;
import org.gcontracts.annotations.meta.Postcondition;
import org.gcontracts.classgen.asm.ClosureWriter;
import org.gcontracts.generation.AssertStatementCreationUtility;
import org.gcontracts.generation.CandidateChecks;
import org.gcontracts.generation.TryCatchBlockGenerator;
import org.gcontracts.util.AnnotationUtils;
import org.gcontracts.util.ExpressionUtils;
import org.gcontracts.util.Validate;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Visits interfaces &amp; classes and looks for <tt>@Requires</tt> or <tt>@Ensures</tt> and creates {@link groovy.lang.Closure}
 * classes for the annotation closures.<p/>
 *
 * The annotation closure classes are used later on to check interface contract pre- and post-conditions in
 * implementation classes.
 *
 * @see org.gcontracts.annotations.Requires
 * @see org.gcontracts.annotations.Ensures
 *
 * @see org.gcontracts.ast.visitor.BaseVisitor
 *
 * @author ast
 */
public class AnnotationClosureVisitor extends BaseVisitor {

    private static final String INVARIANT_TYPE_NAME = Invariant.class.getName();
    private static final String POSTCONDITION_TYPE_NAME = Postcondition.class.getName();

    private ClassNode classNode;
    private final ClosureWriter closureWriter = new ClosureWriter();

    public AnnotationClosureVisitor(final SourceUnit sourceUnit, final ReaderSource source) {
        super(sourceUnit, source);
    }

    @Override
    public void visitClass(ClassNode node) {
        if ( !(CandidateChecks.isInterfaceContractsCandidate(node) || CandidateChecks.isContractsCandidate(node)) ) return;

        classNode = node;

        // only check classes for invariants
        if (CandidateChecks.isContractsCandidate(node))  {
            final List<AnnotationNode> annotationNodes = AnnotationUtils.hasMetaAnnotations(node, ContractElement.class.getName());
            for (AnnotationNode annotationNode : annotationNodes)  {
                ClosureExpression closureExpression = (ClosureExpression) annotationNode.getMember(CLOSURE_ATTRIBUTE_NAME);
                if (closureExpression == null) continue;

                new ClosureExpressionValidator(annotationNode, sourceUnit).visitClosureExpression(closureExpression);

                List<Parameter> parameters = new ArrayList<Parameter>(Arrays.asList(closureExpression.getParameters()));

                final List<BooleanExpression> booleanExpressions = ExpressionUtils.getBooleanExpression(closureExpression);
                if (booleanExpressions == null || booleanExpressions.isEmpty()) continue;

                BlockStatement closureBlockStatement = (BlockStatement) closureExpression.getCode();

                BlockStatement newClosureBlockStatement = TryCatchBlockGenerator.generateTryCatchBlock(
                        ClassHelper.makeWithoutCaching(ClassInvariantViolation.class),
                        "<" + annotationNode.getClassNode().getName() + "> " + classNode.getName() + " \n\n",
                        AssertStatementCreationUtility.getAssertionStatemens(booleanExpressions)
                );

                newClosureBlockStatement.setSourcePosition(closureBlockStatement);

                ClosureExpression rewrittenClosureExpression = new ClosureExpression(parameters.toArray(new Parameter[parameters.size()]), newClosureBlockStatement);
                rewrittenClosureExpression.setSourcePosition(closureExpression);
                rewrittenClosureExpression.setDeclaringClass(closureExpression.getDeclaringClass());
                rewrittenClosureExpression.setSynthetic(true);
                rewrittenClosureExpression.setVariableScope(closureExpression.getVariableScope());
                rewrittenClosureExpression.setType(closureExpression.getType());

                ClassNode closureClassNode = closureWriter.createClosureClass(classNode, null, rewrittenClosureExpression, false, false, Opcodes.ACC_PUBLIC);
                classNode.getModule().addClass(closureClassNode);

                final ClassExpression value = new ClassExpression(closureClassNode);
                value.setSourcePosition(annotationNode);

                annotationNode.setMember(CLOSURE_ATTRIBUTE_NAME, value);
            }
        }

        // if the current class node has an annotationprocessor closure we'll
        // generate a closure class too
        if (AnnotationUtils.hasAnnotationOfType(node, AnnotationContract.class.getName()))  {
            List<AnnotationNode> annotationProcessorClosureAnno = node.getAnnotations(ClassHelper.makeWithoutCaching(AnnotationContract.class.getName()));
            // only handle the first processor
            for (AnnotationNode annotationNode : annotationProcessorClosureAnno)  {
                replaceWithAnnotationProcessorClosureWithClassReference(node, annotationNode);
            }
        }

        super.visitClass(node);
    }

    @Override
    public void visitConstructorOrMethod(MethodNode methodNode, boolean isConstructor) {
        if (!CandidateChecks.couldBeContractElementMethodNode(classNode, methodNode) && !(CandidateChecks.isPreconditionCandidate(classNode, methodNode))) return;

        final List<AnnotationNode> annotationNodes = AnnotationUtils.hasMetaAnnotations(methodNode, ContractElement.class.getName());
        for (AnnotationNode annotationNode : annotationNodes)  {
            replaceWithClosureClassReference(annotationNode, methodNode);
        }

        super.visitConstructorOrMethod(methodNode, isConstructor);
    }

    private void replaceWithClosureClassReference(AnnotationNode annotationNode, MethodNode methodNode) {
        Validate.notNull(annotationNode);
        Validate.notNull(methodNode);

        // check whether this is a pre- or postcondition
        boolean isPostcondition = AnnotationUtils.hasAnnotationOfType(annotationNode.getClassNode(), org.gcontracts.annotations.meta.Postcondition.class.getName());

        ClosureExpression closureExpression = (ClosureExpression) annotationNode.getMember(CLOSURE_ATTRIBUTE_NAME);
        if (closureExpression == null) return;

        new ClosureExpressionValidator(annotationNode, sourceUnit).visitClosureExpression(closureExpression);

        List<Parameter> parameters = new ArrayList<Parameter>(Arrays.asList(closureExpression.getParameters()));

        parameters.addAll(new ArrayList<Parameter>(Arrays.asList(methodNode.getParameters())));

        final List<BooleanExpression> booleanExpressions = ExpressionUtils.getBooleanExpression(closureExpression);
        if (booleanExpressions == null || booleanExpressions.isEmpty()) return;

        BlockStatement closureBlockStatement = (BlockStatement) closureExpression.getCode();

        BlockStatement newClosureBlockStatement = TryCatchBlockGenerator.generateTryCatchBlock(
                isPostcondition ? ClassHelper.makeWithoutCaching(PostconditionViolation.class) : ClassHelper.makeWithoutCaching(PreconditionViolation.class),
                "<" + annotationNode.getClassNode().getName() + "> " + classNode.getName() + "." + methodNode.getTypeDescriptor() + " \n\n",
                AssertStatementCreationUtility.getAssertionStatemens(booleanExpressions)
        );

        newClosureBlockStatement.setSourcePosition(closureBlockStatement);

        ClosureExpression rewrittenClosureExpression = new ClosureExpression(parameters.toArray(new Parameter[parameters.size()]), newClosureBlockStatement);
        rewrittenClosureExpression.setSourcePosition(closureExpression);
        rewrittenClosureExpression.setDeclaringClass(closureExpression.getDeclaringClass());
        rewrittenClosureExpression.setSynthetic(true);
        rewrittenClosureExpression.setVariableScope(closureExpression.getVariableScope());
        rewrittenClosureExpression.setType(closureExpression.getType());

        boolean isConstructor = methodNode instanceof ConstructorNode;
        ClassNode closureClassNode = closureWriter.createClosureClass(classNode, methodNode, rewrittenClosureExpression, isPostcondition && !isConstructor, isPostcondition && !isConstructor, Opcodes.ACC_PUBLIC);
        classNode.getModule().addClass(closureClassNode);

        final ClassExpression value = new ClassExpression(closureClassNode);
        value.setSourcePosition(annotationNode);

        annotationNode.setMember(CLOSURE_ATTRIBUTE_NAME, value);
    }

    private void replaceWithAnnotationProcessorClosureWithClassReference(ClassNode annotation, AnnotationNode processorAnnotationNode) {
        Validate.notNull(annotation);
        Validate.notNull(processorAnnotationNode);
        // methodNode could be null in the case of handling an annotation processor closure...

        // check whether this is a pre- or postcondition
        boolean isPostcondition = AnnotationUtils.hasAnnotationOfType(annotation, org.gcontracts.annotations.meta.Postcondition.class.getName());

        ClosureExpression closureExpression = (ClosureExpression) processorAnnotationNode.getMember(CLOSURE_ATTRIBUTE_NAME);
        if (closureExpression == null) return;

        List<Parameter> parameters = new ArrayList<Parameter>(Arrays.asList(closureExpression.getParameters()));

        final List<BooleanExpression> booleanExpressions = ExpressionUtils.getBooleanExpression(closureExpression);
        if (booleanExpressions == null || booleanExpressions.isEmpty()) return;

        BlockStatement closureBlockStatement = (BlockStatement) closureExpression.getCode();

        BlockStatement newClosureBlockStatement = TryCatchBlockGenerator.generateTryCatchBlock(
                isPostcondition ? ClassHelper.makeWithoutCaching(PostconditionViolation.class) : ClassHelper.makeWithoutCaching(PreconditionViolation.class),
                "<" + annotation.getName() + "> Annotation Closure Contract has been violated \n\n",
                AssertStatementCreationUtility.getAssertionStatemens(booleanExpressions)
        );

        newClosureBlockStatement.setSourcePosition(closureBlockStatement);

        ClosureExpression rewrittenClosureExpression = new ClosureExpression(parameters.toArray(new Parameter[parameters.size()]), newClosureBlockStatement);
        rewrittenClosureExpression.setSourcePosition(closureExpression);
        rewrittenClosureExpression.setDeclaringClass(closureExpression.getDeclaringClass());
        rewrittenClosureExpression.setSynthetic(true);
        rewrittenClosureExpression.setVariableScope(closureExpression.getVariableScope());
        rewrittenClosureExpression.setType(closureExpression.getType());

        ClassNode closureClassNode = closureWriter.createClosureClass(classNode, null, rewrittenClosureExpression, false, false, Opcodes.ACC_PUBLIC);
        classNode.getModule().addClass(closureClassNode);

        final ClassExpression value = new ClassExpression(closureClassNode);
        value.setSourcePosition(annotation);

        processorAnnotationNode.setMember(CLOSURE_ATTRIBUTE_NAME, value);
    }

    static class ClosureExpressionValidator extends ClassCodeVisitorSupport implements Opcodes {

        private final AnnotationNode annotationNode;
        private final SourceUnit sourceUnit;

        public ClosureExpressionValidator(AnnotationNode annotationNode, SourceUnit sourceUnit)  {
            this.annotationNode = annotationNode;
            this.sourceUnit = sourceUnit;
        }

        @Override
        public void visitClosureExpression(ClosureExpression expression) {
            if (expression.getCode() == null || expression.getCode() instanceof EmptyStatement)  {
                addError("Annotation does not contain any expressions (e.g. use '@Requires({ argument1 })').", expression);
            }

            if (expression.getCode() instanceof BlockStatement &&
                    ((BlockStatement) expression.getCode()).getStatements().isEmpty())  {
                addError("Annotation does not contain any expressions (e.g. use '@Requires({ argument1 })').", expression);
            }

            if (expression.isParameterSpecified() && !AnnotationUtils.hasAnnotationOfType(annotationNode.getClassNode(), POSTCONDITION_TYPE_NAME))  {
                addError("Annotation does not support parameters (the only exception are postconditions).", expression);
            }

            if (expression.isParameterSpecified())  {
                for (Parameter param : expression.getParameters())  {
                    if (!("result".equals(param.getName()) || "old".equals(param.getName())))  {
                        addError("Postconditions only allow 'old' and 'result' closure parameters.", expression);
                    }

                    if (!param.isDynamicTyped())  {
                        addError("Postconditions do not support explicit types.", expression);
                    }
                }
            }

            super.visitClosureExpression(expression);
        }

        @Override
        public void visitVariableExpression(VariableExpression expression) {

            Variable accessedVariable = expression.getAccessedVariable();
            if (accessedVariable instanceof FieldNode)  {
                FieldNode fieldNode = (FieldNode) accessedVariable;

                if ((fieldNode.getModifiers() & ACC_PRIVATE) != 0)  {
                    // if this is a class invariant we'll change the field node access
                    if (AnnotationUtils.hasAnnotationOfType(annotationNode.getClassNode(), INVARIANT_TYPE_NAME))  {



                    } else {
                        addError("Access to private fields is not allowed, except in class invariants.", expression);
                    }
                }
            }

            if (accessedVariable instanceof Parameter)  {
                Parameter parameter = (Parameter) accessedVariable;
                if ("it".equals(parameter.getName()))  {
                    addError("Access to 'it' is not supported.", expression);
                }
            }

            super.visitVariableExpression(expression);
        }

        @Override
        public void visitPostfixExpression(PostfixExpression expression) {
            checkOperation(expression, expression.getOperation());

            super.visitPostfixExpression(expression);
        }

        @Override
        public void visitPrefixExpression(PrefixExpression expression) {
            checkOperation(expression, expression.getOperation());

            super.visitPrefixExpression(expression);
        }

        @Override
        public void visitBinaryExpression(BinaryExpression expression) {
            checkOperation(expression, expression.getOperation());

            super.visitBinaryExpression(expression);
        }

        private void checkOperation(Expression expression, Token operation) {
            if (Types.ofType(operation.getType(), Types.ASSIGNMENT_OPERATOR))  {
                addError("Assignment operators are not supported.", expression);
            }
            if (Types.ofType(operation.getType(), Types.POSTFIX_OPERATOR))  {
                addError("State changing postfix & prefix operators are not supported.", expression);
            }
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit;
        }
    }

}