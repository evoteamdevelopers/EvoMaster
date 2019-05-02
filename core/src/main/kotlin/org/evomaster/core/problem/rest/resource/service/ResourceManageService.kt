package org.evomaster.core.problem.rest.resource.service

import com.google.inject.Inject
import org.evomaster.client.java.controller.api.dto.TestResultsDto
import org.evomaster.client.java.controller.api.dto.database.execution.ExecutionDto
import org.evomaster.client.java.controller.api.dto.database.operations.DataRowDto
import org.evomaster.core.EMConfig
import org.evomaster.core.database.DbAction
import org.evomaster.core.database.DbActionUtils
import org.evomaster.core.problem.rest.HttpVerb
import org.evomaster.core.problem.rest.RestAction
import org.evomaster.core.problem.rest.RestCallAction
import org.evomaster.core.problem.rest.SampleType
import org.evomaster.core.problem.rest.auth.AuthenticationInfo
import org.evomaster.core.problem.rest.param.BodyParam
import org.evomaster.core.problem.rest.param.PathParam
import org.evomaster.core.problem.rest.param.QueryParam
import org.evomaster.core.problem.rest.resource.ResourceRestIndividual
import org.evomaster.core.problem.rest.resource.model.RestResource
import org.evomaster.core.problem.rest.resource.model.RestResourceCalls
import org.evomaster.core.problem.rest.resource.model.dependency.MutualResourcesRelations
import org.evomaster.core.problem.rest.resource.model.dependency.ParamRelatedToTable
import org.evomaster.core.problem.rest.resource.model.dependency.ResourceRelatedToResources
import org.evomaster.core.problem.rest.resource.model.dependency.SelfResourcesRelation
import org.evomaster.core.problem.rest.resource.util.ParamUtil
import org.evomaster.core.problem.rest.resource.util.ParserUtil
import org.evomaster.core.problem.rest.resource.util.RTemplateHandler
import org.evomaster.core.search.Action
import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.gene.*
import org.evomaster.core.search.service.Randomness
import org.evomaster.core.search.service.Sampler
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * the class is used to manage all resources
 */
class ResourceManageService {

    companion object {
        val log: Logger = LoggerFactory.getLogger(ResourceManageService::class.java)
    }

    @Inject
    private lateinit var sampler: Sampler<*>

    @Inject
    private lateinit var randomness: Randomness

    @Inject
    private lateinit var config: EMConfig

    /**
     * key is resource path
     * value is an abstract resource
     */
    private val resourceCluster : MutableMap<String, RestResource> = mutableMapOf()
    /**
     * key is resource path
     * value is a list of tables that are related to the resource
     */
    private val resourceTables : MutableMap<String, MutableSet<String>> = mutableMapOf()

    /**
     * key is table name
     * value is a list of existing data of PKs in DB
     */
    private val dataInDB : MutableMap<String, MutableList<DataRowDto>> = mutableMapOf()

    /**
     * key is either a path of one resource, or a list of paths of resources
     * value is a list of related to resources
     */
    private val dependencies : MutableMap<String, MutableList<ResourceRelatedToResources>> = mutableMapOf()

    private var flagInitDep = false

    fun initAbstractResources(actionCluster : MutableMap<String, Action>) {
        actionCluster.values.forEach { u ->
            if (u is RestCallAction) {
                val resource = resourceCluster.getOrPut(u.path.toString()) {
                    RestResource(u.path.copy(), mutableListOf()).also {
                        if (config.doesApplyTokenParser)
                            it.initTokens()
                    }
                }
                resource.actions.add(u)
            }
        }
        resourceCluster.values.forEach{it.initAncestors(getResourceCluster().values.toList())}

        resourceCluster.values.forEach{it.init()}

        if(hasDBHandler()){
            snapshotDB()
            /*
                derive possible db creation for each abstract resources.
                The derived db creation needs to be further confirmed based on feedback from evomaster driver (NOT IMPLEMENTED YET)
             */
            resourceCluster.values.forEach {ar->
                if(ar.paramsToTables.isEmpty() && config.doesApplyTokenParser)
                    deriveRelatedTables(ar,false)
            }
        }

        if(config.doesApplyTokenParser)
            initDependency()

    }

    /**
     * [resourceTables] and [RestResource.paramsToTables] are basic ingredients for an initialization of [dependencies]
     * thus, the starting point to invoke [initDependency] depends on when the ingredients are ready.
     *
     * if [EMConfig.doesApplyTokenParser] the invocation happens when init resource cluster,
     * else the invocation happens when all ad-hoc individuals are executed
     *
     * Note that it can only be executed one time
     */
    fun initDependency(){
        if(config.probOfEnablingResourceDependencyHeuristics == 0.0 || flagInitDep) return

        flagInitDep = true

        //1. based on resourceTables to identify mutual relations among resources
        updateDependency()

        //2. for each resource, identify relations based on derived table
        resourceCluster.values
                .flatMap { it.paramsToTables.values.flatMap { p2table-> p2table.targets as MutableList<String> }.toSet() }.toSet()
                .forEach { derivedTab->
                    //get probability of res -> derivedTab, we employ the max to represent the probability
                    val relatedResources = paramToSameTable(null, derivedTab)

                    val absRelatedResources = paramToSameTable(null, derivedTab, 1.0)

                    if(relatedResources.size > 1){
                        if(absRelatedResources.size > 1){
                            val mutualRelation = MutualResourcesRelations(absRelatedResources, 1.0, derivedTab)

                            absRelatedResources.forEach { res ->
                                val relations = dependencies.getOrPut(res){ mutableListOf()}
                                if(relations.find { r-> r.targets.containsAll(mutualRelation.targets) && r.additionalInfo == mutualRelation.additionalInfo } == null )
                                    relations.add(mutualRelation)
                            }
                        }

                        val rest = if(absRelatedResources.size > 1) relatedResources.filter{!absRelatedResources.contains(it)} else relatedResources

                        if(rest.size > 1){
                            for(i  in 0..(rest.size-2)){
                                val res = rest[i]
                                val prob = probOfResToTable(res, derivedTab)
                                for(j in i ..(rest.size - 1)){
                                    val relatedRes = rest[j]
                                    val relatedProb = probOfResToTable(relatedRes, derivedTab)

                                    val res2Res = MutualResourcesRelations(mutableListOf(res, relatedRes), ((prob + relatedProb)/2.0), derivedTab)

                                    dependencies.getOrPut(res){ mutableListOf()}.apply {
                                        if(find { r -> r.targets.contains(res2Res.targets) && r.additionalInfo == res2Res.additionalInfo} == null)
                                            add(res2Res)
                                    }
                                    dependencies.getOrPut(relatedRes){ mutableListOf()}.apply {
                                        if(find { r -> r.targets.contains(res2Res.targets)  && r.additionalInfo == res2Res.additionalInfo } == null)
                                            add(res2Res)
                                    }
                                }
                            }
                        }
                    }
                }
    }

