/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.testing.junit5;

import org.openrewrite.*;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.marker.Markers;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Converts Pragmatists JUnitParamsRunner tests to their JUnit 5 ParameterizedTest and associated MethodSource equivalent
 *     https://github.com/Pragmatists/JUnitParams
 *
 * Supports the following conversions
 *    `@Parameters` annotation with out arguments and default `parametersFor...` init-method exists
 *    `@Parameters(method = "...")` annotation with defined method references
 *    `@Parameters(named = "...")` and associated `@NamedParameter` init-method
 * Unsupported tests are identified with a comment on the associated `@Parameters(...)` annotation.
 */
public class JUnitParamsRunnerToParameterized extends Recipe {

    private static final AnnotationMatcher RUN_WITH_JUNIT_PARAMS_ANNOTATION_MATCHER = new AnnotationMatcher("@org.junit.runner.RunWith(junitparams.JUnitParamsRunner.class)");
    private static final AnnotationMatcher JUNIT_TEST_ANNOTATION_MATCHER = new AnnotationMatcher("@org.junit.Test");
    private static final AnnotationMatcher JUPITER_TEST_ANNOTATION_MATCHER = new AnnotationMatcher("@org.junit.jupiter.api.Test");

    private static final AnnotationMatcher PARAMETERS_MATCHER = new AnnotationMatcher("@junitparams.Parameters");
    private static final AnnotationMatcher TEST_CASE_NAME_MATCHER = new AnnotationMatcher("@junitparams.naming.TestCaseName");
    private static final AnnotationMatcher NAMED_PARAMETERS_MATCHER = new AnnotationMatcher("@junitparams.NamedParameters");

    private static final String INIT_METHOD_REFERENCES = "init-method-references";
    private static final String PARAMETERS_FOR_PREFIX = "parametersFor";
    private static final String INIT_METHODS_MAP = "named-parameters-map";
    private static final String CONVERSION_NOT_SUPPORTED = "conversion-not-supported";

    private static final ThreadLocal<JavaParser> PARAMETERIZED_TEMPLATE_PARSER = ThreadLocal.withInitial(() ->
            JavaParser.fromJavaVersion().dependsOn(Parser.Input.fromResource("/META-INF/rewrite/Parameterized.java", "---")).build()
    );

    @Override
    public String getDisplayName() {
        return "Pragmatists @RunWith(JUnitParamsRunner.class) to JUnit Jupiter Parameterized Tests";
    }

    @Override
    public String getDescription() {
        return "Convert Pragmatists Parameterized test to the JUnit Jupiter ParameterizedTest equivalent.";
    }

    private static String junitParamsDefaultInitMethodName(String methodName) {
        return PARAMETERS_FOR_PREFIX + methodName.substring(0, 1).toUpperCase() + methodName.substring(1);
    }

    @Override
    protected TreeVisitor<?, ExecutionContext> getSingleSourceApplicableTest() {
        return new UsesType<>("junitparams.*");
    }

