package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * Conversational Orbit Memory commands.
 *
 * <p>Reading memories is forgiving, saving needs a clear instruction, and deleting needs an
 * unmistakable one — the consequence of being wrong rises at each step.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class MemoryLanguageTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        MemoryStore.clear(context);
    }

    private int memoryCount() {
        List<MemoryStore.Memory> all = MemoryStore.list(context);
        return all == null ? 0 : all.size();
    }

    // ---- reading ----

    @Test public void theExistingReadCommandsStillWork() {
        assertTrue(MemoryCommandRouter.canHandle("what do you remember about me"));
        assertTrue(MemoryCommandRouter.canHandle("show my memories"));
        assertTrue(MemoryCommandRouter.canHandle("show me my memories"));
        assertNotNull(MemoryCommandRouter.tryHandle(context, "what do you remember about me"));
    }

    @Test public void naturalReadPhrasingsReachLocalMemory() {
        String[] prompts = {
                "What do you know about me?", "What have you saved about me?",
                "What have you remembered about me?", "Show what you remember about me.",
                "Show me what you know about me?", "List my memories"
        };
        for (String prompt : prompts) {
            assertTrue(prompt, MemoryCommandRouter.canHandle(prompt));
            assertNotNull(prompt, MemoryCommandRouter.tryHandle(context, prompt));
        }
    }

    @Test public void topicReadsReachLocalMemory() {
        MemoryStore.add(context, MemoryStore.inferCategory("Niki is my partner"), "Niki is my partner");
        String[] prompts = {
                "What do you remember about Niki?", "What do you know about my PC?",
                "What have I told you about Ireland?", "Do you remember my favorite game?"
        };
        for (String prompt : prompts) {
            assertTrue(prompt, MemoryCommandRouter.canHandle(prompt));
            assertNotNull(prompt, MemoryCommandRouter.tryHandle(context, prompt));
        }
    }

    @Test public void aTopicWithNoMemoriesSaysSoRatherThanGuessing() {
        AssistantReply reply = MemoryCommandRouter.tryHandle(context, "What do you know about Overwatch?");
        assertNotNull(reply);
        assertTrue(reply.text.toLowerCase().contains("do not have any orbit memories"));
    }

    // ---- saving ----

    @Test public void clearSaveInstructionsStore() {
        String[] prompts = {
                "Remember that my favorite game is GRIS.",
                "Remember my favorite color is teal.",
                "Save that I live in Dublin.",
                "Save this about me: I prefer tea.",
                "Keep in mind that I work nights.",
                "Add this to memory: my bike is blue."
        };
        for (String prompt : prompts) {
            int before = memoryCount();
            AssistantReply reply = MemoryCommandRouter.tryHandle(context, prompt);
            assertNotNull(prompt, reply);
            assertTrue(prompt + " should have stored something", memoryCount() > before);
        }
    }

    @Test public void theSavedTextKeepsItsOriginalCasing() {
        MemoryCommandRouter.tryHandle(context, "Remember that my favorite game is GRIS.");
        List<MemoryStore.Memory> all = MemoryStore.list(context);
        assertEquals(1, all.size());
        assertTrue("the stored wording must not be lowercased", all.get(0).text.contains("GRIS"));
    }

    @Test public void casualRemembranceNeverSaves() {
        String[] prompts = {
                "I remember that game.", "I remember going there.", "Do you remember that?",
                "I can't remember his name.", "Remember when we talked about that?",
                "Remember how that went?", "Remember to remind me tomorrow"
        };
        for (String prompt : prompts) {
            int before = memoryCount();
            MemoryCommandRouter.tryHandle(context, prompt);
            assertEquals(prompt + " must not write to Memory", before, memoryCount());
        }
    }

    @Test public void duplicateDetectionStillApplies() {
        MemoryCommandRouter.tryHandle(context, "Remember that my favorite game is GRIS.");
        int after = memoryCount();
        AssistantReply again = MemoryCommandRouter.tryHandle(context, "Remember that my favorite game is GRIS.");
        assertNotNull(again);
        assertEquals("a duplicate must not be stored twice", after, memoryCount());
        assertTrue(again.text.toLowerCase().contains("already have a similar"));
    }

    // ---- deleting ----

    @Test public void explicitForgetPhrasingsDelete() {
        String[] prompts = {
                "Forget that my favorite color is blue.",
                "Remove the memory about my favorite color.",
                "Delete the memory about my favorite color.",
                "Forget what you know about my old PC.",
                "Remove what you remember about my old PC."
        };
        for (String prompt : prompts) {
            MemoryStore.clear(context);
            MemoryStore.add(context, MemoryStore.inferCategory("my favorite color is blue"),
                    "my favorite color is blue");
            MemoryStore.add(context, MemoryStore.inferCategory("my old PC had 16GB of RAM"),
                    "my old PC had 16GB of RAM");
            assertTrue(prompt, MemoryCommandRouter.canHandle(prompt));
            assertNotNull(prompt, MemoryCommandRouter.tryHandle(context, prompt));
        }
    }

    @Test public void conversationalForgettingDeletesNothing() {
        MemoryStore.add(context, MemoryStore.inferCategory("my favorite color is blue"),
                "my favorite color is blue");
        int before = memoryCount();

        String[] prompts = {
                "Did you forget what I said?", "Why did you forget that?",
                "Why do people forget things?", "I forgot my password.",
                "Don't forget to remind me tomorrow.", "Did I forget to tell you something?",
                "Forget it", "Forget about it"
        };
        for (String prompt : prompts) {
            MemoryCommandRouter.tryHandle(context, prompt);
            assertEquals(prompt + " must not delete anything", before, memoryCount());
        }
    }

    @Test public void clearingEverythingStaysExplicit() {
        MemoryStore.add(context, MemoryStore.inferCategory("a"), "a fact worth keeping");
        assertTrue(memoryCount() > 0);

        AssistantReply reply = MemoryCommandRouter.tryHandle(context, "Clear all my memories");
        assertNotNull(reply);
        assertEquals(0, memoryCount());
    }

    @Test public void clearAllVariantsAreRecognized() {
        for (String prompt : new String[]{"clear all memories", "forget all memories",
                "delete all saved memories", "forget everything you remember about me"}) {
            assertTrue(prompt, MemoryCommandRouter.canHandle(prompt));
        }
    }

    // ---- updating ----

    @Test public void explicitUpdatesApply() {
        MemoryStore.add(context, MemoryStore.inferCategory("my favorite game is GRIS"),
                "my favorite game is GRIS");

        AssistantReply reply = MemoryCommandRouter.tryHandle(context,
                "Update my memory about my favorite game to Celeste");
        assertNotNull(reply);
        assertTrue(reply.text.contains("Celeste"));
    }

    @Test public void replaceWordingIsUnderstood() {
        MemoryStore.add(context, MemoryStore.inferCategory("my favorite game is GRIS"),
                "my favorite game is GRIS");
        assertTrue(MemoryCommandRouter.canHandle(
                "Replace the memory about my favorite game with Celeste"));
        assertNotNull(MemoryCommandRouter.tryHandle(context,
                "Replace the memory about my favorite game with Celeste"));
    }

    @Test public void anUpdateWithoutAClearReplacementDoesNothing() {
        MemoryStore.add(context, MemoryStore.inferCategory("my favorite game is GRIS"),
                "my favorite game is GRIS");
        int before = memoryCount();

        // No separator, so there is no unambiguous new value.
        AssistantReply reply = MemoryCommandRouter.tryHandle(context,
                "Update my memory about my favorite game");
        assertNull("an ambiguous update must fall through rather than guess", reply);
        assertEquals(before, memoryCount());
    }

    @Test public void anUpdateForAnUnknownSubjectFailsSafely() {
        AssistantReply reply = MemoryCommandRouter.tryHandle(context,
                "Update my memory about my spaceship to a red one");
        assertNotNull(reply);
        assertTrue(reply.text.toLowerCase().contains("could not find"));
        assertEquals(0, memoryCount());
    }

    // ---- boundaries ----

    @Test public void ordinaryConversationIsNotAMemoryCommand() {
        String[] prompts = {
                "What is the capital of Ireland?", "Tell me about GRIS",
                "How does memory work in Android?", "Do you have a good memory?"
        };
        for (String prompt : prompts) {
            assertFalse(prompt, MemoryCommandRouter.canHandle(prompt));
        }
    }

    @Test public void anEmptyPromptIsHarmless() {
        assertFalse(MemoryCommandRouter.canHandle(""));
        assertFalse(MemoryCommandRouter.canHandle(null));
        assertNull(MemoryCommandRouter.tryHandle(context, ""));
    }
}
