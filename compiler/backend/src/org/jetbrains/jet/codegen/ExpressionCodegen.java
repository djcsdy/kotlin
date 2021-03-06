/*
 * Copyright 2010-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.codegen;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.asm4.Label;
import org.jetbrains.asm4.MethodVisitor;
import org.jetbrains.asm4.Type;
import org.jetbrains.asm4.commons.InstructionAdapter;
import org.jetbrains.asm4.commons.Method;
import org.jetbrains.jet.codegen.binding.CalculatedClosure;
import org.jetbrains.jet.codegen.binding.MutableClosure;
import org.jetbrains.jet.codegen.context.*;
import org.jetbrains.jet.codegen.intrinsics.IntrinsicMethod;
import org.jetbrains.jet.codegen.signature.JvmPropertyAccessorSignature;
import org.jetbrains.jet.codegen.state.GenerationState;
import org.jetbrains.jet.codegen.state.JetTypeMapper;
import org.jetbrains.jet.codegen.state.JetTypeMapperMode;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.diagnostics.DiagnosticUtils;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.resolve.BindingContextUtils;
import org.jetbrains.jet.lang.resolve.DescriptorUtils;
import org.jetbrains.jet.lang.resolve.calls.*;
import org.jetbrains.jet.lang.resolve.constants.CompileTimeConstant;
import org.jetbrains.jet.lang.resolve.java.JvmClassName;
import org.jetbrains.jet.lang.resolve.scopes.receivers.*;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.jet.lang.types.checker.JetTypeChecker;
import org.jetbrains.jet.lang.types.lang.JetStandardClasses;
import org.jetbrains.jet.lang.types.lang.JetStandardLibrary;
import org.jetbrains.jet.lang.types.lang.PrimitiveType;
import org.jetbrains.jet.lexer.JetTokens;

import java.util.*;

import static org.jetbrains.asm4.Opcodes.*;
import static org.jetbrains.jet.codegen.AsmTypeConstants.*;
import static org.jetbrains.jet.codegen.CodegenUtil.*;
import static org.jetbrains.jet.codegen.binding.CodegenBinding.*;
import static org.jetbrains.jet.lang.resolve.BindingContext.*;
import static org.jetbrains.jet.lang.resolve.BindingContextUtils.*;

/**
 * @author max
 * @author yole
 * @author alex.tkachman
 */
public class ExpressionCodegen extends JetVisitor<StackValue, StackValue> implements LocalLookup {

    private static final String CLASS_NO_PATTERN_MATCHED_EXCEPTION = "jet/NoPatternMatchedException";
    private static final String CLASS_TYPE_CAST_EXCEPTION = "jet/TypeCastException";
    public static final Set<DeclarationDescriptor> INTEGRAL_RANGES = JetStandardLibrary.getInstance().getIntegralRanges();

    private int myLastLineNumber = -1;

    final InstructionAdapter v;
    final FrameMap myFrameMap;
    final JetTypeMapper typeMapper;

    private final GenerationState state;
    private final Type returnType;

    private final BindingContext bindingContext;
    final CodegenContext context;

    private final Stack<BlockStackElement> blockStackElements = new Stack<BlockStackElement>();
    private final Collection<String> localVariableNames = new HashSet<String>();

    /*
     * When we create a temporary variable to hold some value not to compute it many times
     * we put it into this map to emit access to that variable instead of evaluating the whole expression
     */
    private final Map<JetElement, StackValue.Local> tempVariables = Maps.newHashMap();

    public CalculatedClosure generateObjectLiteral(
            GenerationState state,
            JetObjectLiteralExpression literal
    ) {
        JetObjectDeclaration objectDeclaration = literal.getObjectDeclaration();

        JvmClassName className =
                classNameForAnonymousClass(bindingContext, objectDeclaration);
        ClassBuilder classBuilder = state.getFactory().newVisitor(className.getInternalName() + ".class");

        final ClassDescriptor classDescriptor = bindingContext.get(CLASS, objectDeclaration);
        assert classDescriptor != null;

        //noinspection SuspiciousMethodCalls
        final CalculatedClosure closure = bindingContext.get(CLOSURE, classDescriptor);

        final CodegenContext objectContext = context.intoAnonymousClass(classDescriptor, this);

        new ImplementationBodyCodegen(objectDeclaration, objectContext, classBuilder, state).generate();

        return closure;
    }

    static class BlockStackElement {
    }

    static class LoopBlockStackElement extends BlockStackElement {
        final Label continueLabel;
        final Label breakLabel;
        public final JetSimpleNameExpression targetLabel;

        LoopBlockStackElement(Label breakLabel, Label continueLabel, JetSimpleNameExpression targetLabel) {
            this.breakLabel = breakLabel;
            this.continueLabel = continueLabel;
            this.targetLabel = targetLabel;
        }
    }

    static class FinallyBlockStackElement extends BlockStackElement {
        final JetTryExpression expression;

        FinallyBlockStackElement(JetTryExpression expression) {
            this.expression = expression;
        }
    }


