package com.foo.graphql.examples.spring.petshop;

import com.foo.graphql.examples.spring.petshop.entities.Pet;
import com.foo.graphql.examples.spring.petshop.enums.Animal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
public class PetRepository {
    private ArrayList<Pet> pets = new ArrayList<Pet>();

    public void CreateRepository() {
        Pet newPet = new Pet(1, "FirstAnimal", Animal.CAT, 10);
        this.pets.add(newPet);
    }

    public ArrayList<Pet> getPets(){
        return this.pets;
    }

    public void setPet(Pet pet){
        this.pets.add(pet);
    }
}
