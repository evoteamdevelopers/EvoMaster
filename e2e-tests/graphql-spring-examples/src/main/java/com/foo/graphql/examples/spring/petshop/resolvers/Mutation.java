package com.foo.graphql.examples.spring.petshop.resolvers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.coxautodev.graphql.tools.GraphQLMutationResolver;
import org.springframework.stereotype.Component;
import com.foo.graphql.examples.spring.petshop.entities.Pet;
import com.foo.graphql.examples.spring.petshop.enums.Animal;

@Component
public class Mutation implements GraphQLMutationResolver {

    public static List<Pet> pets = new ArrayList<Pet>();
    public Pet CreatePet(int id,Animal type,String name,int age){
        Pet newPet = new Pet(id,name,type,age);
        pets.add(newPet);
        return newPet;
    }

    public Pet CreatePet(int id,Animal type,String name){
        Pet newPet = new Pet(id,name,type,2);
        pets.add(newPet);
        return newPet;
    }
}
