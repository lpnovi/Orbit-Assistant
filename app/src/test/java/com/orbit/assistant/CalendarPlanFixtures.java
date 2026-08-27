package com.orbit.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

/** Small shared calendar action params, so the same shapes are not rebuilt in every test. */
final class CalendarPlanFixtures {
    private CalendarPlanFixtures() {}

    static JSONObject singleEvent() {
        try {
            return new JSONObject().put("events", new JSONArray().put(new JSONObject()
                    .put("title", "Michigan vs. Example State")
                    .put("date", "2026-09-05")
                    .put("hour", 12).put("minute", 0)
                    .put("timezone", "America/Detroit")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
