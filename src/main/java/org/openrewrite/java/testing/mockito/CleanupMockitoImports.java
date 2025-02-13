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
package org.openrewrite.java.testing.mockito;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;

/**
 * Orders imports and removes unused imports from classes which import symbols from the "org.mockito" package.
 */
public class CleanupMockitoImports extends Recipe {
    @Override
    public String getDisplayName() {
        return "Cleanup Mockito imports";
    }

    @Override
    public String getDescription() {
        return "Removes unused imports `org.mockito` import symbols.";
    }

    @Override
    protected TreeVisitor<?, ExecutionContext> getVisitor() {
        return new CleanupMockitoImportsVisitor();
    }

    @Nullable
    @Override
    protected TreeVisitor<?, ExecutionContext> getSingleSourceApplicableTest() {
        return new UsesType<>("org.mockito.*");
    }

    public static class CleanupMockitoImportsVisitor extends JavaIsoVisitor<ExecutionContext> {
        @Override
        public J.Import visitImport(J.Import _import, ExecutionContext executionContext) {
            if (_import.getPackageName().startsWith("org.mockito")) {
                maybeRemoveImport(_import.getPackageName() + "." + _import.getClassName());
            }
            return _import;
        }
    }
}