    public ExpressionCodegen(
            MethodVisitor v,
            FrameMap myMap,
            Type returnType,
            CodegenContext context,
            GenerationState state
    ) {
        this.myFrameMap = myMap;
        this.typeMapper = state.getTypeMapper();
        this.returnType = returnType;
        this.state = state;
        this.v = new InstructionAdapter(v) {
            @Override
            public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
                super.visitLocalVariable(name, desc, signature, start, end,
                                         index);
                localVariableNames.add(name);
            }
        };
        this.bindingContext = state.getBindingContext();
        this.context = context;
    }

    public GenerationState getState() {
        return state;
    }

    StackValue castToRequiredTypeOfInterfaceIfNeeded(StackValue inner, DeclarationDescriptor provided, @Nullable ClassDescriptor required) {
        if (required == null) {
            return inner;
        }

        if (provided instanceof CallableDescriptor) {
            provided = ((CallableDescriptor) provided).getReceiverParameter().getType().getConstructor().getDeclarationDescriptor();
        }

        assert provided instanceof ClassDescriptor;

        if (!isInterface(provided) && isInterface(required)) {
            inner.put(OBJECT_TYPE, v);
            final Type type = asmType(required.getDefaultType());
            v.checkcast(type);
            return StackValue.onStack(type);
        }

        return inner;
    }

    public BindingContext getBindingContext() {
        return bindingContext;
    }

    public Collection<String> getLocalVariableNamesForExpression() {
        return localVariableNames;
    }

    public StackValue genQualified(StackValue receiver, JetElement selector) {
        if (tempVariables.containsKey(selector)) {
            throw new IllegalStateException("Inconsistent state: expression saved to a temporary variable is a selector");
        }
        if (!(selector instanceof JetBlockExpression)) {
            markLineNumber(selector);
        }
        try {
            return selector.accept(this, receiver);
        }
        catch (ProcessCanceledException e) {
            throw e;
        }
        catch (CompilationException e) {
            throw e;
        }
        catch (Throwable error) {
            String message = error.getMessage();
            throw new CompilationException(message != null ? message : "null", error, selector);
        }
    }

    public StackValue gen(JetElement expr) {
        StackValue tempVar = tempVariables.get(expr);
        if (tempVar != null) {
            return tempVar;
        }
        if (expr instanceof JetExpression) {
            JetExpression expression = (JetExpression) expr;
            CompileTimeConstant<?> constant = bindingContext.get(BindingContext.COMPILE_TIME_VALUE, expression);
            if (constant != null) {
                return StackValue.constant(constant.getValue(), expressionType(expression));
            }
        }
        return genQualified(StackValue.none(), expr);
    }

    public void gen(JetElement expr, Type type) {
        StackValue value = gen(expr);
        value.put(type, v);
    }

    public void genToJVMStack(JetExpression expr) {
        gen(expr, expressionType(expr));
    }

    @Override
    public StackValue visitClass(JetClass klass, StackValue data) {
        return visitClassOrObject(klass);
    }

    private StackValue visitClassOrObject(JetClassOrObject declaration) {
        ClassDescriptor descriptor = bindingContext.get(BindingContext.CLASS, declaration);
        assert descriptor != null;

        JvmClassName className =
                classNameForAnonymousClass(bindingContext, declaration);
        ClassBuilder classBuilder = state.getFactory().newVisitor(className.getInternalName() + ".class");

        final CodegenContext objectContext = context.intoAnonymousClass(descriptor, this);

        new ImplementationBodyCodegen(declaration, objectContext, classBuilder, state).generate();
        return StackValue.none();
    }

    @Override
    public StackValue visitObjectDeclaration(JetObjectDeclaration declaration, StackValue data) {
        return visitClassOrObject(declaration);
    }

    @Override
    public StackValue visitExpression(JetExpression expression, StackValue receiver) {
        throw new UnsupportedOperationException("Codegen for " + expression + " is not yet implemented");
    }

    @Override
    public StackValue visitSuperExpression(JetSuperExpression expression, StackValue data) {
        final DeclarationDescriptor descriptor = bindingContext.get(BindingContext.REFERENCE_TARGET, expression.getInstanceReference());
        if (descriptor instanceof ClassDescriptor) {
            return StackValue.thisOrOuter(this, (ClassDescriptor) descriptor, true);
        }
        else {
            JetType type = context.getThisDescriptor().getDefaultType();
            return StackValue.local(0, asmType(type));
        }
    }

    @NotNull
    private Type asmType(@NotNull JetType type) {
        return typeMapper.mapType(type, JetTypeMapperMode.VALUE);
    }

    @NotNull
    private Type asmTypeOrVoid(@Nullable JetType type) {
        return type == null ? Type.VOID_TYPE : asmType(type);
    }

    @Override
    public StackValue visitParenthesizedExpression(JetParenthesizedExpression expression, StackValue receiver) {
        return genQualified(receiver, expression.getExpression());
    }

    @Override
    public StackValue visitAnnotatedExpression(JetAnnotatedExpression expression, StackValue receiver) {
        return genQualified(receiver, expression.getBaseExpression());
    }

    private static boolean isEmptyExpression(JetElement expr) {
        if (expr == null) {
            return true;
        }
        if (expr instanceof JetBlockExpression) {
            JetBlockExpression blockExpression = (JetBlockExpression) expr;
            List<JetElement> statements = blockExpression.getStatements();
            if (statements.size() == 0 || statements.size() == 1 && isEmptyExpression(statements.get(0))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public StackValue visitIfExpression(JetIfExpression expression, StackValue receiver) {
        Type asmType = expressionType(expression);

        JetExpression thenExpression = expression.getThen();
        JetExpression elseExpression = expression.getElse();

        if (thenExpression == null && elseExpression == null) {
            throw new CompilationException("Both brunches of if/else are null", null, expression);
        }

        if (isEmptyExpression(thenExpression)) {
            if (isEmptyExpression(elseExpression)) {
                if (!asmType.equals(JET_TUPLE0_TYPE)) {
                    throw new CompilationException("Completely empty 'if' is expected to have Unit type", null, expression);
                }
                StackValue.putTuple0Instance(v);
                return StackValue.onStack(asmType);
            }
            StackValue condition = gen(expression.getCondition());
            return generateSingleBranchIf(condition, elseExpression, false);
        }
        else {
            if (isEmptyExpression(elseExpression)) {
                StackValue condition = gen(expression.getCondition());
                return generateSingleBranchIf(condition, thenExpression, true);
            }
        }


        Label elseLabel = new Label();
        StackValue condition = gen(expression.getCondition());
        condition.condJump(elseLabel, true, v);   // == 0, i.e. false

        Label end = new Label();

        gen(thenExpression, asmType);

        v.goTo(end);
        v.mark(elseLabel);

        gen(elseExpression, asmType);

        v.mark(end);

        return StackValue.onStack(asmType);
    }

    @Override
    public StackValue visitWhileExpression(JetWhileExpression expression, StackValue receiver) {
        Label condition = new Label();
        v.mark(condition);

        Label end = new Label();
        blockStackElements.push(new LoopBlockStackElement(end, condition, targetLabel(expression)));

        final StackValue conditionValue = gen(expression.getCondition());
        conditionValue.condJump(end, true, v);

        gen(expression.getBody(), Type.VOID_TYPE);
        v.goTo(condition);

        v.mark(end);

        blockStackElements.pop();

        return StackValue.onStack(Type.VOID_TYPE);
    }


    @Override
    public StackValue visitDoWhileExpression(JetDoWhileExpression expression, StackValue receiver) {
        Label condition = new Label();
        v.mark(condition);

        Label end = new Label();

        blockStackElements.push(new LoopBlockStackElement(end, condition, targetLabel(expression)));

        gen(expression.getBody(), Type.VOID_TYPE);

        final StackValue conditionValue = gen(expression.getCondition());
        conditionValue.condJump(condition, false, v);

        v.mark(end);

        blockStackElements.pop();
        return StackValue.onStack(Type.VOID_TYPE);
    }

    @Override
    public StackValue visitForExpression(JetForExpression forExpression, StackValue receiver) {
        // Is it a "1..2" or so
        RangeCodegenUtil.BinaryCall binaryCall = RangeCodegenUtil.getRangeAsBinaryCall(forExpression);
        if (binaryCall != null) {
            ResolvedCall<? extends CallableDescriptor> resolvedCall = bindingContext.get(RESOLVED_CALL, binaryCall.op);
            assert resolvedCall != null;

            CallableDescriptor rangeTo = resolvedCall.getResultingDescriptor();
            if (RangeCodegenUtil.isOptimizableRangeTo(rangeTo)
                // todo: currently, only Int ranges are supported, but all primitives must be optimized
                && RangeCodegenUtil.isIntRange(rangeTo.getReturnType())
            ) {
                generateForLoop(new ForInIntRangeLiteralLoopGenerator(forExpression, binaryCall, resolvedCall));
                return StackValue.none();
            }
        }

        final JetExpression loopRange = forExpression.getLoopRange();
        final JetType loopRangeType = bindingContext.get(BindingContext.EXPRESSION_TYPE, loopRange);
        assert loopRangeType != null;
        Type asmLoopRangeType = asmType(loopRangeType);
        if (asmLoopRangeType.getSort() == Type.ARRAY) {
            generateForLoop(new ForInArrayLoopGenerator(forExpression));
            return StackValue.none();
        }
        else {
            // todo: Only IntRange optimized so far
            if (RangeCodegenUtil.isIntRange(loopRangeType)) {
                generateForLoop(new ForInIntRangeInstanceLoopGenerator(forExpression));
                return StackValue.none();
            }

            generateForLoop(new IteratorForLoopGenerator(forExpression));
            return StackValue.none();
        }
    }

    private OwnerKind contextKind() {
        return context.getContextKind();
    }

    private void generateForLoop(AbstractForLoopGenerator generator) {
        Label loopExit = new Label();
        Label loopEntry = new Label();
        Label continueLabel = new Label();

        generator.beforeLoop();

        v.mark(loopEntry);
        generator.conditionAndJump(loopExit);

        generator.beforeBody();
        blockStackElements.push(new LoopBlockStackElement(loopExit, continueLabel, targetLabel(generator.forExpression)));
        generator.body();
        blockStackElements.pop();
        v.mark(continueLabel);
        generator.afterBody();

        v.goTo(loopEntry);

        v.mark(loopExit);
        generator.afterLoop();
    }

    private abstract class AbstractForLoopGenerator {

        // for (e : E in c) {...}
        protected final JetForExpression forExpression;
        private final Label bodyStart = new Label();
        private final Label bodyEnd = new Label();
        private final List<Runnable> leaveVariableTasks = Lists.newArrayList();

        protected final JetType elementType;

        private AbstractForLoopGenerator(@NotNull JetForExpression forExpression) {
            this.forExpression = forExpression;
            this.elementType = getElementType(forExpression);
        }

        @NotNull
        private JetType getElementType(JetForExpression forExpression) {
            JetExpression loopRange = forExpression.getLoopRange();
            assert loopRange != null;
            ResolvedCall<FunctionDescriptor> nextCall = getNotNull(bindingContext,
                                                                   LOOP_RANGE_NEXT_RESOLVED_CALL, loopRange,
                                                                   "No next() function " + DiagnosticUtils.atLocation(loopRange));
            //noinspection ConstantConditions
            return nextCall.getResultingDescriptor().getReturnType();
        }

        public abstract void beforeLoop();

        public abstract void conditionAndJump(@NotNull Label loopExit);

        public void beforeBody() {
            v.mark(bodyStart);
            JetParameter loopParameter = forExpression.getLoopParameter();
            if (loopParameter != null) {
                // E e = tmp<iterator>.next()
                final VariableDescriptor parameterDescriptor = bindingContext.get(BindingContext.VALUE_PARAMETER, loopParameter);
                @SuppressWarnings("ConstantConditions") final Type asmTypeForParameter = asmType(parameterDescriptor.getType());
                final int parameterIndex = myFrameMap.enter(parameterDescriptor, asmTypeForParameter);
                leaveVariableTasks.add(new Runnable() {
                    @Override
                    public void run() {
                        myFrameMap.leave(parameterDescriptor);
                        v.visitLocalVariable(parameterDescriptor.getName().getName(),
                                             asmTypeForParameter.getDescriptor(), null,
                                             bodyStart, bodyEnd,
                                             parameterIndex);
                    }
                });
                assignToLoopParameter(parameterIndex);
            }
            else {
                JetMultiDeclaration multiParameter = forExpression.getMultiParameter();
                assert multiParameter != null;

                // E tmp<e> = tmp<iterator>.next()
                final Type asmElementType = asmType(elementType);
                int tmpParameterIndex = myFrameMap.enterTemp(asmElementType);
                leaveVariableTasks.add(new Runnable() {
                    @Override
                    public void run() {
                        myFrameMap.leaveTemp(asmElementType);
                    }
                });
                assignToLoopParameter(tmpParameterIndex);

                generateMultiVariables(tmpParameterIndex, multiParameter.getEntries());
            }
        }

        protected abstract void assignToLoopParameter(int parameterIndex);

        private void generateMultiVariables(int tmpParameterIndex, List<JetMultiDeclarationEntry> entries) {
            Type asmElementType = asmType(elementType);
            for (JetMultiDeclarationEntry variableDeclaration : entries) {
                final VariableDescriptor componentDescriptor = bindingContext.get(BindingContext.VARIABLE, variableDeclaration);

                @SuppressWarnings("ConstantConditions") final Type componentAsmType = asmType(componentDescriptor.getReturnType());
                final int componentVarIndex = myFrameMap.enter(componentDescriptor, componentAsmType);
                leaveVariableTasks.add(new Runnable() {
                    @Override
                    public void run() {
                        myFrameMap.leave(componentDescriptor);
                        v.visitLocalVariable(componentDescriptor.getName().getName(),
                                             componentAsmType.getDescriptor(), null,
                                             bodyStart, bodyEnd,
                                             componentVarIndex);
                    }
                });


                ResolvedCall<FunctionDescriptor> resolvedCall =
                        bindingContext.get(BindingContext.COMPONENT_RESOLVED_CALL, variableDeclaration);
                assert resolvedCall != null : "Resolved call is null for " + variableDeclaration.getText();
                Call call = makeFakeCall(new TransientReceiver(elementType));
                invokeFunction(call, StackValue.local(tmpParameterIndex, asmElementType), resolvedCall);

                v.store(componentVarIndex, componentAsmType);
            }
        }

        public void body() {
            gen(forExpression.getBody(), Type.VOID_TYPE);
        }

        public void afterBody() {
            v.mark(bodyEnd);
            // e goes out of scope
            for (Runnable task : Lists.reverse(leaveVariableTasks)) {
                task.run();
            }
        }

        public abstract void afterLoop();
    }

    private class IteratorForLoopGenerator extends AbstractForLoopGenerator {

        private int iteratorVarIndex;
        private final ResolvedCall<FunctionDescriptor> iteratorCall;
        private final ResolvedCall<FunctionDescriptor> nextCall;
        private final Type asmTypeForIterator;

        private IteratorForLoopGenerator(@NotNull JetForExpression forExpression) {
            super(forExpression);

            JetExpression loopRange = forExpression.getLoopRange();
            assert loopRange != null;
            this.iteratorCall = getNotNull(bindingContext,
                                           LOOP_RANGE_ITERATOR_RESOLVED_CALL, loopRange,
                                           "No .iterator() function " + DiagnosticUtils.atLocation(loopRange));

            JetType iteratorType = iteratorCall.getResultingDescriptor().getReturnType();
            assert iteratorType != null;
            this.asmTypeForIterator = asmType(iteratorType);

            this.nextCall = getNotNull(bindingContext,
                                       LOOP_RANGE_NEXT_RESOLVED_CALL, loopRange,
                                       "No next() function " + DiagnosticUtils.atLocation(loopRange));
        }

        @Override
        public void beforeLoop() {
            // Iterator<E> tmp<iterator> = c.iterator()

            iteratorVarIndex = myFrameMap.enterTemp(asmTypeForIterator);

            Call call = bindingContext.get(LOOP_RANGE_ITERATOR_CALL, forExpression.getLoopRange());
            invokeFunction(call, StackValue.none(), iteratorCall);
            v.store(iteratorVarIndex, asmTypeForIterator);
        }

        @Override
        public void conditionAndJump(@NotNull Label loopExit) {
            // tmp<iterator>.hasNext()

            JetExpression loopRange = forExpression.getLoopRange();
            @SuppressWarnings("ConstantConditions") ResolvedCall<FunctionDescriptor> hasNextCall = getNotNull(bindingContext,
                                                                      LOOP_RANGE_HAS_NEXT_RESOLVED_CALL, loopRange,
                                                                      "No hasNext() function " + DiagnosticUtils.atLocation(loopRange));
            @SuppressWarnings("ConstantConditions") Call fakeCall = makeFakeCall(new TransientReceiver(iteratorCall.getResultingDescriptor().getReturnType()));
            invokeFunction(fakeCall, StackValue.local(iteratorVarIndex, asmTypeForIterator), hasNextCall);

            JetType type = hasNextCall.getResultingDescriptor().getReturnType();
            assert type != null && JetTypeChecker.INSTANCE.isSubtypeOf(type, JetStandardLibrary.getInstance().getBooleanType());

            Type asmType = asmType(type);
            StackValue.coerce(asmType, Type.BOOLEAN_TYPE, v);
            v.ifeq(loopExit);
        }

        @Override
        protected void assignToLoopParameter(int parameterIndex) {
            @SuppressWarnings("ConstantConditions") Call fakeCall =
                    makeFakeCall(new TransientReceiver(iteratorCall.getResultingDescriptor().getReturnType()));
            invokeFunction(fakeCall, StackValue.local(iteratorVarIndex, asmTypeForIterator), nextCall);
            //noinspection ConstantConditions
            v.store(parameterIndex, asmType(nextCall.getResultingDescriptor().getReturnType()));
        }

        @Override
        public void afterLoop() {
            // tmp<iterator> goes out of scope
            myFrameMap.leaveTemp(asmTypeForIterator);
        }
    }

    private class ForInArrayLoopGenerator extends AbstractForLoopGenerator {
        private int indexVar;
        private int arrayVar;
        private Runnable afterLoopAction;
        private final JetType loopRangeType;

        private ForInArrayLoopGenerator(@NotNull JetForExpression forExpression) {
            super(forExpression);
            loopRangeType = bindingContext.get(BindingContext.EXPRESSION_TYPE, forExpression.getLoopRange());
        }

        @Override
        public void beforeLoop() {
            indexVar = myFrameMap.enterTemp(Type.INT_TYPE);

            JetExpression loopRange = forExpression.getLoopRange();
            StackValue value = gen(loopRange);
            if (value instanceof StackValue.Local) {
                arrayVar = ((StackValue.Local) value).index;
            }
            else {
                arrayVar = myFrameMap.enterTemp(OBJECT_TYPE);
                afterLoopAction = new Runnable() {
                    @Override
                    public void run() {
                        myFrameMap.leaveTemp(OBJECT_TYPE);
                    }
                };
                Type asmLoopRangeType = asmType(loopRangeType);
                value.put(asmLoopRangeType, v);
                v.store(arrayVar, OBJECT_TYPE);
            }

            v.iconst(0);
            v.store(indexVar, Type.INT_TYPE);
        }

        @Override
        public void conditionAndJump(@NotNull Label loopExit) {
            v.load(indexVar, Type.INT_TYPE);
            v.load(arrayVar, OBJECT_TYPE);
            v.arraylength();
            v.ificmpge(loopExit);
        }

        @Override
        protected void assignToLoopParameter(int parameterIndex) {
            Type arrayElParamType;
            Type asmElementType = asmType(elementType);
            if (JetStandardLibrary.getInstance().isArray(loopRangeType)) {
                arrayElParamType = boxType(asmElementType);
            }
            else {
                arrayElParamType = asmElementType;
            }

            v.load(arrayVar, OBJECT_TYPE);
            v.load(indexVar, Type.INT_TYPE);
            v.aload(arrayElParamType);
            StackValue.onStack(arrayElParamType).put(asmElementType, v);
            v.store(parameterIndex, asmElementType);
        }

        @Override
        public void afterBody() {
            v.iinc(indexVar, 1);
            super.afterBody();
        }

        @Override
        public void afterLoop() {
            if (afterLoopAction != null) {
                afterLoopAction.run();
            }
            myFrameMap.leaveTemp(Type.INT_TYPE);
        }
    }

    private class ForInIntRangeLiteralLoopGenerator extends AbstractForLoopGenerator {
        private final RangeCodegenUtil.BinaryCall rangeCall;
        private final ResolvedCall<? extends CallableDescriptor> resolvedCall;
        private Type asmElementType;
        private int indexVar;
        private int lastVar;

        private ForInIntRangeLiteralLoopGenerator(
                @NotNull JetForExpression forExpression,
                @NotNull RangeCodegenUtil.BinaryCall rangeCall,
                ResolvedCall<? extends CallableDescriptor> resolvedCall
        ) {
            super(forExpression);
            this.rangeCall = rangeCall;
            this.resolvedCall = resolvedCall;
        }

        @Override
        public void beforeLoop() {
            JetType elementType = RangeCodegenUtil.getPrimitiveRangeElementType(resolvedCall.getResultingDescriptor().getReturnType());
            assert elementType != null;
            asmElementType = asmType(elementType);

            indexVar = myFrameMap.enterTemp(asmElementType);
            gen(rangeCall.left, asmElementType);
            v.store(indexVar, asmElementType);

            lastVar = myFrameMap.enterTemp(asmElementType);
            gen(rangeCall.right, asmElementType);
            v.store(lastVar, asmElementType);
        }

        @Override
        public void conditionAndJump(@NotNull Label loopExit) {
            v.load(indexVar, asmElementType);
            v.load(lastVar, asmElementType);
            v.ificmpgt(loopExit);
        }

        @Override
        protected void assignToLoopParameter(int parameterIndex) {
            // todo: don't create a temp variable if this is not a multi-decl for
            v.load(indexVar, asmElementType);
            v.store(parameterIndex, asmElementType);
        }

        @Override
        public void afterBody() {
            v.iinc(indexVar, 1);
            super.afterBody();
        }

        @Override
        public void afterLoop() {
            myFrameMap.leaveTemp(asmElementType); // lastVar
            myFrameMap.leaveTemp(asmElementType); // indexVar
        }
    }

    private class ForInIntRangeInstanceLoopGenerator extends AbstractForLoopGenerator {
        private int indexVar;
        private int countVar;
        private int deltaVar;

        private ForInIntRangeInstanceLoopGenerator(
                @NotNull JetForExpression forExpression
        ) {
            super(forExpression);
        }

        @Override
        public void beforeLoop() {
            JetType loopRangeType = bindingContext.get(EXPRESSION_TYPE, forExpression.getLoopRange());
            assert loopRangeType != null;
            Type asmLoopRangeType = asmType(loopRangeType);
            gen(forExpression.getLoopRange(), asmLoopRangeType);
            v.dup();
            v.dup();

            indexVar = myFrameMap.enterTemp(Type.INT_TYPE);
            v.invokevirtual(JET_INT_RANGE_TYPE.getInternalName(), "getStart", "()I");
            v.store(indexVar, Type.INT_TYPE);

            countVar = myFrameMap.enterTemp(Type.INT_TYPE);
            v.invokevirtual(JET_INT_RANGE_TYPE.getInternalName(), "getSize", "()I");
            v.store(countVar, Type.INT_TYPE);

            deltaVar = myFrameMap.enterTemp(Type.INT_TYPE);
            v.invokevirtual(JET_INT_RANGE_TYPE.getInternalName(), "getIsReversed", "()Z");
            Label down = new Label();

            v.ifne(down);
            v.iconst(1);
            Label initEnd = new Label();
            v.goTo(initEnd);
            v.mark(down);
            v.iconst(-1);
            v.mark(initEnd);
            v.store(deltaVar, Type.INT_TYPE);
        }

        @Override
        public void conditionAndJump(@NotNull Label loopExit) {
            v.load(countVar, Type.INT_TYPE);
            v.ifeq(loopExit);
        }

        @Override
        protected void assignToLoopParameter(int parameterIndex) {
            // todo: no temp var when this is not a multi-declaration for-loop
            v.load(indexVar, Type.INT_TYPE);
            v.store(parameterIndex, Type.INT_TYPE);
        }

        @Override
        public void afterBody() {
            v.load(indexVar, Type.INT_TYPE);
            v.load(deltaVar, Type.INT_TYPE);
            v.add(Type.INT_TYPE);
            v.store(indexVar, Type.INT_TYPE);
            v.iinc(countVar, -1);
            super.afterBody();
        }

        @Override
        public void afterLoop() {
            myFrameMap.leaveTemp(Type.INT_TYPE); // deltaVar
            myFrameMap.leaveTemp(Type.INT_TYPE); // countVar
            myFrameMap.leaveTemp(Type.INT_TYPE); // indexVar
        }
    }

    @Override
    public StackValue visitBreakExpression(JetBreakExpression expression, StackValue receiver) {
        JetSimpleNameExpression labelElement = expression.getTargetLabel();

        for (int i = blockStackElements.size() - 1; i >= 0; --i) {
            BlockStackElement stackElement = blockStackElements.get(i);
            if (stackElement instanceof FinallyBlockStackElement) {
                FinallyBlockStackElement finallyBlockStackElement = (FinallyBlockStackElement) stackElement;
                JetTryExpression jetTryExpression = finallyBlockStackElement.expression;
                //noinspection ConstantConditions
                gen(jetTryExpression.getFinallyBlock().getFinalExpression(), Type.VOID_TYPE);
            }
            else if (stackElement instanceof LoopBlockStackElement) {
                LoopBlockStackElement loopBlockStackElement = (LoopBlockStackElement) stackElement;
                //noinspection ConstantConditions
                if (labelElement == null ||
                    loopBlockStackElement.targetLabel != null &&
                    labelElement.getReferencedName().equals(loopBlockStackElement.targetLabel.getReferencedName())) {
                    v.goTo(loopBlockStackElement.breakLabel);
                    return StackValue.none();
                }
            }
            else {
                throw new UnsupportedOperationException();
            }
        }

        throw new UnsupportedOperationException();
    }

    @Override
    public StackValue visitContinueExpression(JetContinueExpression expression, StackValue receiver) {
        JetSimpleNameExpression labelElement = expression.getTargetLabel();

        for (int i = blockStackElements.size() - 1; i >= 0; --i) {
            BlockStackElement stackElement = blockStackElements.get(i);
            if (stackElement instanceof FinallyBlockStackElement) {
                FinallyBlockStackElement finallyBlockStackElement = (FinallyBlockStackElement) stackElement;
                JetTryExpression jetTryExpression = finallyBlockStackElement.expression;
                //noinspection ConstantConditions
                gen(jetTryExpression.getFinallyBlock().getFinalExpression(), Type.VOID_TYPE);
            }
            else if (stackElement instanceof LoopBlockStackElement) {
                LoopBlockStackElement loopBlockStackElement = (LoopBlockStackElement) stackElement;
                //noinspection ConstantConditions
                if (labelElement == null ||
                    loopBlockStackElement.targetLabel != null &&
                    labelElement.getReferencedName().equals(loopBlockStackElement.targetLabel.getReferencedName())) {
                    v.goTo(loopBlockStackElement.continueLabel);
                    return StackValue.none();
                }
            }
            else {
                throw new UnsupportedOperationException();
            }
        }

        throw new UnsupportedOperationException();
    }

    private StackValue generateSingleBranchIf(StackValue condition, JetExpression expression, boolean inverse) {
        Type expressionType = expressionType(expression);
        Type targetType = expressionType;
        if (!expressionType.equals(JET_TUPLE0_TYPE)) {
            targetType = OBJECT_TYPE;
        }

        Label elseLabel = new Label();
        condition.condJump(elseLabel, inverse, v);

        gen(expression, expressionType);
        StackValue.coerce(expressionType, targetType, v);

        Label end = new Label();
        v.goTo(end);

        v.mark(elseLabel);
        StackValue.putTuple0Instance(v);

        v.mark(end);
        return StackValue.onStack(targetType);
    }

    @Override
    public StackValue visitConstantExpression(JetConstantExpression expression, StackValue receiver) {
        CompileTimeConstant<?> compileTimeValue = bindingContext.get(BindingContext.COMPILE_TIME_VALUE, expression);
        assert compileTimeValue != null;
        return StackValue.constant(compileTimeValue.getValue(), expressionType(expression));
    }

    @Override
    public StackValue visitStringTemplateExpression(JetStringTemplateExpression expression, StackValue receiver) {
        StringBuilder constantValue = new StringBuilder("");
        for (JetStringTemplateEntry entry : expression.getEntries()) {
            if (entry instanceof JetLiteralStringTemplateEntry) {
                constantValue.append(entry.getText());
            }
            else if (entry instanceof JetEscapeStringTemplateEntry) {
                constantValue.append(((JetEscapeStringTemplateEntry) entry).getUnescapedValue());
            }
            else {
                constantValue = null;
                break;
            }
        }
        if (constantValue != null) {
            final Type type = expressionType(expression);
            return StackValue.constant(constantValue.toString(), type);
        }
        else {
            generateStringBuilderConstructor();
            for (JetStringTemplateEntry entry : expression.getEntries()) {
                if (entry instanceof JetStringTemplateEntryWithExpression) {
                    invokeAppend(entry.getExpression());
                }
                else {
                    String text = entry instanceof JetEscapeStringTemplateEntry
                                  ? ((JetEscapeStringTemplateEntry) entry).getUnescapedValue()
                                  : entry.getText();
                    v.aconst(text);
                    invokeAppendMethod(JAVA_STRING_TYPE);
                }
            }
            v.invokevirtual("java/lang/StringBuilder", "toString", "()Ljava/lang/String;");
            return StackValue.onStack(expressionType(expression));
        }
    }

    @Override
    public StackValue visitBlockExpression(JetBlockExpression expression, StackValue receiver) {
        List<JetElement> statements = expression.getStatements();
        return generateBlock(statements);
    }

    @Override
    public StackValue visitNamedFunction(JetNamedFunction function, StackValue data) {
        assert data == StackValue.none();

        if (JetPsiUtil.isScriptDeclaration(function)) {
            return StackValue.none();
        }

        StackValue closure = genClosure(function);
        DeclarationDescriptor descriptor = bindingContext.get(BindingContext.DECLARATION_TO_DESCRIPTOR, function);
        int index = lookupLocalIndex(descriptor);
        closure.put(OBJECT_TYPE, v);
        v.store(index, OBJECT_TYPE);
        return StackValue.none();
    }

    @Override
    public StackValue visitFunctionLiteralExpression(JetFunctionLiteralExpression expression, StackValue receiver) {
        //noinspection ConstantConditions
        if (bindingContext.get(BindingContext.BLOCK, expression)) {
            //noinspection ConstantConditions
            return generateBlock(expression.getFunctionLiteral().getBodyExpression().getStatements());
        }
        else {
            return genClosure(expression);
        }
    }

    private StackValue genClosure(JetExpression expression) {
        final FunctionDescriptor descriptor = bindingContext.get(BindingContext.FUNCTION, expression);
        final ClassDescriptor classDescriptor =
                bindingContext.get(CLASS_FOR_FUNCTION, descriptor);
        //noinspection SuspiciousMethodCalls
        final CalculatedClosure closure = bindingContext.get(CLOSURE, classDescriptor);

        ClosureCodegen closureCodegen = new ClosureCodegen(state, (MutableClosure) closure).gen(expression, context, this);

        final JvmClassName className = closureCodegen.name;
        final Type asmType = className.getAsmType();
        final String internalName = className.getInternalName();
        if (isConst(closure)) {
            v.invokestatic(internalName, "$getInstance", "()" + className.getDescriptor());
        }
        else {
            v.anew(asmType);
            v.dup();

            final Method cons = closureCodegen.constructor;
            pushClosureOnStack(closure, false);
            v.invokespecial(internalName, "<init>", cons.getDescriptor());
        }
        return StackValue.onStack(asmType);
    }

    @Override
    public StackValue visitObjectLiteralExpression(JetObjectLiteralExpression expression, StackValue receiver) {
        CalculatedClosure closure = this.generateObjectLiteral(state, expression);

        ConstructorDescriptor constructorDescriptor = bindingContext.get(BindingContext.CONSTRUCTOR, expression.getObjectDeclaration());
        assert constructorDescriptor != null;
        CallableMethod constructor = typeMapper.mapToCallableMethod(constructorDescriptor, closure);

        final JvmClassName name = bindingContext.get(FQN, constructorDescriptor.getContainingDeclaration());
        assert name != null;

        Type type = name.getAsmType();
        v.anew(type);
        v.dup();
        final Method cons = constructor.getSignature().getAsmMethod();

        pushClosureOnStack(closure, false);

        final JetDelegatorToSuperCall superCall = closure.getSuperCall();
        if (superCall != null) {
            ConstructorDescriptor superConstructor = (ConstructorDescriptor) bindingContext.get(BindingContext.REFERENCE_TARGET,
                                                                                                superCall
                                                                                                        .getCalleeExpression()
                                                                                                        .getConstructorReferenceExpression());
            assert superConstructor != null;
            //noinspection SuspiciousMethodCalls
            CallableMethod superCallable = typeMapper
                    .mapToCallableMethod(superConstructor,
                                         bindingContext.get(CLOSURE, superConstructor.getContainingDeclaration()));
            Type[] argumentTypes = superCallable.getSignature().getAsmMethod().getArgumentTypes();
            ResolvedCall resolvedCall = bindingContext.get(BindingContext.RESOLVED_CALL, superCall.getCalleeExpression());
            assert resolvedCall != null;
            pushMethodArguments(resolvedCall, Arrays.asList(argumentTypes));
        }

        v.invokespecial(name.getInternalName(), "<init>", cons.getDescriptor());
        return StackValue.onStack(type);
    }

    protected void pushClosureOnStack(CalculatedClosure closure, boolean ignoreThisAndReceiver) {
        if (closure != null) {
            if (!ignoreThisAndReceiver) {
                final ClassDescriptor captureThis = closure.getCaptureThis();
                if (captureThis != null) {
                    generateThisOrOuter(captureThis, false).put(OBJECT_TYPE, v);
                }

                final ClassifierDescriptor captureReceiver = closure.getCaptureReceiver();
                if (captureReceiver != null) {
                    final Type asmType = typeMapper.mapType(captureReceiver.getDefaultType(), JetTypeMapperMode.IMPL);
                    v.load(context.isStatic() ? 0 : 1, asmType);
                }
            }

            for (Map.Entry<DeclarationDescriptor, EnclosedValueDescriptor> entry : closure.getCaptureVariables().entrySet()) {
                //if (entry.getKey() instanceof VariableDescriptor && !(entry.getKey() instanceof PropertyDescriptor)) {
                Type sharedVarType = typeMapper.getSharedVarType(entry.getKey());
                if (sharedVarType == null) {
                    sharedVarType = typeMapper.mapType(((VariableDescriptor) entry.getKey()).getType(), JetTypeMapperMode.VALUE);
                }
                entry.getValue().getOuterValue(this).put(sharedVarType, v);
                //}
            }
        }
    }

    private StackValue generateBlock(List<JetElement> statements) {
        final Label blockEnd = new Label();

        List<Function<StackValue, Void>> leaveTasks = Lists.newArrayList();

        StackValue answer = StackValue.none();

        for (Iterator<JetElement> iterator = statements.iterator(); iterator.hasNext(); ) {
            JetElement statement = iterator.next();

            if (statement instanceof JetNamedDeclaration) {
                JetNamedDeclaration declaration = (JetNamedDeclaration) statement;
                if (JetPsiUtil.isScriptDeclaration(declaration)) {
                    continue;
                }
            }

            if (statement instanceof JetMultiDeclaration) {
                JetMultiDeclaration multiDeclaration = (JetMultiDeclaration) statement;
                for (JetMultiDeclarationEntry entry : multiDeclaration.getEntries()) {
                    generateLocalVariableDeclaration(entry, blockEnd, leaveTasks);
                }
            }

            if (statement instanceof JetVariableDeclaration) {
                generateLocalVariableDeclaration((JetVariableDeclaration) statement, blockEnd, leaveTasks);
            }

            if (statement instanceof JetNamedFunction) {
                generateLocalFunctionDeclaration((JetNamedFunction) statement, leaveTasks);
            }

            if (!iterator.hasNext()) {
                answer = gen(statement);
            }
            else {
                gen(statement, Type.VOID_TYPE);
            }
        }

        v.mark(blockEnd);

        for (Function<StackValue, Void> task : Lists.reverse(leaveTasks)) {
            task.fun(answer);
        }

        return answer;
    }

    private void generateLocalVariableDeclaration(
            @NotNull JetVariableDeclaration variableDeclaration,
            final @NotNull Label blockEnd,
            @NotNull List<Function<StackValue, Void>> leaveTasks
    ) {
        final VariableDescriptor variableDescriptor = bindingContext.get(BindingContext.VARIABLE, variableDeclaration);
        assert variableDescriptor != null;

        final Label scopeStart = new Label();
        v.mark(scopeStart);

        final Type sharedVarType = typeMapper.getSharedVarType(variableDescriptor);
        final Type type = sharedVarType != null ? sharedVarType : asmType(variableDescriptor.getType());
        int index = myFrameMap.enter(variableDescriptor, type);

        if (sharedVarType != null) {
            v.anew(sharedVarType);
            v.dup();
            v.invokespecial(sharedVarType.getInternalName(), "<init>", "()V");
            v.store(index, OBJECT_TYPE);
        }

        leaveTasks.add(new Function<StackValue, Void>() {
            @Override
            public Void fun(StackValue answer) {
                int index = myFrameMap.leave(variableDescriptor);

                if (sharedVarType != null) {
                    if (answer instanceof StackValue.Shared && index == ((StackValue.Shared) answer).getIndex()) {
                        ((StackValue.Shared) answer).releaseOnPut();
                    }
                    else {
                        v.aconst(null);
                        v.store(index, OBJECT_TYPE);
                    }
                }
                v.visitLocalVariable(variableDescriptor.getName().getName(), type.getDescriptor(), null, scopeStart, blockEnd,
                                     index);
                return null;
            }
        });
    }

    private void generateLocalFunctionDeclaration(
            @NotNull JetNamedFunction namedFunction,
            @NotNull List<Function<StackValue, Void>> leaveTasks
    ) {
        final DeclarationDescriptor descriptor = bindingContext.get(BindingContext.DECLARATION_TO_DESCRIPTOR, namedFunction);
        myFrameMap.enter(descriptor, OBJECT_TYPE);

        leaveTasks.add(new Function<StackValue, Void>() {
            @Override
            public Void fun(StackValue value) {
                myFrameMap.leave(descriptor);
                return null;
            }
        });
    }

    private void markLineNumber(@NotNull JetElement statement) {
        final Document document = statement.getContainingFile().getViewProvider().getDocument();
        if (document != null) {
            int lineNumber = document.getLineNumber(statement.getTextRange().getStartOffset());  // 0-based
            if (lineNumber == myLastLineNumber) {
                return;
            }
            myLastLineNumber = lineNumber;

            Label label = new Label();
            v.visitLabel(label);
            v.visitLineNumber(lineNumber + 1, label);  // 1-based
        }
    }

    private void doFinallyOnReturn() {
        for (int i = blockStackElements.size() - 1; i >= 0; --i) {
            BlockStackElement stackElement = blockStackElements.get(i);
            if (stackElement instanceof FinallyBlockStackElement) {
                FinallyBlockStackElement finallyBlockStackElement = (FinallyBlockStackElement) stackElement;
                JetTryExpression jetTryExpression = finallyBlockStackElement.expression;
                blockStackElements.pop();
                //noinspection ConstantConditions
                gen(jetTryExpression.getFinallyBlock().getFinalExpression(), Type.VOID_TYPE);
                blockStackElements.push(finallyBlockStackElement);
            }
            else {
                break;
            }
        }
    }

    @Override
    public StackValue visitReturnExpression(JetReturnExpression expression, StackValue receiver) {
        final JetExpression returnedExpression = expression.getReturnedExpression();
        if (returnedExpression != null) {
            gen(returnedExpression, returnType);
            doFinallyOnReturn();
            v.areturn(returnType);
        }
        else {
            v.visitInsn(RETURN);
        }
        return StackValue.none();
    }

    public void returnExpression(JetExpression expr) {
        StackValue lastValue = gen(expr);

        if (lastValue.type != Type.VOID_TYPE) {
            lastValue.put(returnType, v);
            v.areturn(returnType);
        }
        else if (!endsWithReturn(expr)) {
            v.areturn(returnType);
        }
    }

    private static boolean endsWithReturn(JetElement bodyExpression) {
        if (bodyExpression instanceof JetBlockExpression) {
            final List<JetElement> statements = ((JetBlockExpression) bodyExpression).getStatements();
            return statements.size() > 0 && statements.get(statements.size() - 1) instanceof JetReturnExpression;
        }

        return bodyExpression instanceof JetReturnExpression;
    }

    @Override
    public StackValue visitSimpleNameExpression(JetSimpleNameExpression expression, StackValue receiver) {
        ResolvedCall<? extends CallableDescriptor> resolvedCall = bindingContext.get(BindingContext.RESOLVED_CALL, expression);

        DeclarationDescriptor descriptor;
        if (resolvedCall == null) {
            descriptor = bindingContext.get(BindingContext.REFERENCE_TARGET, expression);
        }
        else {
            if (resolvedCall instanceof VariableAsFunctionResolvedCall) {
                VariableAsFunctionResolvedCall call = (VariableAsFunctionResolvedCall) resolvedCall;
                resolvedCall = call.getVariableCall();
            }
            receiver = StackValue.receiver(resolvedCall, receiver, this, null);
            descriptor = resolvedCall.getResultingDescriptor();
        }

        //if (descriptor instanceof VariableAsFunctionDescriptor) {
        //    descriptor = ((VariableAsFunctionDescriptor) descriptor).getVariableDescriptor();
        //}

        IntrinsicMethod intrinsic = null;
        if (descriptor instanceof CallableMemberDescriptor) {
            CallableMemberDescriptor memberDescriptor = (CallableMemberDescriptor) descriptor;
            memberDescriptor = unwrapFakeOverride(memberDescriptor);

            intrinsic = state.getIntrinsics().getIntrinsic(memberDescriptor);
        }
        if (intrinsic != null) {
            final Type expectedType = expressionType(expression);
            return intrinsic.generate(this, v, expectedType, expression, Collections.<JetExpression>emptyList(), receiver, state);
        }

        assert descriptor != null;
        final DeclarationDescriptor container = descriptor.getContainingDeclaration();

        if (descriptor instanceof VariableDescriptor) {
            VariableDescriptor variableDescriptor = (VariableDescriptor) descriptor;
            ClassDescriptor objectClassDescriptor = getBindingContext().get(BindingContext.OBJECT_DECLARATION_CLASS, variableDescriptor);
            if (objectClassDescriptor != null) {
                return genObjectClassInstance(variableDescriptor, objectClassDescriptor);
            }
        }

        int index = lookupLocalIndex(descriptor);
        if (index >= 0) {
            return stackValueForLocal(descriptor, index);
        }

        if (descriptor instanceof PropertyDescriptor) {
            PropertyDescriptor propertyDescriptor = (PropertyDescriptor) descriptor;

            boolean isStatic = container instanceof NamespaceDescriptor;
            final boolean directToField =
                    expression.getReferencedNameElementType() == JetTokens.FIELD_IDENTIFIER && contextKind() != OwnerKind.TRAIT_IMPL;
            JetExpression r = getReceiverForSelector(expression);
            final boolean isSuper = r instanceof JetSuperExpression;
            propertyDescriptor = accessablePropertyDescriptor(propertyDescriptor);
            final StackValue.Property iValue =
                    intermediateValueForProperty(propertyDescriptor, directToField, isSuper ? (JetSuperExpression) r : null);
            if (!directToField && resolvedCall != null && !isSuper) {
                receiver.put(propertyDescriptor.getReceiverParameter().exists() || isStatic
                             ? receiver.type
                             : iValue.methodOwner.getAsmType(), v);
            }
            else {
                if (!isStatic) {
                    if (receiver == StackValue.none()) {
                        if (resolvedCall == null) {
                            receiver = generateThisOrOuter((ClassDescriptor) propertyDescriptor.getContainingDeclaration(), false);
                        }
                        else {
                            if (resolvedCall.getThisObject() instanceof ExtensionReceiver) {
                                receiver = generateReceiver(((ExtensionReceiver) resolvedCall.getThisObject()).getDeclarationDescriptor());
                            }
                            else {
                                receiver = generateThisOrOuter((ClassDescriptor) propertyDescriptor.getContainingDeclaration(), false);
                            }
                        }
                    }
                    JetType receiverType = bindingContext.get(BindingContext.EXPRESSION_TYPE, r);
                    receiver.put(receiverType != null && !isSuper ? asmType(receiverType) : OBJECT_TYPE, v);
                    if (receiverType != null) {
                        ClassDescriptor propReceiverDescriptor = (ClassDescriptor) propertyDescriptor.getContainingDeclaration();
                        if (!isInterface(propReceiverDescriptor) &&
                            isInterface(receiverType.getConstructor().getDeclarationDescriptor())) {
                            v.checkcast(asmType(propReceiverDescriptor.getDefaultType()));
                        }
                    }
                }
            }
            return iValue;
        }

        if (descriptor instanceof ClassDescriptor) {
            PsiElement declaration = descriptorToDeclaration(bindingContext, descriptor);
            if (declaration instanceof JetClass) {
                final ClassDescriptor descriptor1 = ((ClassDescriptor) descriptor).getClassObjectDescriptor();
                assert descriptor1 != null;
                final Type type = typeMapper.mapType(descriptor1.getDefaultType(), JetTypeMapperMode.VALUE);
                return StackValue.field(type,
                                        JvmClassName.byType(typeMapper.mapType(((ClassDescriptor) descriptor).getDefaultType(),
                                                                               JetTypeMapperMode.IMPL)),
                                        "$classobj",
                                        true);
            }
            else {
                // todo ?
                return StackValue.none();
            }
        }

        if (descriptor instanceof TypeParameterDescriptor) {
            TypeParameterDescriptor typeParameterDescriptor = (TypeParameterDescriptor) descriptor;
            v.invokevirtual("jet/TypeInfo", "getClassObject", "()Ljava/lang/Object;");
            JetType type = typeParameterDescriptor.getClassObjectType();
            assert type != null;
            v.checkcast(asmType(type));

            return StackValue.onStack(OBJECT_TYPE);
        }

        StackValue value = context.lookupInContext(descriptor, StackValue.local(0, OBJECT_TYPE), state, false);
        if (value != null) {

            if (value instanceof StackValue.Composed) {
                StackValue.Composed composed = (StackValue.Composed) value;
                composed.prefix.put(OBJECT_TYPE, v);
                value = composed.suffix;
            }

            if (value instanceof StackValue.FieldForSharedVar) {
                StackValue.FieldForSharedVar fieldForSharedVar = (StackValue.FieldForSharedVar) value;
                Type sharedType = StackValue.sharedTypeForType(value.type);
                v.visitFieldInsn(GETFIELD, fieldForSharedVar.owner.getInternalName(), fieldForSharedVar.name,
                                 sharedType.getDescriptor());
            }

            return value;
        }

        if (descriptor instanceof ValueParameterDescriptor && descriptor.getContainingDeclaration() instanceof ScriptDescriptor) {
            ScriptDescriptor scriptDescriptor = (ScriptDescriptor) descriptor.getContainingDeclaration();
            assert scriptDescriptor != null;
            JvmClassName scriptClassName = classNameForScriptDescriptor(bindingContext, scriptDescriptor);
            ValueParameterDescriptor valueParameterDescriptor = (ValueParameterDescriptor) descriptor;
            final ClassDescriptor scriptClass = bindingContext.get(CLASS_FOR_FUNCTION, scriptDescriptor);
            final StackValue script = StackValue.thisOrOuter(this, scriptClass, false);
            script.put(script.type, v);
            Type fieldType = typeMapper.mapType(valueParameterDescriptor.getType(), JetTypeMapperMode.VALUE);
            return StackValue.field(fieldType, scriptClassName, valueParameterDescriptor.getName().getIdentifier(), false);
        }

        throw new UnsupportedOperationException("don't know how to generate reference " + descriptor);
    }

    private StackValue genObjectClassInstance(VariableDescriptor variableDescriptor, ClassDescriptor objectClassDescriptor) {
        boolean isEnumEntry = DescriptorUtils.isEnumClassObject(variableDescriptor.getContainingDeclaration());
        if (isEnumEntry) {
            ClassDescriptor containing = (ClassDescriptor) variableDescriptor.getContainingDeclaration().getContainingDeclaration();
            assert containing != null;
            Type type = typeMapper.mapType(containing.getDefaultType(), JetTypeMapperMode.VALUE);
            StackValue.field(type, JvmClassName.byType(type), variableDescriptor.getName().getName(), true).put(type, v);
            return StackValue.onStack(type);
        }
        else {
            Type type = typeMapper.mapType(objectClassDescriptor.getDefaultType(), JetTypeMapperMode.VALUE);
            return StackValue.field(type, JvmClassName.byType(type), "$instance", true);
        }
    }

    private StackValue stackValueForLocal(DeclarationDescriptor descriptor, int index) {
        if (descriptor instanceof VariableDescriptor) {
            Type sharedVarType = typeMapper.getSharedVarType(descriptor);
            final JetType outType = ((VariableDescriptor) descriptor).getType();
            if (sharedVarType != null) {
                return StackValue.shared(index, asmType(outType));
            }
            else {
                return StackValue.local(index, asmType(outType));
            }
        }
        else {
            return StackValue.local(index, OBJECT_TYPE);
        }
    }

    @Override
    public boolean lookupLocal(DeclarationDescriptor descriptor) {
        return lookupLocalIndex(descriptor) != -1;
    }

    public int lookupLocalIndex(DeclarationDescriptor descriptor) {
        return myFrameMap.getIndex(descriptor);
    }

    public StackValue.Property intermediateValueForProperty(
            PropertyDescriptor propertyDescriptor,
            final boolean forceField,
            @Nullable JetSuperExpression superExpression
    ) {
        boolean isSuper = superExpression != null;

        DeclarationDescriptor containingDeclaration = propertyDescriptor.getContainingDeclaration();
        assert containingDeclaration != null;
        containingDeclaration = containingDeclaration.getOriginal();

        boolean isStatic = containingDeclaration instanceof NamespaceDescriptor;
        boolean overridesTrait = isOverrideForTrait(propertyDescriptor);
        boolean isFakeOverride = propertyDescriptor.getKind() == CallableMemberDescriptor.Kind.FAKE_OVERRIDE;
        PropertyDescriptor initialDescriptor = propertyDescriptor;
        propertyDescriptor = initialDescriptor.getOriginal();
        boolean isInsideClass = !isFakeOverride &&
                                (((containingDeclaration == null && !context.hasThisDescriptor() ||
                                   context.hasThisDescriptor() && containingDeclaration == context.getThisDescriptor()) ||
                                  (context.getParentContext() instanceof NamespaceContext) &&
                                  context.getParentContext().getContextDescriptor() == containingDeclaration)
                                 && contextKind() != OwnerKind.TRAIT_IMPL);
        Method getter = null;
        Method setter = null;
        if (!forceField) {
            //noinspection ConstantConditions
            if (isInsideClass &&
                (propertyDescriptor.getGetter() == null ||
                 propertyDescriptor.getGetter().isDefault() && propertyDescriptor.getGetter().getModality() == Modality.FINAL)) {
                getter = null;
            }
            else {
                if (isSuper) {
                    PsiElement enclosingElement = bindingContext.get(BindingContext.LABEL_TARGET, superExpression.getTargetLabel());
                    ClassDescriptor enclosed =
                            (ClassDescriptor) bindingContext.get(BindingContext.DECLARATION_TO_DESCRIPTOR, enclosingElement);
                    if (!isInterface(containingDeclaration)) {
                        if (enclosed != null && enclosed != context.getThisDescriptor()) {
                            CodegenContext c = context;
                            while (c.getContextDescriptor() != enclosed) {
                                c = c.getParentContext();
                            }
                            propertyDescriptor = (PropertyDescriptor) c.getAccessor(propertyDescriptor);
                            isSuper = false;
                        }
                    }
                }
                else {
                    propertyDescriptor = accessablePropertyDescriptor(propertyDescriptor);
                }

                getter = typeMapper.mapGetterSignature(propertyDescriptor, OwnerKind.IMPLEMENTATION).getJvmMethodSignature().getAsmMethod();

                if (propertyDescriptor.getGetter() == null) {
                    getter = null;
                }

                if (getter == null && propertyDescriptor.getReceiverParameter().exists()) {
                    throw new IllegalStateException();
                }
            }
            //noinspection ConstantConditions
            if (!propertyDescriptor.isVar() || isInsideClass &&
                                               (propertyDescriptor.getSetter() == null ||
                                                propertyDescriptor.getSetter().isDefault() &&
                                                propertyDescriptor.getSetter().getModality() == Modality.FINAL)) {
                setter = null;
            }
            else {
                JvmPropertyAccessorSignature jvmMethodSignature =
                        typeMapper.mapSetterSignature(propertyDescriptor, OwnerKind.IMPLEMENTATION);
                setter = jvmMethodSignature != null ? jvmMethodSignature.getJvmMethodSignature().getAsmMethod() : null;

                if (propertyDescriptor.getSetter() == null) {
                    setter = null;
                }

                if (setter == null && propertyDescriptor.isVar() && propertyDescriptor.getReceiverParameter().exists()) {
                    throw new IllegalStateException();
                }
            }
        }

        int invokeOpcode;

        JvmClassName owner;
        JvmClassName ownerParam;
        boolean isInterface;
        if (isInsideClass || isStatic || propertyDescriptor.getGetter() == null) {
            owner = ownerParam = typeMapper.getOwner(propertyDescriptor, contextKind());
            isInterface = overridesTrait;
            invokeOpcode = isStatic ? INVOKESTATIC :
                           overridesTrait ? INVOKEINTERFACE
                                          : INVOKEVIRTUAL;
        }
        else {
            isInterface = isInterface(containingDeclaration) || overridesTrait;
            // TODO ugly
            CallableMethod callableMethod = typeMapper.mapToCallableMethod(propertyDescriptor.getGetter(), isSuper, contextKind());
            invokeOpcode = callableMethod.getInvokeOpcode();
            owner = isFakeOverride && !overridesTrait && !isInterface(initialDescriptor.getContainingDeclaration())
                    ? JvmClassName.byType(typeMapper.mapType(
                    ((ClassDescriptor) initialDescriptor.getContainingDeclaration()).getDefaultType(), JetTypeMapperMode.IMPL))
                    : callableMethod.getOwner();
            ownerParam = callableMethod.getDefaultImplParam();
        }

        return StackValue
                .property(propertyDescriptor.getName().getName(), owner, ownerParam, asmType(propertyDescriptor.getType()), isStatic,
                          isInterface, isSuper, getter, setter, invokeOpcode);
    }

    private PropertyDescriptor accessablePropertyDescriptor(PropertyDescriptor propertyDescriptor) {
        PropertySetterDescriptor setter = propertyDescriptor.getSetter();
        if ((propertyDescriptor.getVisibility() == Visibilities.PRIVATE ||
             (setter != null && setter.getVisibility() == Visibilities.PRIVATE))
            && !DescriptorUtils.isClassObject(propertyDescriptor.getContainingDeclaration())
            && propertyDescriptor.getContainingDeclaration() instanceof ClassDescriptor) {
            if (context.getClassOrNamespaceDescriptor() != propertyDescriptor.getContainingDeclaration()) {
                DeclarationDescriptor enclosed = propertyDescriptor.getContainingDeclaration();
                if (!context.hasThisDescriptor() || enclosed != context.getThisDescriptor()) {
                    CodegenContext c = context;
                    while (c != null && c.getContextDescriptor() != enclosed) {
                        c = c.getParentContext();
                    }
                    if (c != null) {
                        propertyDescriptor = (PropertyDescriptor) c.getAccessor(propertyDescriptor);
                    }
                }
            }
        }
        return propertyDescriptor;
    }

    private static boolean isOverrideForTrait(CallableMemberDescriptor propertyDescriptor) {
        if (propertyDescriptor.getKind() == CallableMemberDescriptor.Kind.FAKE_OVERRIDE) {
            final Set<? extends CallableMemberDescriptor> overriddenDescriptors = propertyDescriptor.getOverriddenDescriptors();
            for (CallableMemberDescriptor descriptor : overriddenDescriptors) {
                if (isInterface(descriptor.getContainingDeclaration())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public StackValue visitCallExpression(JetCallExpression expression, StackValue receiver) {
        final JetExpression callee = expression.getCalleeExpression();
        assert callee != null;

        ResolvedCall<? extends CallableDescriptor> resolvedCall = bindingContext.get(BindingContext.RESOLVED_CALL, callee);
        if (resolvedCall == null) {
            throw new CompilationException("Cannot resolve: " + callee.getText(), null, expression);
        }

        DeclarationDescriptor funDescriptor = resolvedCall.getResultingDescriptor();

        if (funDescriptor instanceof ConstructorDescriptor) {
            receiver = StackValue.receiver(resolvedCall, receiver, this, null);
            return generateConstructorCall(expression, (JetSimpleNameExpression) callee, receiver);
        }
        else if (funDescriptor instanceof FunctionDescriptor) {
            Call call = bindingContext.get(CALL, expression.getCalleeExpression());
            if (resolvedCall instanceof VariableAsFunctionResolvedCall) {
                VariableAsFunctionResolvedCall variableAsFunctionResolvedCall = (VariableAsFunctionResolvedCall) resolvedCall;
                ResolvedCallWithTrace<FunctionDescriptor> functionCall = variableAsFunctionResolvedCall.getFunctionCall();
                return invokeFunction(call, receiver, functionCall);
            }
            else {
                return invokeFunction(call, receiver, resolvedCall);
            }
        }
        else {
            throw new UnsupportedOperationException("unknown type of callee descriptor: " + funDescriptor);
        }
    }

    private StackValue invokeFunction(
            Call call,
            StackValue receiver,
            ResolvedCall<? extends CallableDescriptor> resolvedCall
    ) {
        FunctionDescriptor fd = (FunctionDescriptor) resolvedCall.getResultingDescriptor();
        boolean superCall = false;
        ReceiverDescriptor explicitReceiver = call.getExplicitReceiver();
        if (explicitReceiver instanceof ExpressionReceiver) {
            final JetExpression receiverExpression = ((ExpressionReceiver) explicitReceiver).getExpression();
            if (receiverExpression instanceof JetSuperExpression) {
                superCall = true;
                receiver = StackValue.thisOrOuter(this, context.getThisDescriptor(), true);
                JetSuperExpression superExpression = (JetSuperExpression) receiverExpression;
                PsiElement enclosingElement = bindingContext.get(BindingContext.LABEL_TARGET, superExpression.getTargetLabel());
                ClassDescriptor enclosed = (ClassDescriptor) bindingContext.get(BindingContext.DECLARATION_TO_DESCRIPTOR, enclosingElement);
                if (!isInterface(fd.getContainingDeclaration())) {
                    if (enclosed == null) {
                        enclosed = (ClassDescriptor) fd.getContainingDeclaration();
                    }
                    if (enclosed != context.getThisDescriptor()) {
                        CodegenContext c = context;
                        //noinspection ConstantConditions
                        while (!(c instanceof ClassContext) ||
                               !DescriptorUtils.isSubclass(c.getThisDescriptor(), enclosed)) {
                            c = c.getParentContext();
                            assert c != null;
                        }
                        fd = unwrapFakeOverride(fd);
                        fd = (FunctionDescriptor) c.getAccessor(fd);
                        superCall = false;
                        receiver = StackValue.thisOrOuter(this, enclosed, true);
                    }
                }
            }
        }

        if (fd.getVisibility() == Visibilities.PRIVATE
            && !DescriptorUtils.isClassObject(fd.getContainingDeclaration())
            && fd.getContainingDeclaration() instanceof ClassDescriptor) {
            if (context.getClassOrNamespaceDescriptor() != fd.getContainingDeclaration()) {
                DeclarationDescriptor enclosed = fd.getContainingDeclaration();
                if (enclosed != context.getThisDescriptor()) {
                    CodegenContext c = context;
                    while (c.getContextDescriptor() != enclosed) {
                        c = c.getParentContext();
                        assert c != null;
                    }
                    fd = (FunctionDescriptor) c.getAccessor(fd);
                }
            }
        }

        Callable callable = resolveToCallable(fd, superCall);
        if (callable instanceof CallableMethod) {
            final CallableMethod callableMethod = (CallableMethod) callable;
            invokeMethodWithArguments(callableMethod, resolvedCall, call, receiver);

            final Type callReturnType = callableMethod.getSignature().getAsmMethod().getReturnType();
            return returnValueAsStackValue(fd, callReturnType);
        }
        else {
            receiver = StackValue.receiver(resolvedCall, receiver, this, null);

            IntrinsicMethod intrinsic = (IntrinsicMethod) callable;
            List<JetExpression> args = new ArrayList<JetExpression>();
            for (ValueArgument argument : call.getValueArguments()) {
                args.add(argument.getArgumentExpression());
            }
            JetType type = resolvedCall.getCandidateDescriptor().getReturnType();
            assert type != null;
            Type callType = typeMapper.mapType(type, JetTypeMapperMode.VALUE);

            Type exprType = asmTypeOrVoid(type);
            StackValue stackValue = intrinsic.generate(this, v, callType, call.getCallElement(), args, receiver, state);
            stackValue.put(exprType, v);
            return StackValue.onStack(exprType);
        }
    }

    private StackValue returnValueAsStackValue(FunctionDescriptor fd, Type callReturnType) {
        if (callReturnType != Type.VOID_TYPE) {
            JetType type = fd.getReturnType();
            assert type != null;
            final Type retType = typeMapper.mapReturnType(type);
            StackValue.onStack(callReturnType).upcast(retType, v);
            return StackValue.onStack(retType);
        }
        return StackValue.none();
    }

    Callable resolveToCallable(@NotNull FunctionDescriptor fd, boolean superCall) {
        final IntrinsicMethod intrinsic = state.getIntrinsics().getIntrinsic(fd);
        if (intrinsic != null) {
            return intrinsic;
        }

        CallableMethod callableMethod;
        //if (fd instanceof VariableAsFunctionDescriptor) {
        //    assert !superCall;
        //    callableMethod = ClosureCodegen.asCallableMethod((FunctionDescriptor) fd);
        //}
        if (isCallAsFunctionObject(fd)) {
            SimpleFunctionDescriptor invoke = createInvoke(fd);
            callableMethod = typeMapper.asCallableMethod(invoke);
        }
        else {
            callableMethod = typeMapper.mapToCallableMethod(fd, superCall, OwnerKind.IMPLEMENTATION);
        }
        return callableMethod;
    }

    private boolean isCallAsFunctionObject(FunctionDescriptor fd) {
        if (fd.getContainingDeclaration() instanceof ScriptDescriptor) {
            JetNamedFunction psi = (JetNamedFunction) descriptorToDeclaration(bindingContext, fd);
            assert psi != null;
            return !JetPsiUtil.isScriptDeclaration(psi);
        }
        else if (fd instanceof ExpressionAsFunctionDescriptor) {
            return true;
        }
        else if (fd instanceof SimpleFunctionDescriptor &&
                 (fd.getContainingDeclaration() instanceof FunctionDescriptor ||
                  fd.getContainingDeclaration() instanceof ScriptDescriptor)) {
            return true;
        }
        else {
            return false;
        }
    }

    public void invokeMethodWithArguments(CallableMethod callableMethod, JetCallElement expression, StackValue receiver) {
        JetExpression calleeExpression = expression.getCalleeExpression();
        Call call = bindingContext.get(CALL, calleeExpression);
        ResolvedCall<? extends CallableDescriptor> resolvedCall = bindingContext.get(BindingContext.RESOLVED_CALL, calleeExpression);

        assert resolvedCall != null;
        assert call != null;
        invokeMethodWithArguments(callableMethod, resolvedCall, call, receiver);
    }

    protected void invokeMethodWithArguments(
            @NotNull CallableMethod callableMethod,
            @NotNull ResolvedCall<? extends CallableDescriptor> resolvedCall,
            @NotNull Call call,
            @NotNull StackValue receiver
    ) {
        final Type calleeType = callableMethod.getGenerateCalleeType();
        if (calleeType != null) {
            assert !callableMethod.isNeedsThis();
            gen(call.getCalleeExpression(), calleeType);
        }

        if (resolvedCall instanceof VariableAsFunctionResolvedCall) {
            resolvedCall = ((VariableAsFunctionResolvedCall) resolvedCall).getFunctionCall();
        }

        if (!(resolvedCall.getResultingDescriptor() instanceof ConstructorDescriptor)) { // otherwise already
            receiver = StackValue.receiver(resolvedCall, receiver, this, callableMethod);
            receiver.put(receiver.type, v);
            if (calleeType != null) {
                StackValue.onStack(receiver.type).put(boxType(receiver.type), v);
            }
        }

        int mask = pushMethodArguments(resolvedCall, callableMethod.getValueParameterTypes());
        if (mask == 0) {
            callableMethod.invoke(v);
        }
        else {
            callableMethod.invokeWithDefault(v, mask);
        }
    }

    private void genThisAndReceiverFromResolvedCall(
            StackValue receiver,
            ResolvedCall<? extends CallableDescriptor> resolvedCall,
            CallableMethod callableMethod
    ) {
        receiver = StackValue.receiver(resolvedCall, receiver, this, callableMethod);
        receiver.put(receiver.type, v);
    }

    public void generateFromResolvedCall(@NotNull ReceiverDescriptor descriptor, @NotNull Type type) {
        if (descriptor instanceof ClassReceiver) {
            Type exprType = asmType(descriptor.getType());
            ClassReceiver classReceiver = (ClassReceiver) descriptor;
            ClassDescriptor classReceiverDeclarationDescriptor = classReceiver.getDeclarationDescriptor();
            if (DescriptorUtils.isClassObject(classReceiverDeclarationDescriptor)) {
                if (context.getContextDescriptor() instanceof FunctionDescriptor &&
                    classReceiverDeclarationDescriptor == context.getContextDescriptor().getContainingDeclaration()) {
                    v.load(0, OBJECT_TYPE);
                }
                else {
                    ClassDescriptor containingDeclaration = (ClassDescriptor) classReceiverDeclarationDescriptor.getContainingDeclaration();
                    Type classObjType = typeMapper.mapType(containingDeclaration.getDefaultType(), JetTypeMapperMode.IMPL);
                    v.getstatic(classObjType.getInternalName(), "$classobj", exprType.getDescriptor());
                }
                StackValue.onStack(exprType).put(type, v);
            }
            else {
                StackValue.thisOrOuter(this, classReceiverDeclarationDescriptor, false).put(type, v);
            }
        }
        else if (descriptor instanceof ScriptReceiver) {
            generateScript((ScriptReceiver) descriptor);
        }
        else if (descriptor instanceof ExtensionReceiver) {
            ExtensionReceiver extensionReceiver = (ExtensionReceiver) descriptor;
            generateReceiver(extensionReceiver.getDeclarationDescriptor()).put(type, v);
        }
        else if (descriptor instanceof ExpressionReceiver) {
            ExpressionReceiver expressionReceiver = (ExpressionReceiver) descriptor;
            JetExpression expr = expressionReceiver.getExpression();
            gen(expr, type);
        }
        else if (descriptor instanceof AutoCastReceiver) {
            AutoCastReceiver autoCastReceiver = (AutoCastReceiver) descriptor;
            Type intermediateType = asmType(autoCastReceiver.getType());
            generateFromResolvedCall(autoCastReceiver.getOriginal(), intermediateType);
            StackValue.onStack(intermediateType).put(type, v);
        }
        else {
            throw new UnsupportedOperationException("Unsupported receiver type: " + descriptor);
        }
    }

    @Nullable
    private static JetExpression getReceiverForSelector(PsiElement expression) {
        if (expression.getParent() instanceof JetDotQualifiedExpression && !isReceiver(expression)) {
            final JetDotQualifiedExpression parent = (JetDotQualifiedExpression) expression.getParent();
            return parent.getReceiverExpression();
        }
        return null;
    }

    private StackValue generateReceiver(DeclarationDescriptor provided) {
        if (context.getCallableDescriptorWithReceiver() == provided) {
            StackValue result = context.getReceiverExpression(typeMapper);
            return castToRequiredTypeOfInterfaceIfNeeded(result, provided, null);
        }

        StackValue result = context.lookupInContext(provided, StackValue.local(0, OBJECT_TYPE), state, false);
        return castToRequiredTypeOfInterfaceIfNeeded(result, provided, null);
    }

    private void generateScript(@NotNull ScriptReceiver receiver) {
        CodegenContext cur = context;
        StackValue result = StackValue.local(0, OBJECT_TYPE);
        while (cur != null) {
            if (cur instanceof MethodContext && !(cur instanceof ConstructorContext)) {
                cur = cur.getParentContext();
            }

            if (cur instanceof ScriptContext) {
                ScriptContext scriptContext = (ScriptContext) cur;

                JvmClassName currentScriptClassName =
                        classNameForScriptDescriptor(bindingContext,
                                                                    scriptContext.getScriptDescriptor());
                if (scriptContext.getScriptDescriptor() == receiver.getDeclarationDescriptor()) {
                    result.put(currentScriptClassName.getAsmType(), v);
                }
                else {
                    JvmClassName className =
                            classNameForScriptDescriptor(bindingContext,
                                                                        receiver.getDeclarationDescriptor());
                    String fieldName = state.getScriptCodegen().getScriptFieldName(receiver.getDeclarationDescriptor());
                    result.put(currentScriptClassName.getAsmType(), v);
                    StackValue.field(className.getAsmType(), currentScriptClassName, fieldName, false).put(className.getAsmType(), v);
                }
                return;
            }

            assert cur != null;
            result = cur.getOuterExpression(result, false);

            if (cur instanceof ConstructorContext) {
                cur = cur.getParentContext();
            }
            assert cur != null;
            cur = cur.getParentContext();
        }

        throw new UnsupportedOperationException();
    }

    public StackValue generateThisOrOuter(@NotNull final ClassDescriptor calleeContainingClass, boolean isSuper) {
        PsiElement psiElement = classDescriptorToDeclaration(bindingContext, calleeContainingClass);
        boolean isObject = psiElement instanceof JetClassOrObject && isNonLiteralObject((JetClassOrObject) psiElement);

        CodegenContext cur = context;
        Type type = asmType(calleeContainingClass.getDefaultType());
        StackValue result = StackValue.local(0, type);
        while (cur != null) {
            if (cur instanceof MethodContext && !(cur instanceof ConstructorContext)) {
                cur = cur.getParentContext();
            }

            assert cur != null;
            final ClassDescriptor thisDescriptor = cur.getThisDescriptor();
            if (!isSuper && thisDescriptor.equals(calleeContainingClass)
            || isSuper && DescriptorUtils.isSubclass(thisDescriptor, calleeContainingClass)) {
                if (!isObject || (thisDescriptor.equals(calleeContainingClass))) {
                    return castToRequiredTypeOfInterfaceIfNeeded(result, thisDescriptor, calleeContainingClass);
                }
                else {
                    v.getstatic(type.getInternalName(), "$instance", type.getDescriptor());
                }
            }

            result = cur.getOuterExpression(result, false);

            if (cur instanceof ConstructorContext) {
                cur = cur.getParentContext();
            }
            assert cur != null;
            cur = cur.getParentContext();
        }

        throw new UnsupportedOperationException();
    }

    private static boolean isReceiver(PsiElement expression) {
        final PsiElement parent = expression.getParent();
        if (parent instanceof JetQualifiedExpression) {
            final JetExpression receiverExpression = ((JetQualifiedExpression) parent).getReceiverExpression();
            return expression == receiverExpression;
        }
        return false;
    }

    private int pushMethodArguments(@NotNull ResolvedCall resolvedCall, List<Type> valueParameterTypes) {
        @SuppressWarnings("unchecked")
        List<ResolvedValueArgument> valueArguments = resolvedCall.getValueArgumentsByIndex();
        CallableDescriptor fd = resolvedCall.getResultingDescriptor();

        if (fd.getValueParameters().size() != valueArguments.size()) {
            throw new IllegalStateException();
        }

        int index = 0;
        int mask = 0;

        for (ValueParameterDescriptor valueParameterDescriptor : fd.getValueParameters()) {
            ResolvedValueArgument resolvedValueArgument = valueArguments.get(valueParameterDescriptor.getIndex());
            if (resolvedValueArgument instanceof ExpressionValueArgument) {
                ExpressionValueArgument valueArgument = (ExpressionValueArgument) resolvedValueArgument;
                //noinspection ConstantConditions
                gen(valueArgument.getValueArgument().getArgumentExpression(), valueParameterTypes.get(index));
            }
            else if (resolvedValueArgument instanceof DefaultValueArgument) {
                Type type = valueParameterTypes.get(index);
                if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
                    v.aconst(null);
                }
                else if (type.getSort() == Type.FLOAT) {
                    v.aconst(0f);
                }
                else if (type.getSort() == Type.DOUBLE) {
                    v.aconst(0d);
                }
                else if (type.getSort() == Type.LONG) {
                    v.aconst(0l);
                }
                else {
                    v.iconst(0);
                }
                mask |= (1 << index);
            }
            else if (resolvedValueArgument instanceof VarargValueArgument) {
                VarargValueArgument valueArgument = (VarargValueArgument) resolvedValueArgument;
                genVarargs(valueParameterDescriptor, valueArgument);
            }
            else {
                throw new UnsupportedOperationException();
            }
            index++;
        }
        return mask;
    }

    public void genVarargs(ValueParameterDescriptor valueParameterDescriptor, VarargValueArgument valueArgument) {
        JetType outType = valueParameterDescriptor.getType();

        Type type = asmType(outType);
        assert type.getSort() == Type.ARRAY;
        Type elementType = correctElementType(type);
        List<ValueArgument> arguments = valueArgument.getArguments();
        int size = arguments.size();

        boolean hasSpread = false;
        for (int i = 0; i != size; ++i) {
            if (arguments.get(i).getSpreadElement() != null) {
                hasSpread = true;
                break;
            }
        }

        if (hasSpread) {
            if (size == 1) {
                gen(arguments.get(0).getArgumentExpression(), type);
            }
            else {
                String owner = "jet/runtime/Intrinsics$SpreadBuilder";
                v.anew(Type.getObjectType(owner));
                v.dup();
                v.invokespecial(owner, "<init>", "()V");
                for (int i = 0; i != size; ++i) {
                    v.dup();
                    ValueArgument argument = arguments.get(i);
                    if (argument.getSpreadElement() != null) {
                        gen(argument.getArgumentExpression(), OBJECT_TYPE);
                        v.invokevirtual(owner, "addSpread", "(Ljava/lang/Object;)V");
                    }
                    else {
                        gen(argument.getArgumentExpression(), elementType);
                        v.invokevirtual(owner, "add", "(Ljava/lang/Object;)Z");
                        v.pop();
                    }
                }
                v.dup();
                v.invokevirtual(owner, "size", "()I");
                v.newarray(elementType);
                v.invokevirtual(owner, "toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;");
                v.checkcast(type);
            }
        }
        else {
            v.iconst(arguments.size());
            v.newarray(elementType);
            for (int i = 0; i != size; ++i) {
                v.dup();
                v.iconst(i);
                gen(arguments.get(i).getArgumentExpression(), elementType);
                StackValue.arrayElement(elementType, false).store(elementType, v);
            }
        }
    }

    public int pushMethodArguments(JetCallElement expression, List<Type> valueParameterTypes) {
        ResolvedCall<? extends CallableDescriptor> resolvedCall =
                bindingContext.get(BindingContext.RESOLVED_CALL, expression.getCalleeExpression());
        if (resolvedCall != null) {
            return pushMethodArguments(resolvedCall, valueParameterTypes);
        }
        else {
            List<? extends ValueArgument> args = expression.getValueArguments();
            for (int i = 0, argsSize = args.size(); i < argsSize; i++) {
                ValueArgument arg = args.get(i);
                gen(arg.getArgumentExpression(), valueParameterTypes.get(i));
            }
            return 0;
        }
    }

    @NotNull
    public Type expressionType(JetExpression expr) {
        JetType type = bindingContext.get(BindingContext.EXPRESSION_TYPE, expr);
        return asmTypeOrVoid(type);
    }

    public int indexOfLocal(JetReferenceExpression lhs) {
        final DeclarationDescriptor declarationDescriptor = bindingContext.get(BindingContext.REFERENCE_TARGET, lhs);
        if (isVarCapturedInClosure(bindingContext, declarationDescriptor)) {
            return -1;
        }
        return lookupLocalIndex(declarationDescriptor);
    }

    @Override
    public StackValue visitDotQualifiedExpression(JetDotQualifiedExpression expression, StackValue receiver) {
        return genQualified(StackValue.none(), expression.getSelectorExpression());
    }

    @Override
    public StackValue visitSafeQualifiedExpression(JetSafeQualifiedExpression expression, StackValue receiver) {
        JetExpression expr = expression.getReceiverExpression();
        JetType receiverJetType = bindingContext.get(BindingContext.EXPRESSION_TYPE, expression.getReceiverExpression());
        assert receiverJetType != null;
        Type receiverType = asmType(receiverJetType);
        gen(expr, receiverType);
        if (!receiverJetType.isNullable()) {
            StackValue propValue = genQualified(StackValue.onStack(receiverType), expression.getSelectorExpression());
            Type type = propValue.type;
            propValue.put(type, v);
            if (isPrimitive(type) && !type.equals(Type.VOID_TYPE)) {
                StackValue.valueOf(v, type);
                type = boxType(type);
            }

            return StackValue.onStack(type);
        }
        else {
            Label ifnull = new Label();
            Label end = new Label();
            v.dup();
            v.ifnull(ifnull);
            StackValue propValue = genQualified(StackValue.onStack(receiverType), expression.getSelectorExpression());
            Type type = propValue.type;
            propValue.put(type, v);
            if (isPrimitive(type) && !type.equals(Type.VOID_TYPE)) {
                StackValue.valueOf(v, type);
                type = boxType(type);
            }
            v.goTo(end);

            v.mark(ifnull);
            v.pop();
            if (!propValue.type.equals(Type.VOID_TYPE)) {
                v.aconst(null);
            }
            v.mark(end);

            return StackValue.onStack(type);
        }
    }

    @Override
    public StackValue visitBinaryExpression(JetBinaryExpression expression, StackValue receiver) {
        final IElementType opToken = expression.getOperationReference().getReferencedNameElementType();
        if (opToken == JetTokens.EQ) {
            return generateAssignmentExpression(expression);
        }
        else if (JetTokens.AUGMENTED_ASSIGNMENTS.contains(opToken)) {
            return generateAugmentedAssignment(expression);
        }
        else if (opToken == JetTokens.ANDAND) {
            return generateBooleanAnd(expression);
        }
        else if (opToken == JetTokens.OROR) {
            return generateBooleanOr(expression);
        }
        else if (opToken == JetTokens.EQEQ || opToken == JetTokens.EXCLEQ ||
                 opToken == JetTokens.EQEQEQ || opToken == JetTokens.EXCLEQEQEQ) {
            return generateEquals(expression.getLeft(), expression.getRight(), opToken);
        }
        else if (opToken == JetTokens.LT || opToken == JetTokens.LTEQ ||
                 opToken == JetTokens.GT || opToken == JetTokens.GTEQ) {
            return generateCompareOp(expression.getLeft(), expression.getRight(), opToken, expressionType(expression.getLeft()));
        }
        else if (opToken == JetTokens.ELVIS) {
            return generateElvis(expression);
        }
        else if (opToken == JetTokens.IN_KEYWORD || opToken == JetTokens.NOT_IN) {
            return generateIn(expression);
        }
        else {
            DeclarationDescriptor op = bindingContext.get(BindingContext.REFERENCE_TARGET, expression.getOperationReference());
            final Callable callable = resolveToCallable((FunctionDescriptor) op, false);
            if (callable instanceof IntrinsicMethod) {
                IntrinsicMethod intrinsic = (IntrinsicMethod) callable;
                return intrinsic.generate(this, v, expressionType(expression), expression,
                                          Arrays.asList(expression.getLeft(), expression.getRight()), receiver, state);
            }
            else {
                return invokeOperation(expression, (FunctionDescriptor) op, (CallableMethod) callable);
            }
        }
    }

    private StackValue generateIn(JetBinaryExpression expression) {
        boolean inverted = expression.getOperationReference().getReferencedNameElementType() == JetTokens.NOT_IN;
        if (isIntRangeExpr(expression.getRight())) {
            StackValue leftValue = StackValue.expression(Type.INT_TYPE, expression.getLeft(), this);
            JetBinaryExpression rangeExpression = (JetBinaryExpression) expression.getRight();
            getInIntRange(leftValue, rangeExpression, inverted);
        }
        else {
            invokeFunctionByReference(expression.getOperationReference());
            if (inverted) {
                invertBoolean();
            }
        }
        return StackValue.onStack(Type.BOOLEAN_TYPE);
    }

    private void getInIntRange(StackValue leftValue, JetBinaryExpression rangeExpression, boolean inverted) {
        v.iconst(1);
        // 1
        leftValue.put(Type.INT_TYPE, v);
        // 1 l
        v.dup2();
        // 1 l 1 l

        //noinspection ConstantConditions
        gen(rangeExpression.getLeft(), Type.INT_TYPE);
        // 1 l 1 l r
        Label lok = new Label();
        v.ificmpge(lok);
        // 1 l 1
        v.pop();
        v.iconst(0);
        v.mark(lok);
        // 1 l c
        v.dupX2();
        // c 1 l c
        v.pop();
        // c 1 l

        gen(rangeExpression.getRight(), Type.INT_TYPE);
        // c 1 l r
        Label rok = new Label();
        v.ificmple(rok);
        // c 1
        v.pop();
        v.iconst(0);
        v.mark(rok);
        // c c

        v.and(Type.INT_TYPE);
        if (inverted) {
            invertBoolean();
        }
    }

    private StackValue generateBooleanAnd(JetBinaryExpression expression) {
        gen(expression.getLeft(), Type.BOOLEAN_TYPE);
        Label ifFalse = new Label();
        v.ifeq(ifFalse);
        gen(expression.getRight(), Type.BOOLEAN_TYPE);
        Label end = new Label();
        v.goTo(end);
        v.mark(ifFalse);
        v.iconst(0);
        v.mark(end);
        return StackValue.onStack(Type.BOOLEAN_TYPE);
    }

    private StackValue generateBooleanOr(JetBinaryExpression expression) {
        gen(expression.getLeft(), Type.BOOLEAN_TYPE);
        Label ifTrue = new Label();
        v.ifne(ifTrue);
        gen(expression.getRight(), Type.BOOLEAN_TYPE);
        Label end = new Label();
        v.goTo(end);
        v.mark(ifTrue);
        v.iconst(1);
        v.mark(end);
        return StackValue.onStack(Type.BOOLEAN_TYPE);
    }

    private StackValue generateEquals(JetExpression left, JetExpression right, IElementType opToken) {
        JetType leftJetType = bindingContext.get(BindingContext.EXPRESSION_TYPE, left);
        assert leftJetType != null;
        Type leftType = asmType(leftJetType);
        JetType rightJetType = bindingContext.get(BindingContext.EXPRESSION_TYPE, right);
        assert rightJetType != null;
        Type rightType = asmType(rightJetType);
        if (leftType.equals(JET_NOTHING_TYPE)) {
            return genCmpWithNull(right, rightType, opToken);
        }

        if (rightType.equals(JET_NOTHING_TYPE)) {
            return genCmpWithNull(left, leftType, opToken);
        }

        if (isPrimitive(leftType) != isPrimitive(rightType)) {
            gen(left, leftType);
            StackValue.valueOf(v, leftType);
            leftType = boxType(leftType);
            gen(right, rightType);
            StackValue.valueOf(v, rightType);
            rightType = boxType(rightType);
        }
        else {
            gen(left, leftType);
            gen(right, rightType);
        }

        if (isPrimitive(leftType)) // both are primitive
        {
            return generateEqualsForExpressionsOnStack(opToken, leftType, rightType, false, false);
        }

        return generateEqualsForExpressionsOnStack(opToken, leftType, rightType, leftJetType.isNullable(), rightJetType.isNullable());
    }

    private StackValue genCmpWithNull(JetExpression exp, Type expType, IElementType opToken) {
        v.iconst(1);
        gen(exp, boxType(expType));
        Label ok = new Label();
        if (JetTokens.EQEQ == opToken || JetTokens.EQEQEQ == opToken) {
            v.ifnull(ok);
        }
        else {
            v.ifnonnull(ok);
        }
        v.pop();
        v.iconst(0);
        v.mark(ok);
        return StackValue.onStack(Type.BOOLEAN_TYPE);
    }

    public StackValue generateEqualsForExpressionsOnStack(
            IElementType opToken,
            Type leftType,
            Type rightType,
            boolean leftNullable,
            boolean rightNullable
    ) {
        if ((CodegenUtil.isNumberPrimitive(leftType) || leftType.getSort() == Type.BOOLEAN) && leftType == rightType) {
            return compareExpressionsOnStack(opToken, leftType);
        }
        else {
            if (opToken == JetTokens.EQEQEQ || opToken == JetTokens.EXCLEQEQEQ) {
                return StackValue.cmp(opToken, leftType);
            }
            else {
                return generateNullSafeEquals(opToken, leftNullable, rightNullable);
            }
        }
    }

    private StackValue generateNullSafeEquals(IElementType opToken, boolean leftNullable, boolean rightNullable) {
        if (!leftNullable) {
            v.invokevirtual("java/lang/Object", "equals", "(Ljava/lang/Object;)Z");
            if (opToken == JetTokens.EXCLEQ) {
                invertBoolean();
            }
        }
        else {
            if (rightNullable) {
                v.dup2();   // left right left right
                Label rightNull = new Label();
                v.ifnull(rightNull);
                Label leftNull = new Label();
                v.ifnull(leftNull);
                v.invokevirtual("java/lang/Object", "equals", "(Ljava/lang/Object;)Z");
                if (opToken == JetTokens.EXCLEQ || opToken == JetTokens.EXCLEQEQEQ) {
                    invertBoolean();
                }
                Label end = new Label();
                v.goTo(end);
                v.mark(rightNull);
                // left right left
                Label bothNull = new Label();
                v.ifnull(bothNull);
                v.mark(leftNull);
                v.pop2();
                v.iconst(opToken == JetTokens.EXCLEQ || opToken == JetTokens.EXCLEQEQEQ ? 1 : 0);
                v.goTo(end);
                v.mark(bothNull);
                v.pop2();
                v.iconst(opToken == JetTokens.EXCLEQ || opToken == JetTokens.EXCLEQEQEQ ? 0 : 1);
                v.mark(end);
            }
            else {
                v.dup2();   // left right left right
                v.pop();
                Label leftNull = new Label();
                v.ifnull(leftNull);
                v.invokevirtual("java/lang/Object", "equals", "(Ljava/lang/Object;)Z");
                if (opToken == JetTokens.EXCLEQ || opToken == JetTokens.EXCLEQEQEQ) {
                    invertBoolean();
                }
                Label end = new Label();
                v.goTo(end);
                // left right
                v.mark(leftNull);
                v.pop2();
                v.iconst(opToken == JetTokens.EXCLEQ ? 1 : 0);
                v.mark(end);
            }
        }

        return StackValue.onStack(Type.BOOLEAN_TYPE);
    }

    private StackValue generateElvis(JetBinaryExpression expression) {
        final Type exprType = expressionType(expression);
        JetType type = bindingContext.get(BindingContext.EXPRESSION_TYPE, expression.getLeft());
        assert type != null;
        final Type leftType = asmType(type);
        if (type.isNullable()) {
            gen(expression.getLeft(), leftType);
            v.dup();
            Label end = new Label();
            Label ifNull = new Label();
            v.ifnull(ifNull);
            StackValue.onStack(leftType).put(exprType, v);
            v.goTo(end);
            v.mark(ifNull);
            v.pop();
            gen(expression.getRight(), exprType);
            v.mark(end);
        }
        else {
            gen(expression.getLeft(), leftType);
            StackValue.onStack(leftType).put(exprType, v);
        }
        return StackValue.onStack(exprType);
    }

    private static boolean isClass(DeclarationDescriptor descriptor, String name) {
        if (!(descriptor instanceof ClassDescriptor)) {
            return false;
        }
        String className = descriptor.getName().getName();
        return className.equals(name);
    }

    private StackValue generateCompareOp(JetExpression left, JetExpression right, IElementType opToken, Type operandType) {
        gen(left, operandType);
        gen(right, operandType);
        return compareExpressionsOnStack(opToken, operandType);
    }

    private StackValue compareExpressionsOnStack(IElementType opToken, Type operandType) {
        if (operandType.getSort() == Type.OBJECT) {
            v.invokeinterface("java/lang/Comparable", "compareTo", "(Ljava/lang/Object;)I");
            v.iconst(0);
            operandType = Type.INT_TYPE;
        }
        return StackValue.cmp(opToken, operandType);
    }

    private StackValue generateAssignmentExpression(JetBinaryExpression expression) {
        StackValue stackValue = gen(expression.getLeft());
        gen(expression.getRight(), stackValue.type);
        stackValue.store(stackValue.type, v);
        return StackValue.none();
    }

    private StackValue generateAugmentedAssignment(JetBinaryExpression expression) {
        DeclarationDescriptor op = bindingContext.get(BindingContext.REFERENCE_TARGET, expression.getOperationReference());
        final Callable callable = resolveToCallable((FunctionDescriptor) op, false);
        final JetExpression lhs = expression.getLeft();

        //        if (lhs instanceof JetArrayAccessExpression) {
        //            JetArrayAccessExpression arrayAccessExpression = (JetArrayAccessExpression) lhs;
        //            if (arrayAccessExpression.getIndexExpressions().size() != 1) {
        //                throw new UnsupportedOperationException("Augmented assignment with multi-index");
        //            }
        //        }

        Type lhsType = expressionType(lhs);
        //noinspection ConstantConditions
        if (bindingContext.get(BindingContext.VARIABLE_REASSIGNMENT, expression)) {
            if (callable instanceof IntrinsicMethod) {
                StackValue value = gen(lhs);              // receiver
                value.dupReceiver(v);                                        // receiver receiver
                value.put(lhsType, v);                                          // receiver lhs
                final IntrinsicMethod intrinsic = (IntrinsicMethod) callable;
                //noinspection NullableProblems
                JetExpression right = expression.getRight();
                assert right != null;
                StackValue stackValue = intrinsic.generate(this, v, lhsType, expression,
                                                           Arrays.asList(right),
                                                           StackValue.onStack(lhsType), state);
                value.store(stackValue.type, v);
            }
            else {
                callAugAssignMethod(expression, (CallableMethod) callable, lhsType, true);
            }
        }
        else {
            JetType type = ((FunctionDescriptor) op).getReturnType();
            assert type != null;
            final boolean keepReturnValue = !type.equals(JetStandardClasses.getUnitType());
            callAugAssignMethod(expression, (CallableMethod) callable, lhsType, keepReturnValue);
        }

        return StackValue.none();
    }

    private void callAugAssignMethod(JetBinaryExpression expression, CallableMethod callable, Type lhsType, final boolean keepReturnValue) {
        ResolvedCall<? extends CallableDescriptor> resolvedCall =
                bindingContext.get(BindingContext.RESOLVED_CALL, expression.getOperationReference());
        assert resolvedCall != null;

        StackValue value = gen(expression.getLeft());
        if (keepReturnValue) {
            value.dupReceiver(v);
        }
        value.put(lhsType, v);
        StackValue receiver = StackValue.onStack(lhsType);

        if (!(resolvedCall.getResultingDescriptor() instanceof ConstructorDescriptor)) { // otherwise already
            receiver = StackValue.receiver(resolvedCall, receiver, this, callable);
            receiver.put(receiver.type, v);
        }

        pushMethodArguments(resolvedCall, callable.getValueParameterTypes());
        callable.invoke(v);
        if (keepReturnValue) {
            value.store(callable.getReturnType(), v);
        }
    }

    public void generateStringBuilderConstructor() {
        Type type = getType(StringBuilder.class);
        v.anew(type);
        v.dup();
        Method method = new Method("<init>", Type.VOID_TYPE, new Type[0]);
        v.invokespecial("java/lang/StringBuilder", method.getName(), method.getDescriptor());
    }

    public void invokeAppend(final JetExpression expr) {
        if (expr instanceof JetBinaryExpression) {
            final JetBinaryExpression binaryExpression = (JetBinaryExpression) expr;
            if (binaryExpression.getOperationToken() == JetTokens.PLUS) {
                JetExpression left = binaryExpression.getLeft();
                JetExpression right = binaryExpression.getRight();
                Type leftType = expressionType(left);
                Type rightType = expressionType(right);

                if (leftType.equals(JAVA_STRING_TYPE) && rightType.equals(JAVA_STRING_TYPE)) {
                    invokeAppend(left);
                    invokeAppend(right);
                    return;
                }
            }
        }
        Type exprType = expressionType(expr);
        gen(expr, exprType);
        invokeAppendMethod(exprType.getSort() == Type.ARRAY ? OBJECT_TYPE : exprType);
    }

    public void invokeAppendMethod(Type exprType) {
        Method appendDescriptor = new Method("append", getType(StringBuilder.class),
                                             new Type[] {exprType.getSort() == Type.OBJECT ? OBJECT_TYPE : exprType});
        v.invokevirtual("java/lang/StringBuilder", "append", appendDescriptor.getDescriptor());
    }

    @Nullable
    private static JetSimpleNameExpression targetLabel(JetExpression expression) {
        if (expression.getParent() instanceof JetPrefixExpression) {
            JetPrefixExpression parent = (JetPrefixExpression) expression.getParent();
            JetSimpleNameExpression operationSign = parent.getOperationReference();
            if (JetTokens.LABELS.contains(operationSign.getReferencedNameElementType())) {
                return operationSign;
            }
        }
        return null;
    }

    @Override
    public StackValue visitPrefixExpression(JetPrefixExpression expression, StackValue receiver) {
        JetSimpleNameExpression operationSign = expression.getOperationReference();
        if (JetTokens.LABELS.contains(operationSign.getReferencedNameElementType())) {
            return genQualified(receiver, expression.getBaseExpression());
        }

        DeclarationDescriptor op = bindingContext.get(BindingContext.REFERENCE_TARGET, expression.getOperationReference());
        final Callable callable = resolveToCallable((FunctionDescriptor) op, false);
        if (callable instanceof IntrinsicMethod) {
            IntrinsicMethod intrinsic = (IntrinsicMethod) callable;
            //noinspection ConstantConditions
            return intrinsic.generate(this, v, expressionType(expression), expression,
                                      Arrays.asList(expression.getBaseExpression()), receiver, state);
        }
        else {
            DeclarationDescriptor cls = op.getContainingDeclaration();
            CallableMethod callableMethod = (CallableMethod) callable;
            if (isPrimitiveNumberClassDescriptor(cls) || !(op.getName().getName().equals("inc") || op.getName().getName().equals("dec"))) {
                return invokeOperation(expression, (FunctionDescriptor) op, callableMethod);
            }
            else {
                ResolvedCall<? extends CallableDescriptor> resolvedCall =
                        bindingContext.get(BindingContext.RESOLVED_CALL, expression.getOperationReference());
                assert resolvedCall != null;

                StackValue value = gen(expression.getBaseExpression());
                value.dupReceiver(v);
                value.dupReceiver(v);

                Type type = expressionType(expression.getBaseExpression());
                value.put(type, v);
                callableMethod.invoke(v);
                value.store(callableMethod.getReturnType(), v);
                value.put(type, v);
                return StackValue.onStack(type);
            }
        }
    }

    private StackValue invokeOperation(JetOperationExpression expression, FunctionDescriptor op, CallableMethod callable) {
        int functionLocalIndex = lookupLocalIndex(op);
        if (functionLocalIndex >= 0) {
            stackValueForLocal(op, functionLocalIndex).put(getInternalClassName(op).getAsmType(), v);
        }
        ResolvedCall<? extends CallableDescriptor> resolvedCall =
                bindingContext.get(BindingContext.RESOLVED_CALL, expression.getOperationReference());
        assert resolvedCall != null;
        genThisAndReceiverFromResolvedCall(StackValue.none(), resolvedCall, callable);
        pushMethodArguments(resolvedCall, callable.getValueParameterTypes());
        callable.invoke(v);
        return returnValueAsStackValue(op, callable.getSignature().getAsmMethod().getReturnType());
    }

    @Override
    public StackValue visitPostfixExpression(JetPostfixExpression expression, StackValue receiver) {
        if (expression.getOperationReference().getReferencedNameElementType() == JetTokens.EXCLEXCL) {
            JetExpression baseExpression = expression.getBaseExpression();
            JetType type = bindingContext.get(BindingContext.EXPRESSION_TYPE, baseExpression);
            StackValue base = genQualified(receiver, baseExpression);
            if (type != null && type.isNullable()) {
                base.put(base.type, v);
                v.dup();
                Label ok = new Label();
                v.ifnonnull(ok);
                v.invokestatic("jet/runtime/Intrinsics", "throwNpe", "()V");
                v.mark(ok);
                return StackValue.onStack(base.type);
            }
            else {
                return base;
            }
        }
        DeclarationDescriptor op = bindingContext.get(BindingContext.REFERENCE_TARGET, expression.getOperationReference());
        if (op instanceof FunctionDescriptor) {
            final Type asmType = expressionType(expression);
            DeclarationDescriptor cls = op.getContainingDeclaration();
            if (op.getName().getName().equals("inc") || op.getName().getName().equals("dec")) {
                if (isPrimitiveNumberClassDescriptor(cls)) {
                    receiver.put(receiver.type, v);
                    JetExpression operand = expression.getBaseExpression();
                    if (operand instanceof JetReferenceExpression) {
                        final int index = indexOfLocal((JetReferenceExpression) operand);
                        if (index >= 0 && isIntPrimitive(asmType)) {
                            int increment = op.getName().getName().equals("inc") ? 1 : -1;
                            return StackValue.postIncrement(index, increment);
                        }
                    }
                    gen(operand, asmType);                               // old value
                    generateIncrement(op, asmType, operand, receiver);   // increment in-place
                    return StackValue.onStack(asmType);                                         // old value
                }
                else {
                    ResolvedCall<? extends CallableDescriptor> resolvedCall =
                            bindingContext.get(BindingContext.RESOLVED_CALL, expression.getOperationReference());
                    assert resolvedCall != null;

                    final Callable callable = resolveToCallable((FunctionDescriptor) op, false);

                    StackValue value = gen(expression.getBaseExpression());
                    value.dupReceiver(v);

                    Type type = expressionType(expression.getBaseExpression());
                    value.put(type, v);

                    switch (value.receiverSize()) {
                        case 0:
                            if (type.getSize() == 2) {
                                v.dup2();
                            }
                            else {
                                v.dup();
                            }
                            break;

                        case 1:
                            if (type.getSize() == 2) {
                                v.dup2X1();
                            }
                            else {
                                v.dupX1();
                            }
                            break;

                        case 2:
                            if (type.getSize() == 2) {
                                v.dup2X2();
                            }
                            else {
                                v.dupX2();
                            }
                            break;

                        case -1:
                            throw new UnsupportedOperationException();
                    }

                    CallableMethod callableMethod = (CallableMethod) callable;
                    callableMethod.invoke(v);
                    value.store(callableMethod.getReturnType(), v);
                    return StackValue.onStack(type);
                }
            }
        }
        throw new UnsupportedOperationException("Don't know how to generate this postfix expression");
    }

    private void generateIncrement(DeclarationDescriptor op, Type asmType, JetExpression operand, StackValue receiver) {
        int increment = op.getName().getName().equals("inc") ? 1 : -1;
        if (operand instanceof JetReferenceExpression) {
            final int index = indexOfLocal((JetReferenceExpression) operand);
            if (index >= 0 && isIntPrimitive(asmType)) {
                v.iinc(index, increment);
                return;
            }
        }
        StackValue value = genQualified(receiver, operand);
        value.dupReceiver(v);
        value.put(asmType, v);
        if (asmType == Type.LONG_TYPE) {
            //noinspection UnnecessaryBoxing
            v.lconst(increment);
        }
        else if (asmType == Type.FLOAT_TYPE) {
            //noinspection UnnecessaryBoxing
            v.fconst(increment);
        }
        else if (asmType == Type.DOUBLE_TYPE) {
            //noinspection UnnecessaryBoxing
            v.dconst(increment);
        }
        else {
            v.iconst(increment);
        }
        v.add(asmType);
        value.store(asmType, v);
    }

    @Override
    public StackValue visitProperty(JetProperty property, StackValue receiver) {
        final JetExpression initializer = property.getInitializer();
        if (initializer == null) {
            return StackValue.none();
        }
        initializeLocalVariable(property, new Function<VariableDescriptor, Void>() {
            @Override
            public Void fun(VariableDescriptor descriptor) {
                Type varType = asmType(descriptor.getType());
                gen(initializer, varType);
                return null;
            }
        });
        return StackValue.none();
    }

    @Override
    public StackValue visitMultiDeclaration(JetMultiDeclaration multiDeclaration, StackValue receiver) {
        JetExpression initializer = multiDeclaration.getInitializer();
        if (initializer == null) return StackValue.none();

        JetType initializerType = bindingContext.get(EXPRESSION_TYPE, initializer);
        assert initializerType != null;

        final Type initializerAsmType = asmType(initializerType);

        final TransientReceiver initializerAsReceiver = new TransientReceiver(initializerType);

        final int tempVarIndex = myFrameMap.enterTemp(initializerAsmType);

        gen(initializer, initializerAsmType);
        v.store(tempVarIndex, initializerAsmType);
        final StackValue.Local local = StackValue.local(tempVarIndex, initializerAsmType);

        for (final JetMultiDeclarationEntry variableDeclaration : multiDeclaration.getEntries()) {
            initializeLocalVariable(variableDeclaration, new Function<VariableDescriptor, Void>() {
                @Override
                public Void fun(VariableDescriptor descriptor) {
                    ResolvedCall<FunctionDescriptor> resolvedCall =
                            bindingContext.get(BindingContext.COMPONENT_RESOLVED_CALL, variableDeclaration);
                    assert resolvedCall != null : "Resolved call is null for " + variableDeclaration.getText();
                    Call call = makeFakeCall(initializerAsReceiver);
                    invokeFunction(call, local, resolvedCall);
                    return null;
                }
            });
        }

        if(initializerAsmType.getSort() == Type.OBJECT || initializerAsmType.getSort() == Type.ARRAY) {
            v.aconst(null);
            v.store(tempVarIndex, initializerAsmType);
        }
        myFrameMap.leaveTemp(initializerAsmType);

        return StackValue.none();
    }

    private void initializeLocalVariable(
            @NotNull JetVariableDeclaration variableDeclaration,
            @NotNull Function<VariableDescriptor, Void> generateInitializer
    ) {

        VariableDescriptor variableDescriptor = bindingContext.get(BindingContext.VARIABLE, variableDeclaration);

        if (JetPsiUtil.isScriptDeclaration(variableDeclaration)) {
            return;
        }
        int index = lookupLocalIndex(variableDescriptor);

        if (index < 0) {
            throw new IllegalStateException("Local variable not found for " + variableDescriptor);
        }

        Type sharedVarType = typeMapper.getSharedVarType(variableDescriptor);
        assert variableDescriptor != null;

        Type varType = asmType(variableDescriptor.getType());

        if (JetPsiUtil.isScriptDeclaration(variableDeclaration)) {
            generateInitializer.fun(variableDescriptor);
            JetScript scriptPsi = JetPsiUtil.getScript(variableDeclaration);
            assert scriptPsi != null;
            JvmClassName scriptClassName = classNameForScriptPsi(bindingContext, scriptPsi);
            v.putfield(scriptClassName.getInternalName(), variableDeclaration.getName(), varType.getDescriptor());
        }
        else if (sharedVarType == null) {
            generateInitializer.fun(variableDescriptor);
            v.store(index, varType);
        }
        else {
            v.load(index, OBJECT_TYPE);
            generateInitializer.fun(variableDescriptor);
            v.putfield(sharedVarType.getInternalName(), "ref",
                       sharedVarType == JET_SHARED_VAR_TYPE ? "Ljava/lang/Object;" : varType.getDescriptor());
        }
    }

    private StackValue generateConstructorCall(
            JetCallExpression expression,
            JetSimpleNameExpression constructorReference,
            StackValue receiver
    ) {
        DeclarationDescriptor constructorDescriptor = bindingContext.get(BindingContext.REFERENCE_TARGET, constructorReference);
        assert constructorDescriptor != null;
        final PsiElement declaration = BindingContextUtils.descriptorToDeclaration(bindingContext, constructorDescriptor);
        Type type;
        if (declaration instanceof PsiMethod) {
            type = generateJavaConstructorCall(expression);
        }
        else if (constructorDescriptor instanceof ConstructorDescriptor) {
            //noinspection ConstantConditions
            JetType expressionType = bindingContext.get(BindingContext.EXPRESSION_TYPE, expression);
            assert expressionType != null;
            type = typeMapper.mapType(expressionType, JetTypeMapperMode.VALUE);
            if (type.getSort() == Type.ARRAY) {
                generateNewArray(expression, expressionType);
            }
            else {
                v.anew(type);
                v.dup();

                final ClassDescriptor classDescriptor = ((ConstructorDescriptor) constructorDescriptor).getContainingDeclaration();

                CallableMethod method = typeMapper
                        .mapToCallableMethod((ConstructorDescriptor) constructorDescriptor,
                                             bindingContext.get(CLOSURE, classDescriptor));

                receiver.put(receiver.type, v);

                pushClosureOnStack(bindingContext.get(CLOSURE, classDescriptor), true);
                invokeMethodWithArguments(method, expression, StackValue.none());
            }
        }
        else {
            throw new UnsupportedOperationException("don't know how to generate this new expression");
        }
        return StackValue.onStack(type);
    }

    private Type generateJavaConstructorCall(JetCallExpression expression) {
        JetExpression callee = expression.getCalleeExpression();
        ResolvedCall<? extends CallableDescriptor> resolvedCall = bindingContext.get(BindingContext.RESOLVED_CALL, callee);
        if (resolvedCall == null) {
            assert callee != null;
            throw new CompilationException("Cannot resolve: " + callee.getText(), null, expression);
        }

        FunctionDescriptor descriptor = (FunctionDescriptor) resolvedCall.getResultingDescriptor();
        ClassDescriptor javaClass = (ClassDescriptor) descriptor.getContainingDeclaration();
        Type type = asmType(javaClass.getDefaultType());
        v.anew(type);
        v.dup();
        final CallableMethod callableMethod = typeMapper.mapToCallableMethod(descriptor, false, OwnerKind.IMPLEMENTATION);
        invokeMethodWithArguments(callableMethod, expression, StackValue.none());
        return type;
    }

    public void generateNewArray(JetCallExpression expression, JetType arrayType) {
        List<JetExpression> args = new ArrayList<JetExpression>();
        for (ValueArgument va : expression.getValueArguments()) {
            args.add(va.getArgumentExpression());
        }
        args.addAll(expression.getFunctionLiteralArguments());

        boolean isArray = JetStandardLibrary.getInstance().isArray(arrayType);
        if (isArray) {
            //            if (args.size() != 2 && !arrayType.getArguments().get(0).getType().isNullable()) {
            //                throw new CompilationException("array constructor of non-nullable type requires two arguments");
            //            }
        }
        else {
            if (args.size() != 1) {
                throw new CompilationException("primitive array constructor requires one argument", null, expression);
            }
        }

        if (isArray) {
            gen(args.get(0), Type.INT_TYPE);
            v.newarray(boxType(asmType(arrayType.getArguments().get(0).getType())));
        }
        else {
            Type type = typeMapper.mapType(arrayType, JetTypeMapperMode.VALUE);
            gen(args.get(0), Type.INT_TYPE);
            v.newarray(correctElementType(type));
        }

        if (args.size() == 2) {
            int sizeIndex = myFrameMap.enterTemp(Type.INT_TYPE);
            int indexIndex = myFrameMap.enterTemp(Type.INT_TYPE);

            v.dup();
            v.arraylength();
            v.store(sizeIndex, Type.INT_TYPE);

            v.iconst(0);
            v.store(indexIndex, Type.INT_TYPE);

            gen(args.get(1), JET_FUNCTION1_TYPE);

            Label begin = new Label();
            Label end = new Label();
            v.visitLabel(begin);
            v.load(indexIndex, Type.INT_TYPE);
            v.load(sizeIndex, Type.INT_TYPE);
            v.ificmpge(end);

            v.dup2();
            v.load(indexIndex, Type.INT_TYPE);
            v.invokestatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;");
            v.invokevirtual("jet/Function1", "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
            v.load(indexIndex, Type.INT_TYPE);
            v.iinc(indexIndex, 1);
            v.swap();
            v.astore(OBJECT_TYPE);

            v.goTo(begin);
            v.visitLabel(end);
            v.pop();

            myFrameMap.leaveTemp(Type.INT_TYPE);
            myFrameMap.leaveTemp(Type.INT_TYPE);
        }
    }

    @Override
    public StackValue visitArrayAccessExpression(JetArrayAccessExpression expression, StackValue receiver) {
        final JetExpression array = expression.getArrayExpression();
        JetType type = bindingContext.get(BindingContext.EXPRESSION_TYPE, array);
        final Type arrayType = asmTypeOrVoid(type);
        final List<JetExpression> indices = expression.getIndexExpressions();
        FunctionDescriptor operationDescriptor = (FunctionDescriptor) bindingContext.get(BindingContext.REFERENCE_TARGET, expression);
        assert operationDescriptor != null;
        if (arrayType.getSort() == Type.ARRAY &&
            indices.size() == 1 &&
            PrimitiveType.INT.getClassName().is(operationDescriptor.getValueParameters().get(0).getType())) {
            gen(array, arrayType);
            for (JetExpression index : indices) {
                gen(index, Type.INT_TYPE);
            }
            assert type != null;
            if (JetStandardLibrary.getInstance().isArray(type)) {
                JetType elementType = type.getArguments().get(0).getType();
                Type notBoxed = asmType(elementType);
                return StackValue.arrayElement(notBoxed, true);
            }
            else {
                return StackValue.arrayElement(correctElementType(arrayType), false);
            }
        }
        else {
            CallableMethod accessor = typeMapper.mapToCallableMethod(operationDescriptor, false, OwnerKind.IMPLEMENTATION);

            boolean isGetter = accessor.getSignature().getAsmMethod().getName().equals("get");

            ResolvedCall<FunctionDescriptor> resolvedSetCall = bindingContext.get(BindingContext.INDEXED_LVALUE_SET, expression);
            ResolvedCall<FunctionDescriptor> resolvedGetCall = bindingContext.get(BindingContext.INDEXED_LVALUE_GET, expression);

            FunctionDescriptor setterDescriptor = resolvedSetCall == null ? null : resolvedSetCall.getResultingDescriptor();
            FunctionDescriptor getterDescriptor = resolvedGetCall == null ? null : resolvedGetCall.getResultingDescriptor();

            Type asmType;
            Type[] argumentTypes = accessor.getSignature().getAsmMethod().getArgumentTypes();
            int index = 0;
            if (isGetter) {
                assert getterDescriptor != null;
                Callable callable = resolveToCallable(getterDescriptor, false);
                if (callable instanceof CallableMethod) {
                    genThisAndReceiverFromResolvedCall(receiver, resolvedGetCall, (CallableMethod) callable);
                }
                else {
                    gen(array, asmType(((ClassDescriptor) getterDescriptor.getContainingDeclaration()).getDefaultType()));
                }

                if (getterDescriptor.getReceiverParameter().exists()) {
                    index++;
                }
                asmType = accessor.getSignature().getAsmMethod().getReturnType();
            }
            else {
                assert resolvedSetCall != null;
                Callable callable = resolveToCallable(resolvedSetCall.getResultingDescriptor(), false);
                if (callable instanceof CallableMethod) {
                    genThisAndReceiverFromResolvedCall(receiver, resolvedSetCall, (CallableMethod) callable);
                }
                else {
                    gen(array, arrayType);
                }

                if (setterDescriptor.getReceiverParameter().exists()) {
                    index++;
                }
                asmType = argumentTypes[argumentTypes.length - 1];
            }

            for (JetExpression jetExpression : expression.getIndexExpressions()) {
                gen(jetExpression, argumentTypes[index]);
                index++;
            }
            return StackValue.collectionElement(asmType, resolvedGetCall, resolvedSetCall, this, state);
        }
    }

    @Override
    public StackValue visitThrowExpression(JetThrowExpression expression, StackValue receiver) {
        gen(expression.getThrownExpression(), JAVA_THROWABLE_TYPE);
        v.athrow();
        return StackValue.none();
    }

    @Override
    public StackValue visitThisExpression(JetThisExpression expression, StackValue receiver) {
        final DeclarationDescriptor descriptor = bindingContext.get(BindingContext.REFERENCE_TARGET, expression.getInstanceReference());
        if (descriptor instanceof ClassDescriptor) {
            return StackValue.thisOrOuter(this, (ClassDescriptor) descriptor, false);
        }
        else {
            if (descriptor instanceof CallableDescriptor) {
                return generateReceiver(descriptor);
            }
            throw new UnsupportedOperationException("neither this nor receiver");
        }
    }

    @Override
    public StackValue visitTryExpression(JetTryExpression expression, StackValue receiver) {
        /*
The "returned" value of try expression with no finally is either the last expression in the try block or the last expression in the catch block
(or blocks).
         */
        JetFinallySection finallyBlock = expression.getFinallyBlock();
        FinallyBlockStackElement finallyBlockStackElement = null;
        if (finallyBlock != null) {
            finallyBlockStackElement = new FinallyBlockStackElement(expression);
            blockStackElements.push(finallyBlockStackElement);
        }

        JetType jetType = bindingContext.get(BindingContext.EXPRESSION_TYPE, expression);
        assert jetType != null;
        Type expectedAsmType = asmType(jetType);

        Label tryStart = new Label();
        v.mark(tryStart);
        v.nop(); // prevent verify error on empty try

        gen(expression.getTryBlock(), expectedAsmType);

        int savedValue = myFrameMap.enterTemp(expectedAsmType);
        v.store(savedValue, expectedAsmType);

        Label tryEnd = new Label();
        v.mark(tryEnd);
        if (finallyBlock != null) {
            blockStackElements.pop();
            gen(finallyBlock.getFinalExpression(), Type.VOID_TYPE);
            blockStackElements.push(finallyBlockStackElement);
        }
        Label end = new Label();
        v.goTo(end);

        List<JetCatchClause> clauses = expression.getCatchClauses();
        for (int i = 0, size = clauses.size(); i < size; i++) {
            JetCatchClause clause = clauses.get(i);

            Label clauseStart = new Label();
            v.mark(clauseStart);

            VariableDescriptor descriptor = bindingContext.get(BindingContext.VALUE_PARAMETER, clause.getCatchParameter());
            assert descriptor != null;
            Type descriptorType = asmType(descriptor.getType());
            myFrameMap.enter(descriptor, descriptorType);
            int index = lookupLocalIndex(descriptor);
            v.store(index, descriptorType);

            gen(clause.getCatchBody(), expectedAsmType);

            v.store(savedValue, expectedAsmType);

            myFrameMap.leave(descriptor);

            if (finallyBlock != null) {
                blockStackElements.pop();
                gen(finallyBlock.getFinalExpression(), Type.VOID_TYPE);
                blockStackElements.push(finallyBlockStackElement);
            }

            if (i != size - 1 || finallyBlock != null) {
                v.goTo(end);
            }

            v.visitTryCatchBlock(tryStart, tryEnd, clauseStart, descriptorType.getInternalName());
        }

        if (finallyBlock != null) {
            Label finallyStart = new Label();
            v.mark(finallyStart);

            int savedException = myFrameMap.enterTemp(JAVA_THROWABLE_TYPE);
            v.store(savedException, JAVA_THROWABLE_TYPE);

            blockStackElements.pop();
            gen(finallyBlock.getFinalExpression(), Type.VOID_TYPE);
            blockStackElements.push(finallyBlockStackElement);

            v.load(savedException, JAVA_THROWABLE_TYPE);
            myFrameMap.leaveTemp(JAVA_THROWABLE_TYPE);

            v.athrow();

            v.visitTryCatchBlock(tryStart, tryEnd, finallyStart, null);
        }
        v.mark(end);

        v.load(savedValue, expectedAsmType);
        myFrameMap.leaveTemp(expectedAsmType);

        if (finallyBlock != null) {
            blockStackElements.pop();
        }

        return StackValue.onStack(expectedAsmType);
    }

    @Override
    public StackValue visitBinaryWithTypeRHSExpression(final JetBinaryExpressionWithTypeRHS expression, StackValue receiver) {
        JetSimpleNameExpression operationSign = expression.getOperationSign();
        IElementType opToken = operationSign.getReferencedNameElementType();
        if (opToken == JetTokens.COLON) {
            return gen(expression.getLeft());
        }
        else {
            JetTypeReference typeReference = expression.getRight();
            JetType rightType = bindingContext.get(BindingContext.TYPE, typeReference);
            assert rightType != null;
            Type rightTypeAsm = boxType(asmType(rightType));
            JetExpression left = expression.getLeft();
            JetType leftType = bindingContext.get(BindingContext.EXPRESSION_TYPE, left);
            DeclarationDescriptor descriptor = rightType.getConstructor().getDeclarationDescriptor();
            if (descriptor instanceof ClassDescriptor || descriptor instanceof TypeParameterDescriptor) {
                StackValue value = genQualified(receiver, left);
                value.put(boxType(value.type), v);
                assert leftType != null;

                if (opToken != JetTokens.AS_SAFE) {
                    if (leftType.isNullable()) {
                        if (!rightType.isNullable()) {
                            v.dup();
                            Label nonnull = new Label();
                            v.ifnonnull(nonnull);
                            throwNewException(CLASS_TYPE_CAST_EXCEPTION);
                            v.mark(nonnull);
                        }
                    }
                }
                else {
                    v.dup();
                    v.instanceOf(rightTypeAsm);
                    Label ok = new Label();
                    v.ifne(ok);
                    v.pop();
                    v.aconst(null);
                    v.mark(ok);
                }

                v.checkcast(rightTypeAsm);
                return StackValue.onStack(rightTypeAsm);
            }
            else {
                throw new UnsupportedOperationException("don't know how to handle non-class types in as/as?");
            }
        }
    }

    @Override
    public StackValue visitIsExpression(final JetIsExpression expression, StackValue receiver) {
        final StackValue match = StackValue.expression(OBJECT_TYPE, expression.getLeftHandSide(), this);
        return generateIsCheck(match, expression.getTypeRef(), expression.isNegated());
    }

    private StackValue generateExpressionMatch(
            StackValue expressionToMatch,
            JetExpression patternExpression,
            boolean expressionToMatchIsNullable
    ) {
        if (expressionToMatch != null) {
            Type subjectType = expressionToMatch.type;
            expressionToMatch.dupReceiver(v);
            expressionToMatch.put(subjectType, v);
            boolean patternIsNullable = false;
            JetType condJetType = bindingContext.get(BindingContext.EXPRESSION_TYPE, patternExpression);
            Type condType;
            if (CodegenUtil.isNumberPrimitive(subjectType) || subjectType.getSort() == Type.BOOLEAN) {
                assert condJetType != null;
                condType = asmType(condJetType);
                if (!(CodegenUtil.isNumberPrimitive(condType) || condType.getSort() == Type.BOOLEAN)) {
                    subjectType = boxType(subjectType);
                    expressionToMatch.coerce(subjectType, v);
                }
            }
            else {
                condType = OBJECT_TYPE;
                patternIsNullable = condJetType != null && condJetType.isNullable();
            }
            gen(patternExpression, condType);
            return generateEqualsForExpressionsOnStack(JetTokens.EQEQ, subjectType, condType, expressionToMatchIsNullable,
                                                       patternIsNullable);
        }
        else {
            return gen(patternExpression);
        }
    }

    private StackValue generateIsCheck(StackValue expressionToMatch, JetTypeReference typeReference, boolean negated) {
        JetType jetType = bindingContext.get(BindingContext.TYPE, typeReference);
        expressionToMatch.dupReceiver(v);
        generateInstanceOf(expressionToMatch, jetType, false);
        StackValue value = StackValue.onStack(Type.BOOLEAN_TYPE);
        return negated ? StackValue.not(value) : value;
    }

    private void generateInstanceOf(StackValue expressionToGen, JetType jetType, boolean leaveExpressionOnStack) {
        expressionToGen.put(OBJECT_TYPE, v);
        if (leaveExpressionOnStack) {
            v.dup();
        }
        Type type = boxType(asmType(jetType));
        if (jetType.isNullable()) {
            Label nope = new Label();
            Label end = new Label();

            v.dup();
            v.ifnull(nope);
            v.instanceOf(type);
            v.goTo(end);
            v.mark(nope);
            v.pop();
            v.iconst(1);
            v.mark(end);
        }
        else {
            v.instanceOf(type);
        }
    }

    @Override
    public StackValue visitWhenExpression(JetWhenExpression expression, StackValue receiver) {
        JetExpression expr = expression.getSubjectExpression();
        JetType subjectJetType = bindingContext.get(BindingContext.EXPRESSION_TYPE, expr);
        final Type subjectType = asmTypeOrVoid(subjectJetType);
        final Type resultType = expressionType(expression);
        final int subjectLocal = expr != null ? myFrameMap.enterTemp(subjectType) : -1;
        if (subjectLocal != -1) {
            gen(expr, subjectType);
            tempVariables.put(expr, StackValue.local(subjectLocal, subjectType));
            v.store(subjectLocal, subjectType);
        }

        Label end = new Label();
        boolean hasElse = false;
        for (JetWhenEntry whenEntry : expression.getEntries()) {
            if (whenEntry.isElse()) {
                hasElse = true;
                break;
            }
        }

        Label nextCondition = null;
        for (JetWhenEntry whenEntry : expression.getEntries()) {
            if (nextCondition != null) {
                v.mark(nextCondition);
            }
            nextCondition = new Label();
            FrameMap.Mark mark = myFrameMap.mark();
            Label thisEntry = new Label();
            if (!whenEntry.isElse()) {
                final JetWhenCondition[] conditions = whenEntry.getConditions();
                for (int i = 0; i < conditions.length; i++) {
                    StackValue conditionValue = generateWhenCondition(subjectType, subjectLocal,
                                                                      subjectJetType != null && subjectJetType.isNullable(),
                                                                      conditions[i], nextCondition);
                    conditionValue.condJump(nextCondition, true, v);
                    if (i < conditions.length - 1) {
                        v.goTo(thisEntry);
                        v.mark(nextCondition);
                        nextCondition = new Label();
                    }
                }
            }

            v.visitLabel(thisEntry);
            gen(whenEntry.getExpression(), resultType);
            mark.dropTo();
            if (!whenEntry.isElse()) {
                v.goTo(end);
            }
        }
        if (!hasElse && nextCondition != null) {
            v.mark(nextCondition);
            throwNewException(CLASS_NO_PATTERN_MATCHED_EXCEPTION);
        }
        v.mark(end);

        myFrameMap.leaveTemp(subjectType);
        tempVariables.remove(expr);
        return StackValue.onStack(resultType);
    }

    private StackValue generateWhenCondition(
            Type subjectType, int subjectLocal, boolean subjectIsNullable,
            JetWhenCondition condition, @Nullable Label nextEntry
    ) {
        if (condition instanceof JetWhenConditionInRange) {
            JetWhenConditionInRange conditionInRange = (JetWhenConditionInRange) condition;
            JetExpression rangeExpression = conditionInRange.getRangeExpression();
            while (rangeExpression instanceof JetParenthesizedExpression) {
                rangeExpression = ((JetParenthesizedExpression) rangeExpression).getExpression();
            }
            JetSimpleNameExpression operationReference = conditionInRange.getOperationReference();
            boolean inverted = operationReference.getReferencedNameElementType() == JetTokens.NOT_IN;
            if (isIntRangeExpr(rangeExpression)) {
                getInIntRange(new StackValue.Local(subjectLocal, subjectType), (JetBinaryExpression) rangeExpression, inverted);
            }
            else {
                //FunctionDescriptor op =
                //        (FunctionDescriptor) bindingContext.get(BindingContext.REFERENCE_TARGET, conditionInRange.getOperationReference());
                //genToJVMStack(rangeExpression);
                //new StackValue.Local(subjectLocal, subjectType).put(OBJECT_TYPE, v);
                //invokeFunctionNoParams(op, Type.BOOLEAN_TYPE, v);
                invokeFunctionByReference(operationReference);
                if (inverted) {
                    invertBoolean();
                }
            }
            return StackValue.onStack(Type.BOOLEAN_TYPE);
        }
        StackValue.Local match = subjectLocal == -1 ? null : StackValue.local(subjectLocal, subjectType);
        if (condition instanceof JetWhenConditionIsPattern) {
            JetWhenConditionIsPattern patternCondition = (JetWhenConditionIsPattern) condition;
            return generateIsCheck(match, patternCondition.getTypeRef(), patternCondition.isNegated());
        }
        else if (condition instanceof JetWhenConditionWithExpression) {
            JetExpression patternExpression = ((JetWhenConditionWithExpression) condition).getExpression();
            return generateExpressionMatch(match, patternExpression, subjectIsNullable);
        }
        else {
            throw new UnsupportedOperationException("unsupported kind of when condition");
        }
    }

    private void invokeFunctionByReference(JetSimpleNameExpression operationReference) {
        ResolvedCall<? extends CallableDescriptor> resolvedCall =
                bindingContext.get(RESOLVED_CALL, operationReference);
        Call call = bindingContext.get(CALL, operationReference);
        invokeFunction(call, StackValue.none(), resolvedCall);
    }

    private void invertBoolean() {
        v.iconst(1);
        v.xor(Type.INT_TYPE);
    }

    private boolean isIntRangeExpr(JetExpression rangeExpression) {
        if (rangeExpression instanceof JetBinaryExpression) {
            JetBinaryExpression binaryExpression = (JetBinaryExpression) rangeExpression;
            if (binaryExpression.getOperationReference().getReferencedNameElementType() == JetTokens.RANGE) {
                JetType jetType = bindingContext.get(BindingContext.EXPRESSION_TYPE, rangeExpression);
                assert jetType != null;
                final DeclarationDescriptor descriptor = jetType.getConstructor().getDeclarationDescriptor();
                return INTEGRAL_RANGES.contains(descriptor);
            }
        }
        return false;
    }

    @Override
    public StackValue visitTupleExpression(JetTupleExpression expression, StackValue receiver) {
        final List<JetExpression> entries = expression.getEntries();
        if (entries.size() > 22) {
            throw new UnsupportedOperationException("tuple too large");
        }
        if (entries.size() == 0) {
            v.visitFieldInsn(GETSTATIC, "jet/Tuple0", "INSTANCE", "Ljet/Tuple0;");
            return StackValue.onStack(JET_TUPLE0_TYPE);
        }

        final String className = "jet/Tuple" + entries.size();
        Type tupleType = Type.getObjectType(className);
        StringBuilder signature = new StringBuilder("(");
        for (int i = 0; i != entries.size(); ++i) {
            signature.append("Ljava/lang/Object;");
        }
        signature.append(")V");

        v.anew(tupleType);
        v.dup();
        for (JetExpression entry : entries) {
            gen(entry, OBJECT_TYPE);
        }
        v.invokespecial(className, "<init>", signature.toString());
        return StackValue.onStack(tupleType);
    }

    private void throwNewException(final String className) {
        v.anew(Type.getObjectType(className));
        v.dup();
        v.invokespecial(className, "<init>", "()V");
        v.athrow();
    }

    private Call makeFakeCall(ReceiverDescriptor initializerAsReceiver) {
        JetSimpleNameExpression fake = JetPsiFactory.createSimpleName(state.getProject(), "fake");
        return CallMaker.makeCall(fake, initializerAsReceiver);
    }

    @Override
    public String toString() {
        return context.getContextDescriptor().toString();
    }
}