    private fun updateDependency(){
        resourceTables.values.flatten().toSet().forEach { tab->
            val mutualResources = resourceTables.filter { it.value.contains(tab) }.map { it.key }.toHashSet().toList()

            if(mutualResources.isNotEmpty() && mutualResources.size > 1){
                val mutualRelation = MutualResourcesRelations(mutualResources, 1.0, tab)

                mutualResources.forEach { res ->
                    val relations = dependencies.getOrPut(res){ mutableListOf()}
                    if(relations.find { r-> r.targets.contains(mutualRelation.targets) && r.additionalInfo == mutualRelation.additionalInfo } == null )
                        relations.add(mutualRelation)
                }
            }
        }
    }

    /**
     * detect possible dependency among resources,
     * the entry is structure mutation
     *
     * [isBetter] 1 means current is better than previous, 0 means that they are equal, and -1 means current is worse than previous
     */
    fun detectDependency(previous : EvaluatedIndividual<ResourceRestIndividual>, current : EvaluatedIndividual<ResourceRestIndividual>, isBetter: Int){
        val seqPre = previous.individual.getResourceCalls()
        val seqCur = current.individual.getResourceCalls()

        when(seqCur.size - seqPre.size){
            0 ->{
                //SWAP, MODIFY, REPLACE
                if(seqPre.map { it.resourceInstance.getAResourceKey() }.toList() == seqCur.map { it.resourceInstance.getAResourceKey() }.toList()){
                    //MODIFY
                    /*
                        For instance, ABCDEFG, if we replace B with another resource instance, then check CDEFG.
                        if C is worse/better, C rely on B, else C may not rely on B, i.e., the changes of B cannot affect C.
                     */
                    if(isBetter != 0){
                        val locOfModified = (0 until seqCur.size).find { seqPre[it].resourceInstance.getKey() != seqCur[it].resourceInstance.getKey() }?:
                            return
                        //throw IllegalArgumentException("mutator does not change anything.")

                        val modified = seqCur[locOfModified]

                        var actionIndex = seqCur.mapIndexed { index, restResourceCalls ->
                            if(index < locOfModified) restResourceCalls.actions.size
                            else 0
                        }.sum()

                        ((locOfModified + 1) until seqCur.size).forEach { indexOfCalls ->
                            var isAnyChange = false
                            seqCur[indexOfCalls].actions.forEach {
                                actionIndex += 1
                                val actionA = actionIndex
                                isAnyChange = isAnyChange || compare(actionA, current, actionIndex, previous) !=0
                            }

                            if(isAnyChange){
                                //seqPre[indexOfCalls] depends on added
                                val seqKey = seqPre[indexOfCalls].resourceInstance.getAResourceKey()
                                updateDependencies(seqKey, mutableListOf(modified!!.resourceInstance.getAResourceKey()), RestResourceStructureMutator.MutationType.MODIFY.toString())
                            }
                        }
                    }


                }else if(seqPre.map { it.resourceInstance.getAResourceKey() }.toSet() == seqCur.map { it.resourceInstance.getAResourceKey() }.toSet()){
                    //SWAP
                    /*
                        For instance, ABCDEFG, if we swap B and F, become AFCDEBG, then check FCDE (do not include B!).
                        if F is worse, F may rely on {C, D, E, B}
                        if C is worse, C rely on B; else if C is better, C rely on F; else C may not rely on B and F

                        there is another case regarding duplicated resources calls (i.e., same resource and same actions) in a test,
                        for instance, ABCDB*B**EF, swap B and F, become AFCDB*B**EB, in this case,
                        B* probability become better, B** is same, B probability become worse
                     */
                    if(isBetter != 0){
                        //find the element is not in the same position
                        val swapsloc = mutableListOf<Int>()

                        seqCur.forEachIndexed { index, restResourceCalls ->
                            if(restResourceCalls.resourceInstance.getKey() != seqPre[index].resourceInstance.getKey())
                                swapsloc.add(index)
                        }

                        assert(swapsloc.size == 2)
                        val swapF = seqCur[swapsloc[0]]
                        val swapB = seqCur[swapsloc[1]]

                        val locOfF = swapsloc[0]

                        val distance = swapF.actions.size - swapB.actions.size

                        var actionIndex = seqCur.mapIndexed { index, restResourceCalls ->
                            if(index < locOfF) restResourceCalls.actions.size
                            else 0
                        }.sum()


                        ( (locOfF + 1) until swapsloc[1] ).forEach { indexOfCalls ->
                            var isAnyChange = false
                            var changeDegree = 0
                            seqCur[indexOfCalls].actions.forEach {
                                actionIndex += 1
                                val actionA = actionIndex + distance

                                val compareResult = swapF.actions.plus(swapB.actions).find { it.getName() == current.individual.seeActions()[actionA].getName() }.run {
                                    if(this == null) compare(actionA, current, actionIndex, previous)
                                    else compare(this.getName(), current, previous)
                                }.also { r-> changeDegree += r }

                                isAnyChange = isAnyChange || compareResult!=0

                                //isAnyChange = isAnyChange || compare(actionA, current, actionIndex, previous).also { r-> changeDegree += r } !=0
                            }

                            if(isAnyChange){
                                val seqKey = seqPre[indexOfCalls].resourceInstance.getAResourceKey()

                                val relyOn = if(changeDegree > 0){
                                    mutableListOf(swapF!!.resourceInstance.getAResourceKey())
                                }else if(changeDegree < 0){
                                    mutableListOf(swapB!!.resourceInstance.getAResourceKey())
                                }else
                                    mutableListOf(swapB!!.resourceInstance.getAResourceKey(), swapF!!.resourceInstance.getAResourceKey())

                                updateDependencies(seqKey, relyOn, RestResourceStructureMutator.MutationType.SWAP.toString())
                            }

                            //check F
                            if(compare(swapsloc[0], current, swapsloc[1], previous) != 0){

                                val middles = seqCur.subList(swapsloc[0]+1, swapsloc[1]+1).map { it.resourceInstance.getAResourceKey() }
                                middles.forEach {
                                    updateDependencies(swapF.resourceInstance.getAResourceKey(), mutableListOf(it),RestResourceStructureMutator.MutationType.SWAP.toString(), (1.0/middles.size))
                                }
                            }
                        }

                    }

                }else{
                    //REPLACE
                    /*
                        For instance, ABCDEFG, if we replace B with H become AHCDEFG, then check CDEFG.
                        if C is worse, C rely on B; else if C is better, C rely on H; else C may not rely on B and H

                     */
                    if(isBetter != 0){
                        val mutatedIndex = (0 until seqCur.size).find { seqCur[it].resourceInstance.getKey() != seqPre[it].resourceInstance.getKey() }!!

                        val replaced = seqCur[mutatedIndex]!!
                        val replace = seqPre[mutatedIndex]!!

                        val locOfReplaced = seqCur.indexOf(replaced)
                        val distance = locOfReplaced - seqPre.indexOf(replace)

                        var actionIndex = seqCur.mapIndexed { index, restResourceCalls ->
                            if(index < locOfReplaced) restResourceCalls.actions.size
                            else 0
                        }.sum()

                        ( (locOfReplaced + 1) until seqCur.size ).forEach { indexOfCalls ->
                            var isAnyChange = false
                            var changeDegree = 0
                            seqCur[indexOfCalls].actions.forEach {
                                actionIndex += 1
                                val actionA = actionIndex + distance

                                val compareResult = replaced.actions.plus(replace.actions).find { it.getName() == current.individual.seeActions()[actionA].getName() }.run {
                                    if(this == null) compare(actionA, current, actionIndex, previous)
                                    else compare(this.getName(), current, previous)
                                }.also { r-> changeDegree += r }

                                isAnyChange = isAnyChange || compareResult!=0
                                //isAnyChange = isAnyChange || compare(actionA, current, actionIndex, previous).also { r-> changeDegree += r } !=0
                            }

                            if(isAnyChange){
                                val seqKey = seqPre[indexOfCalls].resourceInstance.getAResourceKey()

                                val relyOn = if(changeDegree > 0){
                                    mutableListOf(replaced.resourceInstance.getAResourceKey())
                                }else if(changeDegree < 0){
                                    mutableListOf(replace.resourceInstance.getAResourceKey())
                                }else
                                    mutableListOf(replaced.resourceInstance.getAResourceKey(), replace.resourceInstance.getAResourceKey())

                                updateDependencies(seqKey, relyOn, RestResourceStructureMutator.MutationType.REPLACE.toString())
                            }
                        }

                    }
                }
            }
            1 ->{
                //ADD
                /*
                     For instance, ABCDEFG, if we add H at 3nd position, become ABHCDEFG, then check CDEFG.
                     if C is better, C rely on H; else if C is worse, C rely on H ? ;else C may not rely on H
                */
                if(isBetter != 0){
                    val added = seqCur.find { cur -> seqPre.find { pre-> pre.resourceInstance.getKey() == cur.resourceInstance.getKey() } == null }?: return
                    val addedKey = added!!.resourceInstance.getAResourceKey()

                    val locOfAdded = seqCur.indexOf(added!!)
                    var actionIndex = seqCur.mapIndexed { index, restResourceCalls ->
                        if(index < locOfAdded) restResourceCalls.actions.size
                        else 0
                    }.sum()

                    val distance = added!!.actions.size

                    (locOfAdded until seqPre.size).forEach { indexOfCalls ->
                        var isAnyChange = false

                        seqPre[indexOfCalls].actions.forEach {
                            val actionA = actionIndex + distance

                            val compareResult = added.actions.find { it.getName() == current.individual.seeActions().get(actionA).getName() }.run {
                                if(this == null) compare(actionA, current, actionIndex, previous)
                                else compare(this.getName(), current, previous)
                            }

                            isAnyChange = isAnyChange || compareResult!=0
                            actionIndex += 1 //actionB
                            //isAnyChange = isAnyChange || compare(actionA, current, actionIndex, previous)!=0
                        }

                        if(isAnyChange){
                            //seqPre[indexOfCalls] depends on added
                            val seqKey = seqPre[indexOfCalls].resourceInstance.getAResourceKey()
                            updateDependencies(seqKey, mutableListOf(addedKey), RestResourceStructureMutator.MutationType.ADD.toString())
                        }
                    }
                }
            }
            -1 ->{
                //DELETE
                /*
                     For instance, ABCDEFG, if B is deleted, become ACDEFG, then check CDEFG.
                     if C is worse, C rely on B;
                        else if C is better, C rely one B ?;
                        else C may not rely on B.

                     there is another case regarding duplicated resources calls (i.e., same resource and same actions) in a test, for instance, ABCB* (B* denotes the 2nd B), if B is deleted, become ACB*, then check CB* as before,
                     when comparing B*, B* probability achieves better performance by taking target from previous first B, so we need to compare with merged targets, i.e., B and B*.
                */
                if(isBetter != 0){
                    val delete = seqPre.find { pre -> seqCur.find { cur-> pre.resourceInstance.getKey() == cur.resourceInstance.getKey() } == null }?:return
                    val deleteKey = delete!!.resourceInstance.getAResourceKey()

                    val locOfDelete = seqPre.indexOf(delete!!)
                    var actionIndex = seqPre.mapIndexed { index, restResourceCalls ->
                        if(index < locOfDelete) restResourceCalls.actions.size
                        else 0
                    }.sum()

                    val distance = delete!!.actions.size

                    ((locOfDelete + 1) until seqPre.size).forEach { indexOfCalls ->
                        var isAnyChange = false

                        seqPre[indexOfCalls].actions.forEach {
                            actionIndex += 1 //actionB
                            val actionA = actionIndex - distance
                            val compareResult = delete.actions.find { it.getName() == current.individual.seeActions()[actionA].getName() }.run {
                                if(this == null) compare(actionA, current, actionIndex, previous)
                                else compare(this.getName(), current, previous)
                            }

                            isAnyChange = isAnyChange || compareResult!=0
                        }

                        if(isAnyChange){
                            //seqPre[indexOfCalls] depends on added
                            val seqKey = seqPre[indexOfCalls].resourceInstance.getAResourceKey()
                            updateDependencies(seqKey, mutableListOf(deleteKey), RestResourceStructureMutator.MutationType.DELETE.toString())
                        }
                    }
                }
            }
            else ->{
                TODO("not support yet")
            }
        }

    }

