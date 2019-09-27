package org.evomaster.e2etests.utils;

import org.apache.commons.io.FileUtils;
import org.evomaster.client.java.controller.InstrumentedSutStarter;
import org.evomaster.client.java.controller.internal.SutController;
import org.evomaster.client.java.instrumentation.shared.ClassName;
import org.evomaster.core.output.OutputFormat;
import org.evomaster.core.output.compiler.CompilerForTestGenerated;
import org.evomaster.core.problem.graphql.GraphqlIndividual;
import org.evomaster.core.remote.service.RemoteController;
import org.evomaster.core.search.Solution;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

public abstract class TestBase {

    protected static InstrumentedSutStarter embeddedStarter;
    protected static String baseUrlOfSut;
    protected static SutController controller;
    protected static RemoteController remoteController;
    protected static int controllerPort;


    protected String outputFolderPath(String outputFolderName){
        return "target/em-tests/" + outputFolderName;
    }


    protected void runTestHandlingFlaky(
            String outputFolderName,
            String fullClassName,
            int iterations,
            Consumer<List<String>> lambda) throws Throwable{

        runTestHandlingFlaky(outputFolderName, fullClassName, iterations, true, lambda);
    }

    protected void runTestHandlingFlaky(
            String outputFolderName,
            String fullClassName,
            int iterations,
            boolean createTests,
            Consumer<List<String>> lambda) throws Throwable{

        /*
            Years have passed, still JUnit 5 does not handle global test timeouts :(
            https://github.com/junit-team/junit5/issues/80
         */
        assertTimeoutPreemptively(Duration.ofMinutes(3), () -> {
            ClassName className = new ClassName(fullClassName);
            clearGeneratedFiles(outputFolderName, className);

            List<String> args = getArgsWithCompilation(iterations, outputFolderName, className, createTests);

            handleFlaky(
                    () -> lambda.accept(new ArrayList<>(args))
            );
        });
    }

    protected void runTestHandlingFlakyAndCompilation(
            String outputFolderName,
            String fullClassName,
            int iterations,
            Consumer<List<String>> lambda) throws Throwable {

        runTestHandlingFlakyAndCompilation(outputFolderName, fullClassName, iterations, true, lambda);
    }

    protected void runTestHandlingFlakyAndCompilation(
            String outputFolderName,
            String fullClassName,
            int iterations,
            boolean createTests,
            Consumer<List<String>> lambda) throws Throwable {

        runTestHandlingFlaky(outputFolderName, fullClassName, iterations, createTests,lambda);

        if (createTests){
            assertTimeoutPreemptively(Duration.ofMinutes(2), () -> {
                ClassName className = new ClassName(fullClassName);
                compileRunAndVerifyTests(outputFolderName, className);
            });
        }
    }

    protected void compileRunAndVerifyTests(String outputFolderName, ClassName className){

        Class<?> klass = loadClass(className);
        assertNull(klass);

        compile(outputFolderName);
        klass = loadClass(className);
        assertNotNull(klass);

        StringWriter writer = new StringWriter();
        PrintWriter pw = new PrintWriter(writer);

        TestExecutionSummary summary = JUnitTestRunner.runTestsInClass(klass);
        summary.printFailuresTo(pw);
        String failures = writer.toString();

        assertTrue(summary.getContainersFoundCount() > 0);
        assertEquals(0, summary.getContainersFailedCount(), failures);
        assertTrue(summary.getContainersSucceededCount() > 0);
        assertTrue(summary.getTestsFoundCount() > 0);
        assertEquals(0, summary.getTestsFailedCount(), failures);
        assertTrue(summary.getTestsSucceededCount() > 0);
    }

    protected void clearGeneratedFiles(String outputFolderName, ClassName testClassName){

        File folder = new File(outputFolderPath(outputFolderName));
        try{
            FileUtils.deleteDirectory(folder);
        }catch (Exception e){
            throw new RuntimeException(e);
        }

        String bytecodePath = "target/test-classes/" + testClassName.getAsResourcePath();
        File compiledFile = new File(bytecodePath);
        compiledFile.delete();
    }

    protected Class<?> loadClass(ClassName className){
        try {
            return this.getClass().getClassLoader().loadClass(className.getFullNameWithDots());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    protected void compile(String outputFolderName){

        CompilerForTestGenerated.INSTANCE.compile(
                OutputFormat.KOTLIN_JUNIT_5,
                new File(outputFolderPath(outputFolderName)),
                new File("target/test-classes")
        );
    }

    protected List<String> getArgsWithCompilation(int iterations, String outputFolderName, ClassName testClassName){
        return getArgsWithCompilation(iterations, outputFolderName, testClassName, true);
    }

    protected abstract List<String> getArgsWithCompilation(int iterations, String outputFolderName, ClassName testClassName, boolean createTests);





    /**
     * Unfortunately JUnit 5 does not handle flaky tests, and Maven is not upgraded yet.
     * See https://github.com/junit-team/junit5/issues/1558#issuecomment-414701182
     *
     * TODO: once that issue is fixed (if it will ever be fixed), then this method
     * will no longer be needed
     *
     * @param lambda
     * @throws Throwable
     */
    protected void handleFlaky(Runnable lambda) throws Throwable{

        int attempts = 3;
        Throwable error = null;

        for(int i=0; i<attempts; i++){

            try{
                lambda.run();
                return;
            }catch (OutOfMemoryError e){
                throw e;
            }catch (Throwable t){
                error = t;
            }
        }

        throw error;
    }
}
