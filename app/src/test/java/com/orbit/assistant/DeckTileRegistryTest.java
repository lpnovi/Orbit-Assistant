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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The table every other part of Deck reads.
 *
 * <p>If a definition is malformed the failure surfaces somewhere else entirely — a tile with no
 * icon, an Add sheet row with no description, a size the editor offers and the store then refuses —
 * so the invariants are asserted here, at the source, rather than being discovered downstream.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class DeckTileRegistryTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DeckLayoutStore.clearForTest(context);
    }

    @Test public void everyTypeIdIsUnique() {
        Set<String> ids = new HashSet<>();
        for (DeckTileRegistry.Definition definition : DeckTileRegistry.all()) {
            assertTrue("duplicate type id: " + definition.type, ids.add(definition.type));
        }
        assertEquals(ids.size(), DeckTileRegistry.all().size());
    }

    @Test public void everyDefinitionIsPresentable() {
        for (DeckTileRegistry.Definition definition : DeckTileRegistry.all()) {
            String id = definition.type;
            assertFalse(id + " needs a title", definition.title.trim().isEmpty());
            assertFalse(id + " needs a description", definition.description.trim().isEmpty());
            assertFalse(id + " needs an accessibility role", definition.roleLabel.trim().isEmpty());
            assertTrue(id + " needs an icon", definition.iconRes != 0);
            assertNotNull(id + " needs a category", definition.category);
        }
    }

    @Test public void everyDefinitionSupportsAtLeastOneSize() {
        for (DeckTileRegistry.Definition definition : DeckTileRegistry.all()) {
            assertFalse(definition.type + " must support a size", definition.sizes.isEmpty());
            assertTrue(definition.type + " must support the size it falls back to",
                    definition.supports(definition.defaultSize()));
        }
    }

    /** Every configurable kind must actually be reachable through a configuration flow. */
    @Test public void everyConfigurableKindIsOneDeckKnowsHowToConfigure() {
        for (DeckTileRegistry.Definition definition : DeckTileRegistry.all()) {
            if (!definition.configurable) continue;
            assertTrue(definition.type + " is configurable but has no configuration surface",
                    DeckTileRegistry.TYPE_ROUTINE.equals(definition.type)
                            || DeckTileRegistry.TYPE_APP.equals(definition.type)
                            || DeckTileRegistry.TYPE_PROMPT.equals(definition.type));
        }
    }

    /** Configured kinds repeat; destinations and direct actions do not. */
    @Test public void onlyConfiguredKindsMayRepeat() {
        for (DeckTileRegistry.Definition definition : DeckTileRegistry.all()) {
            if (definition.configurable) {
                assertFalse(definition.type + " is configured, so it may repeat",
                        definition.singleton);
            } else {
                assertTrue(definition.type + " is a fixed destination, so it may not repeat",
                        definition.singleton);
            }
        }
    }

    @Test public void unknownTypesAreNotInvented() {
        assertNull(DeckTileRegistry.definition("orbit.not_a_real_type"));
        assertNull(DeckTileRegistry.definition(null));
        assertFalse(DeckTileRegistry.knows("orbit.not_a_real_type"));
        assertFalse(DeckTileRegistry.knows(null));
    }

    /** The Add sheet lists definitions in a fixed order, so the sheet cannot reshuffle itself. */
    @Test public void registryOrderIsDeterministic() {
        List<String> first = typeIds(DeckTileRegistry.all());
        for (int i = 0; i < 5; i++) {
            assertEquals(first, typeIds(DeckTileRegistry.all()));
        }
        assertEquals("New chat is offered first", DeckTileRegistry.TYPE_NEW_CHAT, first.get(0));
    }

    @Test public void everyCategoryListedHasSomethingInIt() {
        List<DeckTileRegistry.Category> categories = DeckTileRegistry.categories();
        assertFalse(categories.isEmpty());
        for (DeckTileRegistry.Category category : categories) {
            assertFalse(category + " is listed but empty",
                    DeckTileRegistry.inCategory(category).isEmpty());
        }
    }

    @Test public void everyDefinitionBelongsToAListedCategory() {
        List<DeckTileRegistry.Category> categories = DeckTileRegistry.categories();
        for (DeckTileRegistry.Definition definition : DeckTileRegistry.all()) {
            assertTrue(definition.type + " sits in an unlisted category",
                    categories.contains(definition.category));
        }
    }

    // ---- sizes ------------------------------------------------------------------------------------

    @Test public void appTilesAreStandardOnly() {
        DeckTileRegistry.Definition app = DeckTileRegistry.definition(DeckTileRegistry.TYPE_APP);
        assertTrue(app.supports(DeckTile.Size.STANDARD));
        assertFalse("a wide tile of somebody else's icon is the takeover Deck avoids",
                app.supports(DeckTile.Size.WIDE));
    }

    @Test public void coercingASizeNeverReturnsAnUnsupportedOne() {
        for (DeckTileRegistry.Definition definition : DeckTileRegistry.all()) {
            for (DeckTile.Size requested : DeckTile.Size.values()) {
                DeckTile.Size coerced = DeckTileRegistry.coerceSize(definition.type, requested);
                assertTrue(definition.type + " coerced to an unsupported size",
                        definition.supports(coerced));
            }
        }
    }

    /** An unknown type keeps whatever it was written with, because this build cannot know better. */
    @Test public void coercingAnUnknownTypeLeavesItAlone() {
        assertEquals(DeckTile.Size.WIDE,
                DeckTileRegistry.coerceSize("orbit.future", DeckTile.Size.WIDE));
    }

    // ---- unavailable behaviour is defined for every kind -------------------------------------------

    /**
     * Every kind must have an answer to "what if the thing it points at is gone".
     *
     * <p>An unconfigured instance of each type is resolved, and the only requirement is that it
     * comes back with a real title and a defined availability rather than throwing or rendering
     * blank. That is the contract the grid and the executor both rely on.
     */
    @Test public void everyKindResolvesWhenItHasNothingConfigured() {
        for (DeckTileRegistry.Definition definition : DeckTileRegistry.all()) {
            DeckTile tile = DeckTile.of(definition.type, definition.defaultSize());
            DeckTileResolver.Resolved resolved = DeckTileResolver.resolve(context, tile);
            assertNotNull(definition.type + " must resolve", resolved);
            assertNotNull(definition.type + " must have an availability", resolved.availability);
            assertFalse(definition.type + " must have a title", resolved.title.trim().isEmpty());
            assertFalse(definition.type + " must have a spoken description",
                    resolved.contentDescription.trim().isEmpty());

            if (definition.configurable) {
                assertEquals(definition.type + " has nothing configured, so it is unresolved",
                        DeckTile.Availability.UNRESOLVED, resolved.availability);
            }
        }
    }

    /** A tile that cannot run always explains why, at any size. */
    @Test public void anUnusableTileAlwaysShowsItsReason() {
        DeckTile routine = DeckTile.of(DeckTileRegistry.TYPE_ROUTINE, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_ROUTINE_ID, "gone");
        DeckTileResolver.Resolved resolved = DeckTileResolver.resolve(context, routine);
        assertEquals(DeckTile.Availability.UNRESOLVED, resolved.availability);
        assertTrue("the reason shows even on a standard tile", resolved.liveState);
        assertFalse(resolved.subtitle.trim().isEmpty());
    }

    @Test public void everyCuratedPromptIconResolves() {
        assertFalse(DeckIcons.keys().isEmpty());
        for (String key : DeckIcons.keys()) {
            assertTrue(key + " must resolve to a drawable", DeckIcons.resFor(key) != 0);
            assertFalse(key + " must have a label", DeckIcons.labelFor(key).trim().isEmpty());
            assertTrue(DeckIcons.knows(key));
        }
        assertTrue("an unknown key falls back rather than returning nothing",
                DeckIcons.resFor("not-an-icon") != 0);
        assertFalse(DeckIcons.knows("not-an-icon"));
    }

    private static List<String> typeIds(List<DeckTileRegistry.Definition> definitions) {
        List<String> out = new ArrayList<>();
        for (DeckTileRegistry.Definition definition : definitions) out.add(definition.type);
        return out;
    }
}