    /**
     * update dependencies based on derived info
     * [additionalInfo] is structure mutator in this context
     */
    private fun updateDependencies(key : String, target : MutableList<String>, additionalInfo : String, probability : Double = 1.0){

        val relation = if(target.size == 1 && target[0] == key) SelfResourcesRelation(key, probability, additionalInfo)
                    else ResourceRelatedToResources(listOf(key), target, probability, info = additionalInfo)

        updateDependencies(relation, additionalInfo)
    }

    private fun updateDependencies(relation : ResourceRelatedToResources, additionalInfo: String){
        val found = dependencies.getOrPut(relation.originalKey()){ mutableListOf()}.find { it.targets.containsAll(relation.targets) }
        if (found == null) dependencies[relation.originalKey()]!!.add(relation)
        else {
            //TODO a strategy to manipulate the probability
            found.probability = relation.probability
            if(found.additionalInfo.isBlank())
                found.additionalInfo = additionalInfo
            else if(!found.additionalInfo.contains(additionalInfo))
                found.additionalInfo += ";$additionalInfo"
        }
    }


    private fun compare(actionName : String, eviA : EvaluatedIndividual<ResourceRestIndividual>, eviB : EvaluatedIndividual<ResourceRestIndividual>) : Int{
        val actionAs = mutableListOf<Int>()
        val actionBs = mutableListOf<Int>()
        eviA.individual.seeActions().forEachIndexed { index, action ->
            if(action.getName() == actionName)
                actionAs.add(index)
        }

        eviB.individual.seeActions().forEachIndexed { index, action ->
            if(action.getName() == actionName)
                actionBs.add(index)
        }

        return compare(actionAs, eviA, actionBs, eviB)
    }

