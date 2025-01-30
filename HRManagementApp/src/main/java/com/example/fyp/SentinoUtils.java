package com.example.fyp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

public class SentinoUtils {
    public static List<String> processSentinoQuestionsResponse(String responseBody) {
        JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
        List<String> questions = new ArrayList<>();
        if (jsonObject.has("items")) {
            JsonArray itemsArray = jsonObject.getAsJsonArray("items");
            for (JsonElement itemElement : itemsArray) {
                questions.add(itemElement.getAsString());
            }
        }
        return questions;
    }
}