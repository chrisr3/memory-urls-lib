package com.example.testing;

import java.util.function.Function;

public class ExampleTask implements Function<String, String> {
    @Override
    public String apply(String s) {
        return "JAVA >> '" + s + "' << JAVA";
    }
}
