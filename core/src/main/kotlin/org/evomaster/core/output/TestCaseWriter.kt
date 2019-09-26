package org.evomaster.core.output

import com.google.gson.Gson
import org.apache.commons.lang3.StringEscapeUtils
import org.evomaster.core.EMConfig
import org.evomaster.core.Lazy
import org.evomaster.core.database.DbAction
import org.evomaster.core.output.formatter.OutputFormatter
import org.evomaster.core.problem.rest.RestCallAction
import org.evomaster.core.problem.rest.RestCallResult
import org.evomaster.core.problem.rest.RestIndividual
import org.evomaster.core.problem.rest.UsedObjects
import org.evomaster.core.problem.rest.param.BodyParam
import org.evomaster.core.problem.rest.param.HeaderParam
import org.evomaster.core.search.EvaluatedAction
import org.evomaster.core.search.gene.*
import org.evomaster.core.search.gene.sql.SqlForeignKeyGene
import org.evomaster.core.search.gene.sql.SqlPrimaryKeyGene
import org.evomaster.core.search.gene.sql.SqlWrapperGene
import javax.ws.rs.core.MediaType


class TestCaseWriter {

    private var counter = 0
    private var usedObjects = UsedObjects()
    private var previousChained = false
    private var previousId = ""
    private var chained = false
    //private var relevantObjects: List<Gene> = listOf()

    //TODO: refactor in constructor, and take out of convertToCompilableTestCode
    private var format: OutputFormat = OutputFormat.JAVA_JUNIT_4
    private lateinit var configuration: EMConfig

    companion object{
        val NOT_COVERED_YET = "NotCoveredYet"
    }

    fun convertToCompilableTestCode(
            config: EMConfig,
            test: TestCase,
            baseUrlOfSut: String)
            : Lines {

        //TODO: refactor remove once changes merged
        configuration = config
        this.format = config.outputFormat


        counter = 0

        val lines = Lines()

        if (format.isJUnit()) {
            lines.add("@Test")
        }

        when {
            format.isJava() -> lines.add("public void ${test.name}() throws Exception {")
            format.isKotlin() -> lines.add("fun ${test.name}()  {")
        }

        lines.indented {

            if (test.test.individual is RestIndividual) {
                // BMR: test.test should have the used objects attached (if any).
                //usedObjects = test.test.individual.usedObjects
                if (config.enableCompleteObjects) {
                    usedObjects = (test.test.individual as RestIndividual).usedObjects
                }
                handleDbInitialization(format, (test.test.individual as RestIndividual).dbInitialization, lines)
            }


            if (test.hasChainedLocations()) {
                lines.addEmpty()

                test.test.evaluatedActions()
                        .map { it.action }
                        .filterIsInstance(RestCallAction::class.java)
                        .filter { it.locationId != null }
                        .map { it.locationId }
                        .distinct()
                        .forEach { id ->
                            val name = locationVar(id!!)
                            when {
                                format.isJava() -> lines.add("String $name = \"\";")
                                format.isKotlin() -> lines.add("var $name : String? = \"\"")
                            }
                        }
            }


            test.test.evaluatedActions().forEach { a ->
                when (a.action) {
                    is RestCallAction -> handleRestCall(a, lines, baseUrlOfSut)
                    else -> throw IllegalStateException("Cannot handle " + a.action.getName())
                }
            }
        }
        lines.add("}")

        return lines
    }

    private fun handleDbInitialization(format: OutputFormat, dbInitialization: List<DbAction>, lines: Lines) {

        if (dbInitialization.isEmpty() || dbInitialization.none { !it.representExistingData }) {
            return
        }

        dbInitialization
                .filter { !it.representExistingData }
                .forEachIndexed { index, dbAction ->

                    lines.add(when {
                        index == 0 && format.isJava() -> "List<InsertionDto> insertions = sql()"
                        index == 0 && format.isKotlin() -> "val insertions = sql()"
                        else -> ".and()"
                    } + ".insertInto(\"${dbAction.table.name}\", ${dbAction.geInsertionId()}L)")

                    if (index == 0) {
                        lines.indent()
                    }

                    lines.indented {
                        dbAction.seeGenes()
                                .filter { it.isPrintable() }
                                .forEach { g ->
                                    when {
                                        g is SqlWrapperGene && g.getForeignKey() != null -> {
                                            val line = handleFK(g.getForeignKey()!!, dbAction, dbInitialization)
                                            lines.add(line)
                                        }
                                        g is ObjectGene -> {
                                            val variableName = g.getVariableName()
                                            val printableValue = getPrintableValue(g)
                                            lines.add(".d(\"$variableName\", \"'$printableValue'\")")
                                        }
                                        else -> {
                                            val variableName = g.getVariableName()
                                            val printableValue = getPrintableValue(g)
                                            lines.add(".d(\"$variableName\", \"$printableValue\")")
                                        }
                                    }
                                }

                    }
                }

        lines.add(".dtos()" +
                when {
                    format.isJava() -> ";"
                    format.isKotlin() -> ""
                    else -> ""
                })

        lines.deindent()

        var execInsertionsLine = "controller.execInsertionsIntoDatabase(insertions)"
        when {
            format.isJava() -> execInsertionsLine += ";"
            format.isKotlin() -> {
            }
        }
        lines.add(execInsertionsLine)
    }