    /**
     *  is the performance of [actionA] better than the performance [actionB]?
     */
    private fun compare(actionA : Int, eviA : EvaluatedIndividual<ResourceRestIndividual>, actionB: Int, eviB : EvaluatedIndividual<ResourceRestIndividual>) : Int{
        return compare(mutableListOf(actionA), eviA, mutableListOf(actionB), eviB)
    }

    private fun compare(actionA : MutableList<Int>, eviA : EvaluatedIndividual<ResourceRestIndividual>, actionB: MutableList<Int>, eviB : EvaluatedIndividual<ResourceRestIndividual>) : Int{
        val alistHeuristics = eviA.fitness.getViewOfData().filter { actionA.contains(it.value.actionIndex) }
        val blistHeuristics = eviB.fitness.getViewOfData().filter { actionB.contains(it.value.actionIndex) }

        //whether actionA reach more
        if(alistHeuristics.size > blistHeuristics.size) return 1
        else if(alistHeuristics.size < blistHeuristics.size) return -1

        //whether actionA reach new
        if(alistHeuristics.filter { !blistHeuristics.containsKey(it.key) }.isNotEmpty()) return 1
        else if(blistHeuristics.filter { !alistHeuristics.containsKey(it.key) }.isNotEmpty()) return -1

        val targets = alistHeuristics.keys.plus(blistHeuristics.keys).toHashSet()

        targets.forEach { t->
            val ta = alistHeuristics[t]
            val tb = blistHeuristics[t]

            if(ta != null && tb != null){
                if(ta.distance > tb.distance)
                    return 1
                else if(ta.distance < tb.distance)
                    return -1
            }
        }

        return 0
    }

    private fun probOfResToTable(resourceKey: String, tableName: String) : Double{
        return resourceCluster[resourceKey]!!.paramsToTables.values.filter { it.targets.contains(tableName) }.map { it.probability}.max()!!
    }

    private fun paramToSameTable(resourceKey: String?, tableName: String, minSimilarity : Double = 0.0) : List<String>{
        return resourceCluster
                .filter { resourceKey!= null || it.key != resourceKey }
                .filter {
                    it.value.paramsToTables.values
                        .find { p -> p.targets.contains(tableName) && p.probability >= minSimilarity} != null
                }.keys.toList()
    }

