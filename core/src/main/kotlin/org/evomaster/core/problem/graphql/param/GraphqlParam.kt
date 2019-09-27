package org.evomaster.core.problem.graphql.param

import org.evomaster.core.problem.rest.param.Param
import org.evomaster.core.search.gene.Gene

class GraphqlParam(name: String, gene : Gene) : Param(name, gene){

    override fun copy(): GraphqlParam {
        return GraphqlParam(name, gene.copy())
    }
}