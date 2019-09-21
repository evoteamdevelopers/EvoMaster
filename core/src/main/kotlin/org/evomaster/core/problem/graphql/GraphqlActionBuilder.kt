package org.evomaster.core.problem.graphql

import graphql.language.*
import graphql.schema.idl.TypeDefinitionRegistry
import org.evomaster.core.problem.graphql.param.GraphqlParam
import org.evomaster.core.problem.rest.param.Param
import org.evomaster.core.search.Action
import org.evomaster.core.search.gene.*
import org.slf4j.LoggerFactory
import org.slf4j.Logger
import java.lang.IllegalStateException
import java.util.concurrent.atomic.AtomicInteger


class GraphqlActionBuilder {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(GraphqlActionBuilder::class.java)
        private val idGenerator = AtomicInteger()

        fun addActionsFromSchema(schema: TypeDefinitionRegistry,
                                 actionCluster: MutableMap<String, Action>) {

            actionCluster.clear()

            val rootOperations = mutableListOf<String>("Mutation", "Query")

            rootOperations.forEach{ operationType ->
            val obj = schema.getType(operationType).get() as ObjectTypeDefinition
                val operationList = obj.fieldDefinitions.forEach{

                    val params = extractParams(it, schema)

                    val action = GraphqlCallAction("${it.name}${idGenerator.incrementAndGet()}", it.name, params, OperationType.valueOf(operationType))

                    actionCluster.put(action.getName(), action)
                }
            }
        }


        private fun extractParams(operation: FieldDefinition, schema: TypeDefinitionRegistry): MutableList<GraphqlParam> {
                    val params: MutableList<GraphqlParam> = mutableListOf()
                    val name = operation.name

                    operation.inputValueDefinitions.forEach{
                        val inputName = it.name

                        val type: TypeName
                        when(it.type){
                            is NonNullType ->
                                type = (it.type as NonNullType).type as TypeName
                            is TypeName ->
                                type = it.type as TypeName
//                            it.type is ListType -> //TODO: should handle listType
//                                type = null

                            else -> throw IllegalStateException("Invalid type supplied")
                        }


                        val gene = getGene(inputName, type, schema)
                        params.add(GraphqlParam(name, gene))
                    }

            return params
        }

        private fun getGene(
                name: String,
                type: TypeName,
                schema: TypeDefinitionRegistry
        ) :Gene {
            val typeName = type.name

            /*
            https://graphql.org/learn/schema/

            scalar types:
            Int  A signed 32‐bit integer
            Float
            String
            Boolean
            ID      The ID type is serialized in the same way as a String

             */
            when (typeName) {
                "Int" -> return IntegerGene(name)
                "Float" -> return FloatGene(name)
                "String" -> return StringGene(name)
                "Boolean" -> return BooleanGene(name)
                "ID" -> return StringGene(name) //according to the reference we can treat ID as String

                else -> {
                    return createObjectFromReference(name, typeName, schema)
                }
            }

        }

        fun createObjectFromReference(name: String,
                                      reference: String,
                                      schema: TypeDefinitionRegistry
        ): Gene {
            val classDef = schema.getType(reference).get()

            if (classDef is EnumTypeDefinition){
                return createEnumFromReference(classDef)
            }

            //TODO:After this we should handle ObjectypeDefinition, InterfaceDefinition and UnionDefinition

            return StringGene("test")
        }

        fun createEnumFromReference(classDef: EnumTypeDefinition): Gene {

            var enumTypes = mutableListOf<String>()
            classDef.enumValueDefinitions.forEach{
                enumTypes.add(it.name)
            }

            return EnumGene<String>("name", enumTypes)
        }
    }
}