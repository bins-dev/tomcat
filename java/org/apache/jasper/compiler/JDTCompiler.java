/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jasper.compiler;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.jasper.JasperException;
import org.apache.jasper.runtime.ExceptionUtils;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.ClassFile;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ICompilerRequestor;
import org.eclipse.jdt.internal.compiler.IErrorHandlingPolicy;
import org.eclipse.jdt.internal.compiler.IProblemFactory;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment;
import org.eclipse.jdt.internal.compiler.lookup.ModuleBinding;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;

/**
 * JDT class compiler. This compiler will load source dependencies from the context classloader, reducing dramatically
 * disk access during the compilation process. Based on code from Cocoon2.
 *
 * @author Remy Maucherat
 */
public class JDTCompiler extends org.apache.jasper.compiler.Compiler {

    private final Log log = LogFactory.getLog(JDTCompiler.class); // must not be static

    @Override
    protected void generateClass(Map<String,SmapStratum> smaps)
            throws FileNotFoundException, JasperException, Exception {

        long t1 = 0;
        if (log.isDebugEnabled()) {
            t1 = System.currentTimeMillis();
        }

        final String sourceFile = ctxt.getServletJavaFileName();
        final String outputDir = ctxt.getOptions().getScratchDir().getAbsolutePath();
        String packageName = ctxt.getServletPackageName();
        final String targetClassName =
                ((!packageName.isEmpty()) ? (packageName + ".") : "") + ctxt.getServletClassName();
        final ClassLoader classLoader = ctxt.getJspLoader();
        String[] fileNames = new String[] { sourceFile };
        String[] classNames = new String[] { targetClassName };
        final List<JavacErrorDetail> problemList = new ArrayList<>();

        class CompilationUnit implements ICompilationUnit {

            private final String className;
            private final String sourceFile;

            CompilationUnit(String sourceFile, String className) {
                this.className = className;
                this.sourceFile = sourceFile;
            }

            @Override
            public char[] getFileName() {
                return sourceFile.toCharArray();
            }

            @Override
            public char[] getContents() {
                char[] result = null;
                try (FileInputStream is = new FileInputStream(sourceFile);
                        InputStreamReader isr = new InputStreamReader(is, ctxt.getOptions().getJavaEncoding());
                        Reader reader = new BufferedReader(isr)) {
                    char[] chars = new char[8192];
                    StringBuilder buf = new StringBuilder();
                    int count;
                    while ((count = reader.read(chars, 0, chars.length)) > 0) {
                        buf.append(chars, 0, count);
                    }
                    result = new char[buf.length()];
                    buf.getChars(0, result.length, result, 0);
                } catch (IOException e) {
                    log.error(Localizer.getMessage("jsp.error.compilation.source", sourceFile), e);
                }
                return result;
            }

            @Override
            public char[] getMainTypeName() {
                int dot = className.lastIndexOf('.');
                if (dot > 0) {
                    return className.substring(dot + 1).toCharArray();
                }
                return className.toCharArray();
            }

            @Override
            public char[][] getPackageName() {
                StringTokenizer izer = new StringTokenizer(className, ".");
                char[][] result = new char[izer.countTokens() - 1][];
                for (int i = 0; i < result.length; i++) {
                    String tok = izer.nextToken();
                    result[i] = tok.toCharArray();
                }
                return result;
            }

            @Override
            public boolean ignoreOptionalProblems() {
                return false;
            }

            @Override
            public ModuleBinding module(LookupEnvironment environment) {
                return environment.getModule(ModuleBinding.UNNAMED);
            }

            @Override
            public char[] getModuleName() {
                return ModuleBinding.UNNAMED;
            }
        }

        final INameEnvironment env = new INameEnvironment() {

            @Override
            public NameEnvironmentAnswer findType(char[][] compoundTypeName) {
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < compoundTypeName.length; i++) {
                    if (i > 0) {
                        result.append('.');
                    }
                    result.append(compoundTypeName[i]);
                }
                return findType(result.toString());
            }

            @Override
            public NameEnvironmentAnswer findType(char[] typeName, char[][] packageName) {
                StringBuilder result = new StringBuilder();
                int i = 0;
                for (; i < packageName.length; i++) {
                    if (i > 0) {
                        result.append('.');
                    }
                    result.append(packageName[i]);
                }
                if (i > 0) {
                    result.append('.');
                }
                result.append(typeName);
                return findType(result.toString());
            }

            private NameEnvironmentAnswer findType(String className) {

                if (className.equals(targetClassName)) {
                    ICompilationUnit compilationUnit = new CompilationUnit(sourceFile, className);
                    return new NameEnvironmentAnswer(compilationUnit, null);
                }

                String resourceName = className.replace('.', '/') + ".class";

                try (InputStream is = classLoader.getResourceAsStream(resourceName)) {
                    if (is != null) {
                        byte[] classBytes;
                        byte[] buf = new byte[8192];
                        ByteArrayOutputStream baos = new ByteArrayOutputStream(buf.length);
                        int count;
                        while ((count = is.read(buf, 0, buf.length)) > 0) {
                            baos.write(buf, 0, count);
                        }
                        baos.flush();
                        classBytes = baos.toByteArray();
                        char[] fileName = className.toCharArray();
                        ClassFileReader classFileReader = new ClassFileReader(classBytes, fileName, true);
                        return new NameEnvironmentAnswer(classFileReader, null);
                    }
                } catch (IOException | ClassFormatException exc) {
                    log.error(Localizer.getMessage("jsp.error.compilation.dependent", className), exc);
                }
                return null;
            }

            private boolean isPackage(String result) {
                if (result.equals(targetClassName) || result.startsWith(targetClassName + '$')) {
                    return false;
                }
                /*
                 * This might look heavy-weight but, with only the ClassLoader API available, trying to load the
                 * resource as a class is the only reliable way found so far to differentiate between a class and a
                 * package. Other options, such as getResource(), fail for some edge cases on case insensitive file
                 * systems. As this code is only called at compile time, the performance impact is not a significant
                 * concern.
                 */
                try {
                    classLoader.loadClass(result);
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    return true;
                }
                return false;
            }

            @Override
            public boolean isPackage(char[][] parentPackageName, char[] packageName) {
                StringBuilder result = new StringBuilder();
                int i = 0;
                if (parentPackageName != null) {
                    for (; i < parentPackageName.length; i++) {
                        if (i > 0) {
                            result.append('.');
                        }
                        result.append(parentPackageName[i]);
                    }
                }

                if (Character.isUpperCase(packageName[0])) {
                    if (!isPackage(result.toString())) {
                        return false;
                    }
                }
                if (i > 0) {
                    result.append('.');
                }
                result.append(packageName);

                return isPackage(result.toString());
            }

            @Override
            public void cleanup() {
            }

        };

