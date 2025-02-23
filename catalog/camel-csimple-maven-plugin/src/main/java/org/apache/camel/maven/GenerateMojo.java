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
package org.apache.camel.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.camel.RuntimeCamelException;
import org.apache.camel.catalog.common.CatalogHelper;
import org.apache.camel.language.csimple.CSimpleCodeGenerator;
import org.apache.camel.language.csimple.CSimpleGeneratedCode;
import org.apache.camel.parser.RouteBuilderParser;
import org.apache.camel.parser.XmlRouteParser;
import org.apache.camel.parser.model.CamelCSimpleExpressionDetails;
import org.apache.camel.tooling.util.FileUtil;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.StringHelper;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.mojo.exec.AbstractExecMojo;
import org.jboss.forge.roaster.Roaster;
import org.jboss.forge.roaster.model.JavaType;
import org.jboss.forge.roaster.model.source.JavaClassSource;

import static org.apache.camel.catalog.common.CatalogHelper.findJavaRouteBuilderClasses;
import static org.apache.camel.catalog.common.CatalogHelper.findXmlRouters;

/**
 * Parses the source code and generates source code for the csimple language.
 */
@Mojo(name = "generate", threadSafe = true, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
      defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class GenerateMojo extends AbstractExecMojo {

    public static final String GENERATED_MSG = "Generated by camel build tools - do NOT edit this file!";
    public static final String RESOURCE_FILE = "META-INF/services/org/apache/camel/csimple.properties";

    /**
     * The maven project.
     */
    @Parameter(property = "project", required = true, readonly = true)
    protected MavenProject project;

    // Output directory

    /**
     * The output directory for generated source files
     */
    @Parameter(defaultValue = "${project.basedir}/src/generated/java")
    protected File outputDir;

    /**
     * The output directory for generated resources files
     */
    @Parameter(defaultValue = "${project.basedir}/src/generated/resources")
    protected File outputResourceDir;

    /**
     * The resources directory for configuration files
     */
    @Parameter(defaultValue = "${project.basedir}/src/main/resources")
    protected File resourceDir;

    /**
     * Whether to include Java files to be validated for invalid Camel endpoints
     */
    @Parameter(property = "camel.includeJava", defaultValue = "true")
    private boolean includeJava;

    /**
     * Whether to include XML files to be validated for invalid Camel endpoints
     */
    @Parameter(property = "camel.includeXml", defaultValue = "true")
    private boolean includeXml;

    /**
     * Whether to include test source code
     */
    @Parameter(property = "camel.includeTest", defaultValue = "false")
    private boolean includeTest;

    /**
     * To filter the names of java and xml files to only include files matching any of the given list of patterns
     * (wildcard and regular expression). Multiple values can be separated by comma.
     */
    @Parameter(property = "camel.includes")
    private String includes;

    /**
     * To filter the names of java and xml files to exclude files matching any of the given list of patterns (wildcard
     * and regular expression). Multiple values can be separated by comma.
     */
    @Parameter(property = "camel.excludes")
    private String excludes;

    private final Set<String> imports = new TreeSet<>();
    private final Map<String, String> aliases = new HashMap<>();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        loadConfiguration();

        CSimpleCodeGenerator generator = new CSimpleCodeGenerator();
        generator.setAliases(aliases);
        generator.setImports(imports);

        doExecuteRoutes(generator);
    }

    protected void doExecuteRoutes(CSimpleCodeGenerator generator) {
        List<CamelCSimpleExpressionDetails> csimpleExpressions = new ArrayList<>();
        Set<File> javaFiles = new LinkedHashSet<>();
        Set<File> xmlFiles = new LinkedHashSet<>();

        // find all java route builder classes
        findJavaRouteBuilderClasses(javaFiles, includeJava, includeTest, project);

        // find all xml routes
        findXmlRouters(xmlFiles, includeXml, includeTest, project);

        for (File file : javaFiles) {
            addJavaFiles(file, csimpleExpressions);
        }
        for (File file : xmlFiles) {
            addXmlFiles(file, csimpleExpressions);
        }

        if (!csimpleExpressions.isEmpty()) {
            getLog().info("Discovered " + csimpleExpressions.size() + " csimple expressions");

            final List<CSimpleGeneratedCode> classes = new ArrayList<>();

            for (CamelCSimpleExpressionDetails cs : csimpleExpressions) {
                doGenerate(generator, cs, classes);
            }
            if (!classes.isEmpty()) {
                // generate .properties file
                generatePropertiesFile(classes);
            }
        }

    }

    private void generatePropertiesFile(List<CSimpleGeneratedCode> classes) {
        StringWriter w = new StringWriter();
        w.append("# " + GENERATED_MSG + "\n");
        classes.forEach(c -> w.write(c.getFqn() + "\n"));
        String fileName = RESOURCE_FILE;
        outputResourceDir.mkdirs();
        boolean saved = updateResource(outputResourceDir.toPath().resolve(fileName), w.toString());
        if (saved) {
            getLog().info("Generated csimple resource file: " + fileName);
        }
    }

    private void doGenerate(
            CSimpleCodeGenerator generator, CamelCSimpleExpressionDetails cs, List<CSimpleGeneratedCode> classes) {
        String script = cs.getCsimple();
        String fqn = cs.getClassName();
        if (script != null && fqn == null) {
            // its from XML file so use a pseduo fqn name instead
            fqn = "org.apache.camel.language.csimple.XmlRouteBuilder";
        }
        if (script != null) {
            CSimpleGeneratedCode code;
            if (cs.isPredicate()) {
                code = generator.generatePredicate(fqn, script);
            } else {
                code = generator.generateExpression(fqn, script);
            }
            classes.add(code);
            if (getLog().isDebugEnabled()) {
                getLog().debug("Generated source code:\n\n\n" + code.getCode() + "\n\n\n");
            }
            String fileName = code.getFqn().replace('.', '/') + ".java";
            outputDir.mkdirs();
            boolean saved = updateResource(outputDir.toPath().resolve(fileName), code.getCode());
            if (saved) {
                getLog().info("Generated csimple source code file: " + fileName);
            }
        }
    }

    private void addXmlFiles(File file, List<CamelCSimpleExpressionDetails> csimpleExpressions) {
        if (matchRouteFile(file)) {
            try {
                List<CamelCSimpleExpressionDetails> fileSimpleExpressions = new ArrayList<>();
                // parse the xml source code and find Camel routes
                String fqn = file.getPath();
                String baseDir = ".";
                InputStream is = new FileInputStream(file);
                XmlRouteParser.parseXmlRouteCSimpleExpressions(is, baseDir, fqn, fileSimpleExpressions);
                is.close();
                csimpleExpressions.addAll(fileSimpleExpressions);
            } catch (Exception e) {
                getLog().warn("Error parsing xml file " + file + " code due " + e.getMessage(), e);
            }
        }
    }

    private void addJavaFiles(File file, List<CamelCSimpleExpressionDetails> csimpleExpressions) {
        if (matchRouteFile(file)) {
            try {
                List<CamelCSimpleExpressionDetails> fileCSimpleExpressions = new ArrayList<>();

                // parse the java source code and find Camel RouteBuilder classes
                String fqn = file.getPath();
                String baseDir = ".";
                JavaType<?> out = Roaster.parse(file);
                // we should only parse java classes (not interfaces and enums etc)
                if (out instanceof JavaClassSource clazz) {
                    RouteBuilderParser.parseRouteBuilderCSimpleExpressions(clazz, baseDir, fqn, fileCSimpleExpressions);
                    csimpleExpressions.addAll(fileCSimpleExpressions);
                }
            } catch (Exception e) {
                getLog().warn("Error parsing java file " + file + " code due " + e.getMessage(), e);
            }
        }
    }

    private void loadConfiguration() {
        String configFile = resourceDir.getPath() + "/camel-csimple.properties";

        final String loaded = load(configFile);
        if (loaded == null) {
            return;
        }

        int counter1 = 0;
        int counter2 = 0;
        String[] lines = loaded.split("\n");
        for (String line : lines) {
            line = line.trim();
            // skip comments
            if (line.startsWith("#")) {
                continue;
            }
            // imports
            if (line.startsWith("import ")) {
                imports.add(line);
                counter1++;
                continue;
            }
            // aliases as key=value
            String key = StringHelper.before(line, "=");
            String value = StringHelper.after(line, "=");
            if (key != null) {
                key = key.trim();
            }
            if (value != null) {
                value = value.trim();
            }
            if (key != null && value != null) {
                this.aliases.put(key, value);
                counter2++;
            }
        }
        if (counter1 > 0 || counter2 > 0) {
            getLog().info("Loaded csimple language imports: " + counter1 + " and aliases: " + counter2 + " from configuration: "
                          + configFile);
        }
    }

    private static String load(String configFile) {
        String loaded;
        InputStream is = null;
        try {
            // load from file system
            File file = new File(configFile);
            if (file.exists()) {
                is = new FileInputStream(file);
            }
            if (is == null) {
                return null;
            }
            loaded = IOHelper.loadText(is);
        } catch (IOException e) {
            throw new RuntimeCamelException("Cannot load " + configFile);

        }
        IOHelper.close(is);
        return loaded;
    }

    private boolean matchRouteFile(File file) {
        return CatalogHelper.matchRouteFile(file, excludes, includes, project);
    }

    public static boolean updateResource(Path out, String data) {
        try {
            if (FileUtil.updateFile(out, data)) {
                return true;
            }
        } catch (IOException e) {
            throw new IOError(e);
        }
        return false;
    }

}
