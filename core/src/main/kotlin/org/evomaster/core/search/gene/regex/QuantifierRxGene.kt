package org.evomaster.core.search.gene.regex

import org.evomaster.core.output.OutputFormat
import org.evomaster.core.search.gene.Gene
import org.evomaster.core.search.service.AdaptiveParameterControl
import org.evomaster.core.search.service.Randomness
import java.lang.IllegalArgumentException


class QuantifierRxGene(
        name: String,
        val template: RxAtom,
        val min: Int = 1,
        val max: Int = 1
) : RxTerm(name) {

    val atoms = mutableListOf<RxAtom>()

    /**
     *  A * quantifier could lead to billions of atom elements.
     *  Here, to avoid an unnecessary huge search space, we put
     *  a limit on the number of variable elements.
     *  But still constrained by min and max
     */
    private val LIMIT = 2

    val limitedMax: Int

    init {
        if (min < 0) {
            throw IllegalArgumentException("Invalid min value '$min': should be positive")
        }
        if (max < 1) {
            throw IllegalArgumentException("Invalid max value '$max': should be at least 1")
        }
        if (min > max) {
            throw IllegalArgumentException("Invalid min-max values '$min-$max': min is greater than max")
        }

        limitedMax = if ((max - min) > LIMIT) {
            min + LIMIT
        } else {
            max
        }
    }


    override fun copy(): Gene {

        val copy = QuantifierRxGene(
                name,
                template.copy() as RxAtom,
                min,
                max
        )
        copy.atoms.clear()
        this.atoms.forEach { copy.atoms.add(it.copy() as RxAtom) }

        return copy
    }

    override fun randomize(randomness: Randomness, forceNewValue: Boolean, allGenes: List<Gene>) {

        val length = randomness.nextInt(min, limitedMax)

        if (length == 0) {
            //nothing to do
            return
        }

        atoms.clear()

        for (i in 0 until length) {
           addNewAtom(randomness, forceNewValue, allGenes)
        }
    }

    override fun standardMutation(randomness: Randomness, apc: AdaptiveParameterControl, allGenes: List<Gene>) {
        val length = atoms.size

        if( length > min  && randomness.nextBoolean(0.1)){
            atoms.removeAt(randomness.nextInt(length))
        } else if(length < limitedMax && randomness.nextBoolean(0.1)){
            addNewAtom(randomness, false, listOf())
        } else {
            val atoms = atoms.filter { it.isMutable() }
            if(atoms.isEmpty()){
                return
            }
            val atom = randomness.choose(atoms)
            atom.standardMutation(randomness, apc, allGenes)
        }
    }

    fun addNewAtom(randomness: Randomness, forceNewValue: Boolean, allGenes: List<Gene>){
        val base = template.copy() as RxAtom
        if (base.isMutable()) {
            base.randomize(randomness, forceNewValue, allGenes)
        }
        atoms.add(base)
    }

    override fun getValueAsPrintableString(previousGenes: List<Gene>, mode: String?, targetFormat: OutputFormat?): String {

        return atoms.map { it.getValueAsPrintableString(previousGenes, mode, targetFormat) }
                .joinToString("")
    }

    override fun copyValueFrom(other: Gene) {
        if (other !is QuantifierRxGene) {
            throw IllegalArgumentException("Invalid gene type ${other.javaClass}")
        }

        if (this.atoms.size == other.atoms.size) {
            //same size, so just copy over the values
            for (i in 0 until other.atoms.size) {
                this.atoms[i].copyValueFrom(other.atoms[i])
            }
        } else {
            //different size, so clear and create new copies
            this.atoms.clear()
            other.atoms.forEach{this.atoms.add(it.copy() as RxAtom)}
        }
    }

    override fun containsSameValueAs(other: Gene): Boolean {
        if (other !is QuantifierRxGene) {
            throw IllegalArgumentException("Invalid gene type ${other.javaClass}")
        }

        if (this.atoms.size != other.atoms.size) {
            return false
        }

        for (i in 0 until other.atoms.size) {
            if (!this.atoms[i].containsSameValueAs(other.atoms[i])) {
                return false
            }
        }

        return true
    }

    override fun flatView(excludePredicate: (Gene) -> Boolean): List<Gene> {
        return if (excludePredicate(this)) listOf(this)
        else listOf(this).plus(atoms.flatMap { it.flatView(excludePredicate) })
    }
}