    private fun getPrintableValue(g: Gene): String {
        if (g is SqlPrimaryKeyGene) {

            return getPrintableValue(g.gene)

        } else {
            return StringEscapeUtils.escapeJava(g.getValueAsPrintableString(targetFormat = format))
        }
    }

    private fun handleFK(fkg: SqlForeignKeyGene, action: DbAction, allActions: List<DbAction>): String {


        /*
            TODO: why the code here is not relying on SqlForeignKeyGene#getValueAsPrintableString ???
         */

        val variableName = fkg.getVariableName()
        /**
         * At this point all pk Ids should be valid
         * (despite they being NULL or not)
         **/
        Lazy.assert { fkg.hasValidUniqueIdOfPrimaryKey() }
        if (fkg.isNull()) {
            return ".d(\"$variableName\", \"NULL\")"
        }


        val uniqueIdOfPrimaryKey = fkg.uniqueIdOfPrimaryKey

        /*
            TODO: the code here is not handling multi-column PKs/FKs
         */
        val pkExisting = allActions
                .filter { it.representExistingData }
                .flatMap { it.seeGenes() }
                .filterIsInstance<SqlPrimaryKeyGene>()
                .find { it.uniqueId == uniqueIdOfPrimaryKey}

        /*
           This FK might point to a PK of data already existing in the database.
           In such cases, the PK will not be part of the generated SQL commands, and
           we cannot use a "r()" reference to it.
           We need to put the actual value data in a "d()"
        */

        if(pkExisting != null){
            val pk = getPrintableValue(pkExisting)
            return ".d(\"$variableName\", \"$pk\")"
        }

        /*
            Check if this is a reference to an auto-increment
         */
        val keepAutoGeneratedValue = action.selectedColumns
                .filter { it.name == fkg.name }
                .first().foreignKeyToAutoIncrement

        if (keepAutoGeneratedValue) {
            return ".r(\"$variableName\", ${uniqueIdOfPrimaryKey}L)"
        }


        val pkg = allActions
                .flatMap { it.seeGenes() }
                .filterIsInstance<SqlPrimaryKeyGene>()
                .find { it.uniqueId == uniqueIdOfPrimaryKey}!!

        val pk = getPrintableValue(pkg)
        return ".d(\"$variableName\", \"$pk\")"
    }


    private fun locationVar(id: String): String {
        //TODO make sure name is syntactically valid
        //TODO use counters to make them unique
        return "location_${id.trim().replace(" ", "_")}"
    }


    private fun handleRestCall(
            evaluatedAction: EvaluatedAction,
            lines: Lines,
            baseUrlOfSut: String
    ) {
        lines.addEmpty()

        val call = evaluatedAction.action as RestCallAction
        val res = evaluatedAction.result as RestCallResult

        if (res.failedCall()) {
            addRestCallInTryCatch(call, lines, res, baseUrlOfSut)
        } else {
            addRestCallLines(call, lines, res, baseUrlOfSut)
        }
    }

    private fun addRestCallInTryCatch(call: RestCallAction,
                                      lines: Lines,
                                      res: RestCallResult,
                                      baseUrlOfSut: String) {

        lines.add("try{")
        lines.indented {
            addRestCallLines(call, lines, res, baseUrlOfSut)

            if (!res.getTimedout()) {
                /*
                Fail test if exception is not thrown, but not if it was a timeout,
                otherwise the test would become flaky
              */
                lines.add("fail(\"Expected exception\");")
            }
        }

        lines.add("} catch(Exception e){")

        res.getErrorMessage()?.let {
            lines.indented {
                lines.add("//$it")
            }
        }
        lines.add("}")
    }

