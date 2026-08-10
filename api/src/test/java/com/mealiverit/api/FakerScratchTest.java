package com.mealiverit.api;

import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class FakerScratchTest {

    @Test
    void printSampleData() {
        Faker faker = new Faker();
        for (int i = 0; i < 5; i++) {
            System.out.println("name=" + faker.name().fullName());
            System.out.println("phone=" + faker.phoneNumber().phoneNumber());
            System.out.println("email=" + faker.internet().emailAddress());
            System.out.println("---");
        }
    }
}
