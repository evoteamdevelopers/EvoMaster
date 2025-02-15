# EvoMaster: A Tool For Automatically Generating System-Level Test Cases


![](docs/img/carl-cerstrand-136810_compressed.jpg  "Photo by Carl Cerstrand on Unsplash")

[![Build Status](https://travis-ci.org/EMResearch/EvoMaster.svg?branch=master)](https://travis-ci.org/EMResearch/EvoMaster)
[![CircleCI](https://circleci.com/gh/EMResearch/EvoMaster.svg?style=svg)](https://circleci.com/gh/EMResearch/EvoMaster)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.evomaster/evomaster-client-java/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.evomaster/evomaster-client-java)
<!---
Needs auth :(
[[JaCoCo]](https://circleci.com/api/v1.1/project/github/arcuri82/evomaster/latest/artifacts/0/home/circleci/evomaster-build/report/target/site/jacoco-aggregate/index.html)
-->

<!--
<b>HIRING</b>: we are hiring postdocs to work on this project.
For more information and for applying, see [here]().
<br>
-->

_EvoMaster_ ([www.evomaster.org](http://evomaster.org)) is a tool prototype 
that automatically *generates* system-level test cases.
Internally, it uses an *evolutionary algorithm* and *bytecode analysis* to be
able to generate effective test cases.
The approach is to *evolve* test cases from an initial population of 
random ones, trying to maximize measures like code coverage and fault detection.

At the moment, _EvoMaster_ targets RESTful APIs compiled to 
JVM 8 bytecode (e.g., Java and Kotlin).

This project is in early stage of development. Documentation is still under construction.
A short [video](https://youtu.be/7zTLUlH-BNI) shows the use of _EvoMaster_ on one of the 
case studies in [EMB](https://github.com/EMResearch/EMB). 


_EvoMaster_ is currently (2018-2021) funded by a 7.8 million Norwegian Kroner grant 
by the Research Council of Norway (RCN), as part of the 
Frinatek project <i>Evolutionary Enterprise Testing</i>.  

To compile the project, use the Maven command:

`mvn  clean install -DskipTests`

This should create an `evomaster.jar` executable under the `core/target` folder.

Note: if you get an error from the shade-plugin, then make sure to use
`clean` in your Maven commands.
Furthermore, if you decide to do not skip the tests, then you will need to have
_Docker_ installed and running on your machine.


### Example

The following code is an example of one test that was automatically
generated by _EvoMaster_ for a REST service called 
"scout-api" (see [EMB](https://github.com/EMResearch/EMB)).
The generated test uses the [RestAssured](https://github.com/rest-assured/rest-assured) library.

```
@Test
public void test12() throws Exception {
        
    String location_activities = "";
        
    String id_0 = given().accept("*/*")
            .header("Authorization", "ApiKey administrator") // administrator
            .contentType("application/json")
            .body("{\"name\":\"JIg\", \"date_updated\":\"1968-7-28T10:40:58.000Z\", \"description_material\":\"CDasIs\", \"description_prepare\":\"VatRg\", \"description_main\":\"vbhUS\", \"description_safety\":\"mdMZXtHaW6Ac0L7\", \"age_min\":-1639552914, \"age_max\":-546, \"participants_min\":-166, \"time_max\":-728, \"featured\":true, \"activity\":{\"ratings_sum\":-2169794882535544017, \"favourites_count\":2018287764382358555, \"ratings_average\":0.7005221066369205, \"related\":[5230990194698818394, 4025421724722458028, -1291838056, -210322044]}}")
            .post(baseUrlOfSut + "/api/v1/activities")
            .then()
            .statusCode(200)
            .extract().body().path("id").toString();
                
    location_activities = "/api/v1/activities/" + id_0;
        
    given().accept("*/*")
            .header("Authorization", "ApiKey administrator") // administrator
            .contentType("application/json")
            .body("{\"rating\":7126434, \"favourite\":false}")
            .post(resolveLocation(location_activities, baseUrlOfSut + "/api/v1/activities/-324163273/rating"))
            .then()
            .statusCode(204);
        
    given().accept("*/*")
            .header("Authorization", "ApiKey administrator") // administrator
            .delete(resolveLocation(location_activities, baseUrlOfSut + "/api/v1/activities/-324163273/rating"))
            .then()
            .statusCode(204);
}
```

The generated tests are self-contained, i.e. they 
start/stop the REST server by themselves:

```
    private static SutHandler controller = new em.embedded.se.devscout.scoutapi.EmbeddedEvoMasterController();
    private static String baseUrlOfSut;
    
    
    @BeforeClass
    public static void initClass() {
        baseUrlOfSut = controller.startSut();
        assertNotNull(baseUrlOfSut);
        RestAssured.urlEncodingEnabled = false;
    }
    
    
    @AfterClass
    public static void tearDown() {
        controller.stopSut();
    }
    
    
    @Before
    public void initTest() {
        controller.resetStateOfSUT();
    }
```

### EvoMaster Core

Once the file `evomaster.jar` has been packaged, or
the latest release has been downloaded from the 
[releases page](https://github.com/EMResearch/EvoMaster/releases),
it can be executed directly from a commandline/shell.
 
Available options can be queried by using:

`java -jar evomaster.jar --help`

The most important options are:

* `--maxTimeInSeconds <Int>`        
Maximum number of seconds allowed for the search. 
**The more time is allowed, the better results one can expect**.
But then of course the test generation will take longer.

* `--outputFolder <String>`   
The path directory of where the generated test classes 
should be saved to.
 

* `--testSuiteFileName <String>`             
The name of generated file with the test cases, 
without file type   extension. 
In JVM languages, if the name contains `.`, 
folders will be created to represent the given
package structure.


### EvoMaster Driver
    
Note, to generate tests, you need an _EvoMaster Driver_ up and running before 
executing `evomaster.jar`.
These drivers have to be built manually for each system under test (SUT).
See [EMB](https://github.com/EMResearch/EMB) for a set of existing SUTs with drivers.

To build a client driver in Java (or any JVM language), you need to import the
_EvoMaster_ Java client library. For example, in Maven:

```
<dependency>
   <groupId>org.evomaster</groupId>
   <artifactId>evomaster-client-java-controller</artifactId>
   <version>LATEST</version>
</dependency>
```

For the latest version, check [Maven Central Repository](https://mvnrepository.com/artifact/org.evomaster/evomaster-client-java-controller).
The latest version number should also appear at the top of this page.
If you are compiling directly from the _EvoMaster_ source code, make sure to use `mvn install` to 
install the snapshot version of the Java client into your local Maven repository 
(e.g., under *~/.m2*). 

Once the client library is imported, you need to create a class that extends either
`org.evomaster.client.java.controller.EmbeddedSutController`
 or
 `org.evomaster.client.java.controller.ExternalSutController`.
Both these classes extend `SutController`.
The difference is on whether the SUT is started in the same JVM of the _EvoMaster_
driver (*embedded*), or in a separated JVM (*external*).
 
The easiest approach is to use the *embedded* version, especially when dealing with
frameworks like Spring and DropWizard. 
However, when the presence of the _EvoMaster_ client library gives side-effects (although 
its third-party libraries are shaded, side-effects might still happen),
or when it is not possible (or too complicate) to start the SUT directly (e.g., JEE),
it is better to use the *external* version.
The requirement is that there should be a single, self-executable uber/fat jar for the SUT 
(e.g., Wildfly Swarm).
It can be possible to handle WAR files (e.g., by using Payara), 
but currently we have not tried it out yet.

Once a class is written that extends either `EmbeddedSutController` or
`ExternalSutController`, there are a few abstract methods that need to
be implemented.
For example, those methods specify how to start the SUT, how it should be stopped,
and how to reset its state.
The _EvoMaster_ Java client library also provides further utility classes to help
writing those controllers/drivers.
For example, `org.evomaster.client.java.controller.db.DbCleaner` helps in resetting
the state of a database (if any is used by the SUT).

Until better documentation and tutorials are provided,
to implement a `SutController` class check the JavaDocs of the extended super class,
and the existing examples in 
[EMB](https://github.com/EMResearch/EMB).


Once a class `X` that is a descendant of `SutController` is written, you need
to be able to start the _EvoMaster_ driver, by using the 
`org.evomaster.client.java.controller.InstrumentedSutStarter`
class. 
For example, in the source code of the class `X`, you could add:
 
```
public static void main(String[] args){

   SutController controller = new X();
   InstrumentedSutStarter starter = new InstrumentedSutStarter(controller);

   starter.start();
}
```

At this point, once this driver is started (e.g., by right-clicking on it in
an IDE to run it as a Java process),
then you can use `evomaster.jar` to finally generate test cases.


### How to Contribute

There are many ways in which you can contribute.
If you found _EvoMaster_ of any use, the easiest
way to show appreciation is to *star* it.
Issues and feature requests can be reported on
the [issues](https://github.com/EMResearch/EvoMaster/issues) page:  
  
* *Bugs*: as for any bug report, the more detailed
  you can be the better.
  If you are using _EvoMaster_ on an open source project,
  please provide links to it, as then it is much easier
  to reproduce the bugs.
  
* If you are trying to use _EvoMaster_, but the instructions
  in these notes are not enough to get you started, 
  then it means it is a "bug" in the documentation, which then would need
  to be clarified. 
  
* *Feature Requests*: to improve _EvoMaster_,
  we are very keen to receive
  feature requests, although of course we cannot
  guarantee when they are going to be implemented. 
  
* *Pull Requests*: we are very keen to receive PRs, as long as you agree
  with the license of _EvoMaster_. However, before making a PR, should read
  the [notes for developers](docs/for_developers.md).  
    

### Publications and Further Resources

The development of _EvoMaster_ is rooted in academia.
Academic publications based on _EvoMaster_ and slides of presentations can be 
found [here](docs/publications.md).
These can be useful if you want to know more on how _EvoMaster_ works internally,
e.g., details on the Many Independent Objective (MIO) algorithm.


### License
_EvoMaster_'s source code is released under the LGPL (v3) license.
For a list of the used third-party libraries, you can directly see the root [pom.xml](./pom.xml) file.
For a list of code directly imported (and then possibly modified/updated) from 
other open-source projects, see [here](./docs/reused_code.md).


### ![](https://www.yourkit.com/images/yklogo.png)

YourKit supports open source projects with its full-featured Java Profiler.
YourKit, LLC is the creator of 
<a href="https://www.yourkit.com/java/profiler/">YourKit Java Profiler</a>
and 
<a href="https://www.yourkit.com/.net/profiler/">YourKit .NET Profiler</a>,
innovative and intelligent tools for profiling Java and .NET applications.