    /**
     * this function is used to initialized ad-hoc individuals
     */
    fun createAdHocIndividuals(auth: AuthenticationInfo, adHocInitialIndividuals : MutableList<ResourceRestIndividual>){
        val sortedResources = resourceCluster.values.sortedByDescending { it.path.levels() }.asSequence()

        //GET, PATCH, DELETE
        sortedResources.forEach { ar->
            ar.actions.filter { it is RestCallAction && it.verb != HttpVerb.POST && it.verb != HttpVerb.PUT }.forEach {a->
                val call = ar.sampleOneAction(a.copy() as RestAction, randomness, config.maxTestSize)
                call.actions.forEach {a->
                    if(a is RestCallAction) a.auth = auth
                }
                adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
            }
        }

        //all POST with one post action
        sortedResources.forEach { ar->
            ar.actions.filter { it is RestCallAction && it.verb == HttpVerb.POST}.forEach { a->
                val call = ar.sampleOneAction(a.copy() as RestAction, randomness, config.maxTestSize)
                call.actions.forEach { (it as RestCallAction).auth = auth }
                adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
            }
        }

        sortedResources
                .filter { it.actions.find { a -> a is RestCallAction && a.verb == HttpVerb.POST } != null && it.postCreation.actions.size > 1   }
                .forEach { ar->
                    ar.genPostChain(randomness, config.maxTestSize)?.let {call->
                        call.actions.forEach { (it as RestCallAction).auth = auth }
                        call.doesCompareDB = hasDBHandler()
                        adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
                    }
                }

        //PUT
        sortedResources.forEach { ar->
            ar.actions.filter { it is RestCallAction && it.verb == HttpVerb.PUT }.forEach {a->
                val call = ar.sampleOneAction(a.copy() as RestAction, randomness)
                call.actions.forEach { (it as RestCallAction).auth = auth }
                adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
            }
        }

        //template
        sortedResources.forEach { ar->
            ar.templates.values.filter { t-> t.template.contains(RTemplateHandler.SeparatorTemplate) }
                    .forEach {ct->
                        val call = ar.sampleRestResourceCalls(ct.template, randomness, config.maxTestSize)
                        call.actions.forEach { if(it is RestCallAction) it.auth = auth }
                        adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
                    }
        }

    }

    fun isDependencyNotEmpty() : Boolean{
        return dependencies.isNotEmpty()
    }

    /**
     * the method is invoked when mutating an individual with "ADD" resource-based structure mutator
     * in order to keep the values of individual same with previous, we bind values of new resource based on existing values
     *
     * TODO as follows
     * An example of an individual is "ABCDE", each letter is an resource call,
     * 1. at random select an resource call which has [ResourceRelatedToResources], e.g., "C"
     * 2. at random select one of its [ResourceRelatedToResources], e.g., "F"
     * 3.1 if C depends on F, then we add "F" in front of "C"
     * 3.2 if C and F are mutual relationship, it means that C and F are related to same table.
     *          In order to keep the order of creation of resources, we add F after C,
     *          but there may cause an error, e.g.,
     * 3.3 if F depends on C, then we add "F" after "C"
     */
    fun handleAddDepResource(ind : ResourceRestIndividual, maxTestSize : Int) : RestResourceCalls?{
        val existingRs = ind.getResourceCalls().map { it.resourceInstance.getAResourceKey() }
        val candidates = dependencies.filterKeys { existingRs.contains(it) }.keys

        if(candidates.isNotEmpty()){
            val dependerPath = randomness.choose(candidates)
            //val depender = randomness.choose(ind.getResourceCalls().filter { it.resourceInstance.getAResourceKey() == dependerPath })
            val relationCandidates = dependencies[dependerPath]!!
            /*
                add self relation with a relative low probability, i.e., 20%
             */
            val relation = randomness.choose(
                        relationCandidates.filter { (if(it is SelfResourcesRelation) randomness.nextBoolean(0.2) else randomness.nextBoolean(it.probability))
                    }.run { if(isEmpty()) relationCandidates else this })

            /*
                TODO insert call at a position regarding [relation]
             */
            return resourceCluster[randomness.choose(relation.targets)]!!.sampleAnyRestResourceCalls(randomness,maxTestSize )
        }
        return null
    }


    fun handleAddResource(ind : ResourceRestIndividual, maxTestSize : Int) : RestResourceCalls{
        val existingRs = ind.getResourceCalls().map { it.resourceInstance.getAResourceKey() }
        var candidate = randomness.choose(getResourceCluster().filterNot { r-> existingRs.contains(r.key) }.keys)
        return resourceCluster[candidate]!!.sampleAnyRestResourceCalls(randomness,maxTestSize )
    }

    /**
     *  if involved db, there may a problem to solve,
     *  e.g., an individual "ABCDE",
     *  "B" and "C" are mutual, which means that they are related to same table, "B" -> Tables TAB1, TAB2, and "C" -> Tables TAB2, TAB3
     *  in order to create resources for "B", we insert an row in TAB1 and an row in TAB2, but TAB1 and TAB2 may refer to other tables, so we also need to insert relative
     *  rows in reference tables,
     *  1. if TAB1 and TAB2 do not share any same reference tables, it is simple, just insert row with random values
     *  2. if TAB1 and TAB2 share same reference tables, it depends
     */
    fun sampleRelatedResources(calls : MutableList<RestResourceCalls>, sizeOfResource : Int, maxSize : Int) {
        var start = - calls.sumBy { it.actions.size }

        val first = randomness.choose(dependencies.keys)
        sampleCall(first, true, calls, maxSize)
        var sampleSize = 1
        var size = calls.sumBy { it.actions.size } + start
        val excluded = mutableListOf<String>()
        val relatedResources = mutableListOf<RestResourceCalls>()
        excluded.add(first)
        relatedResources.add(calls.last())

        while (sampleSize < sizeOfResource && size < maxSize){
            val candidates = dependencies[first]!!.flatMap { it.targets as MutableList<String> }.filter { !excluded.contains(it) }
            if(candidates.isEmpty())
                break

            val related = randomness.choose(candidates)
            excluded.add(related)
            sampleCall(related, true, calls, size, false, if(related.isEmpty()) null else relatedResources)
            relatedResources.add(calls.last())
            size = calls.sumBy { it.actions.size } + start
        }
    }