    private fun addRestCallLines(call: RestCallAction,
                                 lines: Lines,
                                 res: RestCallResult,
                                 baseUrlOfSut: String) {

        //first handle the first line
        handleFirstLine(call, lines, res)
        lines.indent(2)

        handleHeaders(call, lines)

        handleBody(call, lines)

        handleVerb(baseUrlOfSut, call, lines)

        handleResponse(lines, res)

        //finally, handle the last line(s)
        handleLastLine(call, res, lines)

        //BMR should expectations be here?
        // Having them at the end of a test makes some sense...
        if (configuration.expectationsActive) {
            handleExpectations(res, lines, true)
        }


    }

    private fun handleLastLine(call: RestCallAction, res: RestCallResult, lines: Lines) {

        if (call.saveLocation && !res.stopping) {

            if (!res.getHeuristicsForChainedLocation()) {
                lines.add(".extract().header(\"location\");")
                lines.addEmpty()
                lines.deindent(2)
                lines.add("assertTrue(isValidURIorEmpty(${locationVar(call.path.lastElement())}));")
            } else {
                //TODO BMR: this is a less-than-subtle way to try to fix a problem in ScoutAPI
                // The test generated in java causes a fail due to .path<Object>
                val extraTypeInfo = when {
                    format.isKotlin() -> "<Object>"
                    else -> ""
                }
                lines.add(".extract().body().path$extraTypeInfo(\"${res.getResourceIdName()}\").toString();")
                lines.addEmpty()
                lines.deindent(2)

                val baseUri: String = if (call.locationId != null) {
                    locationVar(call.locationId!!)
                } else {
                    call.path.resolveOnlyPath(call.parameters)
                }

                lines.add("${locationVar(call.path.lastElement())} = \"$baseUri/\" + id_$counter;")

                previousChained = res.getHeuristicsForChainedLocation()
                if(previousChained) previousId = "id_$counter"
                counter++
            }
        } else {
            lines.append(";")
            lines.deindent(2)
        }
    }

    private fun handleFirstLine(call: RestCallAction, lines: Lines, res: RestCallResult) {
        lines.addEmpty()
        if (call.saveLocation && !res.stopping) {

            if (!res.getHeuristicsForChainedLocation()) {
                lines.append("${locationVar(call.path.lastElement())} = ")
            } else {
                if (format.isJava()) {
                    lines.append("String id_$counter = ")
                } else {
                    lines.append("val id_$counter: String = ")
                }
                chained = res.getHeuristicsForChainedLocation()
            }
        }
        lines.append("given()" + getAcceptHeader(call, res))
    }

    private fun handleVerb(baseUrlOfSut: String, call: RestCallAction, lines: Lines) {
        val verb = call.verb.name.toLowerCase()
        lines.add(".$verb(")
        if (call.locationId != null) {
            //lines.append("resolveLocation(${locationVar(call.locationId!!)}, $baseUrlOfSut + \"${applyEscapes(call.resolvedPath())}\")")
            lines.append("resolveLocation(${locationVar(call.locationId!!)}, $baseUrlOfSut + \"${call.resolvedPath()}\")")

        } else {

            lines.append("$baseUrlOfSut + ")

            if (call.path.numberOfUsableQueryParams(call.parameters) <= 1) {
                val uri = call.path.resolve(call.parameters)
                lines.append("\"${GeneUtils.applyEscapes(uri, mode = "uris", format = format)}\"")
                //lines.append("\"$uri\"")
            } else {
                //several query parameters. lets have them one per line
                val path = call.path.resolveOnlyPath(call.parameters)
                val elements = call.path.resolveOnlyQuery(call.parameters)

                lines.append("\"$path?\" + ")

                lines.indented {
                    (0 until elements.lastIndex).forEach { i -> lines.add("\"${GeneUtils.applyEscapes(elements[i], mode = "queries", format = format)}&\" + ") }
                    lines.add("\"${GeneUtils.applyEscapes(elements.last(), mode = "queries", format = format)}\"")
                }
            }
        }
        lines.append(")")
    }

    private fun handleResponse(lines: Lines, res: RestCallResult) {
        if (!res.failedCall()) {
            lines.add(".then()")
            lines.add(".statusCode(${res.getStatusCode()})")

            if (configuration.enableBasicAssertions) {
                handleResponseContents(lines, res)
            }

            //TODO check on body
        }
    }

