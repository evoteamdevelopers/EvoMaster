package com.foo.graphql.examples.spring.petshop.entities;

import com.foo.graphql.examples.spring.petshop.enums.Animal;

public class PetInput extends Pet {
    public PetInput(long id,String name,Animal type,int age){
        super(id, name, type, age);
    }
}