    fun sampleCall(
            resourceKey: String,
            doesCreateResource: Boolean,
            calls : MutableList<RestResourceCalls>,
            size : Int,
            forceInsert: Boolean = false,
            bindWith : MutableList<RestResourceCalls>? = null
    ){
        val ar = resourceCluster[resourceKey]
                ?: throw IllegalArgumentException("resource path $resourceKey does not exist!")

        if(!doesCreateResource ){
            val call = ar.sampleIndResourceCall(randomness,size)
            calls.add(call)
            /*
                with a 50% probability, sample GET with an existing resource in db
             */
            if(hasDBHandler() && call.template.template == HttpVerb.GET.toString() && randomness.nextBoolean(0.5)){
                val created = handleCallWithDBAction(ar, call, false, true)
            }
            return
        }

        assert(!ar.isIndependent())
        var candidateForInsertion : String? = null

        if(hasDBHandler() && ar.paramsToTables.isNotEmpty() && (if(forceInsert) forceInsert else randomness.nextBoolean(0.5))){
            //Insert - GET/PUT/PATCH
            val candidates = ar.templates.filter { it.value.independent }
            candidateForInsertion = if(candidates.isNotEmpty()) randomness.choose(candidates.keys) else null
        }


        val candidate = if(candidateForInsertion.isNullOrBlank()) {
            //prior to select the template with POST
            ar.templates.filter { !it.value.independent }.run {
                if(isNotEmpty())
                    randomness.choose(this.keys)
                else
                    randomness.choose(ar.templates.keys)
            }
        } else candidateForInsertion

        val call = ar.genCalls(candidate,randomness,size,true,true)
        calls.add(call)

        if(hasDBHandler()){
            if(call.status != RestResourceCalls.ResourceStatus.CREATED
                    || checkIfDeriveTable(call)
                    || candidateForInsertion != null){

                call.doesCompareDB = true
                /*
                    derive possible db, and bind value according to db
                */
                val created = handleCallWithDBAction(ar, call, forceInsert, false)
                if(!created){
                    //TODO MAN record the call when postCreation fails
                }
            }else{
                call.doesCompareDB = (!call.template.independent) && (resourceTables[ar.path.toString()] == null)
            }
        }

        if(bindWith != null){
            bindCallWithFront(call, bindWith)
        }
    }

    fun bindCallWithFront(call: RestResourceCalls, front : MutableList<RestResourceCalls>){

        val targets = front.flatMap { it.actions.filter {a -> a is RestCallAction }}

        /*
         TODO remove duplicated post actions
         e.g., A/{a}, A/{a}/B/{b}, A/{a}/C/{c}
         if there are A/{a} and A/{a}/B/{b} that exists in the test,
         1) when appending A/{a}/C/{c}, A/{a} should not be created again;
         2) But when appending A/{a} in the test, A/{a} with new values should be created.
        */
        if(call.actions.size > 1){
            call.actions.removeIf {action->
                action is RestCallAction &&
                        (action.verb == HttpVerb.POST || action.verb == HttpVerb.PUT) &&
                        action != call.actions.last() &&
                        targets.find {it.getName() == action.getName()}!=null
            }
        }

        /*
         bind values based front actions,
         */
        call.actions
                .filter { it is RestCallAction }
                .forEach { a ->
                    (a as RestCallAction).parameters.forEach { p->
                        targets.forEach { ta->
                            ParamUtil.bindParam(p, a.path, (ta as RestCallAction).path, ta.parameters)
                        }
                    }
                }

        /*
         bind values of dbactions based front dbactions
         */
        front.flatMap { it.dbActions }.apply {
            if(isNotEmpty())
                bindCallWithOtherDBAction(call, this.toMutableList())
        }
    }

    private fun checkIfDeriveTable(call: RestResourceCalls) : Boolean{
        if(!call.template.independent) return false

        call.actions.first().apply {
            if (this is RestCallAction){
                if(this.parameters.isNotEmpty()) return true
            }
        }
        return false
    }

    private fun deriveRelatedTables(ar: RestResource, startWithPostIfHas : Boolean = true){
        val post = ar.postCreation.actions.firstOrNull()
        val skip = if(startWithPostIfHas && post != null && (post as RestCallAction).path.isLastElementAParameter())  1 else 0

        val missingParams = mutableListOf<String>()
        var withParam = false

        ar.tokens.values.reversed().asSequence().forEachIndexed { index, pathRToken ->
            if(index >= skip){
                if(pathRToken.isParameter){
                    missingParams.add(0, pathRToken.getKey())
                    withParam = true
                }else if(withParam){
                    missingParams.set(0, ParamUtil.generateParamId(arrayOf(pathRToken.getKey(), missingParams[0]))  )
                }
            }
        }

        val lastToken = if(missingParams.isNotEmpty()) missingParams.last()
                        else if(ar.tokens.isNotEmpty()) ParamUtil.generateParamText(ar.tokens.map { it.value.getKey() })
                        else null
        ar.actions
                .filter { it is RestCallAction }
                .flatMap { (it as RestCallAction).parameters }
                .filter { it !is PathParam }
                .forEach { p->
                    when(p){
                        is BodyParam -> missingParams.add(
                                (if(lastToken!=null) ParamUtil.appendParam(lastToken, "") else "") +
                                        (if(p.gene is ObjectGene && p.gene.refType != null && p.name.toLowerCase() != p.gene.refType.toLowerCase() )
                                            ParamUtil.appendParam(p.name, p.gene.refType) else p.name)
                        )
                        is QueryParam -> missingParams.add((if(lastToken!=null) ParamUtil.appendParam(lastToken, "") else "") + p.name)
                        else ->{
                            //do nothing
                        }
                    }
                }

        missingParams.forEach { pname->
            val params = ParamUtil.parseParams(pname)

            var similarity = 0.0
            var tableName = ""

            params.reversed().forEach findP@{
                dataInDB.forEach { t, u ->
                    val score = ParserUtil.stringSimilarityScore(it, t)
                    if(score > similarity){
                        similarity =score
                        tableName = t
                    }
                }
                if(similarity >= ParserUtil.SimilarityThreshold){
                    return@findP
                }
            }

            val p = params.last()
            val rt = ParamRelatedToTable(p, if(dataInDB[tableName] != null) mutableListOf(tableName) else mutableListOf(), similarity, pname)
            ar.paramsToTables.getOrPut(rt.notateKey()){
                rt
            }
        }
    }

