package org.evomaster.core.search.gene.sql

import org.evomaster.core.output.OutputFormat
import org.evomaster.core.search.gene.Gene
import org.evomaster.core.search.gene.ObjectGene
import org.evomaster.core.search.service.AdaptiveParameterControl
import org.evomaster.core.search.service.Randomness

class SqlXMLGene(name: String, val objectGene: ObjectGene = ObjectGene(name, fields = listOf())) : Gene(name) {


    override fun copy(): Gene = SqlXMLGene(
            name,
            objectGene = this.objectGene.copy() as ObjectGene)


    override fun randomize(randomness: Randomness, forceNewValue: Boolean, allGenes: List<Gene>) {
        objectGene.randomize(randomness, forceNewValue, allGenes)
    }

    override fun standardMutation(randomness: Randomness, apc: AdaptiveParameterControl, allGenes: List<Gene>) {
        objectGene.standardMutation(randomness, apc, allGenes)
    }

    override fun getValueAsPrintableString(previousGenes: List<Gene>, mode: String?, targetFormat: OutputFormat?): String {
        val rawValue = objectGene.getValueAsPrintableString(previousGenes, ObjectGene.XML_MODE, targetFormat)
        when {
            // TODO: refactor with StringGene.getValueAsPrintableString(()
            (targetFormat == null) -> return "\"$rawValue\""
            targetFormat.isKotlin() -> return "\"$rawValue\""
                    .replace("\\", "\\\\")
                    .replace("$", "\\$")
            else -> return "\"$rawValue\""
                    .replace("\\", "\\\\")
        }

    }

    override fun copyValueFrom(other: Gene) {
        if (other !is SqlXMLGene) {
            throw IllegalArgumentException("Invalid gene type ${other.javaClass}")
        }
        this.objectGene.copyValueFrom(other.objectGene)
    }

    /**
     * Genes might contain a value that is also stored
     * in another gene of the same type.
     */
    override fun containsSameValueAs(other: Gene): Boolean {
        if (other !is SqlXMLGene) {
            throw IllegalArgumentException("Invalid gene type ${other.javaClass}")
        }
        return this.objectGene.containsSameValueAs(other.objectGene)
    }

    override fun flatView(excludePredicate: (Gene) -> Boolean): List<Gene> {
        return if (excludePredicate(this)) listOf(this) else
            listOf(this).plus(objectGene.flatView(excludePredicate))
    }

}