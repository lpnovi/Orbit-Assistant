package com.orbit.assistant;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** Deterministic local bridge from user phrases to existing saved Routine action plans. */
public final class CustomCommandRouter {
    private CustomCommandRouter() {}

    public static AssistantReply tryHandle(Context context, String raw) {
        if (context == null) return null;
        String wanted = CustomCommandStore.normalizeForMatch(raw);
        if (wanted.isEmpty()) return null;

        List<CustomCommandStore.Command> matches = new ArrayList<>();
        for (CustomCommandStore.Command command : CustomCommandStore.list(context)) {
            if (!command.enabled) continue;
            for (String phrase : CustomCommandStore.phrases(command)) {
                if (wanted.equals(CustomCommandStore.normalizeForMatch(phrase))) {
                    matches.add(command);
                    break;
                }
            }
        }
        if (matches.isEmpty()) return null;
        if (matches.size() > 1) {
            return new AssistantReply("More than one Custom Command matches that phrase, so Orbit did not run anything. Resolve the conflict in Settings → Routines → Custom Commands.");
        }

        CustomCommandStore.Command command = matches.get(0);
        RoutineStore.Routine routine = RoutineStore.findById(context, command.routineId);
        if (routine == null) {
            return new AssistantReply("That Custom Command needs attention because its Routine is unavailable. Reassign it in Settings → Routines → Custom Commands.");
        }
        return RoutineCommandRouter.replyForRoutine(context, routine);
    }
}