    /**
     * update [resourceTables] based on test results from SUT/EM
     */
    fun updateResourceTables(resourceRestIndividual: ResourceRestIndividual, dto : TestResultsDto){

        resourceRestIndividual.seeActions().forEachIndexed { index, action ->
            val dbDto = dto.extraHeuristics[index].databaseExecutionDto

            if(action is RestCallAction){
                val resourceId = action.path.toString()
                val verb = action.verb.toString()

                val update = resourceCluster[resourceId]!!.updateActionRelatedToTable(verb, dbDto)
                resourceTables.getOrPut(resourceId){ mutableSetOf()}.addAll(resourceCluster[resourceId]!!.getRelatedTables())

                if(update){
                    //TODO update dependencies once any update on resourceTables or paramToTables
                }
            }
        }

    }

    private fun handleCallWithDBAction(ar: RestResource, call: RestResourceCalls, forceInsert : Boolean, forceSelect : Boolean) : Boolean{

        if(ar.paramsToTables.values.find { it.probability < ParserUtil.SimilarityThreshold || it.targets.isEmpty()} == null){
            var failToLinkWithResource = false

            val paramsToBind =
                    ar.actions.filter { (it is RestCallAction) && it.verb != HttpVerb.POST }
                            .flatMap { (it as RestCallAction).parameters.map { p-> ParamRelatedToTable.getNotateKey(p.name.toLowerCase()).toLowerCase()  } }

            /*
                key is table name
                value is a list of information about params
             */
            val tableToParams = mutableMapOf<String, MutableSet<String>>()

            ar.paramsToTables.forEach { t, u ->
                if(paramsToBind.contains(t.toLowerCase())){
                    val params = tableToParams.getOrPut(u.targets.first().toString()){ mutableSetOf() }
                    params.add(u.additionalInfo)
                }
            }

            snapshotDB()

            val dbActions = mutableListOf<DbAction>()
            tableToParams.keys.forEach { tableName->
                if(forceInsert){
                    generateInserSql(tableName, dbActions)
                }else if(forceSelect){
                    if(dataInDB[tableName] != null && dataInDB[tableName]!!.isNotEmpty()) generateSelectSql(tableName, dbActions)
                    else failToLinkWithResource = true
                }else{
                    if(dataInDB[tableName]!= null ){
                        val size = dataInDB[tableName]!!.size
                        when{
                            size < config.minRowOfTable -> generateInserSql(tableName, dbActions).apply {
                                failToLinkWithResource = failToLinkWithResource || !this
                            }
                            else ->{
                                if(randomness.nextBoolean(config.probOfSelectFromDB)){
                                    generateSelectSql(tableName, dbActions)
                                }else{
                                    generateInserSql(tableName, dbActions).apply {
                                        failToLinkWithResource = failToLinkWithResource || !this
                                    }
                                }
                            }
                        }
                    }else
                        failToLinkWithResource = true
                }
            }

            if(dbActions.isNotEmpty()){
                dbActions.removeIf { select->
                    select.representExistingData && dbActions.find { !it.representExistingData && select.table.name == it.table.name } != null
                }

                DbActionUtils.randomizeDbActionGenes(dbActions.filter { !it.representExistingData }, randomness)
                repairDbActions(dbActions.filter { !it.representExistingData }.toMutableList())

                tableToParams.values.forEach { ps ->
                    bindCallActionsWithDBAction(ps.toHashSet().toList(), call, dbActions)
                }

                call.dbActions.addAll(dbActions)
            }
            return tableToParams.isNotEmpty() && !failToLinkWithResource
        }
        return false
    }

    fun repairRestResourceCalls(call: RestResourceCalls)  : Boolean{
        call.repairGenesAfterMutation()

        if(hasDBHandler() && call.dbActions.isNotEmpty()){
            val key = call.resourceInstance.ar.path.toString()
            val ar = resourceCluster[key]
                    ?: throw IllegalArgumentException("resource path $key does not exist!")

            /*
                TODO repair dbaction after mutation
             */
            val previous = call.dbActions.map { it.table.name }
            call.dbActions.clear()
            handleCallWithDBAction(ar, call, true, false)

            if(call.dbActions.size != previous.size){
                //remove additions
                call.dbActions.removeIf {
                    !previous.contains(it.table.name)
                }
            }
        }
        return true
    }

