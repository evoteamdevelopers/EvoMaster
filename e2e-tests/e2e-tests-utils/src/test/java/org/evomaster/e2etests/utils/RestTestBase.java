package org.evomaster.e2etests.utils;

import org.apache.commons.io.FileUtils;
import org.evomaster.client.java.controller.EmbeddedSutController;
import org.evomaster.client.java.controller.InstrumentedSutStarter;
import org.evomaster.client.java.controller.api.dto.SutInfoDto;
import org.evomaster.client.java.controller.internal.SutController;
import org.evomaster.client.java.instrumentation.ClassName;
import org.evomaster.core.Main;
import org.evomaster.core.output.OutputFormat;
import org.evomaster.core.output.compiler.CompilerForTestGenerated;
import org.evomaster.core.problem.graphql.GraphqlIndividual;
import org.evomaster.core.problem.rest.*;
import org.evomaster.core.remote.service.RemoteController;
import org.evomaster.core.search.Action;
import org.evomaster.core.search.EvaluatedIndividual;
import org.evomaster.core.search.Individual;
import org.evomaster.core.search.Solution;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

public abstract class RestTestBase extends TestBase{


    protected Solution<RestIndividual> initAndRun(List<String> args){
        return (Solution<RestIndividual>) Main.initAndRun(args.toArray(new String[0]));
    }


    protected List<String> getArgsWithCompilation(int iterations, String outputFolderName, ClassName testClassName){

        return new ArrayList<>(Arrays.asList(
                "--createTests", "true",
                "--seed", "42",
                "--sutControllerPort", "" + controllerPort,
                "--maxActionEvaluations", "" + iterations,
                "--stoppingCriterion", "FITNESS_EVALUATIONS",
                "--outputFolder", outputFolderPath(outputFolderName),
                "--outputFormat", OutputFormat.KOTLIN_JUNIT_5.toString(),
                "--testSuiteFileName", testClassName.getFullNameWithDots()
        ));
    }

    protected static void initClass(EmbeddedSutController controller) throws Exception {

        RestTestBase.controller = controller;

        embeddedStarter = new InstrumentedSutStarter(controller);
        embeddedStarter.start();

        controllerPort = embeddedStarter.getControllerServerPort();

        remoteController = new RemoteController("localhost", controllerPort);
        boolean started = remoteController.startSUT();
        assertTrue(started);

        SutInfoDto dto = remoteController.getSutInfo();
        assertNotNull(dto);

        baseUrlOfSut = dto.baseUrlOfSUT;
        assertNotNull(baseUrlOfSut);

        System.out.println("Remote controller running on port " + controllerPort);
        System.out.println("SUT listening on " + baseUrlOfSut);
    }



    protected List<Integer> getIndexOfHttpCalls(Individual ind, HttpVerb verb) {

        List<Integer> indices = new ArrayList<>();
        List<Action> actions = ind.seeActions();

        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i) instanceof RestCallAction) {
                RestCallAction action = (RestCallAction) actions.get(i);
                if (action.getVerb() == verb) {
                    indices.add(i);
                }
            }
        }

        return indices;
    }


    protected boolean hasAtLeastOne(EvaluatedIndividual<RestIndividual> ind,
                                    HttpVerb verb,
                                    int expectedStatusCode) {

        List<Integer> index = getIndexOfHttpCalls(ind.getIndividual(), verb);
        for (int i : index) {
            String statusCode = ind.getResults().get(i).getResultValue(
                    RestCallResult.Companion.getSTATUS_CODE());
            if (statusCode.equals("" + expectedStatusCode)) {
                return true;
            }
        }
        return false;
    }

    protected boolean hasAtLeastOne(EvaluatedIndividual<RestIndividual> ind,
                                    HttpVerb verb,
                                    int expectedStatusCode,
                                    String path,
                                    String inResponse) {

        List<Action> actions = ind.getIndividual().seeActions();

        for (int i = 0; i < actions.size(); i++) {

            if (!(actions.get(i) instanceof RestCallAction)) {
                continue;
            }

            RestCallAction action = (RestCallAction) actions.get(i);
            if (action.getVerb() != verb) {
                continue;
            }

            if (path != null) {
                RestPath target = new RestPath(path);
                if (!action.getPath().isEquivalent(target)) {
                    continue;
                }
            }

            RestCallResult res = (RestCallResult) ind.getResults().get(i);
            Integer statusCode = res.getStatusCode();

            if (!statusCode.equals(expectedStatusCode)) {
                continue;
            }

            String body = res.getBody();
            if (inResponse != null && !body.contains(inResponse)) {
                continue;
            }

            return true;
        }

        return false;
    }

    protected void assertHasAtLeastOne(Solution<RestIndividual> solution,
                                       HttpVerb verb,
                                       int expectedStatusCode,
                                       String path,
                                       String inResponse) {

        boolean ok = solution.getIndividuals().stream().anyMatch(
                ind -> hasAtLeastOne(ind, verb, expectedStatusCode, path, inResponse));

        assertTrue(ok, restActions(solution));
    }

    protected void assertInsertionIntoTable(Solution<RestIndividual> solution, String tableName) {

        boolean ok = solution.getIndividuals().stream().anyMatch(
                ind -> ind.getIndividual().getDbInitialization().stream().anyMatch(
                        da -> da.getTable().getName().equalsIgnoreCase(tableName))
        );

        assertTrue(ok);
    }

    protected void assertHasAtLeastOne(Solution<RestIndividual> solution,
                                       HttpVerb verb,
                                       int expectedStatusCode) {
        assertHasAtLeastOne(solution, verb, expectedStatusCode, null, null);
    }

    private String restActions(Solution<RestIndividual> solution) {
        StringBuffer msg = new StringBuffer("REST calls:\n");

        solution.getIndividuals().stream().flatMap(ind -> ind.evaluatedActions().stream())
                .map(ea -> ea.getAction())
                .filter(a -> a instanceof RestCallAction)
                .forEach(a -> msg.append(a.toString()).append("\n"));

        return msg.toString();
    }

    protected void assertNone(Solution<RestIndividual> solution,
                              HttpVerb verb,
                              int expectedStatusCode) {

        boolean ok = solution.getIndividuals().stream().noneMatch(
                ind -> hasAtLeastOne(ind, verb, expectedStatusCode));

        StringBuffer msg = new StringBuffer("REST calls:\n");
        if (!ok) {
            solution.getIndividuals().stream().flatMap(ind -> ind.evaluatedActions().stream())
                    .map(ea -> ea.getAction())
                    .filter(a -> a instanceof RestCallAction)
                    .forEach(a -> msg.append(a.toString() + "\n"));
        }

        assertTrue(ok, msg.toString());
    }

}