    private fun handleFieldValues(resContentsItem: Any?): String {
        if (resContentsItem == null) {
            return "nullValue()"
        } else {
            when (resContentsItem::class) {
                Double::class -> return "numberMatches(${resContentsItem as Double})"
                String::class -> return "containsString(\"${GeneUtils.applyEscapes(resContentsItem as String, mode = "assertions")}\")"
                Map::class -> return NOT_COVERED_YET
                ArrayList::class -> return NOT_COVERED_YET
                else -> return NOT_COVERED_YET
            }
        }
        /* BMR: the code above is due to a somewhat unfortunate problem:
        - Gson parses all numbers as Double
        - Hamcrest has a hard time comparing double to int
        The solution is to use an additional content matcher that can be found in NumberMatcher. This can also
        be used as a template for adding more matchers, should such a step be needed.
        * */
    }

    private fun handleMapLines(index: Int, map: Map<*,*>, lines: Lines){
        map.keys.forEach{
            val printableTh = handleFieldValues(map[it])
            if (printableTh != "null"
                    && printableTh != NOT_COVERED_YET
                    && !printableTh.contains("logged")
            ) {
                //lines.add(".body(\"find{it.$it == \\\"${map[it]}\\\"}.$it\", $printableTh)") //tried a find
                //lines.add(".body(\"sort{it.toString()}.$it[$index]\", $printableTh)")
                //lines.add(".body(\"$it[$index]\", $printableTh)") //just index
                //lines.add(".body(\"sort{it.toString()}[$index].$it\" , $printableTh)") //sort and index
                //reinstated above due to the problem of non-deterministic ordering of retrieved collections
                // (eg. ScoutAPI - users)
                //lines.add(".body(\"get($index).$it\" , $printableTh)")
                lines.add(".body(\"$it\", hasItem($printableTh))")
            }
        }
    }

    private fun handleResponseContents(lines: Lines, res: RestCallResult) {
        lines.add(".assertThat()")

        if (res.getBodyType() == null) lines.add(".contentType(\"\")")
        else lines.add(".contentType(\"${res.getBodyType()
                .toString()
                .split(";").first() //TODO this is somewhat unpleasant. A more elegant solution is needed.
        }\")")

        val bodyString = res.getBody()