        final IErrorHandlingPolicy policy = DefaultErrorHandlingPolicies.proceedWithAllProblems();

        final Map<String,String> settings = new HashMap<>();
        settings.put(CompilerOptions.OPTION_LineNumberAttribute, CompilerOptions.GENERATE);
        settings.put(CompilerOptions.OPTION_SourceFileAttribute, CompilerOptions.GENERATE);
        settings.put(CompilerOptions.OPTION_ReportDeprecation, CompilerOptions.IGNORE);
        if (ctxt.getOptions().getJavaEncoding() != null) {
            settings.put(CompilerOptions.OPTION_Encoding, ctxt.getOptions().getJavaEncoding());
        }
        if (ctxt.getOptions().getClassDebugInfo()) {
            settings.put(CompilerOptions.OPTION_LocalVariableAttribute, CompilerOptions.GENERATE);
        }

        // Source JVM
        if (ctxt.getOptions().getCompilerSourceVM() != null) {
            String opt = ctxt.getOptions().getCompilerSourceVM();
            switch (opt) {
                case "1.1" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_1_1);
                case "1.2" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_1_2);
                case "1.3" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_1_3);
                case "1.4" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_1_4);
                case "1.5" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_1_5);
                case "1.6" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_1_6);
                case "1.7" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_1_7);
                case "1.8" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_1_8);

                // Version format changed from Java 9 onwards.
                // Support old format that was used in EA implementation as well
                case "9", "1.9" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_9);
                case "10" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_10);
                case "11" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_11);
                case "12" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_12);
                case "13" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_13);
                case "14" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_14);
                case "15" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_15);
                case "16" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_16);
                case "17" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_17);
                case "18" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_18);
                case "19" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_19);
                case "20" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_20);
                case "21" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_21);
                case "22" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_22);
                case "23" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_23);
                case "24" -> settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_24);
                case "25" ->
                    // Constant not available in latest ECJ version shipped with
                    // Tomcat. May be supported in a snapshot build.
                    // This is checked against the actual version below.
                    settings.put(CompilerOptions.OPTION_Source, "25");
                default -> {
                    log.warn(Localizer.getMessage("jsp.warning.unknown.sourceVM", opt));
                    settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_21);
                }
            }
        } else {
            // Default to 21
            settings.put(CompilerOptions.OPTION_Source, CompilerOptions.VERSION_21);
        }

        // Target JVM
        if (ctxt.getOptions().getCompilerTargetVM() != null) {
            String opt = ctxt.getOptions().getCompilerTargetVM();
            switch (opt) {
                case "1.1" -> settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_1_1);
                case "1.2" -> settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_1_2);
                case "1.3" -> settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_1_3);
                case "1.4" -> settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_1_4);
                case "1.5" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_1_5);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_1_5);
                }
                case "1.6" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_1_6);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_1_6);
                }
                case "1.7" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_1_7);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_1_7);
                }
                case "1.8" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_1_8);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_1_8);
                    // Version format changed from Java 9 onwards.
                    // Support old format that was used in EA implementation as well
                }
                case "9", "1.9" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_9);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_9);
                }
                case "10" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_10);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_10);
                }
                case "11" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_11);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_11);
                }
                case "12" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_12);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_12);
                }
                case "13" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_13);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_13);
                }
                case "14" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_14);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_14);
                }
                case "15" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_15);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_15);
                }
                case "16" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_16);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_16);
                }
                case "17" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_17);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_17);
                }
                case "18" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_18);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_18);
                }
                case "19" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_19);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_19);
                }
                case "20" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_20);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_20);
                }
                case "21" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_21);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_21);
                }
                case "22" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_22);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_22);
                }
                case "23" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_23);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_23);
                }
                case "24" -> {
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_24);
                    settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_24);
                }
                case "25" -> {
                    // Constant not available in latest ECJ version shipped with
                    // Tomcat. May be supported in a snapshot build.
                    // This is checked against the actual version below.
                    settings.put(CompilerOptions.OPTION_TargetPlatform, "25");
                    settings.put(CompilerOptions.OPTION_Compliance, "25");
                }
                default -> {
                    log.warn(Localizer.getMessage("jsp.warning.unknown.targetVM", opt));
                    settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_21);
                }
            }
        } else {
            // Default to 21
            settings.put(CompilerOptions.OPTION_TargetPlatform, CompilerOptions.VERSION_21);
            settings.put(CompilerOptions.OPTION_Compliance, CompilerOptions.VERSION_21);
        }

        final IProblemFactory problemFactory = new DefaultProblemFactory(Locale.getDefault());

        final ICompilerRequestor requestor = result -> {
            try {
                if (result.hasProblems()) {
                    IProblem[] problems = result.getProblems();
                    for (IProblem problem : problems) {
                        if (problem.isError()) {
                            String name = new String(problem.getOriginatingFileName());
                            try {
                                problemList.add(ErrorDispatcher.createJavacError(name, pageNodes,
                                        new StringBuilder(problem.getMessage()), problem.getSourceLineNumber(),
                                        ctxt));
                            } catch (JasperException e) {
                                log.error(Localizer.getMessage("jsp.error.compilation.jdtProblemError"), e);
                            }
                        }
                    }
                }
                if (problemList.isEmpty()) {
                    ClassFile[] classFiles = result.getClassFiles();
                    for (ClassFile classFile : classFiles) {
                        char[][] compoundName = classFile.getCompoundName();
                        StringBuilder classFileName = new StringBuilder(outputDir).append('/');
                        for (int j = 0; j < compoundName.length; j++) {
                            if (j > 0) {
                                classFileName.append('/');
                            }
                            classFileName.append(compoundName[j]);
                        }
                        byte[] bytes = classFile.getBytes();
                        classFileName.append(".class");
                        try (FileOutputStream fout = new FileOutputStream(classFileName.toString());
                                BufferedOutputStream bos = new BufferedOutputStream(fout)) {
                            bos.write(bytes);
                        }
                    }
                }
            } catch (IOException exc) {
                log.error(Localizer.getMessage("jsp.error.compilation.jdt"), exc);
            }
        };

        ICompilationUnit[] compilationUnits = new ICompilationUnit[classNames.length];
        for (int i = 0; i < compilationUnits.length; i++) {
            String className = classNames[i];
            compilationUnits[i] = new CompilationUnit(fileNames[i], className);
        }
        CompilerOptions cOptions = new CompilerOptions(settings);

        // Check source/target JDK versions as the newest versions are allowed
        // in Tomcat configuration but may not be supported by the ECJ version
        // being used.
        String requestedSource = ctxt.getOptions().getCompilerSourceVM();
        if (requestedSource != null) {
            String actualSource = CompilerOptions.versionFromJdkLevel(cOptions.sourceLevel);
            if (!requestedSource.equals(actualSource)) {
                log.warn(Localizer.getMessage("jsp.warning.unsupported.sourceVM", requestedSource, actualSource));
            }
        }
        String requestedTarget = ctxt.getOptions().getCompilerTargetVM();
        if (requestedTarget != null) {
            String actualTarget = CompilerOptions.versionFromJdkLevel(cOptions.targetJDK);
            if (!requestedTarget.equals(actualTarget)) {
                log.warn(Localizer.getMessage("jsp.warning.unsupported.targetVM", requestedTarget, actualTarget));
            }
        }

        cOptions.parseLiteralExpressionsAsConstants = true;
        Compiler compiler = new Compiler(env, policy, cOptions, requestor, problemFactory);
        compiler.compile(compilationUnits);

        if (!ctxt.keepGenerated()) {
            File javaFile = new File(ctxt.getServletJavaFileName());
            if (!javaFile.delete()) {
                throw new JasperException(Localizer.getMessage("jsp.warning.compiler.javafile.delete.fail", javaFile));
            }
        }

        if (!problemList.isEmpty()) {
            JavacErrorDetail[] jeds = problemList.toArray(new JavacErrorDetail[0]);
            errDispatcher.javacError(jeds);
        }

        if (log.isDebugEnabled()) {
            long t2 = System.currentTimeMillis();
            log.debug(Localizer.getMessage("jsp.compiled", ctxt.getServletJavaFileName(), Long.valueOf(t2 - t1)));
        }

        if (ctxt.isPrototypeMode()) {
            return;
        }

        // JSR45 Support
        if (!options.isSmapSuppressed()) {
            SmapUtil.installSmap(smaps);
        }
    }
}
