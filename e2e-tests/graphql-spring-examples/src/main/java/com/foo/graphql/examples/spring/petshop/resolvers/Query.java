package com.foo.graphql.examples.spring.petshop.resolvers;

import java.util.ArrayList;
import java.util.List;
import com.coxautodev.graphql.tools.GraphQLQueryResolver;
import com.foo.graphql.examples.spring.petshop.PetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.foo.graphql.examples.spring.petshop.entities.Pet;
import com.foo.graphql.examples.spring.petshop.enums.Animal;

@Component
public class Query implements GraphQLQueryResolver {

    public List<Pet> pets() {
        if(Mutation.pets.size() == 0) {
            Mutation.pets.add(new Pet(1, "ehsan", Animal.CAT, 20));
        }
        return Mutation.pets;
    }

    public Pet petsById(String id) {
        if(Mutation.pets.size() == 0) {
            Mutation.pets.add(new Pet(1, "ehsan", Animal.CAT, 20));
        }
        for(int i=0; i<Mutation.pets.size()-1; i++) {
            if (Long.toString(Mutation.pets.get(i).getId()) == id) {
                return Mutation.pets.get(i);
            }
        }
        return null;
    }
}