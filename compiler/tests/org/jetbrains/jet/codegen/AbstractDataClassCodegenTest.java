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

import org.jetbrains.jet.ConfigurationKind;
import org.jetbrains.jet.test.generator.SimpleTestClassModel;
import org.jetbrains.jet.test.generator.TestGenerator;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * @author udalov
 */
public abstract class AbstractDataClassCodegenTest extends CodegenTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        createEnvironmentWithMockJdkAndIdeaAnnotations(ConfigurationKind.JDK_ONLY);
    }

    public static void main(String[] args) throws IOException {
        Class<AbstractDataClassCodegenTest> thisClass = AbstractDataClassCodegenTest.class;
        String aPackage = thisClass.getPackage().getName();
        new TestGenerator(
                "compiler/tests/",
                aPackage,
                "DataClassCodegenTestGenerated",
                thisClass,
                Arrays.asList(
                        new SimpleTestClassModel(new File("compiler/testData/codegen/dataClasses"), true, "kt", "blackBoxFileByFullPath")
                ),
                thisClass
        ).generateAndSave();
    }
}