    @Override
    protected TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext executionContext) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, executionContext);
                Set<String> initMethods = getCursor().getMessage(INIT_METHOD_REFERENCES);
                if (initMethods != null && !initMethods.isEmpty()) {
                    doAfterVisit(new ParametersNoArgsImplicitMethodSource(initMethods,
                            getCursor().computeMessageIfAbsent(INIT_METHODS_MAP, v -> new HashMap<>()),
                            getCursor().computeMessageIfAbsent(CONVERSION_NOT_SUPPORTED, v -> new HashSet<>())));
                }
                return cd;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext executionContext) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, executionContext);
                Cursor classDeclCursor = getCursor().dropParentUntil(J.ClassDeclaration.class::isInstance);
                // methods having names starting with parametersFor... are init methods
                if (m.getSimpleName().startsWith(PARAMETERS_FOR_PREFIX)) {
                    ((Set<String>) classDeclCursor.computeMessageIfAbsent(INIT_METHOD_REFERENCES, v -> new HashSet<>())).add(m.getSimpleName());
                }
                return m;
            }

            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext executionContext) {
                J.Annotation anno = super.visitAnnotation(annotation, executionContext);
                Cursor classDeclCursor = getCursor().dropParentUntil(J.ClassDeclaration.class::isInstance);
                if (PARAMETERS_MATCHER.matches(anno)) {
                    String annotationArgumentValue = getAnnotationArgumentForInitMethod(anno, "method", "named");
                    if (annotationArgumentValue != null) {
                        for (String method : annotationArgumentValue.split(",")) {
                            ((Set<String>) classDeclCursor.computeMessageIfAbsent(INIT_METHOD_REFERENCES, v -> new HashSet<>())).add(method);
                        }
                    } else if (anno.getArguments() != null && !anno.getArguments().isEmpty()) {
                        // This conversion is not supported add a comment to the annotation and the method name to the not supported list
                        String comment = " JunitParamsRunnerToParameterized conversion not supported";
                        if (anno.getComments().stream().noneMatch(c -> c.getText().equals(comment))) {
                            anno = anno.withComments(ListUtils.concat(anno.getComments(), new Comment(Comment.Style.LINE, comment,
                                    "\n" + anno.getPrefix().getIndent(), Markers.EMPTY)));
                        }
                        J.MethodDeclaration m = getCursor().dropParentUntil(J.MethodDeclaration.class::isInstance).getValue();
                        Set<String> unsupportedMethods = classDeclCursor.computeMessageIfAbsent(CONVERSION_NOT_SUPPORTED, v -> new HashSet<>());
                        unsupportedMethods.add(m.getSimpleName());
                        unsupportedMethods.add(junitParamsDefaultInitMethodName(m.getSimpleName()));
                    }
                } else if (NAMED_PARAMETERS_MATCHER.matches(annotation)) {
                    String namedInitMethod = getLiteralAnnotationArgumentValue(annotation);
                    if (namedInitMethod != null) {
                        J.MethodDeclaration m = getCursor().dropParentUntil(J.MethodDeclaration.class::isInstance).getValue();
                        ((Set<String>) classDeclCursor.computeMessageIfAbsent(INIT_METHOD_REFERENCES, v -> new HashSet<>())).add(m.getSimpleName());
                        classDeclCursor.computeMessageIfAbsent(INIT_METHODS_MAP, v -> new HashMap<>()).put(namedInitMethod, m.getSimpleName());
                    }
                } else if (TEST_CASE_NAME_MATCHER.matches(anno)) {
                    // test name for ParameterizedTest argument
                    Object testNameArg = getLiteralAnnotationArgumentValue(anno);
                    String testName = testNameArg != null ? testNameArg.toString() : "{method}({params}) [{index}]";
                    J.MethodDeclaration md = getCursor().dropParentUntil(J.MethodDeclaration.class::isInstance).getValue();
                    classDeclCursor.computeMessageIfAbsent(INIT_METHODS_MAP, v -> new HashMap<>()).put(md.getSimpleName(), testName);
                }
                return anno;
            }

            @Nullable
            private String getLiteralAnnotationArgumentValue(J.Annotation anno) {
                String annotationArgumentValue = null;
                if (anno.getArguments() != null && anno.getArguments().size() == 1 && anno.getArguments().get(0) instanceof J.Literal) {
                    J.Literal literal = (J.Literal) anno.getArguments().get(0);
                    annotationArgumentValue = literal.getValue() != null ? literal.getValue().toString() : null;
                }
                return annotationArgumentValue;
            }

            @Nullable
            private String getAnnotationArgumentForInitMethod(J.Annotation anno, String... variableNames) {
                String value = null;
                if (anno.getArguments() != null && anno.getArguments().size() == 1
                        && anno.getArguments().get(0) instanceof J.Assignment
                        && ((J.Assignment) anno.getArguments().get(0)).getVariable() instanceof J.Identifier
                        && ((J.Assignment) anno.getArguments().get(0)).getAssignment() instanceof J.Literal) {
                    J.Assignment annoArg = (J.Assignment) anno.getArguments().get(0);
                    J.Literal assignment = (J.Literal) annoArg.getAssignment();
                    String identifier = ((J.Identifier) annoArg.getVariable()).getSimpleName();
                    for (String variableName : variableNames) {
                        if (variableName.equals(identifier)) {
                            value = assignment.getValue() != null ? assignment.getValue().toString() : null;
                            break;
                        }
                    }
                }
                return value;
            }
        };
    }

    /***
     * Case 1.
     * - Test has Parameters annotation with out arguments and initMethods has match
     * case 2.
     * - Test has Parameters(method = "...") annotation with defined method source
     * case 3.
     * - Test has Parameters(named = "...") and NamedParameters annotation
     */
    private static class ParametersNoArgsImplicitMethodSource extends JavaIsoVisitor<ExecutionContext> {

        private final Set<String> initMethods;
        private final Set<String> unsupportedConversions;
        private final Map<String, String> initMethodReferences;

        private final JavaTemplate parameterizedTestTemplate;
        private final JavaTemplate parameterizedTestTemplateWithName;
        private final JavaTemplate methodSourceTemplate;

        public ParametersNoArgsImplicitMethodSource(Set<String> initMethods, Map<String, String> initMethodReferences, Set<String> unsupportedConversions) {
            this.initMethods = initMethods;
            this.initMethodReferences = initMethodReferences;
            this.unsupportedConversions = unsupportedConversions;

            // build @ParameterizedTest template
            this.parameterizedTestTemplate = JavaTemplate.builder(this::getCursor, "@ParameterizedTest")
                    .javaParser(PARAMETERIZED_TEMPLATE_PARSER::get)
                    .imports("org.junit.jupiter.params.ParameterizedTest").build();
            // build @ParameterizedTest(#{}) template
            this.parameterizedTestTemplateWithName = JavaTemplate.builder(this::getCursor, "@ParameterizedTest(name = \"#{}\")")
                    .javaParser(PARAMETERIZED_TEMPLATE_PARSER::get)
                    .imports("org.junit.jupiter.params.ParameterizedTest").build();
            // build @MethodSource("...") template
            this.methodSourceTemplate = JavaTemplate.builder(this::getCursor, "@MethodSource(#{})")
                    .javaParser(PARAMETERIZED_TEMPLATE_PARSER::get)
                    .imports("org.junit.jupiter.params.provider.MethodSource").build();
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext executionContext) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, executionContext);
            // Remove @RunWith(JUnitParamsRunner.class) annotation
            doAfterVisit(new RemoveAnnotationVisitor(RUN_WITH_JUNIT_PARAMS_ANNOTATION_MATCHER));

            // Update Imports
            maybeRemoveImport("org.junit.Test");
            maybeRemoveImport("org.junit.jupiter.api.Test");
            maybeRemoveImport("org.junit.runner.RunWith");
            maybeRemoveImport("junitparams.JUnitParamsRunner");
            maybeRemoveImport("junitparams.Parameters");
            maybeRemoveImport("junitparams.NamedParameters");
            maybeRemoveImport("junitparams.naming.TestCaseName");
            maybeAddImport("org.junit.jupiter.params.ParameterizedTest");
            maybeAddImport("org.junit.jupiter.params.provider.MethodSource");
            return cd;
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext executionContext) {
            if (unsupportedConversions.contains(method.getSimpleName())) {
                return method;
            }
            J.MethodDeclaration m = super.visitMethodDeclaration(method, executionContext);
            final String paramTestName = initMethodReferences.get(m.getSimpleName());

            m = m.withLeadingAnnotations(ListUtils.map(m.getLeadingAnnotations(), anno -> {
                if (TEST_CASE_NAME_MATCHER.matches(anno) || NAMED_PARAMETERS_MATCHER.matches(anno)) {
                    return null;
                }
                anno = maybeReplaceTestAnnotation(anno, paramTestName);
                anno = maybeReplaceParametersAnnotation(anno, method.getSimpleName());
                return anno;
            }));

            // If the method is an init-method then add a static modifier if necessary
            if (initMethods.contains(m.getSimpleName()) || initMethodReferences.containsValue(m.getSimpleName())) {
                if (m.getModifiers().stream().noneMatch(it -> J.Modifier.Type.Static.equals(it.getType()))) {
                    J.Modifier staticModifier = new J.Modifier(UUID.randomUUID(), Space.format(" "), Markers.EMPTY, J.Modifier.Type.Static, new ArrayList<>());
                    m = maybeAutoFormat(m, m.withModifiers(ListUtils.concat(m.getModifiers(), staticModifier)), executionContext, getCursor().dropParentUntil(J.class::isInstance));
                }
            }
            return m;
        }

        private J.Annotation maybeReplaceTestAnnotation(J.Annotation anno, @Nullable String parameterizedTestArgument) {
            if (JUPITER_TEST_ANNOTATION_MATCHER.matches(anno) || JUNIT_TEST_ANNOTATION_MATCHER.matches(anno)) {
                if (parameterizedTestArgument == null) {
                    anno = anno.withTemplate(parameterizedTestTemplate, anno.getCoordinates().replace());
                } else {
                    anno = anno.withTemplate(parameterizedTestTemplateWithName, anno.getCoordinates().replace(), parameterizedTestArgument);
                }
            }
            return anno;
        }

        private J.Annotation maybeReplaceParametersAnnotation(J.Annotation annotation, String methodName) {
            if (PARAMETERS_MATCHER.matches(annotation)) {
                String initMethodName = junitParamsDefaultInitMethodName(methodName);
                if (initMethods.contains(initMethodName)) {
                    annotation = annotation.withTemplate(methodSourceTemplate, annotation.getCoordinates().replace(), "\"" + initMethodName + "\"");
                } else {
                    String annotationArg = getAnnotationArgumentValueForMethodTemplate(annotation);
                    if (annotationArg != null) {
                        annotation = annotation.withTemplate(methodSourceTemplate, annotation.getCoordinates().replace(), annotationArg);
                    }
                }
            }
            return annotation;
        }

        @Nullable
        private String getAnnotationArgumentValueForMethodTemplate(J.Annotation anno) {
            String annotationArgumentValue = null;
            if (anno.getArguments() != null && anno.getArguments().size() == 1) {
                Expression annoArg = anno.getArguments().get(0);
                if (annoArg instanceof J.Literal) {
                    annotationArgumentValue = (String) ((J.Literal) annoArg).getValue();
                } else if (annoArg instanceof J.Assignment && (((J.Assignment) annoArg).getAssignment()) instanceof J.Literal) {
                    annotationArgumentValue = (String) ((J.Literal) ((J.Assignment) annoArg).getAssignment()).getValue();
                }
            }
            if (initMethodReferences.containsKey(annotationArgumentValue)) {
                annotationArgumentValue = initMethodReferences.get(annotationArgumentValue);
            }

            if (annotationArgumentValue != null) {
                String[] methodRefs = annotationArgumentValue.split(",");
                if (methodRefs.length > 1) {
                    String methods = Arrays.stream(methodRefs).map(mref -> mref = "\"" + mref + "\"").collect(Collectors.joining(", "));
                    annotationArgumentValue = "{" + methods + "}";
                } else {
                    annotationArgumentValue = "\"" + annotationArgumentValue + "\"";
                }
            }
            return annotationArgumentValue;
        }

    }
}