        if (res.getBodyType() != null) {
            val type = res.getBodyType()!!
            if (type.isCompatible(MediaType.APPLICATION_JSON_TYPE)) {
                when (bodyString?.first()) {
                    '[' -> {
                        // This would be run if the JSON contains an array of objects.
                        val resContents = Gson().fromJson(res.getBody(), ArrayList::class.java)
                        lines.add(".body(\"size()\", equalTo(${resContents.size}))")
                        //resContents.sortBy { it.toString() }
                        //assertions on contents
                        if(resContents.size > 0){
                            resContents.forEachIndexed { test_index, value ->
                                if (value is Map<*, *>){
                                    handleMapLines(test_index, value, lines)
                                }
                                else {
                                    val printableTh = handleFieldValues(value)
                                    if (printableTh != "null"
                                            && printableTh != NOT_COVERED_YET
                                            && !printableTh.contains("logged")
                                    ) {
                                        lines.add(".body(\"get($test_index)\", $printableTh)")
                                    }
                                }
                            }
                        }
                    }
                    '{' -> {
                        // JSON contains an object
                        val resContents = Gson().fromJson(res.getBody(), Map::class.java)
                        addObjectAssertions(resContents, lines)

                    }
                    //'"' -> {
                    // This branch will be called if the JSON is a String
                    // Currently, it only supports very basic string matching
                    //   val resContents = Gson().fromJson(res.getBody(), String::class.java)
                    //   lines.add(".body(containsString(\"${resContents}\"))")
                    //}
                    else -> {
                        // This branch will be called if the JSON is null (or has a basic type)
                        // Currently, it converts the contents to String.
                        // TODO: if the contents are not a valid form of that type, expectations should be developed to handle the case
                        //val resContents = Gson().fromJson("\"" + res.getBody() + "\"", String::class.java)
                        lines.add(".body(containsString(\"${bodyString}\"))")
                    }
                }
            }
        }
        //handleExpectations(res, lines, true)
    }

    private fun addObjectAssertions(resContents: Map<*,*>, lines: Lines){
        resContents.keys
                /* TODO: BMR - We want to avoid time-based fields (timestamps and the like) as they could lead to flaky tests.
                * Even relatively minor timing changes (one second either way) could cause tests to fail
                * as a result, we are now avoiding generating assertions for fields explicitly labeled as "timestamp"
                * Note that this is a temporary (and somewhat hacky) solution.
                * A more elegant and permanent solution could be handled via the flaky test handling (when that will be ready).
                *
                * NOTE: if we have chained locations, then the "id" should be taken from the chained id rather than the test case?
                */
                .filter{ !(it as String).contains("timestamp")}
                .forEach {
                    val actualValue = resContents[it]
                    if (actualValue != null) {
                        val printableTh = handleFieldValues(actualValue)
                        if (printableTh != "null"
                                && printableTh != NOT_COVERED_YET
                                && !printableTh.contains("logged")
                        ) {
                            //lines.add(".body(\"\'${it}\'\", ${printableTh})")
                            if(it != "id") lines.add(".body(\"\'${it}\'\", ${printableTh})")
                            else{
                                if(!chained && previousChained) lines.add(".body(\"\'${it}\'\", numberMatches($previousId))")
                            }
                        }
                    }
                }
    }

    private fun handleBody(call: RestCallAction, lines: Lines) {
        handleBody(call, lines, true)
    }

    private fun handleBody(call: RestCallAction, lines: Lines, readable: Boolean) {

        val bodyParam = call.parameters.find { p -> p is BodyParam }
        val form = call.getBodyFormData()

        if (bodyParam != null && form != null) {
            throw IllegalStateException("Issue: both Body and FormData present")
        }

        if (bodyParam != null && bodyParam is BodyParam) {

            lines.add(".contentType(\"${bodyParam.contentType()}\")")

            if (bodyParam.isJson()) {

                val body = if (readable) {
                    OutputFormatter.JSON_FORMATTER.getFormatted(bodyParam.gene.getValueAsPrintableString(mode = "json", targetFormat = format))
                } else {
                    bodyParam.gene.getValueAsPrintableString(mode = "json", targetFormat = format)
                }

                //needed as JSON uses ""
                val bodyLines = body.split("\n").map { s ->
                    //"\"" + s.trim().replace("\"", "\\\"") + "\""
                    //"\"" + s.trim().replace("\"", "\\\"") + "\""
                    "\"" + GeneUtils.applyEscapes(s.trim(), "json", format).replace("\\\\u", "\\u") + "\""
                    /*
                     The \u denote unicode characters. For some reason, escaping the \\ leads to these being invalid.
                     Since they are valid in the back end (and they should, arguably, be possible), this leads to inconsistent behaviour.
                     This fix is a hack. It may be that some \u chars are not valid. E.g. \uAndSomeRubbish.

                     As far as I understand, the addition of an \ in the \unicode should not really happen.
                     They should be their own chars, and the .replace("\\", """\\""" should be fine, but for some reason
                     they are not.
                     */

                }

                if (bodyLines.size == 1) {
                    lines.add(".body(${bodyLines.first()})")
                } else {
                    lines.add(".body(${bodyLines.first()} + ")
                    lines.indented {
                        (1 until bodyLines.lastIndex).forEach { i ->
                            lines.add("${bodyLines[i]} + ")
                        }
                        lines.add("${bodyLines.last()})")
                    }
                }

            } /* else if(bodyParam.isXml()) {
                val body = bodyParam.gene.getValueAsPrintableString("xml")
                lines.add(".body(\"$body\")")
            } */ else if (bodyParam.isTextPlain()) {
                val body = bodyParam.gene.getValueAsPrintableString(mode = "text", targetFormat = format)
                if (body != "\"\"") {
                    lines.add(".body($body)")
                }
                else {
                    lines.add(".body(\"${"""\"\""""}\")")
                }

                //BMR: this is needed because, if the string is empty, it causes a 400 (bad request) code on the test end.
                // inserting \"\" should prevent that problem
                // TODO: get some tests done of this
            } else {
                throw IllegalStateException("Unrecognized type: " + bodyParam.contentType())
            }
        }

        if (form != null) {
            lines.add(".contentType(\"application/x-www-form-urlencoded\")")
            lines.add(".body(\"$form\")")
        }
    }

    private fun handleHeaders(call: RestCallAction, lines: Lines) {

        val prechosenAuthHeaders = call.auth.headers.map { it.name }

        call.auth.headers.forEach {
            lines.add(".header(\"${it.name}\", \"${it.value}\") // ${call.auth.name}")
        }

        call.parameters.filterIsInstance<HeaderParam>()
                .filter { !prechosenAuthHeaders.contains(it.name) }
                .forEach {
                    lines.add(".header(\"${it.name}\", ${it.gene.getValueAsPrintableString(targetFormat = format)})")
                }
    }

    private fun getAcceptHeader(call: RestCallAction, res: RestCallResult): String {
        /*
         *  Note: using the type in result body is wrong:
         *  if you request a JSON but make an error, you might
         *  get back a text/plain with an explanation
         *
         *  TODO: get the type from the REST call
         */

        if (call.produces.isEmpty() || res.getBodyType() == null){
            return ".accept(\"*/*\")"
        }

        //if (call.produces.contains(res.getBodyType().toString())) return ".accept(${res.getBodyType().toString()})"
        val accepted = call.produces.filter { res.getBodyType().toString().contains(it, true) }

        if (accepted.size == 1)
            return ".accept(\"${accepted.first()}\")"
        else
            //FIXME: there seems to have been something or a problem
            return ".accept(\"*/*\")"
    }

    private fun handleExpectations(result: RestCallResult, lines: Lines, active: Boolean) {

        /*
        TODO: This is a WiP to show the basic idea of the expectations:
        An exception is thrown ONLY if the expectations are set to active.
        If inactive, the condition will still be processed (with a goal to later adding to summaries or
        other information processing/handling that might be needed), but it does not cause
        the test case to fail regardless of truth value.

        The example below aims to show this behaviour and provide a reminder.
        As it is still work in progress, expect quite significant changes to this.
        */

        lines.add("expectationHandler()")
        lines.indented {
            lines.add(".expect()")
            //lines.add(".that(activeExpectations, true)")
            //lines.add(".that(activeExpectations, false)")
            if (configuration.enableCompleteObjects == false) {
                addExpectationsWithoutObjects(result, lines)
            }
            lines.append(when {
                format.isJava() -> ";"
                else -> ""
            })
        }
    }

    private fun addExpectationsWithoutObjects(result: RestCallResult, lines: Lines) {
        if (result.getBodyType() != null) {
            // if there is a body, add expectations based on the body type. Right now only application/json is supported
            when (result.getBodyType().toString()) {
                "application/json" -> {
                    when (result.getBody()?.first()) {
                        '[' -> {
                            // This would be run if the JSON contains an array of objects
                            val resContents = Gson().fromJson(result.getBody(), ArrayList::class.java)
                        }
                        '{' -> {
                            // This would be run if the JSON contains a single object
                            val resContents = Gson().fromJson(result.getBody(), Object::class.java)

                            (resContents as Map<*, *>).keys.forEach {
                                val printableTh = handleFieldValues(resContents[it]!!)
                                if (printableTh != "null"
                                        && printableTh != NOT_COVERED_YET
                                ) {
                                    lines.add(".that(activeExpectations, (\"${it}\" == \"${resContents[it]}\"))")
                                }
                            }
                        }
                        else -> {
                            // this shouldn't be run if the JSON is okay. Panic! Update: could also be null. Pause, then panic!
                            //lines.add(".body(isEmptyOrNullString())")
                            if(result.getBody() != null)  lines.add(".body(containsString(\"${GeneUtils.applyEscapes(result.getBody().toString(), mode = "assertions", format = format)}\"))")
                        }
                    }
                }
            }
        }
    }

    /**
     * [applyEscapes] currently sets up the string for printing.
     * This includes escaping special chars for java and kotlin.
     * Currently, Strings containing "@" are split, on the assumption (somewhat premature, admittedly) that
     * the symbol signifies an object reference (which would likely cause the assertion to fail).
     * TODO: Tests are needed to make sure this does not break.
     */

    /*


    private fun applyEscapes(string: String): String {
        val timeRegEx = "[0-2]?[0-9]:[0-5][0-9]".toRegex()
        val ret = string.split("@")[0] //first split off any reference that might differ between runs
                .split(timeRegEx)[0] //split off anything after specific timestamps that might differ
                .replace("""\\""", """\\\\""")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")



        if (format.isKotlin()) return ret.replace("\$", "\\\$")
        else return ret
    }*/
}