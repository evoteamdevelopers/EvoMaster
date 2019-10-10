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

                        val type = it.type as AbstractNode<*>

                        val gene = getGene(inputName, type, schema)
                        params.add(GraphqlParam(name, gene))
                    }

            return params
        }

        private fun getGene(
                name: String,
                type: AbstractNode<*>,
                schema: TypeDefinitionRegistry
        ) :Gene {

            /*
            https://graphql.org/learn/schema/

            scalar types:
            Int  A signed 32‐bit integer
            Float
            String
            Boolean
            ID      The ID type is serialized in the same way as a String

             */

            val primitiveTypeName: String
            val typeName: TypeName
            when(type){
                is NonNullType -> {
                    typeName = type.type as TypeName
                    return getGene(name, typeName, schema)
                }

                is TypeName -> {
                    primitiveTypeName = type.name
                    when (primitiveTypeName) {
                        "Int" -> return IntegerGene(name)
                        "Float" -> return FloatGene(name)
                        "String" -> return StringGene(name)
                        "Boolean" -> return BooleanGene(name)
                        "ID" -> return StringGene(name) //according to the reference we can treat ID as String

                        else -> {
                            return createObjectFromReference(name, primitiveTypeName, schema)
                        }
                    }
                }
                is ListType -> {
                    typeName = type.type as TypeName
                    val template = getGene(name, typeName, schema)
                    return ArrayGene(name, template)
                }

                else -> throw IllegalStateException("Invalid type supplied")
            }

        }

        fun createObjectFromReference(name: String,
                                      reference: String,
                                      schema: TypeDefinitionRegistry
        ): Gene {
            val classDef = schema.getType(reference).get()

            if (classDef is EnumTypeDefinition) {
                return createEnumFromReference(name, classDef)
            } else if (classDef is InputObjectTypeDefinition) {
                return createInputObjectFromReference(name, classDef, schema)
            }
            else {
                throw IllegalStateException("Invalid type supplied")
            }
        }

        fun createInputObjectFromReference(name: String, classDef: InputObjectTypeDefinition, schema: TypeDefinitionRegistry): Gene {
            val fields = createFields(classDef.inputValueDefinitions, schema)
            return ObjectGene(name, fields, name)
        }

        fun createFields(inputFields: MutableList<InputValueDefinition>, schema: TypeDefinitionRegistry): List<out Gene> {
            val fields: MutableList<Gene> = mutableListOf()

            inputFields.forEach {
                val type = it.type as AbstractNode<*>
                var gene = getGene(it.name,
                                    type,
                                    schema)

                fields.add(gene)
            }

            return fields
        }

        fun createEnumFromReference(name: String, classDef: EnumTypeDefinition): Gene {

            var enumTypes = mutableListOf<String>()
            classDef.enumValueDefinitions.forEach{
                enumTypes.add(it.name)
            }

            return EnumGene<String>(name, enumTypes)
        }
    }
}