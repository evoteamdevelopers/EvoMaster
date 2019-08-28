package org.evomaster.e2etests.utils;

import org.evomaster.client.java.controller.EmbeddedSutController;
import org.evomaster.client.java.controller.InstrumentedSutStarter;
import org.evomaster.client.java.controller.api.dto.SutInfoDto;
import org.evomaster.client.java.instrumentation.ClassName;
import org.evomaster.core.Main;
import org.evomaster.core.output.OutputFormat;
import org.evomaster.core.problem.graphql.GraphqlIndividual;
import org.evomaster.core.remote.service.RemoteController;

import org.evomaster.core.search.Solution;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


import static org.junit.jupiter.api.Assertions.*;

public abstract class GraphqlTestBase extends TestBase{

    protected Solution<GraphqlIndividual> initAndRun(List<String> args){
        return (Solution<GraphqlIndividual>) Main.initAndRun(args.toArray(new String[0]));
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
                "--testSuiteFileName", testClassName.getFullNameWithDots(),
                "--problemType=GRAPHQL"
        ));
    }

    protected static void initClass(EmbeddedSutController controller) throws Exception {

        GraphqlTestBase.controller = controller;

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


}