    private fun generateSelectSql(tableName : String, dbActions: MutableList<DbAction>, forceDifferent: Boolean = false, withDbAction: DbAction?=null){
        if(dbActions.map { it.table.name }.contains(tableName)) return

        assert(dataInDB[tableName] != null && dataInDB[tableName]!!.isNotEmpty())
        assert(!forceDifferent || withDbAction == null)

        val columns = if(forceDifferent && withDbAction!!.representExistingData){
            selectToDataRowDto(withDbAction, tableName)
        }else {
            randomness.choose(dataInDB[tableName]!!)
        }

        val selectDbAction = (sampler as ResourceRestSampler).sqlInsertBuilder!!.extractExistingByCols(tableName, columns)
        dbActions.add(selectDbAction)
    }

    private fun generateInserSql(tableName : String, dbActions: MutableList<DbAction>) : Boolean{
        val insertDbAction =
                (sampler as ResourceRestSampler).sqlInsertBuilder!!
                        .createSqlInsertionActionWithAllColumn(tableName)

        if(insertDbAction.isEmpty()) return false

        val pasted = mutableListOf<DbAction>()
        insertDbAction.reversed().forEach {ndb->
            val index = dbActions.indexOfFirst { it.table.name == ndb.table.name && !it.representExistingData}
            if(index == -1) pasted.add(0, ndb)
            else{
                if(pasted.isNotEmpty()){
                    dbActions.addAll(index+1, pasted)
                    pasted.clear()
                }
            }
        }

        if(pasted.isNotEmpty()){
            if(pasted.size == insertDbAction.size)
                dbActions.addAll(pasted)
            else
                dbActions.addAll(0, pasted)
        }
        return true
    }

    private fun bindCallWithOtherDBAction(call : RestResourceCalls, dbActions: MutableList<DbAction>){
        val dbRelatedToTables = dbActions.map { it.table.name }.toMutableList()
        val dbTables = call.dbActions.map { it.table.name }.toMutableList()

        if(dbRelatedToTables.containsAll(dbTables)){
            call.dbActions.clear()
        }else{
            call.dbActions.removeIf { dbRelatedToTables.contains(it.table.name) }
            //val selections = mutableListOf<DbAction>()
            repairDbActions(dbActions.plus(call.dbActions).toMutableList())
            val previous = mutableListOf<DbAction>()
            call.dbActions.forEach {
                val refers = DbActionUtils.repairFK(it, dbActions.plus(previous).toMutableList())
                //selections.addAll( (sampler as ResourceRestSampler).sqlInsertBuilder!!.generateSelect(refers) )
                previous.add(it)
            }
            //call.dbActions.addAll(0, selections)
        }

        val paramsToBind =
                call.actions.filter { (it is RestCallAction) && it.verb != HttpVerb.POST }
                        .flatMap { (it as RestCallAction).parameters.map { p-> ParamRelatedToTable.getNotateKey(p.name.toLowerCase()).toLowerCase()  } }

        val targets = call.resourceInstance.ar.paramsToTables.filter { paramsToBind.contains(it.key.toLowerCase())}

        val tables = targets.map { it.value.targets.first().toString() }.toHashSet()

        tables.forEach { tableName->
            if(dbRelatedToTables.contains(tableName)){
                val ps = targets.filter { it.value.targets.first().toString() == tableName }.map { it.value.additionalInfo }.toHashSet().toList()
                val relatedDbActions = dbActions.plus(call.dbActions).first { it.table.name == tableName }
                bindCallActionsWithDBAction(ps, call, listOf(relatedDbActions), true)
            }
        }

    }

    private fun bindCallActionsWithDBAction(ps: List<String>, call: RestResourceCalls, dbActions : List<DbAction>, bindParamBasedOnDB : Boolean = false){
        ps.forEach { pname->
            val pss = ParamUtil.parseParams(pname)
            call.actions
                    .filter { (it is RestCallAction) && it.parameters.find { it.name.toLowerCase() == pss.last().toLowerCase() } != null }
                    .forEach { action->
                        (action as RestCallAction).parameters.filter { it.name.toLowerCase() == pss.last().toLowerCase() }
                                .forEach {param->
                                    dbActions.forEach { db->
                                        ParamUtil.bindParam(db, param,if(pss.size > 1) pss[pss.size - 2] else "", db.representExistingData || bindParamBasedOnDB )
                                    }
                                }
                    }
        }
    }

    private fun selectToDataRowDto(dbAction : DbAction, tableName : String) : DataRowDto{
        dbAction.seeGenes().forEach { assert((it is SqlPrimaryKeyGene || it is ImmutableDataHolderGene || it is SqlForeignKeyGene)) }
        val set = dbAction.seeGenes().filter { it is SqlPrimaryKeyGene }.map { ((it as SqlPrimaryKeyGene).gene as ImmutableDataHolderGene).value }.toSet()
        return randomness.choose(dataInDB[tableName]!!.filter { it.columnData.toSet().equals(set) })
    }

    private fun hasDBHandler() : Boolean = sampler is ResourceRestSampler && (sampler as ResourceRestSampler).sqlInsertBuilder!= null && config.doesInvolveDB

    /**
     * update existing data in db
     * the existing data can be applied to an sampled individual
     */
    private fun snapshotDB(){
        if(hasDBHandler()){
            (sampler as ResourceRestSampler).sqlInsertBuilder!!.extractExistingPKs(dataInDB)
        }
    }

    fun getResourceCluster() : Map<String, RestResource> {
        return resourceCluster.toMap()
    }
    fun onlyIndependentResource() : Boolean {
        return resourceCluster.values.filter{ r -> !r.isIndependent() }.isEmpty()
    }

    /**
     * copy code
     */
    private fun repairDbActions(dbActions: MutableList<DbAction>){
        /**
         * First repair SQL Genes (i.e. SQL Timestamps)
         */
        GeneUtils.repairGenes(dbActions.flatMap { it.seeGenes() })

        /**
         * Now repair database constraints (primary keys, foreign keys, unique fields, etc.)
         */
        DbActionUtils.repairBrokenDbActionsList(dbActions, randomness)
    }
}